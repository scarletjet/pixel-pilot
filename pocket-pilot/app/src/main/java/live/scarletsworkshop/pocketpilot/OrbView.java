package live.scarletsworkshop.pocketpilot;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** The round floating button. Colour shows what it's doing. */
public class OrbView extends View {
    public static final int IDLE = 0, LISTENING = 1, THINKING = 2, SPEAKING = 3, ERROR = 4;
    /** Hot mic on, waiting for the wake word: a calm colour with a thin ring. Nothing is being sent. */
    public static final int STANDBY = 5;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glyph = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int state = IDLE;
    private float pulse = 0f;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            pulse += 0.08f;
            if (pulse > 1f) pulse = 0f;
            invalidate();
            if (state == LISTENING || state == THINKING) postDelayed(this, 40);
        }
    };

    public OrbView(Context c) {
        super(c);
        ring.setStyle(Paint.Style.STROKE);
        glyph.setColor(Color.WHITE);
        glyph.setStyle(Paint.Style.FILL);
    }

    public void setState(int s) {
        state = s;
        removeCallbacks(tick);
        if (s == LISTENING || s == THINKING) post(tick);
        invalidate();
    }

    public int getState() { return state; }

    private int color() {
        switch (state) {
            case LISTENING: return 0xFFE53950;
            case THINKING: return 0xFFFFA726;
            case SPEAKING: return 0xFF26A69A;
            case ERROR: return 0xFF757575;
            case STANDBY: return 0xFF455A64;
            default: return 0xFF7C4DFF;
        }
    }

    @Override
    protected void onDraw(Canvas cv) {
        float w = getWidth(), h = getHeight();
        float cx = w / 2f, cy = h / 2f;
        float r = Math.min(w, h) / 2f * 0.78f;
        int col = color();
        if (state == LISTENING || state == THINKING) {
            ring.setColor((col & 0x00FFFFFF) | ((int) (160 * (1 - pulse)) << 24));
            ring.setStrokeWidth(r * 0.18f);
            cv.drawCircle(cx, cy, r + r * 0.25f * pulse, ring);
        }
        if (state == STANDBY) {
            ring.setColor(0x99FFFFFF);
            ring.setStrokeWidth(r * 0.07f);
            cv.drawCircle(cx, cy, r * 1.12f, ring);
        }
        fill.setColor(col);
        fill.setShadowLayer(r * 0.15f, 0, r * 0.06f, 0x66000000);
        cv.drawCircle(cx, cy, r, fill);
        // microphone glyph
        float mw = r * 0.34f, mh = r * 0.62f;
        RectF body = new RectF(cx - mw / 2, cy - mh * 0.75f, cx + mw / 2, cy + mh * 0.18f);
        cv.drawRoundRect(body, mw / 2, mw / 2, glyph);
        Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
        arc.setColor(Color.WHITE);
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeWidth(r * 0.08f);
        RectF a = new RectF(cx - mw * 0.95f, cy - mh * 0.45f, cx + mw * 0.95f, cy + mh * 0.42f);
        cv.drawArc(a, 20, 140, false, arc);
        cv.drawLine(cx, cy + mh * 0.42f, cx, cy + mh * 0.6f, arc);
    }
}
