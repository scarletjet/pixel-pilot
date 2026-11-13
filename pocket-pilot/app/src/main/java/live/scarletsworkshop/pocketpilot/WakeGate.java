package live.scarletsworkshop.pocketpilot;

import org.vosk.Model;
import org.vosk.Recognizer;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The wake word gate. Everything here runs on the phone, in memory, with no network.
 *
 * Audio arrives in 0.1 s chunks and goes straight into an offline Vosk recognizer; the caller
 * zeroes each chunk afterwards, so no recording is kept anywhere, not even a ring buffer.
 * The recognizer's own working memory holds at most the current stretch of speech.
 *
 * STANDBY: at every pause the recognizer's text is checked for the wake phrase and then thrown
 * away, and the recognizer is reset. Nothing is shown, stored or sent. A long stretch without a
 * pause (a TV, say) is cut off and dropped every 15 s, so even working memory stays small.
 *
 * COMMAND: once the wake phrase shows up (even mid-sentence, in the live partial result), the
 * indicator turns on and the gate keeps listening until you pause. The request is the text after
 * the wake phrase. If the finished sentence turns out not to contain the wake phrase after all
 * (partials flicker), it's dropped as a false wake. "Hey Pilot" on its own asks "Yes?" and waits a
 * few seconds for the request.
 *
 * Plain Java + org.vosk only, so the same class runs in the app and in the desktop test.
 * Time is counted in samples fed, not wall-clock, so behaviour is the same in tests.
 */
public final class WakeGate {
    public interface Listener {
        /** The wake phrase was heard: show the "listening for your request" indicator. */
        void onWake();
        /** Request words so far (wake phrase removed), for the live caption. */
        void onPartial(String text);
        /** The wake phrase alone, with no request yet: say "Yes?" and keep listening a little longer. */
        void onWakeOnly();
        /** The finished request, wake phrase removed. Never empty. */
        void onCommand(String text);
        /** The finished sentence didn't contain the wake phrase after all: nothing is kept or sent. */
        void onFalseWake();
        /** Woke, but nothing followed in time. Nothing is kept or sent. */
        void onTimeout();
    }

    public static final int STANDBY = 0, COMMAND = 1;

    private static final Pattern TEXT = Pattern.compile("\"(?:text|partial)\"\\s*:\\s*\"([^\"]*)\"");

    private final int rate;
    private final Listener out;
    private final Recognizer rec;
    private final String[] wake;       // {"hey", "pilot"}
    private final boolean relaxed;

    private int state = STANDBY;
    private long samples = 0;          // samples fed since the gate was made
    private long utterStart = 0, wokeAt, lastSpeech;
    private boolean confirmed, promptedYes, speaking;
    private final StringBuilder said = new StringBuilder();

    private long silence;              // samples of quiet that end a request
    private final long wakeOnlyWait, maxCommand, maxStandbyUtterance;

    /**
     * @param phrase    wake phrase, for example "hey pilot"
     * @param relaxed   also wake on the last word alone ("Pilot, open maps") when it starts a sentence
     * @param silenceMs how long a pause ends the request
     */
    public WakeGate(Model model, String phrase, boolean relaxed, int sampleRate, int silenceMs,
                    Listener listener) throws java.io.IOException {
        this.rate = sampleRate;
        this.out = listener;
        String p = normalize(phrase);
        if (p.isEmpty()) throw new IllegalArgumentException("empty wake phrase");
        this.wake = p.split(" ");
        this.relaxed = relaxed;
        this.rec = new Recognizer(model, sampleRate);
        this.silence = ms(silenceMs);
        this.wakeOnlyWait = ms(6000);
        this.maxCommand = ms(25000);
        this.maxStandbyUtterance = ms(15000);
    }

    /** Lowercase letters (any language), apostrophes and single spaces. */
    public static String normalize(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{M}' ]", " ").replaceAll("\\s+", " ").trim();
    }

    public int state() { return state; }

    public void setSilenceMs(int ms) { silence = ms(ms); }

    private long ms(long m) { return m * rate / 1000; }

    /** Feed one chunk of 16-bit mono audio. Call from the audio thread only. The caller zeroes buf after. */
    public void feed(short[] buf, int n) {
        if (n <= 0) return;
        samples += n;
        boolean end = rec.acceptWaveForm(buf, n);
        if (state == STANDBY) standby(end); else command(end);
    }

    private void standby(boolean end) {
        if (end) {
            String t = text(rec.getResult());       // Vosk resets itself after a result
            utterStart = samples;
            if (findWake(t) < 0) return;             // not for us: the text is dropped right here
            wakeUp();
            take(t);
            speaking = false;
            return;
        }
        String p = text(rec.getPartialResult());
        if (p.isEmpty()) { utterStart = samples; return; }
        if (findWake(p) >= 0) { wakeUp(); return; } // carry on in COMMAND with the same sentence
        if (samples - utterStart > maxStandbyUtterance) {   // endless noise: drop it and start over
            rec.reset();
            utterStart = samples;
        }
    }

    private void wakeUp() {
        state = COMMAND;
        said.setLength(0);
        confirmed = false;
        promptedYes = false;
        speaking = true;
        wokeAt = samples;
        lastSpeech = samples;
        out.onWake();
    }

    private void command(boolean end) {
        if (end) {
            take(text(rec.getResult()));
            speaking = false;
        } else {
            String p = text(rec.getPartialResult());
            if (!p.isEmpty()) {
                lastSpeech = samples;
                speaking = true;
                String shown = request(join(said.toString(), p));
                if (shown != null && !shown.isEmpty()) out.onPartial(shown);
            }
        }
        if (samples - wokeAt > maxCommand) { take(text(rec.getFinalResult())); finishIfDone(true); return; }
        finishIfDone(false);
    }

    private void take(String t) {
        if (t.isEmpty()) return;
        if (said.length() > 0) said.append(' ');
        said.append(t);
        lastSpeech = samples;
    }

    private void finishIfDone(boolean force) {
        long quiet = samples - lastSpeech;
        if (said.length() == 0) {
            if (force || (!speaking && quiet > wakeOnlyWait)) {
                if (confirmed || !speaking) out.onTimeout(); else out.onFalseWake();
                wipe();
            }
            return;
        }
        if (!force && (speaking || quiet <= silence)) return;
        String req = request(said.toString());
        if (req == null) { out.onFalseWake(); wipe(); return; }
        if (req.isEmpty()) {             // "Hey Pilot" ... (pause)
            if (promptedYes) { out.onTimeout(); wipe(); return; }
            promptedYes = true;
            confirmed = true;
            said.setLength(0);
            lastSpeech = samples;
            wokeAt = samples;
            out.onWakeOnly();
            return;
        }
        out.onCommand(req);
        wipe();
    }

    /**
     * The request part: everything after the wake phrase. Null when the wake phrase isn't there
     * (and wasn't already confirmed by an earlier "Hey Pilot").
     */
    String request(String all) {
        String t = normalize(all);
        String[] w = t.isEmpty() ? new String[0] : t.split(" ");
        int at = findWake(w);
        if (at >= 0) return tail(w, at);
        return confirmed ? t : null;
    }

    /** Index just after the wake phrase, or -1. */
    int findWake(String text) {
        String t = normalize(text);
        return t.isEmpty() ? -1 : findWake(t.split(" "));
    }

    private int findWake(String[] w) {
        int n = wake.length;
        for (int i = 0; i + n <= w.length; i++) {
            boolean ok = true;
            for (int k = 0; k < n && ok; k++) ok = close(w[i + k], wake[k], k == n - 1);
            if (ok) return i + n;
        }
        if (relaxed && n > 1) {          // "Pilot, ..." at the start of a sentence (one filler word allowed)
            String last = wake[n - 1];
            for (int i = 0; i < Math.min(2, w.length); i++) if (w[i].equals(last)) return i + 1;
        }
        return -1;
    }

    /** The distinctive last word must match exactly; earlier words ("hey") may be a letter off ("hay"). */
    private static boolean close(String heard, String want, boolean exact) {
        if (heard.equals(want)) return true;
        if (exact) return false;
        return edit(heard, want) <= (want.length() <= 4 ? 1 : 2);
    }

    private static int edit(String a, String b) {
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++)
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
            int[] s = prev; prev = cur; cur = s;
        }
        return prev[b.length()];
    }

    private static String tail(String[] w, int from) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < w.length; i++) sb.append(sb.length() > 0 ? " " : "").append(w[i]);
        return sb.toString();
    }

    /**
     * Claude just asked a question ("Should I send it?"): take the answer without the wake word,
     * for a few seconds only, with the indicator on. Empty or late answers are dropped as usual.
     */
    public void followUp() {
        wipe();
        state = COMMAND;
        confirmed = true;
        promptedYes = true;
        wokeAt = samples;
        lastSpeech = samples;
        out.onWake();
    }

    /** Drop everything heard so far and go back to waiting for the wake phrase. */
    public void wipe() {
        said.setLength(0);
        rec.reset();
        confirmed = false;
        promptedYes = false;
        speaking = false;
        utterStart = samples;
        state = STANDBY;
    }

    /** For tests: true when nothing heard is held. */
    boolean isClear() { return state == STANDBY && said.length() == 0; }

    public void close() {
        wipe();
        try { rec.close(); } catch (Exception ignored) { }
    }

    static String text(String json) {
        if (json == null) return "";
        Matcher m = TEXT.matcher(json);
        if (!m.find()) return "";
        return m.group(1).replace("[unk]", " ").replaceAll("\\s+", " ").trim();
    }

    private static String join(String a, String b) {
        return a.isEmpty() ? b : b.isEmpty() ? a : a + " " + b;
    }
}
