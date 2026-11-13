package live.scarletsworkshop.pocketpilot;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;
import android.view.Display;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Fast hands for Claude. Reads the screen as text, taps and types through Android's accessibility
 * system (tens of milliseconds instead of 1-2 s per adb command), and knows when the screen settles.
 * Commands arrive from the Linux Terminal bridge over a long-poll HTTP link (see agentLoop).
 */
public class PilotAccessibilityService extends AccessibilityService {
    static volatile PilotAccessibilityService instance;

    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile long lastEventMs = 0;
    private volatile boolean running = false;
    private Thread loop;

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        running = true;
        loop = new Thread(new Runnable() { @Override public void run() { agentLoop(); } }, "pilot-agent");
        loop.start();
        BubbleService.onAccessibilityChanged();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        running = false;
        instance = null;
        BubbleService.onAccessibilityChanged();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        running = false;
        instance = null;
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        CharSequence pkg = e.getPackageName();
        if (pkg != null && getPackageName().contentEquals(pkg)) return; // ignore our own bubble
        lastEventMs = SystemClock.uptimeMillis();
    }

    @Override public void onInterrupt() { }

    // ------------------------------------------------------------------ link to the bridge

    private void agentLoop() {
        while (running) {
            try {
                Bridge br = new Bridge(this);
                JSONObject job = br.longPoll("/agent/next", 30000);
                if (job == null || !job.has("cmd")) continue;
                long t0 = SystemClock.uptimeMillis();
                JSONObject res;
                try {
                    res = handle(job.getString("cmd"), job.optJSONObject("args") == null ? new JSONObject() : job.optJSONObject("args"));
                } catch (Throwable t) {
                    res = new JSONObject().put("error", String.valueOf(t.getMessage()));
                }
                res.put("app_ms", SystemClock.uptimeMillis() - t0);
                br.post("/agent/result", new JSONObject().put("id", job.getString("id")).put("result", res));
            } catch (Throwable t) {
                SystemClock.sleep(2000);
            }
        }
    }

    // ------------------------------------------------------------------ commands

    JSONObject handle(String cmd, JSONObject a) throws Exception {
        if ("ping".equals(cmd)) return new JSONObject().put("ok", true);
        if ("info".equals(cmd)) return info();
        if ("tree".equals(cmd)) return tree(a.optBoolean("all"), a.optInt("limit", 220));
        if ("screenshot".equals(cmd)) return screenshot(a.optInt("width", 540), a.optInt("quality", 70));
        if ("wait_change".equals(cmd)) {
            return waitChange(a.optString("baseline", signature()), a.optInt("timeout_ms", 5000),
                    a.optInt("settle_ms", 300), a.optString("until_text", ""), a.optString("until_gone", ""));
        }
        if ("launch".equals(cmd)) {
            String before = signature();
            Intent i = getPackageManager().getLaunchIntentForPackage(a.getString("pkg"));
            if (i == null) return new JSONObject().put("error", "No app " + a.getString("pkg"));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(i);
            return after(new JSONObject().put("ok", true), a, before);
        }
        if ("intent".equals(cmd)) {
            String before = signature();
            Intent i = new Intent(a.optString("action", Intent.ACTION_VIEW));
            if (a.has("data")) i.setData(Uri.parse(a.getString("data")));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return after(new JSONObject().put("ok", true), a, before);
        }
        if ("global".equals(cmd)) {
            String before = signature();
            int g = globalAction(a.getString("action"));
            if (g < 0) return new JSONObject().put("error", "unknown action");
            boolean ok = performGlobalAction(g);
            return after(new JSONObject().put("ok", ok), a, before);
        }
        if ("media".equals(cmd)) return media(a.getString("key"));
        if ("apps".equals(cmd)) return apps();
        if ("tap".equals(cmd)) {
            int x = a.getInt("x"), y = a.getInt("y");
            String hold = holdFor(labelAt(x, y), a);
            if (hold != null) return new JSONObject().put("hold", hold);
            String before = signature();
            boolean ok = gesture(x, y, x, y, a.optBoolean("long") ? 650 : 40);
            return after(new JSONObject().put("ok", ok), a, before);
        }
        if ("swipe".equals(cmd)) {
            String before = signature();
            boolean ok = gesture(a.getInt("x1"), a.getInt("y1"), a.getInt("x2"), a.getInt("y2"), a.optInt("ms", 300));
            return after(new JSONObject().put("ok", ok), a, before);
        }
        if ("click".equals(cmd)) return click(a);
        if ("scroll".equals(cmd)) return scroll(a);
        if ("type".equals(cmd)) return type(a);
        return new JSONObject().put("error", "unknown command " + cmd);
    }

    /** Launchable apps with their names, so "open my bank app" can match by label. */
    private JSONObject apps() throws Exception {
        android.content.pm.PackageManager pm = getPackageManager();
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        JSONArray arr = new JSONArray();
        for (android.content.pm.ResolveInfo r : pm.queryIntentActivities(i, 0)) {
            arr.put(new JSONObject().put("pkg", r.activityInfo.packageName).put("label", s(r.loadLabel(pm))));
        }
        return new JSONObject().put("apps", arr);
    }

    private JSONObject info() throws Exception {
        Rect r = screenRect();
        return new JSONObject().put("pkg", topPackage()).put("w", r.width()).put("h", r.height())
                .put("sdk", Build.VERSION.SDK_INT);
    }

    private Rect screenRect() {
        android.view.WindowManager wm = (android.view.WindowManager) getSystemService(WINDOW_SERVICE);
        if (Build.VERSION.SDK_INT >= 30) return wm.getCurrentWindowMetrics().getBounds();
        android.util.DisplayMetrics m = getResources().getDisplayMetrics();
        return new Rect(0, 0, m.widthPixels, m.heightPixels);
    }

    // ------------------------------------------------------------------ reading the screen

    static final class N {
        AccessibilityNodeInfo node;
        String text = "", desc = "", id = "", cls = "", pkg = "", hint = "";
        Rect b = new Rect();
        boolean click, longc, scroll, edit, checkable, checked, focused, enabled, selected;
    }

    private List<AccessibilityWindowInfo> windowsSafe() {
        try { return getWindows(); } catch (Throwable t) { return new ArrayList<AccessibilityWindowInfo>(); }
    }

    /** Every visible node across app, system and keyboard windows, top window first. */
    List<N> collect(boolean all) {
        ArrayList<N> out = new ArrayList<N>();
        List<AccessibilityWindowInfo> ws = windowsSafe();
        ArrayList<AccessibilityNodeInfo> roots = new ArrayList<AccessibilityNodeInfo>();
        for (AccessibilityWindowInfo w : ws) {
            AccessibilityNodeInfo r = w.getRoot();
            if (r != null) roots.add(r);
        }
        if (roots.isEmpty()) {
            AccessibilityNodeInfo r = getRootInActiveWindow();
            if (r != null) roots.add(r);
        }
        String self = getPackageName();
        for (AccessibilityNodeInfo r : roots) {
            if (r.getPackageName() != null && self.contentEquals(r.getPackageName())) continue;
            walk(r, out, all, 0);
        }
        return out;
    }

    private void walk(AccessibilityNodeInfo n, List<N> out, boolean all, int depth) {
        if (n == null || depth > 60 || out.size() > 1500) return;
        if (!n.isVisibleToUser()) return;
        N x = new N();
        x.node = n;
        x.text = s(n.getText());
        x.desc = s(n.getContentDescription());
        x.id = n.getViewIdResourceName() == null ? "" : n.getViewIdResourceName();
        x.cls = s(n.getClassName());
        int dot = x.cls.lastIndexOf('.');
        if (dot >= 0) x.cls = x.cls.substring(dot + 1);
        x.pkg = s(n.getPackageName());
        if (Build.VERSION.SDK_INT >= 26) x.hint = s(n.getHintText());
        n.getBoundsInScreen(x.b);
        x.click = n.isClickable(); x.longc = n.isLongClickable(); x.scroll = n.isScrollable();
        x.edit = n.isEditable(); x.checkable = n.isCheckable(); x.checked = n.isChecked();
        x.focused = n.isFocused(); x.enabled = n.isEnabled(); x.selected = n.isSelected();
        boolean useful = !x.text.isEmpty() || !x.desc.isEmpty() || x.click || x.edit || x.scroll || x.checkable;
        if ((useful || all) && x.b.width() > 0 && x.b.height() > 0) out.add(x);
        for (int i = 0; i < n.getChildCount(); i++) walk(n.getChild(i), out, all, depth + 1);
    }

    private static String s(CharSequence c) { return c == null ? "" : c.toString(); }

    String topPackage() {
        AccessibilityNodeInfo r = getRootInActiveWindow();
        return r == null || r.getPackageName() == null ? "" : r.getPackageName().toString();
    }

    /** Cheap fingerprint of what's on screen: text, descriptions, checked states. */
    String signature() {
        StringBuilder sb = new StringBuilder();
        for (N x : collect(false)) {
            sb.append(x.text).append('|').append(x.desc).append('|').append(x.checked ? 1 : 0).append('|')
                    .append(x.b.top / 40).append(';');
        }
        return Integer.toHexString(sb.toString().hashCode()) + ":" + sb.length();
    }

    private JSONObject tree(boolean all, int limit) throws Exception {
        List<N> ns = collect(all);
        JSONArray arr = new JSONArray();
        for (N x : ns) {
            if (arr.length() >= limit) break;
            JSONObject o = new JSONObject();
            if (!x.text.isEmpty()) o.put("t", x.text.length() > 120 ? x.text.substring(0, 120) : x.text);
            if (!x.desc.isEmpty()) o.put("d", x.desc.length() > 120 ? x.desc.substring(0, 120) : x.desc);
            if (!x.hint.isEmpty() && x.edit) o.put("hint", x.hint);
            if (!x.id.isEmpty()) o.put("id", x.id.substring(x.id.indexOf('/') + 1));
            o.put("c", x.cls);
            o.put("b", new JSONArray().put(x.b.left).put(x.b.top).put(x.b.right).put(x.b.bottom));
            StringBuilder f = new StringBuilder();
            if (x.click) f.append("tap ");
            if (x.longc) f.append("long ");
            if (x.scroll) f.append("scroll ");
            if (x.edit) f.append("field ");
            if (x.checkable) f.append(x.checked ? "on " : "off ");
            if (x.selected) f.append("selected ");
            if (x.focused) f.append("focused ");
            if (!x.enabled) f.append("disabled ");
            if (f.length() > 0) o.put("f", f.toString().trim());
            arr.put(o);
        }
        Rect r = screenRect();
        return new JSONObject().put("pkg", topPackage()).put("w", r.width()).put("h", r.height())
                .put("nodes", arr).put("total", ns.size()).put("sig", signature());
    }

    private String summary() {
        StringBuilder sb = new StringBuilder(topPackage());
        int n = 0;
        for (N x : collect(false)) {
            String l = !x.text.isEmpty() ? x.text : x.desc;
            if (l.isEmpty()) continue;
            sb.append(n == 0 ? ": " : " | ").append(l.length() > 40 ? l.substring(0, 40) : l);
            if (++n >= 14) break;
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ waiting for the screen

    JSONObject waitChange(String baseline, int timeoutMs, int settleMs, String untilText, String untilGone) throws Exception {
        long start = SystemClock.uptimeMillis();
        long deadline = start + Math.max(100, timeoutMs);
        String sig = baseline;
        boolean changed = false;
        while (SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(40);
            long now = SystemClock.uptimeMillis();
            boolean quiet = now - lastEventMs >= settleMs;
            if (!quiet) continue;
            if (!untilText.isEmpty() || !untilGone.isEmpty()) {
                String all = screenText();
                boolean okText = untilText.isEmpty() || all.contains(untilText.toLowerCase());
                boolean okGone = untilGone.isEmpty() || !all.contains(untilGone.toLowerCase());
                if (okText && okGone) { changed = true; break; }
                continue;
            }
            if (lastEventMs > start || now - start > 400) {
                sig = signature();
                if (!sig.equals(baseline)) { changed = true; break; }
            }
        }
        if (!changed && untilText.isEmpty() && untilGone.isEmpty()) {
            sig = signature();
            changed = !sig.equals(baseline);
        }
        return new JSONObject().put("changed", changed).put("waited_ms", SystemClock.uptimeMillis() - start)
                .put("sig", sig).put("summary", summary());
    }

    private String screenText() {
        StringBuilder sb = new StringBuilder();
        for (N x : collect(false)) sb.append(x.text).append(' ').append(x.desc).append(' ');
        return sb.toString().toLowerCase();
    }

    /** After an action: wait for the screen to settle (unless told not to) and say what's there now. */
    private JSONObject after(JSONObject res, JSONObject a, String before) throws Exception {
        if (a.optBoolean("wait", true)) {
            JSONObject w = waitChange(before, a.optInt("timeout_ms", 2500), a.optInt("settle_ms", 250), "", "");
            res.put("changed", w.getBoolean("changed")).put("waited_ms", w.getLong("waited_ms"))
                    .put("summary", w.getString("summary")).put("sig", w.getString("sig"));
        }
        return res;
    }

    // ------------------------------------------------------------------ acting

    private boolean gesture(int x1, int y1, int x2, int y2, int ms) throws InterruptedException {
        Path p = new Path();
        p.moveTo(x1, y1);
        if (x1 != x2 || y1 != y2) p.lineTo(x2, y2);
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, Math.max(1, ms))).build();
        final CountDownLatch done = new CountDownLatch(1);
        final boolean[] ok = {false};
        boolean sent = dispatchGesture(g, new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription d) { ok[0] = true; done.countDown(); }
            @Override public void onCancelled(GestureDescription d) { done.countDown(); }
        }, main);
        if (!sent) return false;
        done.await(ms + 2000, TimeUnit.MILLISECONDS);
        return ok[0];
    }

    private String labelAt(int x, int y) {
        List<N> ns = collect(true);
        N best = null;
        for (N n : ns) {
            if (!n.b.contains(x, y)) continue;
            if (best == null || (n.click && !best.click) || (n.click == best.click && area(n) < area(best))) best = n;
        }
        return best == null ? "" : labelOf(best, ns);
    }

    private static long area(N n) { return (long) n.b.width() * n.b.height(); }

    private static String labelOf(N target, List<N> ns) {
        StringBuilder sb = new StringBuilder();
        ArrayList<String> seen = new ArrayList<String>();
        String rid = target.id.substring(target.id.indexOf('/') + 1).replace('_', ' ');
        for (String w : new String[]{target.text, target.desc, rid}) add(sb, seen, w);
        for (N n : ns) if (target.b.contains(n.b)) { add(sb, seen, n.text); add(sb, seen, n.desc); }
        String l = sb.toString().trim();
        return l.length() > 120 ? l.substring(0, 120) : l;
    }

    private static void add(StringBuilder sb, List<String> seen, String w) {
        if (w == null || w.isEmpty() || seen.contains(w.toLowerCase())) return;
        seen.add(w.toLowerCase());
        sb.append(w).append(' ');
    }

    /** Returns a HOLD message if the label looks like send/pay/delete/... and the step isn't confirmed. */
    private static String holdFor(String label, JSONObject a) {
        String re = a.optString("guard_re", "");
        if (re.isEmpty() || a.optBoolean("confirmed") || label == null || label.isEmpty()) return null;
        if (Pattern.compile(re, Pattern.CASE_INSENSITIVE).matcher(label).find()) return label;
        return null;
    }

    private List<N> find(String match, boolean exact) {
        String m = match.toLowerCase();
        ArrayList<N> hits = new ArrayList<N>();
        for (N n : collect(true)) {
            String[] vals = {n.text.toLowerCase(), n.desc.toLowerCase(), n.id.toLowerCase(),
                    n.id.substring(n.id.indexOf('/') + 1).toLowerCase(), n.hint.toLowerCase()};
            boolean hit = false;
            for (String v : vals) {
                if (v.isEmpty()) continue;
                if (exact ? v.equals(m) : v.contains(m)) { hit = true; break; }
            }
            if (hit) hits.add(n);
        }
        // exact text matches first, then shorter labels
        final String mm = m;
        java.util.Collections.sort(hits, new java.util.Comparator<N>() {
            @Override public int compare(N p, N q) {
                int ep = (p.text.equalsIgnoreCase(mm) || p.desc.equalsIgnoreCase(mm)) ? 0 : 1;
                int eq = (q.text.equalsIgnoreCase(mm) || q.desc.equalsIgnoreCase(mm)) ? 0 : 1;
                if (ep != eq) return ep - eq;
                return (p.text.length() + p.desc.length()) - (q.text.length() + q.desc.length());
            }
        });
        return hits;
    }

    private JSONObject click(JSONObject a) throws Exception {
        List<N> hits;
        if (a.has("x") && a.has("y")) {
            return handle("tap", a);
        }
        hits = find(a.getString("match"), a.optBoolean("exact"));
        if (hits.isEmpty()) return new JSONObject().put("error", "Nothing on screen matches \"" + a.getString("match") + "\"");
        int idx = Math.min(a.optInt("index", 0), hits.size() - 1);
        N n = hits.get(idx);
        List<N> all = collect(true);
        String label = labelOf(n, all);
        String hold = holdFor(label, a);
        if (hold != null) return new JSONObject().put("hold", hold);
        String before = signature();
        boolean longp = a.optBoolean("long");
        boolean ok = false;
        AccessibilityNodeInfo t = n.node;
        while (t != null && !(longp ? t.isLongClickable() : t.isClickable())) t = t.getParent();
        if (t != null) ok = t.performAction(longp ? AccessibilityNodeInfo.ACTION_LONG_CLICK : AccessibilityNodeInfo.ACTION_CLICK);
        String how = "action";
        if (!ok) {
            ok = gesture(n.b.centerX(), n.b.centerY(), n.b.centerX(), n.b.centerY(), longp ? 650 : 40);
            how = "gesture";
        }
        JSONObject r = new JSONObject().put("ok", ok).put("tapped", label).put("how", how).put("matches", hits.size());
        return after(r, a, before);
    }

    private JSONObject scroll(JSONObject a) throws Exception {
        String dir = a.optString("dir", "down");
        String before = signature();
        N target = null;
        String match = a.optString("match", "");
        for (N n : collect(true)) {
            if (!n.scroll) continue;
            if (!match.isEmpty() && !(n.text + n.desc + n.id).toLowerCase().contains(match.toLowerCase())) continue;
            if (target == null || area(n) > area(target)) target = n;
        }
        boolean ok = false;
        if (target != null && Build.VERSION.SDK_INT >= 23) {
            AccessibilityNodeInfo.AccessibilityAction act;
            if ("down".equals(dir)) act = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN;
            else if ("up".equals(dir)) act = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP;
            else if ("left".equals(dir)) act = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT;
            else act = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT;
            ok = target.node.performAction(act.getId());
            if (!ok) {
                boolean fwd = "down".equals(dir) || "right".equals(dir);
                ok = target.node.performAction(fwd ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
            }
        }
        if (!ok) {
            Rect r = target != null ? target.b : screenRect();
            int cx = r.centerX(), cy = r.centerY(), dy = (int) (r.height() * 0.35), dx = (int) (r.width() * 0.35);
            if ("down".equals(dir)) ok = gesture(cx, cy + dy, cx, cy - dy, 300);
            else if ("up".equals(dir)) ok = gesture(cx, cy - dy, cx, cy + dy, 300);
            else if ("left".equals(dir)) ok = gesture(cx - dx, cy, cx + dx, cy, 300);
            else ok = gesture(cx + dx, cy, cx - dx, cy, 300);
        }
        return after(new JSONObject().put("ok", ok), a, before);
    }

    /** Apps whose editors keep text reliably only when it arrives like a paste. */
    private static final String PASTE_FIRST = "com.google.android.keep|com.google.android.apps.docs|com.microsoft.office|com.evernote|notion|md.obsidian";

    private static String norm(String x) { return x == null ? "" : x.replaceAll("\\s+", " ").trim(); }

    /** The text really in the field, not its grey hint. */
    private String fieldText(AccessibilityNodeInfo f) {
        if (f == null) return "";
        if (Build.VERSION.SDK_INT >= 26 && f.isShowingHintText()) return "";
        return s(f.getText());
    }

    /** Re-find the field after the app redraws it (Keep swaps its note body view on focus). */
    private AccessibilityNodeInfo fresh(AccessibilityNodeInfo f, Rect where) {
        try { if (f != null && f.refresh() && f.isVisibleToUser()) return f; } catch (Throwable ignored) { }
        AccessibilityNodeInfo foc = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (foc != null && foc.isEditable()) return foc;
        return editableAt(where);
    }

    private AccessibilityNodeInfo editableAt(Rect where) {
        if (where == null) return null;
        N best = null;
        for (N n : collect(true)) {
            if (!n.edit) continue;
            if (Rect.intersects(n.b, where) && (best == null || n.b.contains(where.centerX(), where.centerY()))) best = n;
        }
        return best == null ? null : best.node;
    }

    private boolean setText(AccessibilityNodeInfo f, String want) {
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, want);
        return f.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
    }

    /** Paste through the app's own editor, the way a person would. Replaces the field unless appending. */
    private boolean paste(AccessibilityNodeInfo f, String text, boolean append, String prior) {
        try {
            final android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("Pocket Pilot", text));
            Bundle sel = new Bundle();
            sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, append ? prior.length() : 0);
            sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, prior.length());
            f.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel);
            boolean ok = f.performAction(AccessibilityNodeInfo.ACTION_PASTE);
            // don't leave what Claude typed sitting on the clipboard
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override public void run() {
                    try { if (Build.VERSION.SDK_INT >= 28) cm.clearPrimaryClip(); } catch (Throwable ignored) { }
                }
            }, 1500);
            return ok;
        } catch (Throwable t) {
            return false;
        }
    }

    private JSONObject type(JSONObject a) throws Exception {
        AccessibilityNodeInfo field = null;
        String fieldLabel = "";
        Rect where = new Rect();
        String text = a.optString("text", "");
        boolean append = a.optBoolean("append");
        if (a.has("match") && !a.optString("match").isEmpty()) {
            String m = a.getString("match");
            N editHit = null, placeholder = null;
            for (N n : find(m, false)) {
                if (n.edit) {
                    // a field whose hint is exactly the word ("Note") beats one that only contains it
                    if (editHit == null || n.hint.equalsIgnoreCase(m)) editHit = n;
                    if (n.hint.equalsIgnoreCase(m)) break;
                } else if (placeholder == null) placeholder = n;
            }
            N hit = editHit != null ? editHit : placeholder;
            if (hit == null) return new JSONObject().put("error", "No text field matches \"" + m + "\"");
            where.set(hit.b);
            fieldLabel = hit.hint + " " + hit.id + " " + hit.desc;
            hit.node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            boolean clicked = hit.node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            if (!clicked) gestureTapCenter(hit.b);   // some editors only wake up for a real touch
            // a "Note"-style placeholder turns into the real editor after the tap
            SystemClock.sleep(editHit == null ? 350 : 120);
            field = fresh(editHit != null ? hit.node : null, where);
            if (field == null) return new JSONObject().put("error", "Tapped \"" + m + "\" but no text box opened there.");
        } else {
            field = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (field != null) field.getBoundsInScreen(where);
        }
        if (field == null) return new JSONObject().put("error", "No text field is focused. Tap the field first, or pass match.");
        if (fieldLabel.trim().isEmpty())
            fieldLabel = s(Build.VERSION.SDK_INT >= 26 ? field.getHintText() : "") + " "
                    + (field.getViewIdResourceName() == null ? "" : field.getViewIdResourceName()) + " " + s(field.getContentDescription());

        String prior = fieldText(field);
        String want = append ? prior + text : text;
        String pkg = s(field.getPackageName());
        String method = a.optString("method", "auto");
        String[] order;
        if ("set".equals(method)) order = new String[]{"set"};
        else if ("paste".equals(method)) order = new String[]{"paste"};
        else if (Pattern.compile(PASTE_FIRST).matcher(pkg).find()) order = new String[]{"paste", "set"};
        else order = new String[]{"set", "paste"};

        String used = "", now = prior;
        boolean ok = false, verified = false;
        JSONArray tried = new JSONArray();
        for (String m : order) {
            field = fresh(field, where);
            if (field == null) break;
            String cur = fieldText(field);
            ok = "set".equals(m) ? setText(field, want) : paste(field, text, append, cur);
            tried.put(m);
            used = m;
            // check it landed, then check again a moment later so an app that reverts it is caught
            SystemClock.sleep(250);
            field = fresh(field, where);
            now = fieldText(field);
            if (!norm(now).contains(norm(want))) continue;
            SystemClock.sleep(500);
            field = fresh(field, where);
            now = fieldText(field);
            if (norm(now).contains(norm(want))) { verified = true; break; }
        }
        JSONObject r = new JSONObject().put("ok", ok && verified).put("chars", text.length())
                .put("method", used).put("tried", tried).put("verified", verified);
        if (!verified) {
            r.put("seen", now.length() > 200 ? now.substring(0, 200) : now);
            r.put("fallback", "keys");   // phonectl types it key by key over adb when it can
        }
        if (a.optBoolean("submit") && verified) {
            String sendy = a.optString("submit_guard_re", "");
            if (!sendy.isEmpty() && !a.optBoolean("confirmed")
                    && Pattern.compile(sendy, Pattern.CASE_INSENSITIVE).matcher(fieldLabel + " " + topPackage()).find()) {
                return r.put("hold", "Enter in this field (" + fieldLabel.trim() + ") may send it");
            }
            String before = signature();
            boolean sub = false;
            if (Build.VERSION.SDK_INT >= 30 && field != null) {
                sub = field.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId());
            }
            r.put("submitted", sub);
            if (!sub) r.put("note", "Couldn't press Enter from here; use key enter.");
            return after(r, a, before);
        }
        return r;
    }

    private boolean gestureTapCenter(Rect b) {
        try { return gesture(b.centerX(), b.centerY(), b.centerX(), b.centerY(), 60); } catch (Throwable t) { return false; }
    }

    private int globalAction(String a) {
        if ("back".equals(a)) return GLOBAL_ACTION_BACK;
        if ("home".equals(a)) return GLOBAL_ACTION_HOME;
        if ("recents".equals(a) || "app_switch".equals(a)) return GLOBAL_ACTION_RECENTS;
        if ("notifications".equals(a)) return GLOBAL_ACTION_NOTIFICATIONS;
        if ("quick_settings".equals(a)) return GLOBAL_ACTION_QUICK_SETTINGS;
        if ("power_dialog".equals(a)) return GLOBAL_ACTION_POWER_DIALOG;
        if ("split_screen".equals(a)) return GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN;
        if (Build.VERSION.SDK_INT >= 28 && "lock".equals(a)) return GLOBAL_ACTION_LOCK_SCREEN;
        if (Build.VERSION.SDK_INT >= 28 && "screenshot_save".equals(a)) return GLOBAL_ACTION_TAKE_SCREENSHOT;
        return -1;
    }

    private JSONObject media(String key) throws Exception {
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if ("volume_up".equals(key)) am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI);
        else if ("volume_down".equals(key)) am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI);
        else if ("mute".equals(key)) am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI);
        else {
            int code = "play_pause".equals(key) ? KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                    : "next".equals(key) ? KeyEvent.KEYCODE_MEDIA_NEXT
                    : "previous".equals(key) ? KeyEvent.KEYCODE_MEDIA_PREVIOUS : -1;
            if (code < 0) return new JSONObject().put("error", "unknown key");
            am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
            am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
        }
        return new JSONObject().put("ok", true);
    }

    // ------------------------------------------------------------------ screenshot

    private JSONObject screenshot(final int width, final int quality) throws Exception {
        if (Build.VERSION.SDK_INT < 30) return new JSONObject().put("error", "needs Android 11+");
        final CountDownLatch done = new CountDownLatch(1);
        final JSONObject out = new JSONObject();
        takeScreenshot(Display.DEFAULT_DISPLAY, new Executor() {
            @Override public void execute(Runnable r) { new Thread(r).start(); }
        }, new TakeScreenshotCallback() {
            @Override public void onSuccess(ScreenshotResult res) {
                try {
                    HardwareBuffer hb = res.getHardwareBuffer();
                    Bitmap hw = Bitmap.wrapHardwareBuffer(hb, res.getColorSpace());
                    Bitmap bmp = hw.copy(Bitmap.Config.ARGB_8888, false);
                    hb.close();
                    int w = bmp.getWidth(), h = bmp.getHeight();
                    int ow = Math.min(width, w), oh = Math.round(h * (ow / (float) w));
                    Bitmap sc = Bitmap.createScaledBitmap(bmp, ow, oh, true);
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    sc.compress(Bitmap.CompressFormat.JPEG, quality, bo);
                    out.put("jpeg", Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP))
                            .put("w", ow).put("h", oh).put("screen_w", w).put("screen_h", h);
                } catch (Exception e) {
                    try { out.put("error", e.getMessage()); } catch (Exception ignored) { }
                }
                done.countDown();
            }
            @Override public void onFailure(int code) {
                try { out.put("error", "screenshot failed (" + code + "), Android allows about one per second"); } catch (Exception ignored) { }
                done.countDown();
            }
        });
        done.await(5, TimeUnit.SECONDS);
        if (!out.has("jpeg") && !out.has("error")) out.put("error", "screenshot timed out");
        out.put("pkg", topPackage());
        return out;
    }
}
