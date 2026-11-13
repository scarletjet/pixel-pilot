package live.scarletsworkshop.pocketpilot;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

/** One screen: where the bridge is, the token, permissions, start/stop the bubble. */
public class MainActivity extends Activity {
    private EditText url, token;
    private CheckBox autoListen, speak, speakProgress, offline, hotMic, headsetMic, wakeRelaxed, wakeBuzz;
    private EditText silence, wakePhrase, modelUrl;
    private TextView modelState;
    private volatile boolean busy = false;
    private TextView status;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        final SharedPreferences p = getSharedPreferences(Bridge.PREFS, MODE_PRIVATE);
        int pad = dp(20);

        ScrollView sv = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(pad, pad + dp(24), pad, pad);
        sv.addView(col);

        TextView title = new TextView(this);
        title.setText("Pocket Pilot");
        title.setTextSize(28);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(title);

        TextView sub = new TextView(this);
        sub.setText("A floating bubble that talks to Claude in your Linux Terminal.");
        sub.setPadding(0, dp(4), 0, dp(16));
        col.addView(sub);

        col.addView(label("Bridge address"));
        url = field(p.getString("url", Bridge.DEFAULT_URL), InputType.TYPE_TEXT_VARIATION_URI);
        col.addView(url);

        col.addView(label("Token (run  pilot token  in the terminal)"));
        token = field(p.getString("token", ""), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        col.addView(token);

        speak = new CheckBox(this);
        speak.setText("Speak replies out loud");
        speak.setChecked(p.getBoolean("speak", true));
        col.addView(speak);

        autoListen = new CheckBox(this);
        autoListen.setText("Listen again when Claude asks a question");
        autoListen.setChecked(p.getBoolean("auto_listen", true));
        col.addView(autoListen);

        speakProgress = new CheckBox(this);
        speakProgress.setText("Say what Claude is doing while it works");
        speakProgress.setChecked(p.getBoolean("speak_progress", true));
        col.addView(speakProgress);

        offline = new CheckBox(this);
        offline.setText("Faster speech recognition on the phone (offline)");
        offline.setChecked(p.getBoolean("offline_speech", true));
        col.addView(offline);

        col.addView(label("Hot mic"));
        TextView hotHelp = new TextView(this);
        hotHelp.setText("The hot mic listens for your wake word using a speech model on this phone. "
                + "Everything you say without the wake word is checked on the phone and dropped at once. "
                + "No audio is ever saved, and nothing is sent until you say the wake word.");
        hotHelp.setTextColor(Color.GRAY);
        col.addView(hotHelp);

        hotMic = new CheckBox(this);
        hotMic.setText("Hot mic on when the bubble starts");
        hotMic.setChecked(p.getBoolean("hot_mic", false));
        col.addView(hotMic);

        headsetMic = new CheckBox(this);
        headsetMic.setText("Hot mic uses the Bluetooth headset's own mic (so its mute button works)");
        headsetMic.setChecked(p.getBoolean("headset_mic", true));
        col.addView(headsetMic);

        col.addView(label("Wake word"));
        wakePhrase = field(p.getString("wake_phrase", "Hey Pilot"), InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        col.addView(wakePhrase);
        TextView wakeHelp = new TextView(this);
        wakeHelp.setText("Two or three words work best, ending in a word you don't say often. "
                + "Use Test wake word below to check the phone hears it.");
        wakeHelp.setTextColor(Color.GRAY);
        col.addView(wakeHelp);

        wakeRelaxed = new CheckBox(this);
        wakeRelaxed.setText("Also wake on the last word alone at the start of a sentence (\"Pilot, open maps\"). Handier, but wakes by mistake more often");
        wakeRelaxed.setChecked(p.getBoolean("wake_relaxed", false));
        col.addView(wakeRelaxed);

        wakeBuzz = new CheckBox(this);
        wakeBuzz.setText("Short buzz when it hears the wake word");
        wakeBuzz.setChecked(p.getBoolean("wake_buzz", true));
        col.addView(wakeBuzz);

        col.addView(label("Wake word model (download link, https)"));
        modelUrl = field(p.getString("model_url", WakeModel.DEFAULT_URL), InputType.TYPE_TEXT_VARIATION_URI);
        col.addView(modelUrl);
        modelState = new TextView(this);
        modelState.setPadding(0, dp(4), 0, 0);
        col.addView(modelState);
        col.addView(button("Download wake word model (about 40 MB, once)", new View.OnClickListener() {
            @Override public void onClick(View v) { save(); downloadModel(); }
        }));
        col.addView(button("Test wake word (10 seconds)", new View.OnClickListener() {
            @Override public void onClick(View v) { save(); testWake(); }
        }));

        col.addView(label("Mic waits this long after you stop talking (ms)"));
        silence = field(String.valueOf(p.getInt("silence_ms", 1200)), InputType.TYPE_CLASS_NUMBER);
        silence.setInputType(InputType.TYPE_CLASS_NUMBER);
        col.addView(silence);

        status = new TextView(this);
        status.setPadding(0, dp(12), 0, dp(12));
        col.addView(status);

        col.addView(button("1. Allow drawing over apps", new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            }
        }));
        col.addView(button("2. Allow microphone and notifications", new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (Build.VERSION.SDK_INT >= 33) {
                    requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO,
                            Manifest.permission.POST_NOTIFICATIONS}, 1);
                } else {
                    requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
                }
            }
        }));
        col.addView(button("3. Turn on fast control (Accessibility)", new View.OnClickListener() {
            @Override public void onClick(View v) {
                status.setText("Find Pocket Pilot fast control and switch it on. If it's greyed out: "
                        + "App info > ⋮ (top right) > Allow restricted settings, then come back.");
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            }
        }));
        col.addView(button("Open App info (for Allow restricted settings)", new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName())));
            }
        }));
        col.addView(button("4. Test connection", new View.OnClickListener() {
            @Override public void onClick(View v) { save(); test(); }
        }));
        col.addView(button("5. Start bubble", new View.OnClickListener() {
            @Override public void onClick(View v) {
                save();
                if (!Settings.canDrawOverlays(MainActivity.this)) {
                    status.setText("Step 1 first: allow drawing over apps.");
                    return;
                }
                Intent i = new Intent(MainActivity.this, BubbleService.class);
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
                status.setText("Bubble is on. Tap it to talk, hold it for the chat panel.");
                moveTaskToBack(true);
            }
        }));
        col.addView(button("Stop bubble", new View.OnClickListener() {
            @Override public void onClick(View v) {
                stopService(new Intent(MainActivity.this, BubbleService.class));
                status.setText("Bubble stopped.");
            }
        }));

        TextView help = new TextView(this);
        help.setPadding(0, dp(16), 0, 0);
        help.setTextColor(Color.GRAY);
        help.setText("Tap the bubble: talk. Tap again: stop talking or stop listening.\n"
                + "Hold the bubble: chat panel with typing, history, new chat.\n"
                + "Talk mode (in the chat panel): hands-free, it listens again after every reply. "
                + "Say \"that's all\" or \"stop\" to end it.\n"
                + "Drag it anywhere. While Claude works, the bubble shrinks to a small dot so it never covers what Claude taps; "
                + "progress also shows in the notification, where Stop interrupts.\n\n"
                + "Claude asks before sending, paying, deleting or changing settings. Just say yes or no.");
        col.addView(help);

        setContentView(sv);
        refreshStatus();
        refreshModel();
    }

    @Override protected void onResume() { super.onResume(); refreshStatus(); }

    private void refreshStatus() {
        if (status == null) return;
        StringBuilder s = new StringBuilder();
        s.append(Settings.canDrawOverlays(this) ? "Overlay: allowed\n" : "Overlay: not yet\n");
        s.append(checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                ? "Microphone: allowed\n" : "Microphone: not yet\n");
        s.append(PilotAccessibilityService.instance != null
                ? "Fast control: on" : "Fast control: off (step 3, falls back to slower adb)");
        status.setText(s.toString());
    }

    private void save() {
        SharedPreferences old = getSharedPreferences(Bridge.PREFS, MODE_PRIVATE);
        boolean wakeChanged = !old.getString("wake_phrase", "Hey Pilot").equals(wakePhrase.getText().toString().trim())
                || old.getBoolean("wake_relaxed", false) != wakeRelaxed.isChecked()
                || old.getBoolean("headset_mic", true) != headsetMic.isChecked()
                || old.getInt("silence_ms", 1200) != silenceMs();
        saveNow();
        if (wakeChanged) BubbleService.onWakeSettingsChanged();
    }

    private void saveNow() {
        getSharedPreferences(Bridge.PREFS, MODE_PRIVATE).edit()
                .putString("url", url.getText().toString().trim())
                .putString("token", token.getText().toString().trim())
                .putBoolean("speak", speak.isChecked())
                .putBoolean("auto_listen", autoListen.isChecked())
                .putBoolean("speak_progress", speakProgress.isChecked())
                .putBoolean("offline_speech", offline.isChecked())
                .putBoolean("hot_mic", hotMic.isChecked())
                .putBoolean("headset_mic", headsetMic.isChecked())
                .putString("wake_phrase", wakePhrase.getText().toString().trim())
                .putBoolean("wake_relaxed", wakeRelaxed.isChecked())
                .putBoolean("wake_buzz", wakeBuzz.isChecked())
                .putString("model_url", modelUrl.getText().toString().trim())
                .putInt("silence_ms", silenceMs())
                .apply();
    }

    private int silenceMs() {
        try { return Math.max(300, Math.min(8000, Integer.parseInt(silence.getText().toString().trim()))); }
        catch (Exception e) { return 1200; }
    }

    @Override protected void onPause() { super.onPause(); save(); }

    private void refreshModel() {
        if (modelState == null) return;
        modelState.setText(WakeModel.present(this) ? "Model: on this phone, ready" : "Model: not downloaded yet (needed for the hot mic)");
    }

    private void say(final String m) {
        runOnUiThread(new Runnable() { @Override public void run() { status.setText(m); } });
    }

    private void downloadModel() {
        if (busy) return;
        busy = true;
        final String link = modelUrl.getText().toString().trim();
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    WakeModel.download(MainActivity.this, link, new WakeModel.Progress() {
                        @Override public void update(String text) { say(text); }
                    });
                } catch (Exception e) {
                    say("Download didn't work: " + e.getMessage());
                } finally {
                    busy = false;
                    runOnUiThread(new Runnable() { @Override public void run() { refreshModel(); } });
                }
            }
        }).start();
    }

    /** Listens for 10 seconds and shows what the wake word gate did. Nothing is saved or sent. */
    private void testWake() {
        if (busy) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            status.setText("Step 2 first: allow the microphone.");
            return;
        }
        if (!WakeModel.present(this)) { status.setText("Download the wake word model first."); return; }
        if (BubbleService.isHotOn()) { status.setText("Turn the hot mic off in the bubble first, then test."); return; }
        busy = true;
        final String phrase = wakePhrase.getText().toString().trim();
        final boolean relaxed = wakeRelaxed.isChecked();
        final int silenceMs = silenceMs();
        status.setText("Loading...");
        new Thread(new Runnable() {
            @Override public void run() {
                final StringBuilder log = new StringBuilder();
                WakeListener w = null;
                try {
                    org.vosk.Model m = WakeModel.get(MainActivity.this);
                    say("Listening for 10 seconds. Say \"" + phrase + "\" and a request, like \"" + phrase + ", open the camera\".");
                    w = new WakeListener(m, phrase, relaxed, silenceMs, false,
                            (android.media.AudioManager) getSystemService(AUDIO_SERVICE), new WakeGate.Listener() {
                        void add(String t) { log.append(t).append('\n'); say(log.toString()); }
                        @Override public void onWake() { add("Heard the wake word."); }
                        @Override public void onPartial(String t) { }
                        @Override public void onWakeOnly() { add("Wake word on its own: it would ask \"Yes?\""); }
                        @Override public void onCommand(String t) { add("Request it would send: \"" + t + "\""); }
                        @Override public void onFalseWake() { add("Not the wake word after all: dropped."); }
                        @Override public void onTimeout() { add("Nothing followed: dropped."); }
                    });
                    w.start();
                    Thread.sleep(10000);
                    w.stop();
                    Thread.sleep(300);
                    if (w.failure != null) log.append("Mic problem: ").append(w.failure).append('\n');
                    say((log.length() == 0 ? "Didn't hear the wake word. Try saying it a little slower, or pick other words.\n" : log.toString())
                            + "Test finished. Nothing was saved or sent.");
                } catch (Exception e) {
                    say("Test didn't work: " + e.getMessage());
                } finally {
                    if (w != null) w.stop();
                    busy = false;
                }
            }
        }).start();
    }

    private void test() {
        status.setText("Testing...");
        new Thread(new Runnable() {
            @Override public void run() {
                String msg;
                try {
                    JSONObject o = new Bridge(MainActivity.this).get("/health");
                    msg = o.optBoolean("ok") ? ("Connected to the terminal.\nFast control: "
                            + (o.optBoolean("agent") ? "linked" : (PilotAccessibilityService.instance != null ? "on, linking..." : "off"))
                            + "\nadb fallback: "
                            + (o.optBoolean("phone") ? "ready" : "not connected (optional, phonectl connect)"))
                            : "Bridge answered oddly: " + o;
                } catch (Exception e) {
                    msg = "Can't reach the bridge: " + e.getMessage()
                            + "\nIn the terminal run  pilot start , and allow port 8765 in Terminal settings > Port control.";
                }
                final String m = msg;
                runOnUiThread(new Runnable() { @Override public void run() { status.setText(m); } });
            }
        }).start();
    }

    private TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setPadding(0, dp(10), 0, dp(2));
        t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private EditText field(String v, int type) {
        EditText e = new EditText(this);
        e.setText(v);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | type);
        return e;
    }

    private Button button(String s, View.OnClickListener l) {
        Button btn = new Button(this);
        btn.setText(s);
        btn.setAllCaps(false);
        btn.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        btn.setLayoutParams(lp);
        btn.setOnClickListener(l);
        return btn;
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }
}
