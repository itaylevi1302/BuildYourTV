package io.cleanyourtv.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Connects the TMDB account: a QR code and a link to approve on the phone, then the session is kept. With a read access
 * token (v4) it also syncs the lists shared with tk-stream (#110). Signed in, it shows the account, the sync and a
 * button to disconnect.
 */
public class TmdbLoginActivity extends Activity {
    private static final String APPROVE = "https://www.themoviedb.org/authenticate/";
    private final Handler main = new Handler(Looper.getMainLooper());
    private float k;
    private LinearLayout box;
    private TextView status, syncLine;
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
        if (Tmdb.signedIn(this)) showSignedIn(false); else start();
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

    private void showSignedIn(boolean fresh) {
        box.removeAllViews();
        box.addView(line(56, TvUi.INK, Strings.get(R.string.tmdb_account_connected)));
        box.addView(line(30, TvUi.DIM, Tmdb.accountName(this)));
        box.addView(line(26, TvUi.DIM, Strings.get(R.string.tmdb_list_and_ratings_synced)));
        syncLine = line(26, TvUi.DIM, "");
        syncLine.setMaxWidth(Math.round(1200 * k));
        box.addView(syncLine);
        boolean lists = TmdbLists.on(this);
        View primary = lists ? TvUi.button(this, k, Strings.get(R.string.sync_now), v -> syncNow())
                : TvUi.button(this, k, Strings.get(R.string.reconnect), v -> reconnect());
        View out = TvUi.button(this, k, Strings.get(R.string.disconnect), v -> {
            TmdbLists.signOut(this);
            finish();
        });
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.addView(primary);
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gap.leftMargin = Math.round(20 * k);
        buttons.addView(out, gap);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Math.round(30 * k);
        box.addView(buttons, lp);
        primary.requestFocus();
        if (lists) {
            fillSync();
            if (fresh) syncNow();
        } else {
            syncLine.setText(Strings.get(TmdbLists.lost(this) ? R.string.lists_connection_lost : R.string.lists_need_read_token));
        }
    }

    /** What the last sync did (settings only). */
    private void fillSync() {
        if (!TmdbLists.on(this)) {   // TMDB refused the connection meanwhile
            showSignedIn(false);
            return;
        }
        TmdbLists.Status st = TmdbLists.status(this);
        syncLine.setText(st.error.isEmpty() ? Strings.get(R.string.lists_synced, st.watching, st.watched)
                : Strings.get(R.string.lists_sync_failed, st.error));
    }

    private void syncNow() {
        syncLine.setText(Strings.get(R.string.syncing));
        new Thread(() -> {
            TmdbLists.sync(this, true);
            main.post(() -> { if (!isFinishing()) fillSync(); });
        }, "tk-sync").start();
    }

    /** Signed in without the lists (or TMDB refused them): connect again with the read access token. */
    private void reconnect() {
        if (Tmdb.hasReadToken()) {
            start();
            return;
        }
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setHint(Strings.get(R.string.api_key_or_read_access_token));
        field.setImeOptions(EditorInfo.IME_ACTION_DONE);
        AlertDialog dialog = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(Strings.get(R.string.read_access_token))
                .setView(field)
                .setPositiveButton(Strings.get(R.string.save), (d, which) -> saveReadToken(field.getText().toString()))
                .setNegativeButton(Strings.get(R.string.cancel), null)
                .create();
        field.setOnEditorActionListener((v, action, event) -> {
            dialog.dismiss();
            saveReadToken(field.getText().toString());
            return true;
        });
        dialog.show();
    }

    private void saveReadToken(String value) {
        String v = value.trim();
        if (!v.startsWith("eyJ") || !TvConfig.validTmdbKey(v)) {
            Toast.makeText(this, Strings.get(R.string.not_a_read_token), Toast.LENGTH_LONG).show();
            return;
        }
        Prefs.setTmdbKey(v);
        start();
    }

    private void start() {
        box.removeAllViews();
        box.addView(line(56, TvUi.INK, Strings.get(R.string.connect_your_tmdb_account)));
        status = line(28, TvUi.DIM, Strings.get(R.string.getting_ready));
        box.addView(status);
        boolean v4 = Tmdb.hasReadToken();
        new Thread(() -> {
            try {
                String token = v4 ? TmdbLists.requestToken() : Tmdb.requestToken();
                String url = (v4 ? TmdbLists.APPROVE_V4 : APPROVE) + token;
                Bitmap qr = render(Qr.encode(url), Math.round(420 * k));
                main.post(() -> { if (!isFinishing()) showCode(url, qr); });
                polling = true;
                long until = System.currentTimeMillis() + 15 * 60_000L;
                while (polling && System.currentTimeMillis() < until) {
                    Thread.sleep(3000);
                    if (v4 ? TmdbLists.finishSignIn(this, token) : Tmdb.finishSignIn(this, token)) {
                        main.post(() -> { if (!isFinishing()) showSignedIn(v4); });
                        return;
                    }
                }
                main.post(() -> { if (!isFinishing()) status.setText(Strings.get(R.string.the_link_expired_go_back_and_open_it_again)); });
            } catch (Exception e) {
                main.post(() -> { if (!isFinishing()) status.setText(Strings.get(R.string.couldnt_connect_prefix) + e.getMessage()); });
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
