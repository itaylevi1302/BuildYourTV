package io.cleanyourtv.ui;

import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Handler;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;

/**
 * The muted trailer behind Explore's banner (#113), only when Settings turns it on. One WebView, made when the first
 * trailer is about to play and destroyed as soon as it is not needed (the activity stops, the screen goes off, memory
 * gets short). It plays YouTube's embedded player (youtube-nocookie.com), muted, and is driven through postMessage;
 * the page reports back through the `Trailer` bridge. TMDB lists the title's videos; one that YouTube refuses or that
 * does not start within TIMEOUT_MS is skipped, and the ones YouTube refuses are remembered. With the setting off this
 * class is never used and no WebView exists.
 */
final class BannerTrailer {
    interface Listener {
        void onTrailerPlaying();

        /** `ended`: the trailer finished or nothing played, so the picture stays; otherwise it was stopped from outside. */
        void onTrailerStopped(boolean ended);
    }

    /** No WebView on this TV (or it failed to start): found once, then true for the process. */
    static volatile boolean unavailable;

    private static final long TIMEOUT_MS = 8000;
    private static final int MAX_VIDEOS = 4;
    /** Videos that failed in this process (timeouts, a dead renderer) plus the persistent ones YouTube refused. */
    private static final Set<String> SESSION_BAD = new HashSet<>();
    private static boolean badLoaded;

    private static final String PAGE = "<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><style>"
            + "html,body{margin:0;height:100%;overflow:hidden;background:transparent}"
            + "#f{position:absolute;left:50%;top:50%;border:0;pointer-events:none;transform:translate(-50%,-50%);width:100vw;height:56.25vw}"
            + "@media (max-aspect-ratio:16/9){#f{width:177.78vh;height:100vh}}"
            + "</style></head><body><script>"
            + "var f=null;"
            + "function stop(){if(f){f.parentNode.removeChild(f);f=null}}"
            + "function play(id){stop();f=document.createElement('iframe');f.id='f';f.setAttribute('allow','autoplay; encrypted-media');"
            + "f.src='https://www.youtube-nocookie.com/embed/'+id+'?autoplay=1&mute=1&controls=0&playsinline=1&rel=0&iv_load_policy=3&disablekb=1&fs=0&enablejsapi=1"
            + "&origin='+encodeURIComponent(location.origin)+'&widget_referrer='+encodeURIComponent(location.origin);"
            + "f.onload=function(){if(f)f.contentWindow.postMessage(JSON.stringify({event:'listening',id:'hero',channel:'widget'}),'*')};"
            + "document.body.appendChild(f)}"
            + "function cmd(fn,a){if(f)f.contentWindow.postMessage(JSON.stringify({event:'command',func:fn,args:a||[]}),'*')}"
            + "addEventListener('message',function(e){if(!f||e.source!==f.contentWindow)return;var d;"
            + "try{d=typeof e.data=='string'?JSON.parse(e.data):e.data}catch(x){return}"
            + "if(!d)return;if(d.event=='onError'){Trailer.error(+d.info||0);return}"
            + "var s=d.info&&d.info.playerState!==undefined?d.info.playerState:(d.event=='onStateChange'?d.info:undefined);"
            + "if(s!==undefined&&s!==null)Trailer.state(+s)});"
            + "</script></body></html>";

    private final Activity activity;
    private final FrameLayout box;
    private final Handler main;
    private final ExecutorService io;
    private final Listener listener;

    private WebView web;
    private boolean pageReady, playing, muted = true;
    private volatile int token;
    private Tmdb.Title title;
    private List<String> videos = new ArrayList<>();
    private int at;
    private String pending;

    private final Runnable timeout = () -> {
        if (web == null || playing || at >= videos.size()) return;
        AppLog.w("banner trailer: no start in " + TIMEOUT_MS + " ms");
        fail(videos.get(at), false);
    };

    BannerTrailer(Activity activity, FrameLayout box, Handler main, ExecutorService io, Listener listener) {
        this.activity = activity;
        this.box = box;
        this.main = main;
        this.io = io;
        this.listener = listener;
    }

    /** Whether this TV has a WebView; never creates one. */
    static boolean available() {
        if (unavailable) return false;
        try {
            return WebView.getCurrentWebViewPackage() != null;
        } catch (Throwable t) {
            return false;
        }
    }

    boolean playing() { return playing; }

    Tmdb.Title title() { return title; }

    boolean muted() { return muted; }

    void setMuted(boolean m) {
        muted = m;
        if (web != null && playing) run(m ? "cmd('mute')" : "cmd('unMute');cmd('setVolume',[100])");
    }

    /** Looks up the title's videos and plays the first that works. */
    void start(Tmdb.Title t) {
        if (unavailable) return;
        if (playing) stop();
        int mine = ++token;
        stopPlayback();
        title = t;
        muted = true;
        io.execute(() -> {
            List<String> found = candidates(t);
            main.post(() -> {
                if (mine != token || activity.isFinishing() || activity.isDestroyed()) return;
                if (found.isEmpty()) {
                    token++;
                    listener.onTrailerStopped(true);
                    return;
                }
                videos = found;
                at = 0;
                if (ensureWebView()) playCurrent();
                else listener.onTrailerStopped(true);
            });
        });
    }

    /** Stops what plays and hides it; the WebView stays for the next one. */
    void stop() {
        token++;
        boolean was = playing;
        stopPlayback();
        if (was) listener.onTrailerStopped(false);
    }

    /** Stop, then give the WebView back. */
    void release() {
        stop();
        destroy();
    }

    private void stopPlayback() {
        main.removeCallbacks(timeout);
        pending = null;
        playing = false;
        box.animate().cancel();
        box.setAlpha(0);
        box.setVisibility(View.GONE);
        if (web != null) {
            run("stop()");
            web.onPause();
        }
    }

    private void destroy() {
        main.removeCallbacks(timeout);
        WebView w = web;
        web = null;
        pageReady = false;
        pending = null;
        if (w == null) return;
        try {
            w.evaluateJavascript("stop()", null);
            w.stopLoading();
            w.loadUrl("about:blank");
            w.removeJavascriptInterface("Trailer");
            ViewGroup parent = (ViewGroup) w.getParent();
            if (parent != null) parent.removeView(w);
            w.destroy();
        } catch (Throwable t) {
            AppLog.w("banner trailer: release " + t.getMessage());
        }
    }

    private void run(String js) {
        if (web != null && pageReady) web.evaluateJavascript(js, null);
    }

    private void playCurrent() {
        if (web == null || at >= videos.size()) return;
        playing = false;
        String id = videos.get(at);
        box.setVisibility(View.VISIBLE);
        box.setAlpha(0.01f);   // not GONE: a hidden WebView may not play
        web.onResume();
        if (pageReady) web.evaluateJavascript("play('" + id + "')", null);
        else pending = id;
        main.removeCallbacks(timeout);
        main.postDelayed(timeout, TIMEOUT_MS);
    }

    /** `id` did not work: remember it (for good only when YouTube refused it) and go to the next. */
    private void fail(String id, boolean refused) {
        synchronized (SESSION_BAD) { SESSION_BAD.add(id); }
        if (refused) Prefs.addBadTrailer(id);
        main.removeCallbacks(timeout);
        if (++at < videos.size()) {
            playCurrent();
            return;
        }
        token++;
        stopPlayback();
        listener.onTrailerStopped(true);
    }

    private boolean ensureWebView() {
        if (web != null) return true;
        if (unavailable || !available()) {
            markUnavailable();
            return false;
        }
        try {
            WebView w = new WebView(activity);
            WebSettings s = w.getSettings();
            s.setJavaScriptEnabled(true);
            s.setDomStorageEnabled(true);
            s.setMediaPlaybackRequiresUserGesture(false);
            android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(w, false);
            w.setBackgroundColor(0);
            w.setVerticalScrollBarEnabled(false);
            w.setHorizontalScrollBarEnabled(false);
            w.setFocusable(false);
            w.setFocusableInTouchMode(false);
            w.addJavascriptInterface(new Bridge(), "Trailer");
            w.setWebChromeClient(new WebChromeClient() {
                @Override public Bitmap getDefaultVideoPoster() {
                    return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
                }
            });
            w.setWebViewClient(new WebViewClient() {
                @Override public boolean shouldOverrideUrlLoading(WebView view, android.webkit.WebResourceRequest request) {
                    return true;
                }

                @Override public void onPageFinished(WebView view, String url) {
                    if (view != web || "about:blank".equals(url)) return;
                    pageReady = true;
                    if (pending != null) {
                        String id = pending;
                        pending = null;
                        view.evaluateJavascript("play('" + id + "')", null);
                    }
                }

                @Override public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                    AppLog.w("banner trailer: renderer gone");
                    String id = at < videos.size() ? videos.get(at) : null;
                    if (id != null) synchronized (SESSION_BAD) { SESSION_BAD.add(id); }
                    release();
                    return true;   // never let the whole :home process die with it
                }
            });
            box.addView(w, 0, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            web = w;
            pageReady = false;
            w.loadDataWithBaseURL("https://io.cleanyourtv.ui/", PAGE, "text/html", "utf-8", null);
            return true;
        } catch (Throwable t) {
            web = null;
            markUnavailable();
            return false;
        }
    }

    private static void markUnavailable() {
        if (!unavailable) AppLog.w("banner trailer: no WebView");
        unavailable = true;
    }

    /** Called on a WebView thread: hop to the main thread, and drop what a stopped or replaced video says. */
    private final class Bridge {
        @JavascriptInterface public void state(int s) {
            int mine = token;
            main.post(() -> {
                if (mine != token || web == null) return;
                if (s == 1 && !playing) {
                    playing = true;
                    main.removeCallbacks(timeout);
                    if (muted) run("cmd('mute')");
                    box.animate().alpha(1).setDuration(700).start();
                    listener.onTrailerPlaying();
                } else if (s == 0) {
                    token++;
                    stopPlayback();
                    listener.onTrailerStopped(true);
                }
            });
        }

        @JavascriptInterface public void error(int code) {
            int mine = token;
            main.post(() -> {
                if (mine != token || web == null || at >= videos.size()) return;
                AppLog.w("banner trailer: YouTube error " + code);
                fail(videos.get(at), true);
            });
        }
    }

    /** TMDB's trailers and teasers for the title (YouTube only): ours in the app's language first, official first. */
    private static List<String> candidates(Tmdb.Title t) {
        List<String> out = new ArrayList<>();
        if (!t.kind.equals("movie") && !t.kind.equals("tv")) return out;
        try {
            String mine = Tmdb.lang().substring(0, 2);
            JSONArray all = Tmdb.get("/" + t.kind + "/" + t.id + "/videos?include_video_language=" + mine + ",en,null").optJSONArray("results");
            Set<String> bad = bad();
            List<Object[]> ranked = new ArrayList<>();   // {score, key}
            for (int i = 0; all != null && i < all.length(); i++) {
                JSONObject v = all.optJSONObject(i);
                if (v == null || !"YouTube".equals(v.optString("site"))) continue;
                String key = v.optString("key");
                String type = v.optString("type");
                if (!key.matches("[A-Za-z0-9_-]{6,20}") || bad.contains(key)) continue;
                if (!type.equals("Trailer") && !type.equals("Teaser")) continue;
                double score = (type.equals("Trailer") ? 0 : 1) + (mine.equals(v.optString("iso_639_1")) ? 0 : 3) + (v.optBoolean("official") ? 0 : 0.5);
                ranked.add(new Object[] {score, key});
            }
            java.util.Collections.sort(ranked, (a, b) -> Double.compare((Double) a[0], (Double) b[0]));   // stable
            for (Object[] r : ranked) {
                if (out.size() >= MAX_VIDEOS) break;
                if (!out.contains((String) r[1])) out.add((String) r[1]);
            }
        } catch (Exception e) {
            AppLog.w("banner trailer: videos " + e.getMessage());
        }
        return out;
    }

    private static Set<String> bad() {
        synchronized (SESSION_BAD) {
            if (!badLoaded) {
                badLoaded = true;
                SESSION_BAD.addAll(Prefs.badTrailers());
            }
            return new HashSet<>(SESSION_BAD);
        }
    }
}
