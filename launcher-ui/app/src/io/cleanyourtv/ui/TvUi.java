package io.cleanyourtv.ui;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** Views for Explore, the title page and the services list, sized for a 1920-wide TV screen like the home screen. */
final class TvUi {
    private TvUi() {}

    static final int BG = 0xFF0B0D12, INK = 0xFFF5F6F8, DIM = 0x99F5F6F8, FAINT = 0x59F5F6F8, CARD = 0xFF1A1D24,
            ACCENT = 0xFF19B5A6;
    /** Posters in a row (it scrolls sideways) before its "See more" card. */
    static final int ROW_MAX = 10;
    static final int POSTER_W = 210, POSTER_H = 315, POSTER_GAP = 26;
    /** Like the home screen's cards: they grow this much when focused. */
    private static final float GROW = 0.08f;

    static float k(Activity a) {
        return a.getResources().getDisplayMetrics().widthPixels / 1920f;
    }

    static TextView text(Context c, float k, float size, Typeface face, int color) {
        TextView t = new TextView(c);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * k);
        t.setTypeface(face);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        // Hebrew: a line that starts with a Latin word ("All+ · Movie…") still reads right to left, like the screen.
        if (Ui.he) t.setTextDirection(View.TEXT_DIRECTION_LOCALE);
        return t;
    }

    static GradientDrawable round(int color, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    /**
     * Focused like the home screen's cards: it grows, rises with a deep shadow and gets a soft sheen at the top and a
     * thin light rim; at rest a faint veil and hairline. The caption brightens and, when too long, scrolls like a
     * bus sign.
     */
    static void focusable(View v, float k, float radius) {
        focusable(v, k, radius, null);
    }

    static void focusable(View v, float k, float radius, TextView caption) {
        v.setFocusable(true);
        v.setFocusableInTouchMode(true);
        v.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);
        Look look = new Look(k, radius);
        v.setForeground(look);
        v.setOnFocusChangeListener((view, has) -> {
            float s = has ? 1 + GROW : 1f;
            view.animate().scaleX(s).scaleY(s).translationZ(has ? 30 * k : 0).translationY(has ? -4 * k : 0).setDuration(180).start();
            look.animateTo(has ? 1 : 0);
            if (caption != null) {
                caption.setTextColor(has ? INK : DIM);
                caption.setEllipsize(has ? TextUtils.TruncateAt.MARQUEE : TextUtils.TruncateAt.END);
                caption.setSelected(has);
            }
        });
    }

    /** The focus look drawn over a card: veil at rest; sheen and rim when focused (as HomeView.drawCard). */
    static final class Look extends Drawable {
        private final float k, radius;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private LinearGradient sheen;
        private int sheenH;
        private float f;
        private ValueAnimator anim;

        Look(float k, float radius) {
            this.k = k;
            this.radius = radius;
            line.setStyle(Paint.Style.STROKE);
        }

        void animateTo(float target) {
            if (anim != null) anim.cancel();
            anim = ValueAnimator.ofFloat(f, target);
            anim.setDuration(180);
            anim.addUpdateListener(a -> {
                f = (float) a.getAnimatedValue();
                invalidateSelf();
            });
            anim.start();
        }

        @Override public void draw(Canvas c) {
            Rect b = getBounds();
            r.set(b);
            if (f < 0.99f) {
                fill.setShader(null);
                fill.setColor(Ui.alpha(0xFF000000, 0.1f * (1 - f)));
                c.drawRoundRect(r, radius, radius, fill);
            }
            if (f > 0.01f) {
                if (sheen == null || sheenH != b.height()) {
                    sheenH = b.height();
                    sheen = new LinearGradient(0, b.top, 0, b.top + b.height() / 2f, 0x26FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
                }
                fill.setShader(sheen);
                fill.setAlpha(Math.round(255 * f));
                c.drawRoundRect(r, radius, radius, fill);
                fill.setShader(null);
                fill.setAlpha(255);
            }
            float w = 1.5f * k;
            r.inset(w / 2, w / 2);
            line.setStrokeWidth(w);
            line.setColor(f > 0.01f ? Ui.alpha(0xFFFFFFFF, 0.08f + 0.37f * f) : 0x14FFFFFF);
            c.drawRoundRect(r, radius, radius, line);
        }

        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(ColorFilter cf) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /** A list item (an episode, a service): a soft card that lights up when focused, nothing grows past its edges. */
    static void softFocus(View v, float k, float radius) {
        v.setBackground(round(0x17FFFFFF, radius));
        v.setFocusable(true);
        v.setFocusableInTouchMode(true);
        v.setOnFocusChangeListener((view, has) -> view.setBackground(round(has ? 0x59FFFFFF : 0x17FFFFFF, radius)));
    }

    /** A pill button: white when focused. */
    static TextView button(Context c, float k, String label, View.OnClickListener click) {
        TextView b = text(c, k, 28, Ui.medium, INK);
        b.setText(label);
        b.setGravity(Gravity.CENTER);
        b.setPaddingRelative(Math.round(34 * k), Math.round(18 * k), Math.round(34 * k), Math.round(18 * k));
        b.setBackground(round(0x33FFFFFF, 40 * k));
        b.setFocusable(true);
        b.setFocusableInTouchMode(true);
        b.setOnClickListener(click);
        b.setOnFocusChangeListener((v, has) -> {
            b.setBackground(round(has ? INK : 0x33FFFFFF, 40 * k));
            b.setTextColor(has ? 0xFF14161A : INK);
            v.animate().scaleX(has ? 1.06f : 1f).scaleY(has ? 1.06f : 1f).translationZ(has ? 12 * k : 0).setDuration(140).start();
        });
        b.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);
        return b;
    }

    interface OnTitle { void open(Tmdb.Title t); }

    /**
     * A row: its title, then up to ROW_MAX posters and, when there are more, a "See more" card. `more` opens the rest
     * (null: a grid of the titles given).
     */
    static View row(Activity a, float k, String title, List<Tmdb.Title> titles, OnTitle open) {
        return row(a, k, title, titles, open, null);
    }

    static View row(Activity a, float k, String title, List<Tmdb.Title> titles, OnTitle open, Runnable more) {
        return row(a, k, title, titles, open, more, 0);
    }

    /** `inset`: the page's left margin; the row scrolls under it to the screen's edge, its first poster lines up with it. */
    static View row(Activity a, float k, String title, List<Tmdb.Title> titles, OnTitle open, Runnable more, int inset) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView h = text(a, k, 32, Ui.semibold, INK);
        h.setText(title);
        h.setPaddingRelative(0, 0, 0, Math.round(18 * k));
        box.addView(h);
        Row line = new Row(a);
        line.setClipChildren(false);
        line.setClipToPadding(false);
        line.setPaddingRelative(inset + Math.round(24 * k), Math.round(30 * k), Math.round(96 * k), Math.round(20 * k));
        for (int i = 0; i < titles.size() && i < ROW_MAX; i++) line.addView(poster(a, k, titles.get(i), open));
        if (titles.size() > ROW_MAX || (more != null && titles.size() >= ROW_MAX)) {
            Runnable all = more != null ? more : () -> GridActivity.show(a, title, titles, open);
            line.addView(moreCard(a, k, all));
        }
        HScroll scroll = new HScroll(a, Math.round(300 * k));
        scroll.addView(line);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginStart(-inset);
        box.addView(scroll, lp);
        box.setClipChildren(false);
        box.setPaddingRelative(0, Math.round(26 * k), 0, Math.round(10 * k));
        return box;
    }

    static View poster(Activity a, float k, Tmdb.Title t, OnTitle open) {
        int w = Math.round(POSTER_W * k), h = Math.round(POSTER_H * k);
        LinearLayout cell = new LinearLayout(a);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setClipChildren(false);
        FrameLayout card = new FrameLayout(a);
        card.setBackground(round(CARD, 14 * k));
        card.setClipToOutline(true);
        card.setTag(t);
        ImageView img = new ImageView(a);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        card.addView(img, new FrameLayout.LayoutParams(w, h));
        TextView fallback = text(a, k, 24, Ui.medium, DIM);
        fallback.setText(t.name);
        fallback.setGravity(Gravity.CENTER);
        fallback.setPaddingRelative(Math.round(14 * k), 0, Math.round(14 * k), 0);
        card.addView(fallback, 0, new FrameLayout.LayoutParams(w, h));
        if (t.kind.equals("movie") || t.kind.equals("tv")) card.addView(seenBadge(a, k), badgeParams(k));
        Images.load(img, t.poster.isEmpty() ? null : (t.kind.equals("person") ? Tmdb.PROFILE : Tmdb.POSTER) + t.poster);
        card.setContentDescription(t.name + (t.year.isEmpty() ? "" : ", " + t.year));
        card.setOnClickListener(v -> open.open(t));
        cell.addView(card, new LinearLayout.LayoutParams(w, h));
        TextView name = text(a, k, 22, Ui.regular, DIM);
        focusable(card, k, 14 * k, name);
        name.setText(t.name);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setMarqueeRepeatLimit(-1);
        name.setHorizontalFadingEdgeEnabled(true);
        name.setFadingEdgeLength(Math.round(18 * k));
        name.setPaddingRelative(0, Math.round(26 * k), 0, 0);
        cell.addView(name, new LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(Math.round(POSTER_GAP * k));
        cell.setLayoutParams(lp);
        refreshSeen(card);
        return cell;
    }

    /** A check in a dark circle at the poster's top right: seen. */
    private static View seenBadge(Context c, float k) {
        TextView b = text(c, k, 24, Ui.semibold, INK);
        b.setText("✓");
        b.setGravity(Gravity.CENTER);
        b.setBackground(round(0xD9101216, 22 * k));
        b.setTag("seen");
        b.setElevation(4 * k);
        return b;
    }

    private static FrameLayout.LayoutParams badgeParams(float k) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Math.round(44 * k), Math.round(44 * k), Gravity.TOP | Gravity.END);
        lp.topMargin = Math.round(10 * k);
        lp.setMarginEnd(lp.topMargin);
        return lp;
    }

    /** Shows or hides the seen badges under `root` (back from a title page that changed it). */
    static void refreshSeen(View root) {
        if (root.getTag() instanceof Tmdb.Title && root instanceof ViewGroup) {
            Tmdb.Title t = (Tmdb.Title) root.getTag();
            View badge = root.findViewWithTag("seen");
            if (badge != null) badge.setVisibility(Tmdb.isSeen(root.getContext(), t.kind, t.id) ? View.VISIBLE : View.GONE);
            return;
        }
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) refreshSeen(g.getChildAt(i));
        }
    }

    /**
     * A screen another app (or another of ours) covers lets go of its pictures, which the cache alone can't free while
     * views show them; back in front, they come again from the cache or the disk.
     */
    static void releaseImages(View v) {
        if (v instanceof ImageView) {
            if (v.getTag() instanceof String) ((ImageView) v).setImageDrawable(null);
        } else if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) releaseImages(g.getChildAt(i));
        }
    }

    static void restoreImages(View v) {
        if (v instanceof ImageView) {
            ImageView img = (ImageView) v;
            if (img.getTag() instanceof String && img.getDrawable() == null) Images.load(img, (String) img.getTag());
        } else if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) restoreImages(g.getChildAt(i));
        }
    }

    /** The last card of a long row: opens all of it as a grid. */
    static View moreCard(Activity a, float k, Runnable open) {
        int w = Math.round(POSTER_W * k), h = Math.round(POSTER_H * k);
        LinearLayout cell = new LinearLayout(a);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setClipChildren(false);
        LinearLayout card = new LinearLayout(a);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setBackground(round(0x1FFFFFFF, 14 * k));
        TextView arrow = text(a, k, 64, Ui.regular, INK);
        arrow.setText("›");
        arrow.setGravity(Gravity.CENTER);
        TextView label = text(a, k, 26, Ui.medium, INK);
        label.setText(Ui.tr("Ver mais", "See more"));
        label.setGravity(Gravity.CENTER);
        label.setPaddingRelative(0, Math.round(12 * k), 0, 0);
        card.addView(arrow);
        card.addView(label);
        card.setContentDescription(Ui.tr("Ver mais", "See more"));
        card.setOnClickListener(v -> open.run());
        focusable(card, k, 14 * k);
        cell.addView(card, new LinearLayout.LayoutParams(w, h));
        cell.setLayoutParams(new LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT));
        return cell;
    }

    /** The poster's title under the focus (the backdrop behind Explore follows it), or null. */
    static Tmdb.Title titleOf(View focused) {
        return focused != null && focused.getTag() instanceof Tmdb.Title ? (Tmdb.Title) focused.getTag() : null;
    }

    /**
     * A horizontal line of cards that remembers its card: coming from above or below lands on the card focused last
     * (the first one at the start), not on whichever card happens to be nearest.
     */
    static class Row extends LinearLayout {
        private View last;

        Row(Context c) {
            super(c);
            setOrientation(HORIZONTAL);
        }

        @Override public void requestChildFocus(View child, View focused) {
            super.requestChildFocus(child, focused);
            last = child;
        }

        @Override public void addFocusables(ArrayList<View> views, int direction, int mode) {
            View keep = last != null && last.getParent() == this ? last : getChildCount() > 0 ? getChildAt(0) : null;
            if (hasFocus() || keep == null || (direction != FOCUS_UP && direction != FOCUS_DOWN)) {
                super.addFocusables(views, direction, mode);
            } else {
                keep.addFocusables(views, direction, mode);
            }
        }
    }

    /** A row's sideways scroll: the focused poster is kept `margin` away from the edges, so the next one shows. */
    static final class HScroll extends android.widget.HorizontalScrollView {
        private final int margin;

        HScroll(Context c, int margin) {
            super(c);
            this.margin = margin;
            setFocusable(false);
            setHorizontalScrollBarEnabled(false);
            setClipChildren(false);
            setClipToPadding(false);
            setSmoothScrollingEnabled(true);
        }

        @Override protected int computeScrollDeltaToGetChildRectOnScreen(Rect r) {
            int w = getWidth(), x = getScrollX();
            if (w == 0 || getChildCount() == 0) return 0;
            int max = Math.max(0, getChildAt(0).getWidth() - w);
            if (r.right > x + w - margin) return Math.min(max - x, r.right - (x + w - margin));
            if (r.left < x + margin) return Math.max(-x, r.left - (x + margin));
            return 0;
        }
    }

    /** A symbol from assets/symbols, drawn in the view's color. */
    static final class Icon extends View {
        private final String name;
        private final float size;
        private int color = INK;

        Icon(Context c, String name, float size) {
            super(c);
            this.name = name;
            this.size = size;
        }

        void color(int c) {
            color = c;
            invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            Symbols.draw(getContext(), c, name, getWidth() / 2f, getHeight() / 2f, size, color);
        }
    }

    /** A pill with a symbol (and a label, or none for a round button): white when focused. */
    static LinearLayout iconButton(Context c, float k, String symbol, String label, View.OnClickListener click) {
        LinearLayout b = new LinearLayout(c);
        b.setOrientation(LinearLayout.HORIZONTAL);
        b.setGravity(Gravity.CENTER);
        boolean round = label == null || label.isEmpty();
        b.setPaddingRelative(Math.round((round ? 18 : 30) * k), Math.round(16 * k), Math.round((round ? 18 : 34) * k), Math.round(16 * k));
        Icon icon = new Icon(c, symbol, 22 * k);
        int side = Math.round(36 * k);
        b.addView(icon, new LinearLayout.LayoutParams(side, side));
        TextView t = null;
        if (!round) {
            t = text(c, k, 28, Ui.medium, INK);
            t.setText(label);
            t.setPaddingRelative(Math.round(14 * k), 0, 0, 0);
            b.addView(t);
        }
        b.setBackground(round(0x33FFFFFF, 40 * k));
        b.setFocusable(true);
        b.setFocusableInTouchMode(true);
        b.setOnClickListener(click);
        TextView text = t;
        b.setOnFocusChangeListener((v, has) -> {
            b.setBackground(round(has ? INK : 0x33FFFFFF, 40 * k));
            icon.color(has ? 0xFF14161A : INK);
            if (text != null) text.setTextColor(has ? 0xFF14161A : INK);
            v.animate().scaleX(has ? 1.06f : 1f).scaleY(has ? 1.06f : 1f).translationZ(has ? 12 * k : 0).setDuration(140).start();
        });
        b.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);
        return b;
    }

    /** A chip that is on or off (filters): on shows a check on a light pill; focused it turns white. */
    static TextView toggle(Context c, float k, String label, boolean on, View.OnClickListener click) {
        TextView b = text(c, k, 26, Ui.medium, INK);
        b.setGravity(Gravity.CENTER);
        b.setPaddingRelative(Math.round(28 * k), Math.round(14 * k), Math.round(28 * k), Math.round(14 * k));
        b.setFocusable(true);
        b.setFocusableInTouchMode(true);
        b.setTag(on);
        Runnable style = () -> {
            boolean f = b.hasFocus(), sel = Boolean.TRUE.equals(b.getTag());
            b.setText((sel ? "✓  " : "") + label);
            b.setBackground(round(f ? INK : sel ? 0x66FFFFFF : 0x1FFFFFFF, 40 * k));
            b.setTextColor(f ? 0xFF14161A : sel ? INK : DIM);
        };
        style.run();
        b.setOnFocusChangeListener((v, has) -> style.run());
        b.setOnClickListener(v -> {
            b.setTag(!Boolean.TRUE.equals(b.getTag()));
            style.run();
            if (click != null) click.onClick(v);
        });
        return b;
    }

    /** A centered question with a yes and a no, like the shelf's menu. */
    static void confirm(Activity a, String question, String yes, Runnable ok) {
        View root = a.getWindow().getDecorView();
        int w = root.getWidth(), h = root.getHeight();
        Rect middle = new Rect(w / 2, h / 2, w / 2, h / 2 + 1);
        popover(a, root, middle, question, new String[] {yes, Ui.tr("Cancelar", "Cancel")}, which -> { if (which == 0) ok.run(); });
    }

    /**
     * Posters in a vertical grid, `columns` per line. Like a FlatList: a long list is built a few lines at a time as
     * the focus nears the end (TvUi.Scroll asks), so a list of hundreds opens at once.
     */
    static final class Grid extends LinearLayout {
        private static final int CHUNK_LINES = 3;
        private final Activity a;
        private final float k;
        private final int columns;
        private final OnTitle open;
        private LinearLayout line;
        private final List<Tmdb.Title> waiting = new ArrayList<>();
        /** Asked when the focus nears the end and nothing is waiting (the next TMDB page). */
        Runnable needMore;
        int count;

        Grid(Activity a, float k, int columns, OnTitle open) {
            super(a);
            this.a = a;
            this.k = k;
            this.columns = columns;
            this.open = open;
            setOrientation(VERTICAL);
            setClipChildren(false);
        }

        /** Adds titles: the first lines right away, the rest as the focus gets there. */
        void add(List<Tmdb.Title> titles) {
            waiting.addAll(titles);
            showWaiting(columns * CHUNK_LINES);
        }

        private void showWaiting(int n) {
            for (int i = 0; i < n && !waiting.isEmpty(); i++) add(poster(a, k, waiting.remove(0), open));
        }

        void add(View cell) {
            if (line == null || line.getChildCount() >= columns) {
                line = new LinearLayout(a);
                line.setOrientation(HORIZONTAL);
                line.setClipChildren(false);
                line.setClipToPadding(false);
                line.setPaddingRelative(Math.round(24 * k), Math.round(30 * k), 0, Math.round(16 * k));
                addView(line);
            }
            line.addView(cell);
            count++;
        }

        /** The focus is on `focused` (inside this grid): near the end, the next lines (or the next page). */
        void near(View focused) {
            if (lineOf(focused) < getChildCount() - 2) return;
            if (!waiting.isEmpty()) showWaiting(columns * CHUNK_LINES);
            else if (needMore != null) needMore.run();
        }

        int lineOf(View focused) {
            for (int i = 0; i < getChildCount(); i++) {
                View row = getChildAt(i);
                for (View v = focused; v != null; v = v.getParent() instanceof View ? (View) v.getParent() : null) {
                    if (v == row) return i;
                }
            }
            return -1;
        }
    }

    /**
     * A vertical scroll for the TV: the focused item is brought to the middle of the screen, and only when it would
     * otherwise sit near an edge (moving between things already in view doesn't scroll). A grid under the focus is
     * told, so it builds its next lines.
     */
    static final class Scroll extends ScrollView {
        private int aim = -1;

        Scroll(Context c) {
            super(c);
            setFocusable(false);
            setVerticalScrollBarEnabled(false);
            setClipChildren(false);
            setSmoothScrollingEnabled(true);
        }

        @Override public void requestChildFocus(View child, View focused) {
            super.requestChildFocus(child, focused);
            for (View v = focused; v != null && v != this; v = v.getParent() instanceof View ? (View) v.getParent() : null) {
                if (v instanceof Grid) {
                    ((Grid) v).near(focused);
                    break;
                }
            }
        }

        @Override protected int computeScrollDeltaToGetChildRectOnScreen(Rect r) {
            int h = getHeight();
            if (h == 0 || getChildCount() == 0) return 0;
            int y = aim >= 0 ? aim : getScrollY();
            int margin = Math.round(h * 0.14f);
            if (r.top >= y + margin && r.bottom <= y + h - margin) return 0;
            if (r.top < margin && y == 0) return 0;     // near the top of the page: the page stays put
            int max = Math.max(0, getChildAt(0).getHeight() - h);
            int target = Math.max(0, Math.min(max, r.centerY() - h / 2));
            if (target != y) {
                aim = target;
                smoothScrollTo(0, target);
            }
            return 0;
        }

        @Override protected void onScrollChanged(int l, int t, int oldl, int oldt) {
            super.onScrollChanged(l, t, oldl, oldt);
            if (t == aim) aim = -1;
        }

        void top() {
            aim = -1;
            scrollTo(0, 0);
        }
    }

    /** Children left to right, wrapping to a new line when the width runs out (chips, buttons). */
    static final class Flow extends ViewGroup {
        private final int gapX, gapY;

        Flow(Context c, int gapX, int gapY) {
            super(c);
            this.gapX = gapX;
            this.gapY = gapY;
            setClipChildren(false);
            setClipToPadding(false);
        }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int max = MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight();
            int x = 0, y = 0, lineH = 0, widest = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View c = getChildAt(i);
                if (c.getVisibility() == GONE) continue;
                c.measure(MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                if (x > 0 && x + c.getMeasuredWidth() > max) {
                    x = 0;
                    y += lineH + gapY;
                    lineH = 0;
                }
                x += c.getMeasuredWidth() + gapX;
                widest = Math.max(widest, x - gapX);
                lineH = Math.max(lineH, c.getMeasuredHeight());
            }
            setMeasuredDimension(MeasureSpec.getMode(widthSpec) == MeasureSpec.EXACTLY ? MeasureSpec.getSize(widthSpec)
                    : widest + getPaddingLeft() + getPaddingRight(), y + lineH + getPaddingTop() + getPaddingBottom());
        }

        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int max = r - l - getPaddingLeft() - getPaddingRight();
            int x = 0, y = 0, lineH = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View c = getChildAt(i);
                if (c.getVisibility() == GONE) continue;
                if (x > 0 && x + c.getMeasuredWidth() > max) {
                    x = 0;
                    y += lineH + gapY;
                    lineH = 0;
                }
                // Right to left (Hebrew): the same lines, filled from the right edge.
                int cl = getLayoutDirection() == LAYOUT_DIRECTION_RTL ? r - l - getPaddingRight() - x - c.getMeasuredWidth()
                        : getPaddingLeft() + x;
                c.layout(cl, getPaddingTop() + y, cl + c.getMeasuredWidth(), getPaddingTop() + y + c.getMeasuredHeight());
                x += c.getMeasuredWidth() + gapX;
                lineH = Math.max(lineH, c.getMeasuredHeight());
            }
        }
    }

    interface OnPick { void pick(int which); }

    /**
     * A small menu that drops from a card (instead of Android's dialog): a dark rounded panel above `anchor` (window
     * coordinates), its options as rows that turn white when focused. Back closes it.
     */
    static PopupWindow popover(Activity a, View parent, Rect anchor, String title, String[] items, OnPick pick) {
        return popover(a, parent, anchor, title, items, pick, false);
    }

    /** `light`: frosted white like the home screen by day, dark ink on it. */
    static PopupWindow popover(Activity a, View parent, Rect anchor, String title, String[] items, OnPick pick, boolean light) {
        float k = k(a);
        int ink = light ? 0xFF1C1C1E : INK, pill = light ? 0xFF1C1C1E : INK, onPill = light ? 0xFFFFFFFF : 0xFF14161A;
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPaddingRelative(Math.round(14 * k), Math.round(18 * k), Math.round(14 * k), Math.round(14 * k));
        box.setBackground(round(light ? 0xF7F4F4F6 : 0xF2202329, 26 * k));
        box.setElevation(30 * k);
        TextView h = text(a, k, 22, Ui.semibold, light ? 0x8C1C1C1E : FAINT);
        h.setText(title.toUpperCase(java.util.Locale.ROOT));
        h.setLetterSpacing(0.1f);
        h.setPaddingRelative(Math.round(22 * k), 0, Math.round(22 * k), Math.round(12 * k));
        box.addView(h);
        PopupWindow pop = new PopupWindow(box, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        pop.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
        pop.setElevation(30 * k);
        View first = null;
        for (int i = 0; i < items.length; i++) {
            final int which = i;
            TextView row = text(a, k, 28, Ui.medium, ink);
            row.setText(items[i]);
            row.setMinWidth(Math.round(340 * k));
            row.setPaddingRelative(Math.round(22 * k), Math.round(16 * k), Math.round(22 * k), Math.round(16 * k));
            row.setFocusable(true);
            row.setOnFocusChangeListener((v, has) -> {
                v.setBackground(has ? round(pill, 16 * k) : null);
                ((TextView) v).setTextColor(has ? onPill : ink);
            });
            row.setOnClickListener(v -> {
                pop.dismiss();
                pick.pick(which);
            });
            row.setOnKeyListener((v, code, e) -> code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
                    || (code == KeyEvent.KEYCODE_DPAD_UP && which == 0) || (code == KeyEvent.KEYCODE_DPAD_DOWN && which == items.length - 1));
            box.addView(row);
            if (first == null) first = row;
        }
        box.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int w = box.getMeasuredWidth(), hgt = box.getMeasuredHeight();
        int screenW = a.getResources().getDisplayMetrics().widthPixels;
        int x = Math.max(Math.round(24 * k), Math.min(screenW - w - Math.round(24 * k), anchor.centerX() - w / 2));
        int y = anchor.top - hgt - Math.round(22 * k);
        if (y < Math.round(24 * k)) y = anchor.bottom + Math.round(22 * k);
        pop.setAnimationStyle(android.R.style.Animation_Dialog);
        pop.showAtLocation(parent, Gravity.NO_GRAVITY, x, y);
        View focus = first;
        if (focus != null) focus.post(focus::requestFocus);
        return pop;
    }
}
