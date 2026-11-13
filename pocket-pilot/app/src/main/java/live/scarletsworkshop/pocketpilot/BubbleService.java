package live.scarletsworkshop.pocketpilot;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The floating orb, the see-through conversation captions, and the chat panel.
 * Voice in: Android SpeechRecognizer. Voice out: TextToSpeech, spoken as Claude goes.
 * Brain: Claude Code in the Linux Terminal, reached through voice_bridge.py.
 * When Pocket Pilot's accessibility service is on, the windows are accessibility overlays, which
 * Android keeps visible even on Settings screens.
 */
public class BubbleService extends Service {
    static final String ACTION_STOP_JOB = "live.scarletsworkshop.pocketpilot.STOP_JOB";
    static final String ACTION_QUIT = "live.scarletsworkshop.pocketpilot.QUIT";
    static final String ACTION_HOT = "live.scarletsworkshop.pocketpilot.HOT";
    private static final String CHANNEL = "pilot";
    private static final int NOTE_ID = 7;
    private static volatile BubbleService instance;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService net = Executors.newCachedThreadPool();
    private WindowManager wm;
    private int winType;
    private SharedPreferences prefs;
    private NotificationManager nm;

    private FrameLayout orbWrap;
    private OrbView orb;
    private WindowManager.LayoutParams orbLp;
    private LinearLayout captions;
    private TextView capYou, capClaude;
    private WindowManager.LayoutParams capLp;
    private LinearLayout panel;
    private TextView panelLog, panelTiming;
    private ScrollView panelScroll;

    private SpeechRecognizer recognizer;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private volatile String currentJob = null;
    private volatile boolean polling = false;
    private boolean working = false;
    private boolean conversation = false;   // hands-free: listen again after every reply
    // hot mic: an offline wake word listener on the phone. Only speech after the wake word is sent.
    private boolean hot = false;
    private WakeListener wake;
    private String queued = null;            // said while Claude was busy: sent next
    private boolean scoOn = false;
    private AudioDeviceCallback deviceWatch;
    private final Runnable hotRestart = new Runnable() { @Override public void run() { hotListen(); } };
    private int misses = 0;
    private long heardAt = 0;
    private String lastTiming = "";
    private final ArrayList<String[]> local = new ArrayList<String[]>();
    private final Runnable fadeCaptions = new Runnable() {
        @Override public void run() {
            captions.animate().alpha(0f).setDuration(600).withEndAction(new Runnable() {
                @Override public void run() { captions.setVisibility(View.GONE); }
            });
        }
    };

    @Override public IBinder onBind(Intent i) { return null; }

    /** The wake word settings changed in the app: restart the listener with them. */
    static void onWakeSettingsChanged() {
        final BubbleService s = instance;
        if (s != null) s.ui.post(new Runnable() { @Override public void run() { if (s.hot) s.setHot(true, false); } });
    }

    static boolean isHotOn() {
        BubbleService s = instance;
        return s != null && s.hot;
    }

    /** Called by the accessibility service when it turns on or off: move the windows to the better layer. */
    static void onAccessibilityChanged() {
        final BubbleService s = instance;
        if (s != null) s.ui.post(new Runnable() { @Override public void run() { s.rebuildWindows(); } });
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        prefs = getSharedPreferences(Bridge.PREFS, MODE_PRIVATE);
        nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        goForeground();
        pickWindowLayer();
        buildOrb();
        buildCaptions();
        tts = new TextToSpeech(this, new TextToSpeech.OnInitListener() {
            @Override public void onInit(int status) {
                ttsReady = status == TextToSpeech.SUCCESS;
                if (ttsReady) {
                    tts.setLanguage(Locale.getDefault());
                    tts.setSpeechRate(prefs.getFloat("speech_rate", 1.1f));
                }
            }
        });
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) { ui.post(new Runnable() { @Override public void run() {
                if (hot && wake != null) wake.setPaused(true);   // never listen to Claude's own voice
                if (!working) orb.setState(OrbView.SPEAKING); } }); }
            @Override public void onDone(final String id) { ui.post(new Runnable() { @Override public void run() { afterSpeaking(id); } }); }
            @Override public void onError(String id) { ui.post(new Runnable() { @Override public void run() { if (!working) orb.setState(idle()); } }); }
        });
        if (prefs.getBoolean("hot_mic", false)) ui.postDelayed(new Runnable() { @Override public void run() { setHot(true, false); } }, 800);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP_JOB.equals(intent.getAction())) {
            stopEverything();
        } else if (intent != null && ACTION_HOT.equals(intent.getAction())) {
            setHot(!hot, true);
        } else if (intent != null && ACTION_QUIT.equals(intent.getAction())) {
            stopSelf();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        instance = null;
        polling = false;
        ui.removeCallbacks(hotRestart);
        stopWake();
        headsetMic(false);
        watchDevices(false);
        try { if (recognizer != null) recognizer.destroy(); } catch (Exception ignored) { }
        try { tts.shutdown(); } catch (Exception ignored) { }
        removeView(orbWrap);
        removeView(captions);
        removeView(panel);
        net.shutdownNow();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ notification (also the status on screens that hide overlays)

    private Notification note(String text) {
        int pf = PendingIntent.FLAG_IMMUTABLE;
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, BubbleService.class).setAction(ACTION_STOP_JOB), pf);
        PendingIntent quit = PendingIntent.getService(this, 2, new Intent(this, BubbleService.class).setAction(ACTION_QUIT), pf);
        PendingIntent open = PendingIntent.getActivity(this, 3, new Intent(this, MainActivity.class), pf);
        PendingIntent hotPi = PendingIntent.getService(this, 4, new Intent(this, BubbleService.class).setAction(ACTION_HOT), pf);
        Notification.Builder nb = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        return nb.setSmallIcon(R.drawable.ic_pilot)
                .setContentTitle("Pocket Pilot")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(null, "Stop", stop).build())
                .addAction(new Notification.Action.Builder(null, hot ? "Hot mic off" : "Hot mic on", hotPi).build())
                .addAction(new Notification.Action.Builder(null, "Hide bubble", quit).build())
                .build();
    }

    private void setNote(String text) {
        try { nm.notify(NOTE_ID, note(text)); } catch (Exception ignored) { }
    }

    private void goForeground() {
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Pocket Pilot bubble", NotificationManager.IMPORTANCE_LOW));
        }
        Notification n = note("Tap the bubble to talk. Stop interrupts Claude.");
        boolean mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
                if (mic) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
                startForeground(NOTE_ID, n, type);
            } else if (Build.VERSION.SDK_INT >= 30 && mic) {
                startForeground(NOTE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            } else {
                startForeground(NOTE_ID, n);
            }
        } catch (RuntimeException e) {
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            else startForeground(NOTE_ID, n);
        }
    }

    // ------------------------------------------------------------------ windows

    private void pickWindowLayer() {
        PilotAccessibilityService a = PilotAccessibilityService.instance;
        if (a != null) {
            wm = (WindowManager) a.getSystemService(WINDOW_SERVICE);
            winType = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
        } else {
            wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            winType = Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;
        }
    }

    private void rebuildWindows() {
        removeView(orbWrap);
        removeView(captions);
        removeView(panel);
        pickWindowLayer();
        try {
            orbLp.type = winType;
            capLp.type = winType;
            wm.addView(orbWrap, orbLp);
            wm.addView(captions, capLp);
            setWorking(working);
        } catch (Exception e) {
            // the accessibility layer went away mid-change; fall back to the normal layer
            PilotAccessibilityService.instance = null;
            pickWindowLayer();
            orbLp.type = winType;
            capLp.type = winType;
            try { wm.addView(orbWrap, orbLp); wm.addView(captions, capLp); } catch (Exception ignored) { }
        }
    }

    private int dp(float v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    private int[] screen() {
        if (Build.VERSION.SDK_INT >= 30) {
            Rect r = wm.getCurrentWindowMetrics().getBounds();
            return new int[]{r.width(), r.height()};
        }
        DisplayMetrics m = getResources().getDisplayMetrics();
        return new int[]{m.widthPixels, m.heightPixels};
    }

    private void removeView(View v) {
        try { if (v != null && v.isAttachedToWindow()) ((WindowManager) v.getContext().getSystemService(WINDOW_SERVICE)).removeViewImmediate(v); } catch (Exception ignored) { }
        try { if (v != null && v.isAttachedToWindow()) wm.removeViewImmediate(v); } catch (Exception ignored) { }
    }

    private void buildOrb() {
        final int size = dp(64);
        orb = new OrbView(this);
        orbWrap = new FrameLayout(this);
        orbWrap.addView(orb, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        orbLp = new WindowManager.LayoutParams(size, size, winType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        orbLp.gravity = Gravity.TOP | Gravity.LEFT;
        int[] s = screen();
        orbLp.x = prefs.getInt("orb_x", s[0] - size - dp(8));
        orbLp.y = prefs.getInt("orb_y", s[1] / 2);
        wm.addView(orbWrap, orbLp);

        final int slop = ViewConfiguration.get(this).getScaledTouchSlop();
        orbWrap.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY; int startX, startY; boolean dragging, longFired;
            final Runnable longPress = new Runnable() {
                @Override public void run() { longFired = true; orb.performHapticFeedback(0); togglePanel(); }
            };
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX(); downY = e.getRawY();
                        startX = orbLp.x; startY = orbLp.y;
                        dragging = false; longFired = false;
                        ui.postDelayed(longPress, 500);
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                        if (!dragging && Math.hypot(dx, dy) > slop) { dragging = true; ui.removeCallbacks(longPress); }
                        if (dragging) {
                            orbLp.x = startX + (int) dx; orbLp.y = startY + (int) dy;
                            wm.updateViewLayout(orbWrap, orbLp);
                            placeCaptions();
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        ui.removeCallbacks(longPress);
                        if (dragging) {
                            int[] sc = screen();
                            orbLp.x = (orbLp.x + size / 2 < sc[0] / 2) ? dp(4) : sc[0] - size - dp(4);
                            orbLp.y = Math.max(dp(24), Math.min(sc[1] - size - dp(24), orbLp.y));
                            wm.updateViewLayout(orbWrap, orbLp);
                            prefs.edit().putInt("orb_x", orbLp.x).putInt("orb_y", orbLp.y).apply();
                            placeCaptions();
                        } else if (!longFired && e.getActionMasked() == MotionEvent.ACTION_UP) {
                            onOrbTap();
                        }
                        return true;
                }
                return false;
            }
        });
    }

    private TextView capBubble(boolean mine) {
        TextView t = new TextView(this);
        t.setTextColor(Color.WHITE);
        t.setTextSize(15);
        t.setMaxLines(6);
        t.setPadding(dp(12), dp(8), dp(12), dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(16));
        bg.setColor(mine ? 0x997C4DFF : 0xAA202124);
        t.setBackground(bg);
        t.setShadowLayer(4, 0, 1, 0xAA000000);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = mine ? Gravity.RIGHT : Gravity.LEFT;
        lp.topMargin = dp(4);
        t.setLayoutParams(lp);
        t.setVisibility(View.GONE);
        return t;
    }

    private void buildCaptions() {
        captions = new LinearLayout(this);
        captions.setOrientation(LinearLayout.VERTICAL);
        captions.setPadding(dp(12), 0, dp(12), 0);
        capYou = capBubble(true);
        capClaude = capBubble(false);
        captions.addView(capYou);
        captions.addView(capClaude);
        captions.setVisibility(View.GONE);
        // Never touchable: the captions don't block your taps or Claude's.
        capLp = new WindowManager.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                winType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        wm.addView(captions, capLp);
        placeCaptions();
    }

    private void placeCaptions() {
        int[] s = screen();
        int size = orbLp.width;
        if (orbLp.y < s[1] / 2) {
            capLp.gravity = Gravity.TOP | Gravity.LEFT;
            capLp.y = orbLp.y + size + dp(6);
        } else {
            capLp.gravity = Gravity.BOTTOM | Gravity.LEFT;
            capLp.y = s[1] - orbLp.y + dp(6);
        }
        try { wm.updateViewLayout(captions, capLp); } catch (Exception ignored) { }
    }

    private void showCaption(String you, String claude, boolean italic) {
        ui.removeCallbacks(fadeCaptions);
        captions.animate().cancel();
        captions.setAlpha(1f);
        captions.setVisibility(View.VISIBLE);
        if (you != null) { capYou.setText(you); capYou.setVisibility(you.isEmpty() ? View.GONE : View.VISIBLE); }
        if (claude != null) {
            capClaude.setText(claude);
            capClaude.setTypeface(null, italic ? Typeface.ITALIC : Typeface.NORMAL);
            capClaude.setVisibility(claude.isEmpty() ? View.GONE : View.VISIBLE);
        }
    }

    private void fadeLater(long ms) {
        ui.removeCallbacks(fadeCaptions);
        ui.postDelayed(fadeCaptions, ms);
    }

    /**
     * While Claude works, the orb shrinks to a small status dot that stays fully visible.
     * Only the dot ignores touches, so Claude's taps land on the app underneath.
     */
    private void setWorking(boolean on) {
        working = on;
        int big = dp(64), small = dp(26);
        int cx = orbLp.x + orbLp.width / 2, cy = orbLp.y + orbLp.height / 2;
        if (on) {
            orbLp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            orbLp.width = small; orbLp.height = small;
        } else {
            orbLp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            orbLp.width = big; orbLp.height = big;
        }
        orbLp.x = cx - orbLp.width / 2;
        orbLp.y = cy - orbLp.height / 2;
        orbWrap.setAlpha(1f);
        try { wm.updateViewLayout(orbWrap, orbLp); } catch (Exception ignored) { }
        placeCaptions();
    }

    // ------------------------------------------------------------------ chat panel

    private void togglePanel() {
        if (panel != null && panel.isAttachedToWindow()) { removeView(panel); return; }
        int[] s = screen();
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(14), dp(14), dp(14), dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(20));
        bg.setColor(0xEE1B1B1F);
        panel.setBackground(bg);

        TextView head = new TextView(this);
        head.setText("Pocket Pilot");
        head.setTextColor(Color.WHITE);
        head.setTypeface(Typeface.DEFAULT_BOLD);
        head.setTextSize(17);
        panel.addView(head);

        panelTiming = new TextView(this);
        panelTiming.setTextColor(0xFF9E9E9E);
        panelTiming.setTextSize(12);
        panelTiming.setText(lastTiming);
        panel.addView(panelTiming);

        panelScroll = new ScrollView(this);
        panelLog = new TextView(this);
        panelLog.setTextColor(0xFFE8E8EA);
        panelLog.setTextSize(15);
        panelLog.setPadding(0, dp(8), 0, dp(8));
        panelLog.setTextIsSelectable(true);
        panelScroll.addView(panelLog);
        panel.addView(panelScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (s[1] * 0.36)));

        final EditText input = new EditText(this);
        input.setHint("Type to Claude");
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(0xFF9E9E9E);
        input.setSingleLine(true);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        panel.addView(input);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        final Runnable sendTyped = new Runnable() {
            @Override public void run() {
                String t = input.getText().toString().trim();
                if (t.isEmpty()) return;
                input.setText("");
                removeView(panel);
                heardAt = System.currentTimeMillis();
                ask(t);
            }
        };
        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int id, android.view.KeyEvent ev) { sendTyped.run(); return true; }
        });
        row.addView(smallButton("Send", new View.OnClickListener() { @Override public void onClick(View v) { sendTyped.run(); } }));
        row.addView(smallButton(conversation ? "End talk" : "Talk mode", new View.OnClickListener() {
            @Override public void onClick(View v) {
                removeView(panel);
                if (conversation) { endConversation(); } else { conversation = true; misses = 0; startListening(); }
            }
        }));
        row.addView(smallButton(hot ? "Hot mic off" : "Hot mic", new View.OnClickListener() {
            @Override public void onClick(View v) { removeView(panel); setHot(!hot, true); }
        }));
        row.addView(smallButton("New chat", new View.OnClickListener() { @Override public void onClick(View v) { newChat(); } }));
        row.addView(smallButton("Close", new View.OnClickListener() { @Override public void onClick(View v) { removeView(panel); } }));
        panel.addView(row);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                winType, WindowManager.LayoutParams.FLAG_DIM_BEHIND, PixelFormat.TRANSLUCENT);
        lp.dimAmount = 0.25f;
        lp.gravity = Gravity.BOTTOM;
        lp.y = dp(24);
        lp.horizontalMargin = 0.03f;
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
        wm.addView(panel, lp);
        renderLog();
        loadHistory();
    }

    private Button smallButton(String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setOnClickListener(l);
        b.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return b;
    }

    private void renderLog() {
        if (panelLog == null) return;
        StringBuilder sb = new StringBuilder();
        for (String[] ex : local) {
            sb.append("You: ").append(ex[0]).append("\n");
            sb.append("Claude: ").append(ex[1]).append("\n\n");
        }
        panelLog.setText(sb.length() == 0 ? "No conversation yet. Tap Talk mode or type below." : sb.toString().trim());
        if (panelTiming != null) panelTiming.setText(lastTiming);
        panelScroll.post(new Runnable() { @Override public void run() { panelScroll.fullScroll(View.FOCUS_DOWN); } });
    }

    private void loadHistory() {
        net.execute(new Runnable() {
            @Override public void run() {
                try {
                    JSONArray h = new Bridge(BubbleService.this).get("/history").optJSONArray("history");
                    if (h == null) return;
                    final ArrayList<String[]> got = new ArrayList<String[]>();
                    for (int i = 0; i < h.length(); i++) {
                        JSONObject o = h.getJSONObject(i);
                        got.add(new String[]{o.optString("you"), o.optString("claude")});
                    }
                    ui.post(new Runnable() { @Override public void run() { local.clear(); local.addAll(got); renderLog(); } });
                } catch (Exception ignored) { }
            }
        });
    }

    private void newChat() {
        local.clear();
        renderLog();
        showCaption("", "Fresh start.", true);
        fadeLater(3000);
        net.execute(new Runnable() {
            @Override public void run() { try { new Bridge(BubbleService.this).post("/reset", new JSONObject()); } catch (Exception ignored) { } }
        });
    }

    // ------------------------------------------------------------------ voice

    private void onOrbTap() {
        if (hot) {   // with the hot mic on, a tap pauses or resumes it (like the headset's mute)
            if (tts.isSpeaking()) { tts.stop(); scheduleHot(200); return; }
            setHot(false, true);
            return;
        }
        int st = orb.getState();
        if (st == OrbView.LISTENING) {
            if (recognizer != null) recognizer.stopListening();
        } else if (st == OrbView.SPEAKING) {
            tts.stop();
            orb.setState(idle());
            startListening();
        } else {
            startListening();
        }
    }

    private void endConversation() {
        conversation = false;
        orb.setState(idle());
        showCaption("", "Talk mode off.", true);
        fadeLater(2500);
    }

    private static boolean isExit(String s) {
        String t = s.toLowerCase(Locale.ROOT).replaceAll("[^a-z ]", "").trim();
        return t.equals("stop") || t.equals("thats all") || t.equals("that is all") || t.equals("goodbye")
                || t.equals("bye") || t.equals("thanks") || t.equals("thank you") || t.equals("never mind")
                || t.equals("end") || t.equals("stop listening");
    }

    private void startListening() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            showCaption("", "Open Pocket Pilot and allow the microphone.", false);
            fadeLater(5000);
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            showCaption("", "Speech recognition isn't available. Hold the bubble to type.", false);
            fadeLater(5000);
            return;
        }
        if (tts != null && !hot) tts.stop();
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle b) {
                    if (!hot) showCaption(conversation ? "Listening (talk mode)..." : "Listening...", "", false);
                }
                @Override public void onBeginningOfSpeech() { }
                @Override public void onRmsChanged(float v) { }
                @Override public void onBufferReceived(byte[] b) { }
                @Override public void onEndOfSpeech() { heardAt = System.currentTimeMillis(); }
                @Override public void onEvent(int t, Bundle b) { }
                @Override public void onPartialResults(Bundle b) {
                    ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (r != null && !r.isEmpty() && !r.get(0).isEmpty() && !(hot && working)) showCaption(r.get(0), null, false);
                }
                @Override public void onResults(Bundle b) {
                    ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    String said = (r == null || r.isEmpty()) ? "" : r.get(0).trim();
                    if (said.isEmpty()) { missed(); return; }
                    misses = 0;
                    if (conversation && isExit(said)) { endConversation(); return; }
                    if (heardAt == 0) heardAt = System.currentTimeMillis();
                    ask(said);
                }
                @Override public void onError(int err) {
                    if (err == SpeechRecognizer.ERROR_NO_MATCH || err == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) { missed(); return; }
                    orb.setState(idle());
                    showCaption("", "Mic problem (" + err + "). Tap to try again.", true);
                    fadeLater(3000);
                }
            });
        }
        int silence = prefs.getInt("silence_ms", 1200);
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag());
        i.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, prefs.getBoolean("offline_speech", true));
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, (long) silence);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, (long) silence);
        heardAt = 0;
        if (!working) orb.setState(OrbView.LISTENING);
        recognizer.startListening(i);
    }

    private void missed() {
        if (hot) { misses++; scheduleHot(Math.min(150L * misses, 1200L)); return; }   // silence or muted: just keep listening
        misses++;
        orb.setState(idle());
        if (conversation && misses < 2) { startListening(); return; }
        if (conversation) { endConversation(); return; }
        showCaption("", "Didn't catch that.", true);
        fadeLater(2500);
    }

    private void ask(final String said) {
        orb.setState(OrbView.THINKING);
        showCaption(said, "Thinking...", true);
        setWorking(true);
        setNote("Working on: " + shorten(said, 80));
        final long heard = heardAt == 0 ? System.currentTimeMillis() : heardAt;
        if (hot) scheduleHot(400);
        net.execute(new Runnable() {
            @Override public void run() {
                try {
                    Bridge br = new Bridge(BubbleService.this);
                    JSONObject r = br.post("/ask", new JSONObject().put("text", said).put("heard_at", heard));
                    String job = r.optString("job", "");
                    if (job.isEmpty()) throw new Exception(r.optString("error", "no job"));
                    currentJob = job;
                    polling = true;
                    String lastProgress = "";
                    int spoken = 0;
                    while (polling) {
                        Thread.sleep(250);
                        JSONObject j = br.get("/job?id=" + job);
                        String state = j.optString("state");
                        JSONArray prog = j.optJSONArray("progress");
                        if (prog != null && prog.length() > 0) {
                            final String p = prog.getString(prog.length() - 1);
                            if (!p.equals(lastProgress)) {
                                lastProgress = p;
                                ui.post(new Runnable() { @Override public void run() { showCaption(null, shorten(p, 140), true); setNote(shorten(p, 120)); } });
                            }
                        }
                        // speak Claude's sentences as they arrive instead of waiting for the end
                        JSONArray say = j.optJSONArray("say");
                        if (say != null) {
                            while (spoken < say.length()) {
                                final String line = say.getString(spoken++);
                                final boolean last = !"running".equals(state) && spoken == say.length();
                                ui.post(new Runnable() { @Override public void run() { speak(line, last ? (line.trim().endsWith("?") ? "question" : "answer") : "progress"); } });
                            }
                        }
                        if (!"running".equals(state)) {
                            final String reply = j.optString("reply", "");
                            final boolean error = "error".equals(state);
                            final boolean alreadySpoken = say != null && say.length() > 0
                                    && say.getString(say.length() - 1).trim().equals(reply.trim());
                            final String timing = timingLine(j.optJSONObject("timing"));
                            ui.post(new Runnable() { @Override public void run() { finish(said, reply, error, alreadySpoken, timing); } });
                            break;
                        }
                    }
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override public void run() {
                            finish(said, "I can't reach the terminal. Open Terminal and run pilot start. (" + e.getMessage() + ")", true, false, "");
                        }
                    });
                } finally {
                    polling = false;
                    currentJob = null;
                }
            }
        });
    }

    private static String timingLine(JSONObject t) {
        if (t == null) return "";
        StringBuilder sb = new StringBuilder("Last: ");
        if (t.has("heard_to_bridge_ms")) sb.append("to terminal ").append(sec(t.optLong("heard_to_bridge_ms"))).append(" · ");
        if (t.has("first_word_ms")) sb.append("first words ").append(sec(t.optLong("first_word_ms"))).append(" · ");
        sb.append("tools ").append(t.optInt("tool_calls")).append(" (").append(sec(t.optLong("tool_ms"))).append(") · ");
        sb.append("total ").append(sec(t.optLong("total_ms")));
        return sb.toString();
    }

    private static String sec(long ms) { return String.format(Locale.ROOT, "%.1fs", ms / 1000.0); }

    private void speak(String text, String id) {
        if (!prefs.getBoolean("speak", true) || !ttsReady || text.isEmpty()) return;
        if ("progress".equals(id) && !prefs.getBoolean("speak_progress", true)) return;
        tts.speak(clean(text), TextToSpeech.QUEUE_ADD, new Bundle(), id);
    }

    private void finish(String said, String reply, boolean error, boolean alreadySpoken, String timing) {
        setWorking(false);
        setNote("Tap the bubble to talk. Stop interrupts Claude.");
        if (!timing.isEmpty()) lastTiming = timing;
        local.add(new String[]{said, reply});
        while (local.size() > 30) local.remove(0);
        renderLog();
        showCaption(said, reply, false);
        boolean question = reply.trim().endsWith("?");
        if (!alreadySpoken && prefs.getBoolean("speak", true) && ttsReady && !reply.isEmpty()) {
            tts.speak(clean(reply), TextToSpeech.QUEUE_ADD, new Bundle(), question ? "question" : "answer");
        } else if (!(prefs.getBoolean("speak", true) && ttsReady) || reply.isEmpty()) {
            orb.setState(error ? OrbView.ERROR : idle());
            fadeLater(12000);
            if (!error && (conversation || (question && prefs.getBoolean("auto_listen", true)))) listenAgain();
        }
        // if alreadySpoken, afterSpeaking() handles what comes next
        if (queued != null) {
            final String q = queued;
            queued = null;
            ui.postDelayed(new Runnable() { @Override public void run() { heardAt = System.currentTimeMillis(); ask(q); } }, 300);
        } else if (hot) {
            scheduleHot(600);   // hotListen waits if Claude is still talking
        }
    }

    private void afterSpeaking(String id) {
        if (hot && !tts.isSpeaking()) scheduleHot(250);
        if (working || "progress".equals(id)) return;
        if (tts.isSpeaking()) return;
        orb.setState(idle());
        fadeLater(8000);
        if (conversation || ("question".equals(id) && prefs.getBoolean("auto_listen", true))) listenAgain();
    }

    private void stopEverything() {
        polling = false;
        conversation = false;
        if (tts != null) tts.stop();
        if (recognizer != null) recognizer.cancel();
        final String job = currentJob;
        net.execute(new Runnable() {
            @Override public void run() {
                try { new Bridge(BubbleService.this).post("/stop", new JSONObject().put("job", job == null ? "" : job)); } catch (Exception ignored) { }
            }
        });
        setWorking(false);
        setNote("Tap the bubble to talk. Stop interrupts Claude.");
        orb.setState(idle());
        showCaption(null, "Stopped.", true);
        fadeLater(3000);
        queued = null;
        if (wake != null) wake.wipe();
        if (hot) scheduleHot(700);
    }

    // ------------------------------------------------------------------ hot mic (wake word, all on the phone)

    private int idle() { return hot ? OrbView.STANDBY : OrbView.IDLE; }

    private String wakePhrase() {
        String p = WakeGate.normalize(prefs.getString("wake_phrase", "hey pilot"));
        return p.isEmpty() ? "hey pilot" : p;
    }

    private String wakeLabel() {
        StringBuilder sb = new StringBuilder();
        for (String w : wakePhrase().split(" "))
            sb.append(sb.length() > 0 ? " " : "").append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        return sb.toString();
    }

    private String hotNote() {
        return "Hot mic on. Say \"" + wakeLabel() + "\" and then your request. Everything else stays on the phone and is dropped.";
    }

    void setHot(boolean on, boolean announce) {
        if (on && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            showCaption("", "Hot mic needs the microphone. Open Pocket Pilot and allow it.", true);
            fadeLater(5000);
            on = false;
        } else if (on && !WakeModel.present(this)) {
            showCaption("", "Hot mic needs the wake word model first. Open Pocket Pilot and tap Download wake word model.", true);
            fadeLater(6000);
            on = false;
        }
        hot = on;
        prefs.edit().putBoolean("hot_mic", on).apply();
        conversation = false;
        misses = 0;
        ui.removeCallbacks(hotRestart);
        if (recognizer != null) recognizer.cancel();
        stopWake();
        boolean useHeadset = prefs.getBoolean("headset_mic", true);
        headsetMic(on && useHeadset);
        watchDevices(on && useHeadset);
        if (on) {
            startWake();
            setNote(hotNote());
            if (!working) orb.setState(OrbView.STANDBY);
            if (announce) {
                showCaption("", "Hot mic on. Say \"" + wakeLabel() + "\" first. Your headset's mute button pauses it.", true);
                fadeLater(4000);
            }
        } else {
            if (!working) orb.setState(OrbView.IDLE);
            setNote("Tap the bubble to talk. Stop interrupts Claude.");
            if (announce) { showCaption("", "Hot mic off.", true); fadeLater(2500); }
        }
    }

    private void startWake() {
        final boolean comm = scoOn;
        final String phrase = wakePhrase();
        final boolean relaxed = prefs.getBoolean("wake_relaxed", false);
        final int silence = prefs.getInt("silence_ms", 1200);
        final AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        net.execute(new Runnable() {
            @Override public void run() {
                try {
                    final org.vosk.Model m = WakeModel.get(BubbleService.this);
                    ui.post(new Runnable() { @Override public void run() {
                        if (!hot || wake != null) return;
                        final WakeListener w = new WakeListener(m, phrase, relaxed, silence, comm, am, wakeEvents);
                        wake = w;
                        w.onDied = new Runnable() { @Override public void run() { ui.post(new Runnable() { @Override public void run() {
                            if (wake != w) return;
                            String why = w.failure;
                            wake = null;
                            if (hot) setHot(false, false);
                            showCaption("", "Hot mic stopped: " + why, true);
                            fadeLater(6000);
                        } }); } };
                        wake.setPaused(tts != null && tts.isSpeaking());
                        wake.start();
                    } });
                } catch (final Exception e) {
                    ui.post(new Runnable() { @Override public void run() {
                        setHot(false, false);
                        showCaption("", "Couldn't load the wake word model: " + e.getMessage(), true);
                        fadeLater(6000);
                    } });
                }
            }
        });
    }

    private void stopWake() {
        if (wake != null) { wake.onDied = null; wake.stop(); wake = null; }
    }

    private void restartWake() {
        if (!hot) return;
        stopWake();
        startWake();
    }

    /** Listening resumes once Claude has stopped talking. */
    private void scheduleHot(long ms) {
        if (!hot || wake == null) return;
        if (tts != null && tts.isSpeaking()) return;
        wake.setPaused(false);
    }

    private void hotListen() { scheduleHot(0); }

    /** After a question: tap-to-talk listens again; with the hot mic, a short no-wake-word answer window opens. */
    private void listenAgain() {
        if (!hot) { startListening(); return; }
        if (wake != null) { scheduleHot(0); wake.followUp(); }
    }

    private void buzz() {
        if (!prefs.getBoolean("wake_buzz", true)) return;
        try {
            android.os.Vibrator v = (android.os.Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (v != null) v.vibrate(android.os.VibrationEffect.createOneShot(35, android.os.VibrationEffect.DEFAULT_AMPLITUDE));
        } catch (Throwable ignored) { }
    }

    private void backToStandby() {
        if (!working) orb.setState(idle());
        setNote(hot ? hotNote() : "Tap the bubble to talk. Stop interrupts Claude.");
        fadeLater(1200);
    }

    /** Called on the audio thread; everything is passed to the UI thread. */
    private final WakeGate.Listener wakeEvents = new WakeGate.Listener() {
        @Override public void onWake() { ui.post(new Runnable() { @Override public void run() {
            buzz();
            if (!working) orb.setState(OrbView.LISTENING);
            showCaption("Listening...", "", false);
            setNote("Listening for your request...");
        } }); }
        @Override public void onPartial(final String t) { ui.post(new Runnable() { @Override public void run() {
            showCaption(t, null, false);
        } }); }
        @Override public void onWakeOnly() { ui.post(new Runnable() { @Override public void run() {
            buzz();
            showCaption(wakeLabel(), "Yes?", false);
        } }); }
        @Override public void onCommand(final String t) { ui.post(new Runnable() { @Override public void run() {
            heardHot(t);
        } }); }
        @Override public void onFalseWake() { ui.post(new Runnable() { @Override public void run() { backToStandby(); } }); }
        @Override public void onTimeout() { ui.post(new Runnable() { @Override public void run() { backToStandby(); } }); }
    };

    private static String plain(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L} ]", "").replaceAll("\\s+", " ").trim();
    }

    /** A request heard after the wake word. */
    private void heardHot(String t) {
        misses = 0;
        if (!hot) return;
        setNote(hotNote());
        String p = plain(t);
        if (p.equals("hot mic off") || p.equals("stop listening") || p.equals("mic off")) { setHot(false, true); return; }
        if (working && (p.equals("stop") || p.equals("cancel") || p.equals("stop that") || p.equals("never mind"))) {
            stopEverything();
            return;
        }
        if (working) {
            queued = queued == null ? t : queued + ". " + t;
            showCaption(t, "Got it. That's next.", true);
            return;
        }
        heardAt = System.currentTimeMillis();
        ask(t);
    }

    /** Use the Bluetooth headset's own mic, so its mute button is what Claude hears through. */
    private void headsetMic(boolean on) {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (am == null) return;
        try {
            if (on) {
                if (Build.VERSION.SDK_INT >= 31) {
                    for (AudioDeviceInfo d : am.getAvailableCommunicationDevices()) {
                        int ty = d.getType();
                        if (ty == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || ty == AudioDeviceInfo.TYPE_BLE_HEADSET) {
                            am.setMode(AudioManager.MODE_IN_COMMUNICATION);
                            scoOn = am.setCommunicationDevice(d);
                            break;
                        }
                    }
                } else if (am.isBluetoothScoAvailableOffCall()) {
                    am.setMode(AudioManager.MODE_IN_COMMUNICATION);
                    am.startBluetoothSco();
                    am.setBluetoothScoOn(true);
                    scoOn = true;
                }
            } else if (scoOn) {
                if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice();
                else { am.setBluetoothScoOn(false); am.stopBluetoothSco(); }
                am.setMode(AudioManager.MODE_NORMAL);
                scoOn = false;
            }
        } catch (Throwable ignored) { }
    }

    /** A headset that connects while the hot mic is on gets used straight away. */
    private void watchDevices(boolean on) {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (am == null) return;
        if (on && deviceWatch == null) {
            deviceWatch = new AudioDeviceCallback() {
                @Override public void onAudioDevicesAdded(AudioDeviceInfo[] added) {
                    if (hot && !scoOn) { headsetMic(true); if (scoOn) restartWake(); }
                }
                @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) {
                    for (AudioDeviceInfo d : removed) {
                        int ty = d.getType();
                        if (ty == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || (Build.VERSION.SDK_INT >= 31 && ty == AudioDeviceInfo.TYPE_BLE_HEADSET)) {
                            scoOn = false;
                            try { am.setMode(AudioManager.MODE_NORMAL); } catch (Throwable ignored) { }
                        }
                    }
                }
            };
            am.registerAudioDeviceCallback(deviceWatch, ui);
        } else if (!on && deviceWatch != null) {
            am.unregisterAudioDeviceCallback(deviceWatch);
            deviceWatch = null;
        }
    }

    private static String clean(String s) {
        return s.replaceAll("[*_`#>]", "").replaceAll("https?://\\S+", "the link").trim();
    }

    private static String shorten(String s, int n) {
        s = s.replace('\n', ' ');
        return s.length() <= n ? s : s.substring(0, n - 1) + "…";
    }
}
