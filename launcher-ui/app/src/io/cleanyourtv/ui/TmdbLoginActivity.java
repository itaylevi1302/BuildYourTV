package io.cleanyourtv.ui;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Connects the TMDB account: a QR code and a link to approve on the phone, then the session is kept. Signed in, it
 * shows the account and a button to disconnect.
 */
public class TmdbLoginActivity extends Activity {
    private static final String APPROVE = "https://www.themoviedb.org/authenticate/";
    private final Handler main = new Handler(Looper.getMainLooper());
    private float k;
    private LinearLayout box;
    private TextView status;
    private volatile boolean polling;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        k = TvUi.k(this);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(TvUi.BG);
        box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(box, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        setContentView(root);
        if (Tmdb.signedIn(this)) showSignedIn(); else start();
    }

    @Override protected void onDestroy() {
        polling = false;
        super.onDestroy();
    }

    private TextView line(float size, int color, String text) {
        TextView t = TvUi.text(this, k, size, size > 40 ? Ui.displaySemibold : Ui.regular, color);
        t.setText(text);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, Math.round(14 * k), 0, Math.round(14 * k));
        return t;
    }

    private void showSignedIn() {
        box.removeAllViews();
        box.addView(line(56, TvUi.INK, Strings.get(R.string.tmdb_account_connected)));
        box.addView(line(30, TvUi.DIM, Tmdb.accountName(this)));
        box.addView(line(26, TvUi.DIM, Strings.get(R.string.tmdb_list_and_ratings_synced)));
        TextView out = TvUi.button(this, k, Strings.get(R.string.disconnect), v -> {
            Tmdb.signOut(this);
            finish();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Math.round(30 * k);
        box.addView(out, lp);
        out.requestFocus();
    }

    private void start() {
        box.removeAllViews();
        box.addView(line(56, TvUi.INK, Strings.get(R.string.connect_your_tmdb_account)));
        status = line(28, TvUi.DIM, Strings.get(R.string.getting_ready));
        box.addView(status);
        new Thread(() -> {
            try {
                String token = Tmdb.requestToken();
                String url = APPROVE + token;
                Bitmap qr = render(Qr.encode(url), Math.round(420 * k));
                main.post(() -> showCode(url, qr));
                polling = true;
                long until = System.currentTimeMillis() + 15 * 60_000L;
                while (polling && System.currentTimeMillis() < until) {
                    Thread.sleep(3000);
                    if (Tmdb.finishSignIn(this, token)) {
                        main.post(this::showSignedIn);
                        return;
                    }
                }
                main.post(() -> status.setText(Strings.get(R.string.the_link_expired_go_back_and_open_it_again)));
            } catch (Exception e) {
                main.post(() -> status.setText(Strings.get(R.string.couldnt_connect_prefix) + e.getMessage()));
            }
        }).start();
    }

    private void showCode(String url, Bitmap qr) {
        box.removeAllViews();
        box.addView(line(56, TvUi.INK, Strings.get(R.string.connect_your_tmdb_account)));
        box.addView(line(28, TvUi.DIM, Strings.get(R.string.tmdb_approval_qr_hint)));
        ImageView img = new ImageView(this);
        img.setImageBitmap(qr);
        img.setBackgroundColor(Color.WHITE);
        int pad = Math.round(24 * k);
        img.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(qr.getWidth() + 2 * pad, qr.getHeight() + 2 * pad);
        lp.topMargin = Math.round(20 * k);
        lp.bottomMargin = Math.round(20 * k);
        box.addView(img, lp);
        box.addView(line(24, TvUi.FAINT, url));
        status = line(28, TvUi.DIM, Strings.get(R.string.waiting_for_approval));
        box.addView(status);
    }

    private static Bitmap render(Qr q, int px) {
        int scale = Math.max(1, px / q.size);
        Bitmap b = Bitmap.createBitmap(q.size * scale, q.size * scale, Bitmap.Config.RGB_565);
        for (int y = 0; y < q.size; y++) {
            for (int x = 0; x < q.size; x++) {
                int color = q.dark(x, y) ? Color.BLACK : Color.WHITE;
                for (int dy = 0; dy < scale; dy++) for (int dx = 0; dx < scale; dx++) b.setPixel(x * scale + dx, y * scale + dy, color);
            }
        }
        return b;
    }
}
