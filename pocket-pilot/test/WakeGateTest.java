package live.scarletsworkshop.pocketpilot;
import org.vosk.*;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
public class WakeGateTest {
  public static void main(String[] a) throws Exception {
    LibVosk.setLogLevel(LogLevel.WARNINGS);
    Model m = new Model(a[0]);
    String phrase = a[1]; boolean relaxed = a[2].equals("relaxed");
    for (int k = 3; k < a.length; k++) {
      final List<String> ev = new ArrayList<>();
      WakeGate g = new WakeGate(m, phrase, relaxed, 16000, 1200, new WakeGate.Listener() {
        public void onWake() { ev.add("WAKE"); }
        public void onPartial(String t) { if (System.getenv("PARTIALS")!=null) ev.add("p:"+t); }
        public void onWakeOnly() { ev.add("YES?"); }
        public void onCommand(String t) { ev.add("CMD[" + t + "]"); }
        public void onFalseWake() { ev.add("FALSE_WAKE"); }
        public void onTimeout() { ev.add("TIMEOUT"); }
      });
      byte[] raw = Files.readAllBytes(Paths.get(a[k]));
      short[] all = new short[raw.length / 2];
      ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(all);
      short[] buf = new short[1600];
      for (int off = 0; off + 1600 <= all.length; off += 1600) {
        System.arraycopy(all, off, buf, 0, 1600);
        g.feed(buf, 1600);
        Arrays.fill(buf, (short)0);
      }
      // 8 s of silence afterwards
      for (int i = 0; i < 80; i++) g.feed(buf, 1600);
      System.out.println(new File(a[k]).getName() + " -> " + ev + "  clear=" + g.isClear());
      g.close();
    }
  }
}
