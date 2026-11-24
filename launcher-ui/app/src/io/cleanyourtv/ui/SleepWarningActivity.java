package io.cleanyourtv.ui;

import android.app.Activity;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The sleep timer's last minute (SleepTimer), over whatever is playing: a countdown, 15 more minutes, keep the TV on,
 * or turn it off now. Our own window, started from the key service's process, which Android lets start activities from
 * the background: no overlay permission. At zero it turns the TV off itself; Back or Home leave it and the timer runs
 * on (the sleep alarm still comes). Plain views in the system font: it lives in :keys, which stays small.
 */
public class SleepWarningActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView title;
    private long end;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (SleepTimer.end(SleepWarningActivity.this) != end) {   // canceled, or a new timer, elsewhere
                finish();
                return;
            }
            long left = end - SystemClock.elapsedRealtime();
            if (left <= 0) {
                SleepTimer.sleepNow(SleepWarningActivity.this);
                finish();
                return;
            }
            title.setText(Strings.get(R.string.sleep_warning, (int) ((left + 999) / 1000)));
            handler.postDelayed(this, 1000);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        end = getIntent().getLongExtra(SleepTimer.EXTRA_END, 0);
        float dp = getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF21C1C1E);
        bg.setCornerRadius(24 * dp);
        box.setBackground(bg);
        int pad = Math.round(32 * dp);
        box.setPadding(pad, pad, pad, pad);
        title = new TextView(this);
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, Math.round(20 * dp));
        box.addView(title);
        Button more = button(box, Strings.get(R.string.sleep_more), dp);
        more.setOnClickListener(v -> {
            SleepTimer.set(this, SleepTimer.MORE);
            finish();
        });
        button(box, Strings.get(R.string.sleep_keep_on), dp).setOnClickListener(v -> {
            SleepTimer.cancel(this);
            finish();
        });
        button(box, Strings.get(R.string.sleep_now), dp).setOnClickListener(v -> {
            SleepTimer.sleepNow(this);
            finish();
        });
        FrameLayout root = new FrameLayout(this);
        root.addView(box, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        setContentView(root);
        more.requestFocus();
    }

    private Button button(LinearLayout box, String text, float dp) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setMinWidth(Math.round(360 * dp));
        box.addView(b, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        return b;
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        end = intent.getLongExtra(SleepTimer.EXTRA_END, end);
    }

    @Override protected void onResume() {
        super.onResume();
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    @Override protected void onPause() {
        handler.removeCallbacks(tick);
        super.onPause();
    }
}
