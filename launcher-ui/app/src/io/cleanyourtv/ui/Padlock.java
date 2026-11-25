package io.cleanyourtv.ui;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

/** A padlock drawn with paths (the symbol set has none): parental control's icon in Settings and on its lock screen. */
final class Padlock {
    private Padlock() {}

    private static final Paint FILL = new Paint(Paint.ANTI_ALIAS_FLAG), STROKE = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Path PATH = new Path();
    private static final RectF R = new RectF();

    static {
        STROKE.setStyle(Paint.Style.STROKE);
        STROKE.setStrokeCap(Paint.Cap.ROUND);
    }

    /** About `size` tall, centred on cx, cy; the keyhole in `hole` (the colour behind it). Main thread only. */
    static void draw(Canvas c, float cx, float cy, float size, int color, int hole) {
        float bw = size * 0.78f, bh = size * 0.6f, top = cy - size * 0.02f, sr = bw * 0.3f, arcY = top - size * 0.12f;
        STROKE.setColor(color);
        STROKE.setStrokeWidth(size * 0.11f);
        PATH.reset();
        PATH.moveTo(cx - sr, top + size * 0.02f);
        PATH.lineTo(cx - sr, arcY);
        R.set(cx - sr, arcY - sr, cx + sr, arcY + sr);
        PATH.arcTo(R, 180, 180);
        PATH.lineTo(cx + sr, top + size * 0.02f);
        c.drawPath(PATH, STROKE);
        FILL.setColor(color);
        R.set(cx - bw / 2, top, cx + bw / 2, top + bh);
        c.drawRoundRect(R, size * 0.12f, size * 0.12f, FILL);
        FILL.setColor(hole);
        float kh = size * 0.085f;
        c.drawCircle(cx, top + bh * 0.42f, kh, FILL);
        R.set(cx - kh * 0.45f, top + bh * 0.42f, cx + kh * 0.45f, top + bh * 0.74f);
        c.drawRoundRect(R, kh * 0.3f, kh * 0.3f, FILL);
    }
}
