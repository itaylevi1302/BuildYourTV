package io.cleanyourtv.example.openmovies;

import android.app.Activity;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.VideoView;

/** Full-screen player for one film, opened by openmovies://play?tmdb=ID. */
public class PlayerActivity extends Activity {
    private static final int SEEK_MS = 10000;

    private VideoView video;
    private TextView status;
    private View overlay;
    private Film film;
    private int source;
    private boolean ready;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        float d = getResources().getDisplayMetrics().density;

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        video = new VideoView(this);
        root.addView(video, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER));

        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(22);
        status.setGravity(Gravity.CENTER);
        root.addView(status, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setBackgroundColor(0xB0000000);
        int p = (int) (24 * d);
        bar.setPadding(p, p / 2, p, p / 2);
        bar.setVisibility(View.GONE);
        overlay = bar;
        root.addView(bar, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        setContentView(root);

        film = resolve();
        if (film == null) {
            status.setText(R.string.not_found);
            return;
        }
        TextView title = new TextView(this);
        title.setText(film.title);
        title.setTextColor(Color.WHITE);
        title.setTextSize(24);
        bar.addView(title);
        TextView credit = new TextView(this);
        credit.setText(film.credit + " · " + film.licence);
        credit.setTextColor(0xFFC0C8D0);
        credit.setTextSize(14);
        bar.addView(credit);

        video.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer mp) {
                ready = true;
                status.setVisibility(View.GONE);
                mp.setOnInfoListener(new MediaPlayer.OnInfoListener() {
                    @Override
                    public boolean onInfo(MediaPlayer m, int what, int extra) {
                        if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) {
                            status.setText(R.string.buffering);
                            status.setVisibility(View.VISIBLE);
                        } else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END) {
                            status.setVisibility(View.GONE);
                        }
                        return false;
                    }
                });
                video.start();
            }
        });
        video.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer mp, int what, int extra) {
                if (source + 1 < film.sources.length) {
                    source++;
                    play();
                } else {
                    ready = false;
                    status.setText(R.string.play_error);
                    status.setVisibility(View.VISIBLE);
                }
                return true;
            }
        });
        video.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            @Override
            public void onCompletion(MediaPlayer mp) {
                finish();
            }
        });
        play();
    }

    private Film resolve() {
        Uri data = getIntent() == null ? null : getIntent().getData();
        if (data == null || !"openmovies".equals(data.getScheme()) || !"play".equals(data.getHost())) return null;
        try {
            return Catalog.byTmdb(Long.parseLong(data.getQueryParameter("tmdb")));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void play() {
        ready = false;
        status.setText(getString(R.string.loading, film.title));
        status.setVisibility(View.VISIBLE);
        video.setVideoURI(Uri.parse(film.sources[source]));
    }

    private void togglePause() {
        if (!ready) return;
        if (video.isPlaying()) {
            video.pause();
            overlay.setVisibility(View.VISIBLE);
        } else {
            video.start();
            overlay.setVisibility(View.GONE);
        }
    }

    private void seek(int deltaMs) {
        if (!ready) return;
        int to = Math.max(0, Math.min(video.getDuration(), video.getCurrentPosition() + deltaMs));
        video.seekTo(to);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_SPACE:
                togglePause();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                if (ready && !video.isPlaying()) togglePause();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                if (ready && video.isPlaying()) togglePause();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                seek(-SEEK_MS);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                seek(SEEK_MS);
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        video.stopPlayback();
        finish();
    }
}
