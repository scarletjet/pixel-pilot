package live.scarletsworkshop.pocketpilot;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import org.vosk.Model;

import java.util.Arrays;

/**
 * Reads the microphone in 0.1 s chunks and hands each one to the WakeGate, then zeroes it.
 * No audio is written anywhere. While paused (Claude is talking, the phone mic is muted, or the
 * app asked for it) chunks are zeroed without being looked at, and anything half-heard is dropped.
 */
final class WakeListener implements Runnable {
    private static final int RATE = 16000;

    private final Model model;
    private final String phrase;
    private final boolean relaxed;
    private final int silenceMs;
    private final boolean communication;   // true when a Bluetooth headset mic is the input
    private final AudioManager am;
    private final WakeGate.Listener out;
    private volatile boolean running = false;
    private volatile boolean paused = false;
    private volatile boolean wipeNow = false;
    private volatile boolean followNow = false;
    private Thread thread;
    volatile String failure;
    volatile Runnable onDied;   // called on the audio thread if the mic or model fails

    WakeListener(Model model, String phrase, boolean relaxed, int silenceMs, boolean communication,
                 AudioManager am, WakeGate.Listener out) {
        this.model = model;
        this.phrase = phrase;
        this.relaxed = relaxed;
        this.silenceMs = silenceMs;
        this.communication = communication;
        this.am = am;
        this.out = out;
    }

    void start() {
        running = true;
        thread = new Thread(this, "wake-word");
        thread.setPriority(Thread.MAX_PRIORITY - 1);
        thread.start();
    }

    void stop() {
        running = false;
        if (thread != null) thread.interrupt();
    }

    boolean isRunning() { return running; }

    /** Paused: the mic stays open but nothing is analysed, and half-heard speech is dropped. */
    void setPaused(boolean p) {
        if (p && !paused) wipeNow = true;
        paused = p;
    }

    /** Take the next answer without the wake word, for a few seconds (Claude asked a question). */
    void followUp() { followNow = true; }

    /** Drop whatever is half-heard (for example after Stop). */
    void wipe() { wipeNow = true; }

    @SuppressLint("MissingPermission")   // the service checks RECORD_AUDIO before starting this
    @Override public void run() {
        AudioRecord rec = null;
        WakeGate gate = null;
        short[] buf = new short[RATE / 10];
        try {
            gate = new WakeGate(model, phrase, relaxed, RATE, silenceMs, out);
            int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            int src = communication ? MediaRecorder.AudioSource.VOICE_COMMUNICATION : MediaRecorder.AudioSource.VOICE_RECOGNITION;
            rec = new AudioRecord(src, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(min, buf.length * 2 * 4));
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("The microphone didn't open.");
            rec.startRecording();
            while (running) {
                int n = rec.read(buf, 0, buf.length);
                if (n <= 0) { if (n < 0) Thread.sleep(50); continue; }
                if (wipeNow) { wipeNow = false; gate.wipe(); }
                if (followNow && !paused) { followNow = false; gate.followUp(); }
                boolean muted = am != null && am.isMicrophoneMute();
                if (!paused && !muted) gate.feed(buf, n);
                Arrays.fill(buf, (short) 0);
            }
        } catch (InterruptedException ignored) {
        } catch (Throwable t) {
            failure = t.getMessage() == null ? t.toString() : t.getMessage();
        } finally {
            running = false;
            Arrays.fill(buf, (short) 0);
            if (rec != null) {
                try { rec.stop(); } catch (Exception ignored) { }
                rec.release();
            }
            if (gate != null) gate.close();
            if (failure != null && onDied != null) onDied.run();
        }
    }
}
