package live.scarletsworkshop.pocketpilot;

import android.content.Context;

import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The offline speech model the wake word runs on. Downloaded once (about 40 MB) into the app's
 * private storage, then everything is on the phone. No audio or text ever goes to this server:
 * the download is a plain file fetch, and after it the model works with the network off.
 */
final class WakeModel {
    static final String DEFAULT_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip";
    private static volatile Model loaded;
    private static volatile String loadedPath;

    interface Progress { void update(String text); }

    static File dir(Context c) { return new File(c.getFilesDir(), "wake-model"); }

    static boolean present(Context c) {
        File d = dir(c);
        return new File(d, "am/final.mdl").isFile() && new File(d, "conf/model.conf").isFile();
    }

    /** Loads the model once and keeps it for the life of the process. Slow (a second or two): not on the UI thread. */
    static Model get(Context c) throws IOException {
        File d = dir(c);
        if (loaded != null && d.getPath().equals(loadedPath)) return loaded;
        synchronized (WakeModel.class) {
            if (loaded != null && d.getPath().equals(loadedPath)) return loaded;
            if (!present(c)) throw new IOException("The wake word model isn't downloaded yet.");
            LibVosk.setLogLevel(LogLevel.WARNINGS);
            loaded = new Model(d.getPath());
            loadedPath = d.getPath();
            return loaded;
        }
    }

    /** Downloads and unpacks a Vosk model zip. Replaces any earlier model only when the new one is complete. */
    static void download(Context c, String url, Progress p) throws Exception {
        if (!url.startsWith("https://")) throw new IOException("The model link has to start with https://");
        File zip = new File(c.getCacheDir(), "wake-model.zip");
        File tmp = new File(c.getFilesDir(), "wake-model.part");
        deleteTree(tmp);
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(15000);
        con.setReadTimeout(30000);
        con.setInstanceFollowRedirects(true);
        int code = con.getResponseCode();
        if (code != 200) throw new IOException("The download answered " + code + ".");
        long total = con.getContentLengthLong();
        try (InputStream in = new BufferedInputStream(con.getInputStream());
             OutputStream out = new FileOutputStream(zip)) {
            byte[] b = new byte[64 * 1024];
            long got = 0, shown = 0;
            int n;
            while ((n = in.read(b)) > 0) {
                out.write(b, 0, n);
                got += n;
                if (got - shown > 1_000_000) {
                    shown = got;
                    p.update(total > 0 ? ("Downloading the wake word model: " + (got * 100 / total) + "%")
                            : ("Downloading the wake word model: " + (got / 1_000_000) + " MB"));
                }
            }
        } finally {
            con.disconnect();
        }
        p.update("Unpacking...");
        unzip(zip, tmp);
        zip.delete();
        if (!new File(tmp, "am/final.mdl").isFile() || !new File(tmp, "conf/model.conf").isFile()) {
            deleteTree(tmp);
            throw new IOException("That file doesn't look like a Vosk model.");
        }
        synchronized (WakeModel.class) {
            if (loaded != null) { try { loaded.close(); } catch (Exception ignored) { } loaded = null; loadedPath = null; }
            File d = dir(c);
            deleteTree(d);
            if (!tmp.renameTo(d)) throw new IOException("Couldn't save the model.");
        }
        p.update("Wake word model ready.");
    }

    /** Unzips, dropping the model's own top folder (vosk-model-.../), and refuses paths that climb out. */
    private static void unzip(File zip, File into) throws IOException {
        String root = into.getCanonicalPath() + File.separator;
        try (ZipInputStream z = new ZipInputStream(new BufferedInputStream(new java.io.FileInputStream(zip)))) {
            ZipEntry e;
            byte[] b = new byte[64 * 1024];
            while ((e = z.getNextEntry()) != null) {
                String name = e.getName();
                int slash = name.indexOf('/');
                if (slash < 0) continue;                 // files at the top level aren't part of a model
                name = name.substring(slash + 1);
                if (name.isEmpty()) continue;
                File f = new File(into, name);
                if (!f.getCanonicalPath().startsWith(root)) throw new IOException("Bad path in the model zip.");
                if (e.isDirectory()) { f.mkdirs(); continue; }
                f.getParentFile().mkdirs();
                try (OutputStream out = new FileOutputStream(f)) {
                    int n;
                    while ((n = z.read(b)) > 0) out.write(b, 0, n);
                }
            }
        }
    }

    static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }
}
