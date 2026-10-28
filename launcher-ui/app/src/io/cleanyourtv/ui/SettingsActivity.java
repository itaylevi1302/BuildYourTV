package io.cleanyourtv.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeProvider;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The settings (the app's icon, "All Apps", or the gear on the home screen), in the style of Apple TV's settings: the
 * big symbol and title on the left, the list on the right with the focused row in white. Switching the home screen
 * and turning apps off is done from the computer (tools/), so nothing here needs ADB.
 */
public class SettingsActivity extends Activity {
    private SettingsView view;
    private View preview;   // "Screensaver": the screensaver right here, in the style picked, without changing the system setting

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.init(this);
        view = new SettingsView(this);
        setContentView(view);
    }

    @Override protected void onResume() {
        super.onResume();
        TvConfig.apply(this);
        view.resume();
        // From ADB: am start -n io.cleanyourtv.ui/.SettingsActivity --ez screensaver true (straight to the preview).
        if (preview == null && getIntent().getBooleanExtra("screensaver", false)) {
            getIntent().removeExtra("screensaver");
            view.pause();
            showPreview();
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    @Override protected void onPause() {
        closePreview();     // before pausing the view: closing the preview resumes it (view.resume)
        view.pause();
        super.onPause();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (preview != null) {
            // Like the real screensaver: arrows, OK or Back close it (other keys go on to the system).
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_BACK:
                    if (event.getAction() == KeyEvent.ACTION_UP) closePreview();
                    return true;
                default:
                    return super.dispatchKeyEvent(event);
            }
        }
        if (view.onKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    void showPreview() {
        preview = Screensaver.make(this);
        setContentView(preview);
        ((Screensaver.Show) preview).start();
    }

    private void closePreview() {
        if (preview == null) return;
        ((Screensaver.Show) preview).stop();
        preview = null;
        setContentView(view);
        view.resume();
    }

    static final class SettingsView extends View implements Choreographer.FrameCallback {
        private static final int MAIN = 0, HOME_SETTINGS = 2, REMOTE_KEYS = 3, PICKER = 4, SHELF_EDIT = 5;
        private static final float LIST_ROW_H = 54, LIST_ROW_GAP = 2, SUB_TOP = 156;
        private static final int PICK_VISIBLE = 12;
        private static final float LIST_X = 1000, LIST_R = 1824, LIST_TOP = 186, ROW_H = 74, ROW_GAP = 8;
        private static final int AMBER = 0xFFFFC266, INK = 0xFF1C1C1E;

        private final SettingsActivity activity;
        private final Scene scene = new Scene();
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Spring[] pop = new Spring[12];
        private final List<String> appPkgs = new ArrayList<>(), appLabels = new ArrayList<>();
        // The list picker (the app of a remote button): what it shows, which one is in use, where we are.
        private final List<String> pickLabels = new ArrayList<>();
        // The shelf editor: the apps it can hold (selected ones first, in order) and the selection.
        private final List<String[]> shelfRows = new ArrayList<>();
        private final List<String> shelfOrder = new ArrayList<>();
        private int pickFor, pickCurrent, pickScroll, keysScroll;   // pickFor: the remote button (index + 1)
        private static final int PICK_CITY = 0;
        private final List<String[]> cities = new ArrayList<>();
        private String cityStatus = "";
        private final RectF tmp = new RectF(), rowRect = new RectF(), focusBox = new RectF();
        private JSONObject recs = new JSONObject();
        private boolean hasKey;
        private int screen = MAIN, focus;
        private boolean running, okDown, backDown;
        private final Spoken spoken;
        private long startedAt, lastNanos, lastSky;
        private int w, h;
        private float k = 1;
        private Paint title, subtitle, version, rowLabel, rowValue, rowSub, header, cellLabel, cellValue, warn, spinner, white, shadow;
        private TextPaint notice;

        private final Runnable poll = new Runnable() {
            @Override public void run() {
                if (!running) return;
                hasKey = Recs.hasKey(getContext());
                recs = Recs.status(getContext());
                invalidate();
                handler.postDelayed(this, 2000);
            }
        };

        SettingsView(SettingsActivity a) {
            super(a);
            activity = a;
            for (int i = 0; i < pop.length; i++) pop[i] = new Spring(0.28f, 0.85f, 0);
            HomeData.Weather wx = HomeData.get(a).weather;
            Shaders.sky(scene.palette, System.currentTimeMillis(), wx.sunrise, wx.sunset);
            scene.setMotion(0.5f, 0.2f);
            scene.applyPalette();
            setFocusable(true);
            setContentDescription("BuildYourTV");
            // TalkBack: one item per row, id = row + 1.
            spoken = new Spoken(this, new Spoken.Source() {
                @Override public void items(List<Integer> out) { for (int i = 1; i <= rowCount(); i++) out.add(i); }
                @Override public String text(int id) { return id >= 1 && id <= rowCount() ? rowText(id - 1) : ""; }
                @Override public boolean bounds(int id, Rect out) {
                    if (id < 1 || id > rowCount()) return false;
                    rowBox(id - 1).round(out);
                    return true;
                }
                @Override public int focused() { return focus + 1; }
                @Override public void focus(int id) {
                    focus = id - 1;
                    invalidate();
                }
                @Override public void click(int id) { activate(); }
            });
        }

        @Override public AccessibilityNodeProvider getAccessibilityNodeProvider() {
            return spoken;
        }

        /** A row as TalkBack reads it: its name and its value, as on screen. */
        private String rowText(int row) {
            switch (screen) {
                case MAIN: {
                    if (row >= MAIN_ROWS) return "";
                    String label = mainLabels()[row];
                    switch (row) {
                        case WATCH_ROW: return label + ", " + onOff(Prefs.continueWatching());
                        case YOUTUBE_ROW_M: return label + ", " + onOff(Prefs.youtubePicks());
                        case ADS_ROW_M: return label + ", " + onOff(adsOn());
                        case STYLE_ROW: return label + ", " + styleName(Prefs.screensaverStyle());
                        case DELAY_ROW: return label + ", " + saverMinutes() + Ui.tr(" min", " min");
                        case REMOTE_ROW: return label + ", " + onOff(remoteOn());
                        case TMDB_ROW: return label + ", " + account();
                        default: return label;
                    }
                }
                case HOME_SETTINGS: {
                    String[] labels = settingLabels(), values = settingValues();
                    return row < labels.length ? labels[row] + ", " + values[row] : "";
                }
                case REMOTE_KEYS: {
                    List<RemoteKeys.Button> keys = RemoteKeys.buttons(getContext());
                    int at = keysScroll + row;
                    return at < keys.size() ? keys.get(at).name + ", " + describe(RemoteKeys.action(keys.get(at))) : "";
                }
                case PICKER: {
                    int at = pickScroll + row;
                    if (at >= pickLabels.size()) return "";
                    return pickLabels.get(at) + (at == pickCurrent ? Ui.tr(", escolhido", ", selected") : "")
                            + ", " + (at + 1) + Ui.tr(" de ", " of ") + pickLabels.size();
                }
                case SHELF_EDIT: {
                    int at = pickScroll + row;
                    if (at >= shelfRows.size()) return "";
                    String[] app = shelfRows.get(at);
                    int place = shelfOrder.indexOf(app[0]);
                    return app[1] + ", " + (place >= 0 ? Ui.tr("na prateleira, posição ", "on the shelf, place ") + (place + 1)
                            : Ui.tr("fora da prateleira", "not on the shelf"));
                }
                default:
                    return "";
            }
        }

        private RectF rowBox(int row) {
            float top, height;
            if (screen == MAIN) {
                top = mainRowTop(row);
                height = M_ROW;
            } else {
                top = SUB_TOP + row * (LIST_ROW_H + LIST_ROW_GAP);
                height = LIST_ROW_H;
            }
            focusBox.set(listLeft(), top * k, listRight(), (top + height) * k);
            return focusBox;
        }

        void resume() {
            if (running) return;
            running = true;
            startedAt = SystemClock.uptimeMillis();
            lastNanos = 0;
            handler.post(poll);
            Choreographer.getInstance().postFrameCallback(this);
            spoken.readAgain();
        }

        void pause() {
            running = false;
            handler.removeCallbacks(poll);
            Choreographer.getInstance().removeFrameCallback(this);
        }

        @Override public void doFrame(long frameTimeNanos) {
            if (!running) return;
            boolean moving = false;
            for (Spring s : pop) moving |= !s.settled();
            if (moving || SystemClock.uptimeMillis() - lastSky >= 100) invalidate();
            Choreographer.getInstance().postFrameCallback(this);
        }

        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            w = width;
            h = height;
            k = w / 1920f;
            scene.setSize(w, h);
            title = Ui.text(Ui.displaySemibold, 50 * k, 0xFFFFFFFF);
            title.setTextAlign(Paint.Align.CENTER);
            subtitle = Ui.text(Ui.regular, 26 * k, 0x99FFFFFF);
            subtitle.setTextAlign(Paint.Align.CENTER);
            version = Ui.text(Ui.regular, 22 * k, 0x66FFFFFF);
            version.setTextAlign(Paint.Align.CENTER);
            rowLabel = Ui.text(Ui.medium, 30 * k, 0xF2FFFFFF);
            rowValue = Ui.text(Ui.regular, 28 * k, 0x8CFFFFFF);
            rowValue.setTextAlign(Paint.Align.RIGHT);
            rowSub = Ui.text(Ui.regular, 23 * k, 0x8CFFFFFF);
            header = Ui.text(Ui.semibold, 21 * k, 0x73FFFFFF);
            header.setLetterSpacing(0.06f);
            cellLabel = Ui.text(Ui.regular, 25 * k, 0xD9FFFFFF);
            cellValue = Ui.numbers(Ui.regular, 25 * k, 0xD9FFFFFF);
            cellValue.setTextAlign(Paint.Align.RIGHT);
            warn = Ui.text(Ui.regular, 23 * k, AMBER);
            notice = new TextPaint(Ui.text(Ui.regular, 23 * k, 0xB3FFFFFF));
            spinner = new Paint(Paint.ANTI_ALIAS_FLAG);
            spinner.setStyle(Paint.Style.STROKE);
            spinner.setStrokeCap(Paint.Cap.ROUND);
            spinner.setStrokeWidth(3.5f * k);
            white = new Paint(Paint.ANTI_ALIAS_FLAG);
            white.setColor(0xFFF5F5F7);
            shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
            shadow.setMaskFilter(new BlurMaskFilter(22 * k, BlurMaskFilter.Blur.NORMAL));
        }

        private int rowCount() {
            switch (screen) {
                case MAIN: return MAIN_ROWS;
                case HOME_SETTINGS: return settingLabels().length;
                case PICKER: return Math.min(PICK_VISIBLE, pickLabels.size());
                case SHELF_EDIT: return Math.min(PICK_VISIBLE, shelfRows.size());
                default: return Math.min(PICK_VISIBLE, RemoteKeys.buttons(getContext()).size());
            }
        }

        @Override protected void onDraw(Canvas c) {
            if (w == 0) return;
            long now = System.nanoTime();
            float dt = lastNanos == 0 ? 1 / 60f : Math.min(0.05f, (now - lastNanos) / 1e9f);
            lastNanos = now;
            for (int i = 0; i < pop.length; i++) {
                pop[i].target = i == focus && i < rowCount() ? 1 : 0;
                pop[i].step(dt);
            }
            long up = SystemClock.uptimeMillis();
            float seconds = (up - startedAt) / 1000f;
            if (up - lastSky >= 100) {
                lastSky = up;
                scene.advance(seconds);
            }
            scene.drawBackground(c);

            // The side panel (on the left, the right in Hebrew): on the main list it describes the focused row, like the
            // Apple TV's settings; elsewhere it shows the app. The credits sit at its foot.
            float cx = (Ui.he ? 1420 : 500) * k;
            if (screen == MAIN || screen == HOME_SETTINGS || screen == SHELF_EDIT || screen == REMOTE_KEYS || screen == PICKER) {
                drawPanel(c, cx, screen == MAIN ? Math.min(focus, MAIN_ROWS - 1) : parentRow());
            } else {
                Symbols.draw(getContext(), c, "sparkles", cx, 400 * k, 150 * k, 0xEBFFFFFF);
                c.drawText("BuildYourTV", cx, 612 * k, title);
                c.drawText(Ui.tr("Início e tela de descanso da TV", "Your TV's home screen and screensaver"), cx, 660 * k, subtitle);
            }
            c.drawText(versionName(getContext()) + "  ·  Itay Levi  ·  github.com/itaylevi1302/BuildYourTV", cx, 988 * k, version);
            // The project it grew from (MIT): credited here as well as in LICENSE and NOTICE; TMDB asks for its line.
            c.drawText(Ui.tr("Baseado no CleanYourTV UI, de Matheus Caldeira", "Based on CleanYourTV UI by Matheus Caldeira"), cx, 1022 * k, version);
            c.drawText(Ui.tr("Usa a API do TMDB, sem endosso ou certificação do TMDB.",
                    "This product uses the TMDB API but is not endorsed or certified by TMDB."), cx, 1056 * k, version);

            if (screen == MAIN) drawMain(c); else if (screen == PICKER) drawPicker(c); else if (screen == SHELF_EDIT) drawShelfEditor(c); else drawList(c);
        }

        // MARK: - Main list: four sections of rows, as in the Apple TV's settings

        private static final int HOME_ROW = 0, SHELF_ROW = 1, WATCH_ROW = 2, YOUTUBE_ROW_M = 3, STYLE_ROW = 4,
                DELAY_ROW = 5, ADS_ROW_M = 6, REMOTE_ROW = 7, SERVICES_ROW = 8, TMDB_ROW = 9, TV_ROW = 10, MAIN_ROWS = 11;
        private static final int[] SECTION_START = {HOME_ROW, STYLE_ROW, ADS_ROW_M, TMDB_ROW, MAIN_ROWS};
        private static final String[] MAIN_ICONS = {"home", "apps", "play", "tv", "sparkles", "bedtime", "check", "tune",
                "live_tv", "sync", "tv_settings"};
        private static final int[] MAIN_COLORS = {0xFF0A84FF, 0xFF5E5CE6, 0xFFFF375F, 0xFFFF3B30, 0xFFBF5AF2, 0xFFFF9F0A,
                0xFF30D158, 0xFF0FA3C8, 0xFFE0A800, 0xFF8E8E93, 0xFF636366};
        /** Design units: where the list starts, a row's height and the gaps around a section's card. */
        private static final float M_TOP = 132, M_HEADER = 38, M_ROW = 54, M_GAP = 2, M_PAD = 6, M_AFTER = 12;

        private String[] sectionNames() {
            return new String[] {Ui.tr("Tela inicial", "Home screen"), Ui.tr("Tela de descanso", "Screensaver"),
                    Ui.tr("Rede e controle", "Network and remote"), Ui.tr("Conta e sistema", "Account and system")};
        }

        private String[] mainLabels() {
            return new String[] {Ui.tr("Ajustes do Início", "Home Settings"), Ui.tr("Prateleira", "Shelf"),
                    Ui.tr("Continuar assistindo", "Continue watching"), Ui.tr("Sugestões do YouTube", "YouTube picks"),
                    Ui.tr("Estilo", "Style"), Ui.tr("Começa após", "Starts after"), Ui.tr("Bloqueio de anúncios", "Ad blocking"),
                    Ui.tr("Botões do Controle", "Remote Buttons"), Ui.tr("Serviços", "Services"), Ui.tr("Conta TMDB", "TMDB account"),
                    Ui.tr("Ajustes da TV", "TV Settings")};
        }

        private String[] mainDescriptions() {
            return new String[] {
                Ui.tr("Idioma, relógio, clima, cidade e tema da tela inicial.", "Language, clock, weather, city and theme of the home screen."),
                Ui.tr("Os apps da prateleira e a ordem deles.", "Which apps sit on the shelf, and in what order."),
                Ui.tr("Os primeiros cartões: o que você começou na Netflix e no Stremio, com o progresso.",
                        "The first cards: what you started on Netflix and Stremio, with how far along you are."),
                Ui.tr("Vídeos que o YouTube recomenda, depois de Continuar assistindo.", "Videos YouTube recommends for you, after Continue watching."),
                Ui.tr("Destaques, relógio e aurora, ou um álbum do Google Fotos. ← → mudam, OK mostra.",
                        "Picks, the clock over the aurora, or a Google Photos album. ← → change it, OK shows it."),
                Ui.tr("Quanto tempo sem tocar no controle até a tela de descanso.", "How long without touching the remote until the screensaver."),
                Ui.tr("Um DNS que bloqueia anúncios e rastreamento em todos os apps. Não bloqueia os do YouTube.",
                        "A DNS that blocks ad and tracking servers in every app. It doesn't block YouTube's ads."),
                Ui.tr("O que fazem os botões Netflix, YouTube e outros do controle.", "What the remote's Netflix, YouTube and other buttons do."),
                Ui.tr("De quais serviços vêm as sugestões.", "Which streaming services the recommendations come from."),
                Ui.tr("A conta da sua lista e das suas notas.", "The account behind your list and your ratings."),
                Ui.tr("Abre os ajustes normais da TV.", "Opens the TV's own settings.")};
        }

        /** The top of row `i` (design units), and through `headerY` the baseline of each section's name. */
        private float mainRowTop(int i) {
            float y = M_TOP;
            for (int sct = 0; sct < 4; sct++) {
                y += M_HEADER;
                for (int r = SECTION_START[sct]; r < SECTION_START[sct + 1]; r++) {
                    if (r == i) return y + M_PAD + (r - SECTION_START[sct]) * (M_ROW + M_GAP);
                }
                y += 2 * M_PAD + (SECTION_START[sct + 1] - SECTION_START[sct]) * (M_ROW + M_GAP) - M_GAP + M_AFTER;
            }
            return y;
        }

        private void drawMain(Canvas c) {
            Paint heading = Ui.text(Ui.displaySemibold, 40 * k, 0xFFFFFFFF);
            listText(c, Ui.tr("Ajustes", "Settings"), 104 * k, heading);
            String[] names = sectionNames(), labels = mainLabels();
            Paint card = new Paint(Paint.ANTI_ALIAS_FLAG);
            card.setColor(0x14FFFFFF);
            // The cards behind each section and the section names, then the rows (the focused one last, on top).
            for (int sct = 0; sct < 4; sct++) {
                int first = SECTION_START[sct], last = SECTION_START[sct + 1] - 1;
                float top = (mainRowTop(first) - M_PAD) * k, bottom = (mainRowTop(last) + M_ROW + M_PAD) * k;
                tmp.set(listLeft(), top, listRight(), bottom);
                c.drawRoundRect(tmp, 20 * k, 20 * k, card);
                listText(c, names[sct], top - 12 * k, header);
            }
            for (int i = 0; i < MAIN_ROWS; i++) if (i != focus) drawMainRow(c, i, labels[i]);
            if (focus < MAIN_ROWS) drawMainRow(c, focus, labels[focus]);
        }

        private void drawMainRow(Canvas c, int i, String label) {
            listRow(mainRowTop(i) * k, M_ROW * k);
            rowRect.inset(6 * k, 0);
            float f = drawRowBackground(c, i);
            float base = rowRect.centerY() + Ui.halfCap(rowLabel);
            // The icon on a coloured tile at the row's start, the label after it.
            float tile = 38 * k, tx = Ui.he ? rowRect.right - 18 * k - tile : rowRect.left + 18 * k;
            tmp.set(tx, rowRect.centerY() - tile / 2, tx + tile, rowRect.centerY() + tile / 2);
            Paint fillTile = new Paint(Paint.ANTI_ALIAS_FLAG);
            fillTile.setColor(MAIN_COLORS[i]);
            c.drawRoundRect(tmp, 10 * k, 10 * k, fillTile);
            Symbols.draw(getContext(), c, MAIN_ICONS[i], tmp.centerX(), tmp.centerY(), 24 * k, 0xFFFFFFFF);
            rowLabel.setColor(blend(0xF2FFFFFF, INK, f));
            atStart(c, label, base, rowLabel, tile + 4 * k);
            // The row's end: a switch, a value in a pill, a status, or a chevron to a page.
            switch (i) {
                case WATCH_ROW: drawSwitch(c, Prefs.continueWatching(), f); break;
                case YOUTUBE_ROW_M: drawSwitch(c, Prefs.youtubePicks(), f); break;
                case ADS_ROW_M: drawSwitch(c, adsOn(), f); break;
                case STYLE_ROW: {
                    String style = styleName(Prefs.screensaverStyle());
                    drawPill(c, f > 0.5f ? "‹  " + style + "  ›" : style, f, 0);
                    break;
                }
                case DELAY_ROW: drawPill(c, saverMinutes() + Ui.tr(" min", " min"), f, 0); break;
                case SHELF_ROW:
                    endIcon(c, "chevron_right", 22 * k, blend(0x66FFFFFF, 0x991C1C1E, f));
                    drawPill(c, String.valueOf(HomeApps.shelfPackages(getContext()).size()), f, 34 * k);
                    break;
                case REMOTE_ROW: {
                    endIcon(c, "chevron_right", 22 * k, blend(0x66FFFFFF, 0x991C1C1E, f));
                    boolean on = remoteOn();
                    drawStatus(c, on, on ? Ui.tr("Ligados", "On ") : Ui.tr("Desligados", "Off "), f, 34 * k);
                    break;
                }
                case TMDB_ROW:
                    rowValue.setColor(blend(0x8CFFFFFF, 0x8C1C1C1E, f));
                    atEnd(c, account(), base, rowValue);
                    break;
                default:
                    endIcon(c, "chevron_right", 22 * k, blend(0x66FFFFFF, 0x991C1C1E, f));
            }
        }

        /** An on/off switch at the row's end: green when on, its knob at the end side. */
        private void drawSwitch(Canvas c, boolean on, float f) {
            float w2 = 58 * k, h2 = 32 * k, x0 = Ui.he ? rowRect.left + 24 * k : rowRect.right - 24 * k - w2;
            tmp.set(x0, rowRect.centerY() - h2 / 2, x0 + w2, rowRect.centerY() + h2 / 2);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(on ? 0xFF30D158 : blend(0x40FFFFFF, 0x331C1C1E, f));
            c.drawRoundRect(tmp, h2 / 2, h2 / 2, p);
            boolean knobRight = on != Ui.he;     // on: the knob at the end (the right, or the left in Hebrew)
            float kx = knobRight ? tmp.right - h2 / 2 : tmp.left + h2 / 2;
            p.setColor(0xFFFFFFFF);
            p.setShadowLayer(3 * k, 0, k, 0x40000000);
            c.drawCircle(kx, tmp.centerY(), h2 / 2 - 3 * k, p);
        }

        /** A value in a pill at the row's end, `offset` further in (when a chevron is there). */
        private void drawPill(Canvas c, String text, float f, float offset) {
            float tw = rowValue.measureText(text), pw = tw + 32 * k, ph = 38 * k;
            float x0 = Ui.he ? rowRect.left + 22 * k + offset : rowRect.right - 22 * k - offset - pw;
            tmp.set(x0, rowRect.centerY() - ph / 2, x0 + pw, rowRect.centerY() + ph / 2);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(blend(0x1FFFFFFF, 0x141C1C1E, f));
            c.drawRoundRect(tmp, ph / 2, ph / 2, p);
            rowValue.setColor(blend(0xD9FFFFFF, 0xD91C1C1E, f));
            Paint.Align a = rowValue.getTextAlign();
            rowValue.setTextAlign(Paint.Align.CENTER);
            c.drawText(text, tmp.centerX(), rowRect.centerY() + Ui.halfCap(rowValue), rowValue);
            rowValue.setTextAlign(a);
        }

        /** A green or amber dot and a word at the row's end. */
        private void drawStatus(Canvas c, boolean ok, String text, float f, float offset) {
            rowValue.setColor(blend(0xB3FFFFFF, 0xB31C1C1E, f));
            float tw = rowValue.measureText(text), dot = 6 * k;
            float textEnd = Ui.he ? rowRect.left + 22 * k + offset : rowRect.right - 22 * k - offset;
            Paint.Align a = rowValue.getTextAlign();
            rowValue.setTextAlign(Ui.he ? Paint.Align.LEFT : Paint.Align.RIGHT);
            c.drawText(text, textEnd, rowRect.centerY() + Ui.halfCap(rowValue), rowValue);
            rowValue.setTextAlign(a);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(ok ? 0xFF30D158 : AMBER);
            float dx = Ui.he ? textEnd + tw + 12 * k : textEnd - tw - 12 * k;
            c.drawCircle(dx, rowRect.centerY(), dot, p);
        }

        /** The side panel on the main list: the focused row's icon, name and what it does. */
        /** The main-list row an inner screen was opened from (its tile and words fill the side panel). */
        private int parentRow() {
            if (screen == SHELF_EDIT) return SHELF_ROW;
            if (screen == REMOTE_KEYS || (screen == PICKER && pickFor != PICK_CITY)) return REMOTE_ROW;
            return HOME_ROW;
        }

        private void drawPanel(Canvas c, float cx, int i) {
            float tile = 172 * k;
            tmp.set(cx - tile / 2, 300 * k, cx + tile / 2, 300 * k + tile);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(MAIN_COLORS[i]);
            p.setShadowLayer(30 * k, 0, 12 * k, 0x59000000);
            c.drawRoundRect(tmp, 40 * k, 40 * k, p);
            Symbols.draw(getContext(), c, MAIN_ICONS[i], cx, tmp.centerY(), 100 * k, 0xFFFFFFFF);
            c.drawText(mainLabels()[i], cx, 560 * k, title);
            String desc = mainDescriptions()[i];
            if (i == TMDB_ROW) desc += "\n" + recsLine();
            if (i == WATCH_ROW && !Prefs.continueWatching()) desc += "\n" + Ui.tr("Desligado agora.", "Off right now.");
            notice.setTextSize(27 * k);
            notice.setColor(0xB3FFFFFF);
            android.text.StaticLayout layout = android.text.StaticLayout.Builder
                    .obtain(desc, 0, desc.length(), notice, Math.round(620 * k))
                    .setAlignment(android.text.Layout.Alignment.ALIGN_CENTER)
                    .setLineSpacing(8 * k, 1)
                    .build();
            c.save();
            c.translate(cx - 310 * k, 600 * k);
            layout.draw(c);
            c.restore();
            notice.setTextSize(23 * k);
        }

        /** The recommendations' state in one sentence (under TMDB account). */
        private String recsLine() {
            String error = recs.optString("error");
            int count = recs.optInt("count");
            if (!hasKey) return Ui.tr("Sem chave do TMDB: digite a sua em Conta TMDB.", "No TMDB key: type yours under TMDB account.");
            if (!error.isEmpty()) return Ui.tr("Erro: ", "Error: ") + error + ".";
            if (count > 0) return Ui.tr("Ligadas · " + count + " títulos nos apps da TV, 3 novos a cada hora.",
                    "On · " + count + " titles from the TV's apps, 3 new ones every hour.");
            return Ui.tr("Buscando os títulos…", "Fetching titles…");
        }

        /** Whether the remote's app buttons are handled (this app's accessibility service is switched on). */
        private boolean remoteOn() {
            String s = Settings.Secure.getString(getContext().getContentResolver(), "enabled_accessibility_services");
            return s != null && s.contains(getContext().getPackageName() + "/");
        }

        private static final String AD_DNS = "dns.adguard-dns.com";

        /** Ad blocking: Private DNS pointed at AdGuard's ad-blocking resolver. */
        private boolean adsOn() {
            android.content.ContentResolver cr = getContext().getContentResolver();
            return "hostname".equals(Settings.Global.getString(cr, "private_dns_mode"))
                    && AD_DNS.equals(Settings.Global.getString(cr, "private_dns_specifier"));
        }

        /** Needs WRITE_SECURE_SETTINGS (pm grant), the same as the screensaver picker. */
        private void setAds(boolean on) {
            try {
                android.content.ContentResolver cr = getContext().getContentResolver();
                if (on) {
                    Settings.Global.putString(cr, "private_dns_specifier", AD_DNS);
                    Settings.Global.putString(cr, "private_dns_mode", "hostname");
                } else {
                    Settings.Global.putString(cr, "private_dns_mode", "opportunistic");
                }
            } catch (SecurityException e) {
                Toast.makeText(activity, Ui.tr("Sem permissão para mudar o DNS.", "No permission to change the DNS."), Toast.LENGTH_LONG).show();
            }
        }

        private static final int[] SAVER_MINUTES = {1, 5, 10, 15, 30};

        private int saverMinutes() {
            return Settings.System.getInt(getContext().getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, 600_000) / 60_000;
        }

        /** The time until the screensaver (screen_off_timeout). Needs WRITE_SETTINGS (appops, from ADB). */
        private void stepSaver(int dir) {
            int at = 0;
            for (int i = 0; i < SAVER_MINUTES.length; i++) if (SAVER_MINUTES[i] <= saverMinutes()) at = i;
            int next = SAVER_MINUTES[(at + dir + SAVER_MINUTES.length) % SAVER_MINUTES.length];
            if (!Settings.System.canWrite(getContext())) {
                Toast.makeText(activity, Ui.tr("Sem permissão para mudar o tempo.", "No permission to change the time."), Toast.LENGTH_LONG).show();
                return;
            }
            Settings.System.putInt(getContext().getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, next * 60_000);
        }

        /** One status line: a green or amber dot and a sentence. */
        private void statusLine(Canvas c, boolean ok, String text, float y) {
            float x = listStartX(), dot = 7 * k;
            white.setAlpha(255);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(ok ? 0xFF30D158 : AMBER);
            c.drawCircle(Ui.he ? x - dot : x + dot, y - Ui.halfCap(notice), dot, p);
            Paint.Align a = notice.getTextAlign();
            notice.setTextAlign(Ui.he ? Paint.Align.RIGHT : Paint.Align.LEFT);
            c.drawText(text, Ui.he ? x - 3 * dot : x + 3 * dot, y, notice);
            notice.setTextAlign(a);
        }

        // MARK: - Layout that mirrors in Hebrew (the list on the left, text from the right)

        private float listLeft() { return (Ui.he ? 1920 - LIST_R : LIST_X) * k; }
        private float listRight() { return (Ui.he ? 1920 - LIST_X : LIST_R) * k; }
        private float listStartX() { return Ui.he ? listRight() - 30 * k : listLeft() + 30 * k; }

        private void listRow(float y, float height) {
            rowRect.set(listLeft(), y, listRight(), y + height);
        }

        /** Text at the list's start edge (left, or right in Hebrew): headings and hints. */
        private void listText(Canvas c, String text, float y, Paint p) {
            Paint.Align a = p.getTextAlign();
            p.setTextAlign(Ui.he ? Paint.Align.RIGHT : Paint.Align.LEFT);
            c.drawText(text, listStartX(), y, p);
            p.setTextAlign(a);
        }

        private void atStart(Canvas c, String text, float base, Paint p) {
            atStart(c, text, base, p, 0);
        }

        /** A row's label at its start edge, `indent` further in (after an icon). */
        private void atStart(Canvas c, String text, float base, Paint p, float indent) {
            Paint.Align a = p.getTextAlign();
            p.setTextAlign(Ui.he ? Paint.Align.RIGHT : Paint.Align.LEFT);
            c.drawText(text, Ui.he ? rowRect.right - 30 * k - indent : rowRect.left + 30 * k + indent, base, p);
            p.setTextAlign(a);
        }

        /** A row's value at its end edge. */
        private void atEnd(Canvas c, String text, float base, Paint p) {
            Paint.Align a = p.getTextAlign();
            p.setTextAlign(Ui.he ? Paint.Align.LEFT : Paint.Align.RIGHT);
            c.drawText(text, Ui.he ? rowRect.left + 30 * k : rowRect.right - 30 * k, base, p);
            p.setTextAlign(a);
        }

        /** A symbol at a row's end; the chevron turns to point left in Hebrew. */
        private void endIcon(Canvas c, String name, float size, int color) {
            float cw = Symbols.width(getContext(), name, size);
            float x = Ui.he ? rowRect.left + 30 * k + cw / 2 : rowRect.right - 30 * k - cw / 2;
            boolean flip = Ui.he && name.startsWith("chevron");
            if (flip) {
                c.save();
                c.scale(-1, 1, x, rowRect.centerY());
            }
            Symbols.draw(getContext(), c, name, x, rowRect.centerY(), size, color);
            if (flip) c.restore();
        }

        private String account() {
            if (!Tmdb.hasKey()) return Ui.tr("Sem chave", "No key");
            return Tmdb.signedIn(getContext()) ? Tmdb.accountName(getContext()) : Ui.tr("Não conectada", "Not connected");
        }

        /** List row: no background; focused, white with a shadow (the Apple TV focus). Returns how focused it is. */
        private float drawRowBackground(Canvas c, int i) {
            float f = Ui.clamp(pop[i].value);
            if (f > 0.01f) {
                float s = 1 + 0.02f * f;
                float cx = rowRect.centerX(), cy = rowRect.centerY();
                tmp.set(cx - rowRect.width() * s / 2, cy - rowRect.height() * s / 2, cx + rowRect.width() * s / 2, cy + rowRect.height() * s / 2);
                float r = 18 * k * s;
                tmp.offset(0, 10 * k * f);
                shadow.setColor(Ui.alpha(0xFF000000, 0.4f * f));
                c.drawRoundRect(tmp, r, r, shadow);
                tmp.offset(0, -10 * k * f);
                white.setAlpha(Math.round(255 * f));
                c.drawRoundRect(tmp, r, r, white);
                rowRect.set(tmp);
            }
            return f;
        }

        private static int blend(int a, int b, float f) {
            int aa = a >>> 24, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
            int ba = b >>> 24, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
            return (Math.round(aa + (ba - aa) * f) << 24) | (Math.round(ar + (br - ar) * f) << 16)
                    | (Math.round(ag + (bg - ag) * f) << 8) | Math.round(ab + (bb - ab) * f);
        }

        private static String versionName(Context c) {
            try {
                return "BuildYourTV " + c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
            } catch (android.content.pm.PackageManager.NameNotFoundException e) {
                return "BuildYourTV";
            }
        }

        boolean onKey(KeyEvent e) {
            boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (down && focus > 0) focus--;
                    else if (down && screen == REMOTE_KEYS && keysScroll > 0) keysScroll--;
                    else if (down && scrolling() && pickScroll > 0) pickScroll--;
                    invalidate();
                    spoken.focusMoved();
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (down && focus < rowCount() - 1) focus++;
                    else if (down && screen == REMOTE_KEYS && keysScroll + rowCount() < RemoteKeys.buttons(getContext()).size()) keysScroll++;
                    else if (down && scrolling() && pickScroll + rowCount() < listSize()) pickScroll++;
                    invalidate();
                    spoken.focusMoved();
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (down && screen == MAIN) {
                        int dir = (e.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT) != Ui.he ? 1 : -1;   // mirrored in Hebrew
                        if (focus == STYLE_ROW) {
                            int at = java.util.Arrays.asList(STYLES).indexOf(Prefs.screensaverStyle());
                            Prefs.setScreensaverStyle(STYLES[(at + dir + STYLES.length) % STYLES.length]);
                        } else if (focus == DELAY_ROW) {
                            stepSaver(dir);
                        } else if (focus == WATCH_ROW || focus == YOUTUBE_ROW_M || focus == ADS_ROW_M) {
                            toggleMain(focus);
                        }
                        invalidate();
                        spoken.focusMoved();
                    } else if (down && screen == SHELF_EDIT) {
                        moveShelfApp((e.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT) != Ui.he ? 1 : -1);
                        invalidate();
                    } else if (down && screen == HOME_SETTINGS) {
                        adjust((e.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT) != Ui.he ? 1 : -1);
                        invalidate();
                    }
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    if (down) {
                        if (e.getRepeatCount() == 0) okDown = true;
                    } else {
                        if (okDown && !e.isCanceled() && spoken.keyClick()) activate();
                        okDown = false;
                    }
                    return true;
                case KeyEvent.KEYCODE_MENU:
                    if (screen != SHELF_EDIT) return false;
                    if (!down && !shelfRows.isEmpty()) customizeTile(shelfRows.get(pickScroll + focus));
                    return true;
                case KeyEvent.KEYCODE_BACK:
                    if (screen == MAIN) return false;   // leaves the settings
                    if (down) {
                        if (e.getRepeatCount() == 0) backDown = true;
                    } else {
                        if (backDown && !e.isCanceled()) {
                            if (screen == PICKER && pickFor == PICK_CITY) {
                                focus = CITY_ROW;
                                screen = HOME_SETTINGS;
                            } else if (screen == PICKER) {
                                focus = pickFor - 1 - keysScroll;
                                screen = REMOTE_KEYS;
                            } else {
                                focus = screen == HOME_SETTINGS ? HOME_ROW : screen == SHELF_EDIT ? SHELF_ROW : screen == REMOTE_KEYS ? REMOTE_ROW : 0;
                                screen = MAIN;
                            }
                        }
                        backDown = false;
                    }
                    invalidate();
                    spoken.focusMoved();
                    return true;
                default:
                    return false;
            }
        }

        // MARK: - Home Settings and Remote Buttons

        private static final String[] LANGUAGES = {"auto", "pt", "en", "es", "de", "ru", "fr", "tr", "he"}, UNITS = {"auto", "C", "F"}, THEMES = {"auto", "light", "dark"};
        private static final String[] CLOCKS = {"auto", "24h", "12h"}, STYLES = {"photos", "aurora", "google"};
        /** Home Settings: language, temperature, theme, clock, weather, city, quotes, then one row per coin. */
        private static final int CLOCK_ROW = 3, WEATHER_ROW = 4, CITY_ROW = 5, QUOTES_ROW = 6;
        private static final int COIN_ROWS = 7;

        private static String styleName(String style) {
            return style.equals("aurora") ? Ui.tr("Relógio e aurora", "Clock and aurora")
                    : style.equals("google") ? Ui.tr("Fotos do Google", "Google Photos") : Ui.tr("Destaques", "Picks");
        }

        private boolean dollar() {
            return "BRL".equals(Prefs.currency());
        }

        private String[] settingLabels() {
            List<String> l = new ArrayList<>();
            l.add(Ui.tr("Idioma", "Language"));
            l.add(Ui.tr("Temperatura", "Temperature"));
            l.add(Ui.tr("Tema", "Theme"));
            l.add(Ui.tr("Formato do relógio", "Clock format"));
            l.add(Ui.tr("Widget do clima", "Weather widget"));
            l.add(Ui.tr("Cidade", "City"));
            l.add(Ui.tr("Widget de cotações", "Quotes widget"));
            if (Prefs.quotes()) {     // the coins only matter with the quotes widget on
                l.add("Bitcoin");
                l.add("Ethereum");
                l.add("Solana");
                if (dollar()) l.add(Ui.tr("Dólar", "Dollar"));
            }
            return l.toArray(new String[0]);
        }

        private String[] settingValues() {
            String[] labels = settingLabels();
            String[] v = new String[labels.length];
            String lang = Prefs.language();
            v[0] = lang.equals("pt") ? "Português" : lang.equals("en") ? "English" : lang.equals("es") ? "Español"
                    : lang.equals("de") ? "Deutsch" : lang.equals("ru") ? "Русский" : lang.equals("fr") ? "Français" : lang.equals("tr") ? "Türkçe" : lang.equals("he") ? "עברית"
                    : Ui.tr("Automático", "Automatic");
            String unit = Prefs.temperature();
            v[1] = unit.equals("C") ? "°C" : unit.equals("F") ? "°F" : Ui.tr("Automática", "Automatic");
            String theme = Prefs.theme();
            v[2] = theme.equals("light") ? Ui.tr("Claro", "Light") : theme.equals("dark") ? Ui.tr("Escuro", "Dark") : Ui.tr("Automático", "Automatic");
            String clock = Prefs.clockFormat();
            v[CLOCK_ROW] = clock.equals("24h") ? Ui.tr("24 horas", "24-hour") : clock.equals("12h") ? Ui.tr("12 horas", "12-hour")
                    : Ui.tr("Automático", "Automatic");
            v[WEATHER_ROW] = onOff(Prefs.weather());
            v[CITY_ROW] = !cityStatus.isEmpty() ? cityStatus : Prefs.city().isEmpty() ? "—" : Prefs.city();
            v[QUOTES_ROW] = onOff(Prefs.quotes());
            // No quotes widget, nowhere to show prices: the coins read as off (and stay locked) until it is back on.
            for (int i = COIN_ROWS; i < v.length; i++) v[i] = onOff(Prefs.quotes() && Prefs.coin(i - COIN_ROWS));
            return v;
        }

        private static String onOff(boolean on) {
            return on ? Ui.tr("Ligado", "On") : Ui.tr("Desligado", "Off");
        }

        /** What an action reads as in the list: an app's name, an input's, or one of the special ones. */
        private String describe(String action) {
            int at = appPkgs.indexOf(action);
            if (at >= 0) return appLabels.get(at);
            return action.startsWith("input:") ? "HDMI" : Ui.tr("Não instalado", "Not installed");
        }

        /** Everything a remote button can do, after "the TV's own": special actions, the HDMI inputs, then the apps. */
        private void loadApps() {
            Context c = getContext();
            appPkgs.clear();
            appLabels.clear();
            String[][] special = {
                {"", Ui.tr("Padrão da TV", "The TV's own")},
                {"home", Ui.tr("Início", "Home screen")},
                {"tmdb:explore", Ui.tr("Explorar filmes e séries", "Explore movies and shows")},
                {"tmdb:watchlist", Ui.tr("Minha lista (TMDB)", "My watchlist (TMDB)")},
                {"live", Ui.tr("TV ao vivo", "Live TV")},
                {"settings", Ui.tr("Configurações da TV", "TV settings")},
            };
            for (String[] sp : special) {
                appPkgs.add(sp[0]);
                appLabels.add(sp[1]);
            }
            for (HomeApps.Item in : HomeApps.allInputs(c)) {
                if (in.inputId == null) continue;
                appPkgs.add("input:" + in.inputId);
                appLabels.add("HDMI " + in.number + (in.label.equals("HDMI " + in.number) ? "" : " · " + in.label));
            }
            List<String[]> found = new ArrayList<>();
            for (String[] app : HomeApps.launchable(c)) {
                if (!app[0].equals(c.getPackageName())) found.add(app);
            }
            Collections.sort(found, (a, b) -> a[1].toLowerCase(Locale.ROOT).compareTo(b[1].toLowerCase(Locale.ROOT)));
            for (String[] f : found) {
                appPkgs.add(f[0]);
                appLabels.add(f[1]);
            }
        }

        /** Changes the focused row's value one step (OK and → go forward, ← goes back). */
        private void adjust(int dir) {
            if (screen == REMOTE_KEYS) {
                RemoteKeys.Button b = RemoteKeys.buttons(getContext()).get(keysScroll + focus);
                int n = appPkgs.size();
                int at = appPkgs.indexOf(RemoteKeys.action(b));
                int next = at < 0 ? (dir > 0 ? 0 : n - 1) : (at + dir + n) % n;
                Prefs.setButton(b.id, appPkgs.get(next));
                return;
            }
            switch (focus) {
                case 0: {
                    int at = java.util.Arrays.asList(LANGUAGES).indexOf(Prefs.language());
                    Prefs.setLanguage(LANGUAGES[(at + dir + LANGUAGES.length) % LANGUAGES.length]);
                    break;
                }
                case 1: {
                    int at = java.util.Arrays.asList(UNITS).indexOf(Prefs.temperature());
                    Prefs.setTemperature(UNITS[(at + dir + UNITS.length) % UNITS.length]);
                    break;
                }
                case 2: {
                    int at = java.util.Arrays.asList(THEMES).indexOf(Prefs.theme());
                    Prefs.setTheme(THEMES[(at + dir + THEMES.length) % THEMES.length]);
                    break;
                }
                case CLOCK_ROW: {
                    int at = java.util.Arrays.asList(CLOCKS).indexOf(Prefs.clockFormat());
                    Prefs.setClockFormat(CLOCKS[(at + dir + CLOCKS.length) % CLOCKS.length]);
                    break;
                }
                case WEATHER_ROW: Prefs.setWeather(!Prefs.weather()); break;
                case CITY_ROW: return;
                case QUOTES_ROW: Prefs.setQuotes(!Prefs.quotes()); break;
                default:
                    if (!Prefs.quotes()) return;
                    Prefs.setCoin(focus - COIN_ROWS, !Prefs.coin(focus - COIN_ROWS));
                    break;
            }
            HomeData.get(getContext()).settingsChanged();
        }

        private void askCity() {
            if (Ui.tr("Procurando…", "Searching…").equals(cityStatus)) return;
            EditText field = new EditText(activity);
            field.setSingleLine(true);
            field.setHint(Ui.tr("Digite o nome da cidade", "Type the city's name"));
            field.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
            AlertDialog dialog = new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(Ui.tr("Cidade", "City"))
                    .setView(field)
                    .setPositiveButton(Ui.tr("Buscar", "Search"), (d, which) -> searchCity(field.getText().toString()))
                    .setNegativeButton(Ui.tr("Cancelar", "Cancel"), null)
                    .create();
            field.setOnEditorActionListener((v, action, event) -> {
                dialog.dismiss();
                searchCity(field.getText().toString());
                return true;
            });
            dialog.show();
        }

        /** The TMDB key (themoviedb.org > Settings > API, free): the picks and Explore need it, the QR sign-in too. */
        private void askTmdbKey() {
            EditText field = new EditText(activity);
            field.setSingleLine(true);
            field.setHint(Ui.tr("Chave da API ou token de leitura", "API key or read access token"));
            field.setImeOptions(EditorInfo.IME_ACTION_DONE);
            AlertDialog dialog = new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(Ui.tr("Chave do TMDB", "TMDB key"))
                    .setMessage(Ui.tr("Grátis em themoviedb.org › Configurações › API.", "Free at themoviedb.org › Settings › API."))
                    .setView(field)
                    .setPositiveButton(Ui.tr("Salvar", "Save"), (d, which) -> saveTmdbKey(field.getText().toString()))
                    .setNegativeButton(Ui.tr("Cancelar", "Cancel"), null)
                    .create();
            field.setOnEditorActionListener((v, action, event) -> {
                dialog.dismiss();
                saveTmdbKey(field.getText().toString());
                return true;
            });
            dialog.show();
        }

        private void saveTmdbKey(String key) {
            key = key.trim();
            if (!TvConfig.validTmdbKey(key)) {
                Toast.makeText(activity, Ui.tr("Essa chave não parece do TMDB.", "That doesn't look like a TMDB key."), Toast.LENGTH_LONG).show();
                return;
            }
            Prefs.setTmdbKey(key);
            hasKey = true;
            HomeData.get(getContext()).settingsChanged();
            invalidate();
        }

        private void searchCity(String name) {
            if (name.trim().isEmpty()) return;
            cityStatus = Ui.tr("Procurando…", "Searching…");
            invalidate();
            new Thread(() -> {
                List<String[]> found;
                String error;
                try {
                    found = HomeData.findCity(name);
                    error = found.isEmpty() ? Ui.tr("Nenhuma cidade com esse nome.", "No city with that name.") : "";
                } catch (Exception e) {
                    found = new ArrayList<>();
                    error = Ui.tr("Não deu para buscar agora: ", "Couldn't search now: ") + e.getMessage();
                    AppLog.w("city search: " + e.getMessage());
                }
                List<String[]> result = found;
                String message = error;
                handler.post(() -> {
                    cityStatus = message;
                    if (!result.isEmpty() && screen == HOME_SETTINGS) {
                        cities.clear();
                        cities.addAll(result);
                        openPicker(PICK_CITY);
                        spoken.focusMoved();
                    }
                    invalidate();
                    if (!message.isEmpty()) handler.postDelayed(() -> { cityStatus = ""; invalidate(); }, 4000);
                });
            }).start();
        }

        private boolean scrolling() {
            return screen == PICKER || screen == SHELF_EDIT;
        }

        private int listSize() {
            return screen == SHELF_EDIT ? shelfRows.size() : pickLabels.size();
        }

        // MARK: - Shelf editor

        private void openShelfEditor() {
            shelfOrder.clear();
            shelfOrder.addAll(HomeApps.shelfPackages(getContext()));
            rebuildShelfRows(HomeApps.shelfCandidates(getContext()));
            pickScroll = 0;
            focus = 0;
            screen = SHELF_EDIT;
        }

        /** Selected apps first, in shelf order, then the rest in the order they came. */
        private void rebuildShelfRows(List<String[]> candidates) {
            shelfRows.clear();
            for (String pkg : shelfOrder) {
                for (String[] cand : candidates) if (cand[0].equals(pkg)) { shelfRows.add(cand); break; }
            }
            for (String[] cand : candidates) if (!shelfOrder.contains(cand[0])) shelfRows.add(cand);
        }

        /** Same rows after a change: keeps the focus on the app that was focused. */
        private void refreshShelfRows(String focusedPkg) {
            List<String[]> all = new ArrayList<>(shelfRows);
            rebuildShelfRows(all);
            for (int i = 0; i < shelfRows.size(); i++) {
                if (!shelfRows.get(i)[0].equals(focusedPkg)) continue;
                int visible = rowCount();
                if (i < pickScroll) pickScroll = i;
                else if (i >= pickScroll + visible) pickScroll = i - visible + 1;
                focus = i - pickScroll;
                break;
            }
            Prefs.setShelf(shelfOrder);
        }

        /** OK: puts the app on the shelf (at the end) or takes it off. */
        private void toggleShelfApp() {
            String pkg = shelfRows.get(pickScroll + focus)[0];
            if (shelfOrder.contains(pkg)) shelfOrder.remove(pkg);
            else if (shelfOrder.size() < HomeApps.MAX_SHELF) shelfOrder.add(pkg);
            else return;
            refreshShelfRows(pkg);
        }

        /** ← → : moves a shelf app one place earlier or later. */
        private void moveShelfApp(int dir) {
            String pkg = shelfRows.get(pickScroll + focus)[0];
            int at = shelfOrder.indexOf(pkg), to = at + dir;
            if (at < 0 || to < 0 || to >= shelfOrder.size()) return;
            shelfOrder.remove(at);
            shelfOrder.add(to, pkg);
            refreshShelfRows(pkg);
        }

        /** Menu on an app in the shelf editor: its logo (a PNG's URL, or empty for the app's icon) and background color. */
        private void customizeTile(String[] app) {
            CustomTiles.edit(activity, app[0], app[1], null);
        }

        // MARK: - Inner screens, in the main list's style: a heading, the rows on one card, a hint under it

        private void subHeading(Canvas c, String text) {
            listText(c, text, 104 * k, Ui.text(Ui.displaySemibold, 40 * k, 0xFFFFFFFF));
        }

        /** The card behind `rows` rows; returns where the hint under it goes. */
        private float subCard(Canvas c, int rows) {
            Paint card = new Paint(Paint.ANTI_ALIAS_FLAG);
            card.setColor(0x14FFFFFF);
            float bottom = (SUB_TOP + Math.max(1, rows) * (LIST_ROW_H + LIST_ROW_GAP) - LIST_ROW_GAP + 6) * k;
            tmp.set(listLeft(), (SUB_TOP - 6) * k, listRight(), bottom);
            c.drawRoundRect(tmp, 20 * k, 20 * k, card);
            return bottom + 46 * k;
        }

        /** Row `i` of an inner screen: placed, inset like the main list's, focused; returns how focused it is. */
        private float subRow(Canvas c, int i) {
            listRow((SUB_TOP + i * (LIST_ROW_H + LIST_ROW_GAP)) * k, LIST_ROW_H * k);
            rowRect.inset(6 * k, 0);
            return drawRowBackground(c, i);
        }

        private Paint hintPaint() {
            return Ui.text(Ui.regular, 23 * k, 0x8CFFFFFF);
        }

        private void drawShelfEditor(Canvas c) {
            subHeading(c, Ui.tr("Prateleira", "Shelf"));
            int rows = rowCount();
            float hy = subCard(c, rows);
            for (int i = 0; i < rows; i++) {
                String[] app = shelfRows.get(pickScroll + i);
                int place = shelfOrder.indexOf(app[0]);
                float f = subRow(c, i);
                rowLabel.setColor(blend(place >= 0 ? 0xF2FFFFFF : 0x80FFFFFF, place >= 0 ? INK : 0x801C1C1E, f));
                float base = rowRect.centerY() + Ui.halfCap(rowLabel);
                atStart(c, TextUtils.ellipsize(app[1], new TextPaint(rowLabel), 520 * k, TextUtils.TruncateAt.END).toString(), base, rowLabel);
                // On the shelf: its place, in a pill; off it: a dim "Off".
                if (place >= 0) drawPill(c, String.valueOf(place + 1), f, 0);
                else {
                    rowValue.setColor(blend(0x59FFFFFF, 0x591C1C1E, f));
                    atEnd(c, Ui.tr("Fora", "Off"), base, rowValue);
                }
            }
            listText(c, Ui.tr("OK põe ou tira (até " + HomeApps.MAX_SHELF + "). ← → mudam a ordem. Menu (≡) ou segurar OK na Home: logo e cor.",
                    "OK adds or removes (up to " + HomeApps.MAX_SHELF + "). ← → change the order.",
                    "OK pone o quita del estante (hasta " + HomeApps.MAX_SHELF + "). ← → cambian el orden."), hy, hintPaint());
        }

        private void openPicker(int which) {
            pickFor = which;
            pickLabels.clear();
            if (which == PICK_CITY) {
                for (String[] city : cities) pickLabels.add(city[0]);
                pickCurrent = -1;
            } else {
                pickLabels.addAll(appLabels);
                pickCurrent = appPkgs.indexOf(RemoteKeys.action(RemoteKeys.buttons(getContext()).get(which - 1)));
            }
            int visible = Math.min(PICK_VISIBLE, pickLabels.size());
            int at = Math.max(0, pickCurrent);
            pickScroll = Math.max(0, Math.min(at - 3, pickLabels.size() - visible));
            focus = at - pickScroll;
            screen = PICKER;
        }

        /** A long list (the apps, the cities): twelve rows at a time, the one in use checked. */
        private void drawPicker(Canvas c) {
            subHeading(c, pickFor == PICK_CITY ? Ui.tr("Cidade", "City") : RemoteKeys.buttons(getContext()).get(pickFor - 1).name);
            int rows = rowCount();
            float hy = subCard(c, rows);
            for (int i = 0; i < rows; i++) {
                int at = pickScroll + i;
                float f = subRow(c, i);
                rowLabel.setColor(blend(0xF2FFFFFF, INK, f));
                atStart(c, pickLabels.get(at), rowRect.centerY() + Ui.halfCap(rowLabel), rowLabel);
                if (at == pickCurrent) endIcon(c, "check", 26 * k, blend(0xFF30D158, 0xFF1F8F3C, f));
            }
            listText(c, (pickScroll + focus + 1) + " / " + pickLabels.size() + "  ·  " + Ui.tr("OK escolhe, Voltar cancela.", "OK picks, Back cancels."),
                    hy, hintPaint());
        }

        private void drawList(Canvas c) {
            if (screen == REMOTE_KEYS) {
                drawKeys(c);
                return;
            }
            String[] labels = settingLabels();
            String[] values = settingValues();
            subHeading(c, Ui.tr("Ajustes do Início", "Home Settings"));
            float hy = subCard(c, labels.length);
            for (int i = 0; i < labels.length; i++) {
                float f = subRow(c, i);
                boolean locked = i >= COIN_ROWS && !Prefs.quotes();
                rowLabel.setColor(locked ? blend(0x73FFFFFF, 0x731C1C1E, f) : blend(0xF2FFFFFF, INK, f));
                atStart(c, labels[i], rowRect.centerY() + Ui.halfCap(rowLabel), rowLabel);
                // On/off settings are switches; the others are a value in a pill (‹ › while focused: the arrows change it).
                if (i == WEATHER_ROW) drawSwitch(c, Prefs.weather(), f);
                else if (i == QUOTES_ROW) drawSwitch(c, Prefs.quotes(), f);
                else if (i >= COIN_ROWS) drawSwitch(c, Prefs.quotes() && Prefs.coin(i - COIN_ROWS), f);
                else if (i == CITY_ROW) drawPill(c, values[i], f, 0);
                else drawPill(c, f > 0.5f ? "‹  " + values[i] + "  ›" : values[i], f, 0);
            }
            listText(c, Ui.tr("OK ou as setas mudam o valor.", "OK or the arrows change the value."), hy, hintPaint());
        }

        /** The remote's configurable buttons, twelve at a time, each with what it does now. */
        private void drawKeys(Canvas c) {
            List<RemoteKeys.Button> keys = RemoteKeys.buttons(getContext());
            String remote = RemoteKeys.remoteName(getContext());
            subHeading(c, Ui.tr("Botões do Controle", "Remote Buttons") + (remote.isEmpty() ? "" : "  ·  " + remote));
            int rows = rowCount();
            float hy = subCard(c, rows);
            for (int i = 0; i < rows; i++) {
                RemoteKeys.Button b = keys.get(keysScroll + i);
                float f = subRow(c, i);
                rowLabel.setColor(blend(0xF2FFFFFF, INK, f));
                atStart(c, b.name, rowRect.centerY() + Ui.halfCap(rowLabel), rowLabel);
                drawPill(c, TextUtils.ellipsize(describe(RemoteKeys.action(b)), new TextPaint(rowValue), 340 * k,
                        TextUtils.TruncateAt.END).toString(), f, 0);
            }
            String line = keys.isEmpty()
                    ? Ui.tr("Nenhum botão configurável: envie o remote.json do seu modelo (launcher-ui/config.sh --remote).",
                            "No configurable buttons: push your model's remote.json (launcher-ui/config.sh --remote).")
                    : (keysScroll + focus + 1) + " / " + keys.size() + "  ·  "
                            + Ui.tr("OK escolhe o que o botão faz. Os demais funcionam como sempre.",
                            "OK picks what the button does. The rest work as always.");
            listText(c, line, hy, hintPaint());
        }

        /** The three switches on the main list. */
        private void toggleMain(int row) {
            if (row == WATCH_ROW) Prefs.setContinueWatching(!Prefs.continueWatching());
            else if (row == YOUTUBE_ROW_M) Prefs.setYoutubePicks(!Prefs.youtubePicks());
            else if (row == ADS_ROW_M) setAds(!adsOn());
            HomeData.get(getContext()).settingsChanged();
        }

        private void activate() {
            Context c = getContext();
            if (screen == PICKER && pickFor == PICK_CITY) {
                String[] city = cities.get(pickScroll + focus);
                Prefs.setCity(city[1], city[2], city[3]);
                HomeData.get(c).settingsChanged();
                focus = CITY_ROW;
                screen = HOME_SETTINGS;
            } else if (screen == PICKER) {
                int at = pickScroll + focus;
                Prefs.setButton(RemoteKeys.buttons(c).get(pickFor - 1).id, appPkgs.get(at));
                focus = pickFor - 1 - keysScroll;
                screen = REMOTE_KEYS;
            } else if (screen == SHELF_EDIT) {
                toggleShelfApp();
                invalidate();
                return;
            } else if (screen == REMOTE_KEYS) {
                openPicker(keysScroll + focus + 1);
            } else if (screen == HOME_SETTINGS && focus == CITY_ROW) {
                askCity();
                return;
            } else if (screen == HOME_SETTINGS) {
                adjust(1);
                invalidate();
                return;
            } else if (focus == WATCH_ROW || focus == YOUTUBE_ROW_M || focus == ADS_ROW_M) {
                toggleMain(focus);
                invalidate();
                spoken.focusMoved();
                return;
            } else if (focus == DELAY_ROW) {
                stepSaver(1);
                invalidate();
                spoken.focusMoved();
                return;
            } else if (focus == STYLE_ROW && Prefs.screensaverStyle().equals("google")) {
                // Google's screensaver: OK opens its own settings, where the Google Photos album is picked.
                try {
                    getContext().startActivity(new android.content.Intent("dreamx.two.panel.SETTINGS")
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (RuntimeException ex) {
                    AppLog.w("Google screensaver settings: " + ex.getMessage());
                }
                return;
            } else if (focus == STYLE_ROW) {
                pause();
                activity.showPreview();
            } else if (focus == HOME_ROW) {
                screen = HOME_SETTINGS;
                focus = 0;
            } else if (focus == SHELF_ROW) {
                openShelfEditor();
            } else if (focus == REMOTE_ROW) {
                loadApps();
                screen = REMOTE_KEYS;
                focus = 0;
                keysScroll = 0;
            } else if (focus == SERVICES_ROW) {
                activity.startActivity(new Intent(c, ServicesActivity.class));
            } else if (focus == TMDB_ROW) {
                // A release APK comes without a key: it is typed here once (or pushed in config.json "tmdb_key").
                if (Tmdb.hasKey()) activity.startActivity(new Intent(c, TmdbLoginActivity.class)); else askTmdbKey();
            } else {
                activity.startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
            for (Spring s : pop) s.snap(0);
            invalidate();
            spoken.focusMoved();
        }
    }
}
