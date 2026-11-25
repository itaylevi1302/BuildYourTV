package io.cleanyourtv.ui;

import android.app.Activity;
import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;

/**
 * BuildYourTV parental control's lock screen (ParentalTimer): over whatever plays once the allowance is used up. "Add
 * time" asks for the parents' 4-digit code (an on-screen keypad, or the remote's digits), then how much more; "Turn off"
 * puts the TV to sleep, and so does a minute with no answer. Back does nothing; anything that covers it while the
 * allowance is still used up brings it back. Drawn on a canvas in the home screen's style; it lives in :keys, whose
 * process may start it over other apps.
 */
public class ParentalLockActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private LockView view;
    private boolean unlocked;

    private final Runnable comeBack = () -> {
        if (!unlocked) ParentalTimer.showLock(getApplicationContext());
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        Ui.init(this);
        view = new LockView(this);
        setContentView(view);
        view.requestFocus();
    }

    @Override protected void onStart() {
        super.onStart();
        handler.removeCallbacks(comeBack);
        if (!ParentalTimer.mustLock(this)) {
            finish();
            return;
        }
        view.shown();
    }

    @Override protected void onResume() {
        super.onResume();
        if (!ParentalTimer.mustLock(this)) finish();
    }

    @Override protected void onStop() {
        super.onStop();
        boolean on = getSystemService(PowerManager.class).isInteractive();
        if (!on) view.screenOff();
        // Another app (a remote button, the input menu) came over it: back on top while the time is still up.
        if (on && !unlocked && !isFinishing() && ParentalTimer.mustLock(this)) handler.postDelayed(comeBack, 600);
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        view.stop();
        super.onDestroy();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_DOWN) view.key(e.getKeyCode());
        int k = e.getKeyCode();
        // Volume and power stay the TV's; everything else is ours while locked.
        return !(k == KeyEvent.KEYCODE_VOLUME_UP || k == KeyEvent.KEYCODE_VOLUME_DOWN || k == KeyEvent.KEYCODE_VOLUME_MUTE
                || k == KeyEvent.KEYCODE_POWER || k == KeyEvent.KEYCODE_SLEEP) || super.dispatchKeyEvent(e);
    }

    @Override public void onBackPressed() {}

    void unlock(int minutes) {
        unlocked = true;
        ParentalTimer.start(this, minutes);
        finish();
    }

    static String t(String en, String he) { return Ui.he ? he : en; }

    /** The whole screen, drawn: background, the ring with the padlock, the words, and the buttons or the keypad. */
    private static final class LockView extends View {
        private static final int LOCK = 0, PIN = 1, MORE = 2;
        private static final String[] PAD = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "del", "0", "back"};

        private final ParentalLockActivity a;
        private int mode = LOCK, focus;
        private long deadline, lastKey, lastFrame;
        private float time, compact, shake, k = 1;
        private final float[] glow = new float[12];
        private String pin = "";
        private boolean wrong, running = true, turnOff;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG),
                shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Paint title, subtitle, small, button, padDigit, padWord;
        private final RectF r = new RectF();

        LockView(ParentalLockActivity a) {
            super(a);
            this.a = a;
            setFocusable(true);
            setFocusableInTouchMode(true);
            setLayoutDirection(LAYOUT_DIRECTION_LTR);   // mirrored by hand for Hebrew, like the home screen
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeCap(Paint.Cap.ROUND);
        }

        void shown() {
            if (deadline == 0) deadline = SystemClock.elapsedRealtime() + ParentalTimer.GRACE_MS;
            running = true;
            invalidate();
        }

        /** The TV went off: the next time it comes on, a full minute again. */
        void screenOff() {
            deadline = 0;
            mode = LOCK;
            focus = 0;
            pin = "";
            wrong = false;
        }

        void stop() { running = false; }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            k = w / 1920f;
            title = Ui.text(Ui.displaySemibold, 72 * k, 0xFFFFFFFF);
            title.setTextAlign(Paint.Align.CENTER);
            subtitle = Ui.text(Ui.regular, 32 * k, 0xB3FFFFFF);
            subtitle.setTextAlign(Paint.Align.CENTER);
            small = Ui.numbers(Ui.regular, 27 * k, 0x8CFFFFFF);
            small.setTextAlign(Paint.Align.CENTER);
            button = Ui.text(Ui.semibold, 31 * k, 0xFFFFFFFF);
            button.setTextAlign(Paint.Align.CENTER);
            padDigit = Ui.numbers(Ui.displayMedium, 44 * k, 0xFFFFFFFF);
            padDigit.setTextAlign(Paint.Align.CENTER);
            padWord = Ui.text(Ui.medium, 26 * k, 0xFFFFFFFF);
            padWord.setTextAlign(Paint.Align.CENTER);
            shadow.setMaskFilter(new BlurMaskFilter(26 * k, BlurMaskFilter.Blur.NORMAL));
        }

        private int count() { return mode == LOCK ? 3 : mode == PIN ? PAD.length : ParentalTimer.MORE.length + 1; }

        // MARK: - Keys

        void key(int code) {
            long now = SystemClock.elapsedRealtime();
            lastKey = now;
            if (mode != LOCK) deadline = now + ParentalTimer.GRACE_MS;   // a parent is answering: the minute waits
            boolean rtl = Ui.he;
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_LEFT: move(mode == PIN ? -1 : rtl ? 1 : -1, 0); break;
                case KeyEvent.KEYCODE_DPAD_RIGHT: move(mode == PIN ? 1 : rtl ? -1 : 1, 0); break;
                case KeyEvent.KEYCODE_DPAD_UP: move(0, -1); break;
                case KeyEvent.KEYCODE_DPAD_DOWN: move(0, 1); break;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    press();
                    break;
                case KeyEvent.KEYCODE_BACK:
                case KeyEvent.KEYCODE_ESCAPE:
                    if (mode != LOCK) toLock();
                    break;
                case KeyEvent.KEYCODE_DEL:
                    if (mode == PIN && !pin.isEmpty()) pin = pin.substring(0, pin.length() - 1);
                    break;
                default:
                    if (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9) {
                        if (mode == LOCK) toPin();
                        digit((char) ('0' + code - KeyEvent.KEYCODE_0));
                    }
            }
            invalidate();
        }

        private void move(int dx, int dy) {
            int n = count();
            if (mode == PIN) {
                int col = focus % 3, row = focus / 3;
                col = Math.max(0, Math.min(2, col + dx));
                row = Math.max(0, Math.min(3, row + dy));
                focus = row * 3 + col;
            } else if (dx != 0) {
                focus = Math.max(0, Math.min(n - 1, focus + dx));
            }
        }

        private void press() {
            if (mode == LOCK) {
                if (focus == 2) sleepNow();
                else {
                    turnOff = focus == 1;   // "Turn off parental control": the same code, then it is off
                    toPin();
                }
            } else if (mode == PIN) {
                String p = PAD[focus];
                if (p.equals("del")) {
                    if (!pin.isEmpty()) pin = pin.substring(0, pin.length() - 1);
                } else if (p.equals("back")) {
                    toLock();
                } else {
                    digit(p.charAt(0));
                }
            } else {
                if (focus < ParentalTimer.MORE.length) a.unlock(ParentalTimer.MORE[focus]);
                else toLock();
            }
        }

        private void digit(char d) {
            if (pin.length() >= 4 || ParentalTimer.lockoutLeft(a) > 0) return;
            wrong = false;
            pin += d;
            if (pin.length() == 4) {
                int result = ParentalTimer.tryPin(a, pin);
                if (result == ParentalTimer.PIN_OK && turnOff) {
                    a.unlock(0);   // parental control off, the lock goes
                } else if (result == ParentalTimer.PIN_OK) {
                    mode = MORE;
                    focus = 1;   // 30 minutes
                } else {
                    wrong = true;
                    shake = 1;
                    if (result == ParentalTimer.PIN_LOCKED_OUT) {   // too many wrong codes: back to the clock, TV off
                        toLock();
                        sleepNow();
                    }
                }
                pin = "";
            }
        }

        private void toPin() {
            mode = PIN;
            focus = 4;   // the keypad's middle, "5"
            pin = "";
            wrong = false;
        }

        private void toLock() {
            mode = LOCK;
            focus = 0;
            pin = "";
            wrong = false;
            turnOff = false;
        }

        private void sleepNow() {
            deadline = 0;
            if (!RemoteKeyService.sleep()) AppLog.w("parental lock: the key service is off, the TV stays on");
        }

        // MARK: - Drawing

        @Override protected void onDraw(Canvas c) {
            long now = SystemClock.elapsedRealtime();
            float dt = lastFrame == 0 ? 1 / 60f : Math.min(0.05f, (now - lastFrame) / 1000f);
            lastFrame = now;
            time += dt;
            if (mode != LOCK && now - lastKey > 30_000) toLock();   // a code left half-typed goes back to the clock
            if (mode == LOCK && deadline != 0 && now >= deadline) sleepNow();
            float ease = Math.min(1, dt * 12);
            compact += ((mode == LOCK ? 0 : 1) - compact) * ease;
            shake = Math.max(0, shake - dt * 2.5f);
            for (int i = 0; i < glow.length; i++) glow[i] += ((i == focus ? 1 : 0) - glow[i]) * Math.min(1, dt * 14);

            drawBackground(c);
            float cy = lerp(300, 205, compact) * k, radius = lerp(122, 92, compact) * k;
            drawRing(c, 960 * k, cy, radius, now);
            float ty = lerp(530, 372, compact) * k;
            boolean tampered = !ParentalTimer.locked(a);   // here only because the key service was switched off
            String head = mode == LOCK && tampered ? t("Parental control is paused", "בקרת ההורים הושהתה")
                    : mode == LOCK ? t("Time's up", "הזמן נגמר") : mode == PIN ? t("Parents' code", "קוד הורים")
                    : t("How much more time?", "כמה זמן להוסיף?");
            long lockout = ParentalTimer.lockoutLeft(a);
            String sub = mode == LOCK && tampered ? t("The remote buttons service was turned off. A parent's code is needed.",
                    "שירות כפתורי השלט כובה. נדרש קוד הורים.")
                    : mode == PIN && lockout > 0 ? t("Too many wrong codes. Try again in " + ((lockout + 59_999) / 60_000) + " min.",
                    "יותר מדי קודים שגויים. אפשר לנסות שוב בעוד " + ((lockout + 59_999) / 60_000) + " דק'.")
                    : mode == LOCK ? t("The viewing time you were given is over.", "זמן הצפייה שאושר הסתיים.")
                    : mode == PIN && turnOff ? t("Enter the code to turn parental control off", "הזינו את הקוד כדי לכבות את בקרת ההורים")
                    : mode == PIN ? t("Enter the 4-digit code", "הזינו את הקוד בן 4 הספרות")
                    : t("It counts from now.", "הזמן נספר מעכשיו.");
            c.drawText(head, 960 * k, ty, title);
            c.drawText(sub, 960 * k, ty + 62 * k, subtitle);

            if (mode == LOCK) drawLock(c, now);
            else if (mode == PIN) drawPin(c);
            else drawMore(c);
            if (running) postInvalidateOnAnimation();
        }

        private static float lerp(float x, float y, float t) { return x + (y - x) * t; }

        private void drawBackground(Canvas c) {
            int w = getWidth(), h = getHeight();
            fill.setColor(0xFFFFFFFF);   // opaque: a paint's alpha also fades its shader
            fill.setShader(new LinearGradient(0, 0, 0, h, 0xFF070A18, 0xFF15091F, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, fill);
            // Two slow glows, violet and blue, drifting like the aurora.
            float x1 = (560 + 140 * (float) Math.sin(time * 0.17)) * k, y1 = (320 + 60 * (float) Math.cos(time * 0.13)) * k;
            fill.setShader(new RadialGradient(x1, y1, 760 * k, 0x557B3CFF, 0x007B3CFF, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, fill);
            float x2 = (1380 + 120 * (float) Math.cos(time * 0.11)) * k, y2 = (800 + 70 * (float) Math.sin(time * 0.15)) * k;
            fill.setShader(new RadialGradient(x2, y2, 820 * k, 0x4426A4FF, 0x0026A4FF, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, fill);
            fill.setShader(null);
        }

        /** The glass disc with the padlock, and on the clock the minute left as a white arc. */
        private void drawRing(Canvas c, float cx, float cy, float radius, long now) {
            fill.setColor(0x33000000);
            c.drawCircle(cx, cy + 14 * k, radius, shadowed());
            fill.setColor(0x1AFFFFFF);
            c.drawCircle(cx, cy, radius, fill);
            stroke.setStrokeWidth(7 * k);
            stroke.setColor(0x1FFFFFFF);
            c.drawCircle(cx, cy, radius + 14 * k, stroke);
            if (mode == LOCK && deadline != 0) {
                float left = Math.max(0, deadline - now) / (float) ParentalTimer.GRACE_MS;
                stroke.setColor(0xF2FFFFFF);
                r.set(cx - radius - 14 * k, cy - radius - 14 * k, cx + radius + 14 * k, cy + radius + 14 * k);
                c.drawArc(r, -90, 360 * left, false, stroke);
            }
            float pulse = mode == LOCK ? 1 + 0.025f * (float) Math.sin(time * 2.2) : 1;
            drawPadlock(c, cx, cy, radius * 1.0f * pulse, 0xFFFFFFFF);
        }

        private Paint shadowed() {
            shadow.setColor(fill.getColor());
            return shadow;
        }

        private void drawPadlock(Canvas c, float cx, float cy, float size, int color) {
            Padlock.draw(c, cx, cy, size, color, 0xFF15112A);
        }

        private void drawLock(Canvas c, long now) {
            long left = deadline == 0 ? ParentalTimer.GRACE_MS : Math.max(0, deadline - now);
            int s = (int) ((left + 999) / 1000);
            String clock = (s / 60) + ":" + (s % 60 < 10 ? "0" : "") + (s % 60);
            c.drawText(t("The TV turns off in ", "הטלוויזיה תיכבה בעוד ") + clock, 960 * k, 662 * k, small);
            String[] labels = {t("Add time", "הוספת זמן"), t("Turn off parental control", "כיבוי בקרת הורים"),
                    t("Turn off the TV", "כיבוי הטלוויזיה")};
            float bw = 390 * k, bh = 92 * k, gap = 30 * k, y = 760 * k;
            float total = 3 * bw + 2 * gap, x0 = 960 * k - total / 2;
            for (int i = 0; i < 3; i++) {
                int slot = Ui.he ? 2 - i : i;   // the first button at the start side (the right in Hebrew)
                pill(c, x0 + slot * (bw + gap), y, bw, bh, labels[i], glow[i], button);
            }
        }

        private void drawPin(Canvas c) {
            float dy = 480 * k, dx0 = 960 * k - 1.5f * 58 * k;
            float jitter = shake > 0 ? (float) Math.sin(shake * 40) * 18 * k * shake : 0;
            for (int i = 0; i < 4; i++) {
                float x = dx0 + i * 58 * k + jitter;
                boolean filled = i < pin.length();
                if (filled) {
                    fill.setColor(0xFFFFFFFF);
                    c.drawCircle(x, dy, 13 * k, fill);
                } else {
                    stroke.setStrokeWidth(3 * k);
                    stroke.setColor(wrong ? 0xFFFF6B6B : 0x80FFFFFF);
                    c.drawCircle(x, dy, 12 * k, stroke);
                }
            }
            if (wrong) {
                int old = small.getColor();
                small.setColor(0xFFFF7A7A);
                c.drawText(t("Wrong code, try again", "קוד שגוי, נסו שוב"), 960 * k, 540 * k, small);
                small.setColor(old);
            }
            float kw = 150 * k, kh = 88 * k, gap = 18 * k, top = 580 * k, left = 960 * k - 1.5f * kw - gap;
            for (int i = 0; i < PAD.length; i++) {
                float x = left + (i % 3) * (kw + gap), y = top + (i / 3) * (kh + gap);
                String p = PAD[i];
                boolean word = p.length() > 1;
                String label = p.equals("del") ? t("Delete", "מחיקה") : p.equals("back") ? t("Cancel", "ביטול") : p;
                pill(c, x, y, kw, kh, label, glow[i], word ? padWord : padDigit);
            }
        }

        private void drawMore(Canvas c) {
            int n = ParentalTimer.MORE.length + 1;
            float bw = 270 * k, bh = 92 * k, gap = 28 * k, y = 520 * k;
            float total = n * bw + (n - 1) * gap, x0 = 960 * k - total / 2;
            for (int i = 0; i < n; i++) {
                String label = i < ParentalTimer.MORE.length ? minutes(ParentalTimer.MORE[i]) : t("Cancel", "ביטול");
                int slot = Ui.he ? n - 1 - i : i;
                pill(c, x0 + slot * (bw + gap), y, bw, bh, label, glow[i], button);
            }
        }

        static String minutes(int m) {
            if (m == 60) return t("1 hour", "שעה");
            return Ui.he ? m + " דקות" : m + " min";
        }

        /** A rounded button: frosted at rest; focused, white with dark text, a little larger, on a soft shadow. */
        private void pill(Canvas c, float x, float y, float w, float h, String label, float f, Paint text) {
            float s = 1 + 0.06f * f, cx = x + w / 2, cy = y + h / 2;
            r.set(cx - w * s / 2, cy - h * s / 2, cx + w * s / 2, cy + h * s / 2);
            float rad = h * s / 2;
            if (f > 0.02f) {
                fill.setColor(Ui.alpha(0xFF000000, 0.45f * f));
                r.offset(0, 12 * k * f);
                c.drawRoundRect(r, rad, rad, shadowed());
                r.offset(0, -12 * k * f);
            }
            fill.setColor(blend(0x24FFFFFF, 0xFFF5F5F7, f));
            c.drawRoundRect(r, rad, rad, fill);
            if (f < 0.5f) {
                stroke.setStrokeWidth(1.5f * k);
                stroke.setColor(Ui.alpha(0x2EFFFFFF, 1 - f * 2));
                c.drawRoundRect(r, rad, rad, stroke);
            }
            text.setColor(blend(0xF2FFFFFF, 0xFF1C1C1E, f));
            c.drawText(label, cx, cy + Ui.halfCap(text), text);
        }

        private static int blend(int a, int b, float f) {
            int aa = a >>> 24, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
            int ba = b >>> 24, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
            return (Math.round(aa + (ba - aa) * f) << 24) | (Math.round(ar + (br - ar) * f) << 16)
                    | (Math.round(ag + (bg - ag) * f) << 8) | Math.round(ab + (bb - ab) * f);
        }
    }
}
