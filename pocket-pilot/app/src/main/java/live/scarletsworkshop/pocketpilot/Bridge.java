package live.scarletsworkshop.pocketpilot;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Talks to voice_bridge.py inside the Linux Terminal VM. Call from a background thread. */
public final class Bridge {
    public static final String PREFS = "pilot";
    public static final String DEFAULT_URL = "http://127.0.0.1:8765";

    private final String base;
    private final String token;

    public Bridge(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String u = p.getString("url", DEFAULT_URL).trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        if (!u.startsWith("http")) u = "http://" + u;
        base = u;
        token = p.getString("token", "").trim();
    }

    public JSONObject get(String path) throws Exception {
        return call("GET", path, null, 8000);
    }

    public JSONObject post(String path, JSONObject body) throws Exception {
        return call("POST", path, body, 15000);
    }

    /**
     * Long-poll: wait up to readTimeoutMs for the bridge to hand over work.
     * Returns null when nothing came (204 or an empty body).
     */
    public JSONObject longPoll(String path, int readTimeoutMs) throws Exception {
        JSONObject o = call("GET", path, null, readTimeoutMs + 5000);
        int code = o.optInt("_code", 200);
        if (code == 204 || o.length() <= 1) return null;
        if (code >= 400) throw new Exception("bridge said " + code);
        return o;
    }

    private JSONObject call(String method, String path, JSONObject body, int timeoutMs) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(4000);
        c.setReadTimeout(timeoutMs);
        c.setRequestProperty("X-Token", token);
        c.setRequestProperty("Content-Type", "application/json");
        if (body != null) {
            c.setDoOutput(true);
            byte[] b = body.toString().getBytes("UTF-8");
            c.setFixedLengthStreamingMode(b.length);
            OutputStream os = c.getOutputStream();
            os.write(b);
            os.close();
        }
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String text = "";
        if (in != null) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            text = bo.toString("UTF-8");
        }
        c.disconnect();
        JSONObject o = text.isEmpty() ? new JSONObject() : new JSONObject(text);
        o.put("_code", code);
        if (code == 401) throw new Exception("The token doesn't match. Check it in Pocket Pilot.");
        return o;
    }
}
