package io.github.timothy_cloudopsguy.opsdeck;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.KeyEvent;

import java.util.List;
import java.util.Locale;

/**
 * The front page: eight tiles that summarise the other panels and open them, a ticker, and the row of TV apps. Each
 * app plays its own note when focus lands on it.
 */
final class BridgePanel extends Panel {
    private static final int COLS = 4;
    private static final int TILES = 8;
    private static final int APP_ROW = 2;
    private static final int AIR_TILE = 5;
    private static final float TILE_W = 387f;
    private static final float TILE_H = 288f;
    private static final float GAP = 18f;
    private static final float APP_W = 240f;
    private static final float APP_H = 135f;
    private static final float APP_TOP = 806f;
    private static final double TICKER_SPEED = 0.09;

    private final String[] label = {"SERVICES", "EXPLOITED CVES", "ON THIS DAY", "QUAKES M4.5+", "SPACE", "AIR TRAFFIC", "GIT", "HIGH SCORE"};
    private final int[] target = {2, 1, 1, 3, 3, 5, 6, 8};
    private final String[] value = new String[TILES];
    private final String[] sub = new String[TILES];
    private final int[] tone = new int[TILES];
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Rect source = new Rect();
    private final RectF dest = new RectF();

    private int row = APP_ROW;
    private int col;
    private int app;
    private float appScroll;
    private long refreshed;
    private String ticker = "";
    private boolean airAlert;
    private float tickerWidth;

    BridgePanel(Deck deck) {
        super(deck);
        focused = true;
        for (int i = 0; i < TILES; i++) {
            value[i] = "--";
            sub[i] = "waiting for data";
            tone[i] = Ui.TEXT;
        }
    }

    @Override String railName() { return "BRIDGE"; }
    @Override String title() { return "THE BRIDGE"; }
    @Override int accent() { return Ui.CYAN; }

    @Override
    boolean key(int key) {
        List<Deck.App> apps = deck.apps;
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (row == 0) return true;
                row--;
                deck.sfx.tick();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (row == APP_ROW) return true;
                row++;
                if (row == APP_ROW) deck.sfx.app(app);
                else deck.sfx.tick();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (row == APP_ROW) {
                    if (app == 0) return false;
                    app--;
                    deck.sfx.app(app);
                    return true;
                }
                if (col == 0) return false;
                col--;
                deck.sfx.tick();
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (row == APP_ROW) {
                    if (app < apps.size() - 1) {
                        app++;
                        deck.sfx.app(app);
                    }
                    return true;
                }
                if (col < COLS - 1) {
                    col++;
                    deck.sfx.tick();
                }
                return true;
            default:
                if (!Ui.isOk(key)) return false;
                deck.sfx.confirm();
                if (row == APP_ROW) {
                    if (app < apps.size()) deck.actions.launch(apps.get(app).packageName);
                } else {
                    deck.actions.openPanel(target[row * COLS + col]);
                }
                return true;
        }
    }

    @Override
    void draw(Canvas c, long now) {
        if (now - refreshed > 1000) {
            refreshed = now;
            refresh(now);
        }
        for (int i = 0; i < TILES; i++) drawTile(c, i, now);
        drawTicker(c, now);
        drawApps(c, now);
    }

    private void drawTile(Canvas c, int i, long now) {
        float l = DeckView.X0 + (i % COLS) * (TILE_W + GAP);
        float t = DeckView.Y0 + (i / COLS) * (TILE_H + GAP);
        float r = l + TILE_W;
        float b = t + TILE_H;
        Ui.card(c, l, t, r, b, Ui.PANEL);
        if (i == AIR_TILE && airAlert) {
            // A slow pulse: it should be seen from across the room without nagging.
            Ui.STROKE.setColor(Ui.RED);
            Ui.STROKE.setAlpha((int) (120 + 135 * (Math.sin(now / 400.0) * 0.5 + 0.5)));
            Ui.STROKE.setStrokeWidth(4);
            Ui.R.set(l, t, r, b);
            c.drawRoundRect(Ui.R, 10, 10, Ui.STROKE);
        }
        Ui.mono(c, label[i], l + 20, t + 40, 23, Ui.DIM);
        Ui.line(c, value[i], l + 20, t + 122, TILE_W - 40, 64, tone[i], Ui.MONO_BOLD);
        Ui.para(c, sub[i], l + 20, t + 170, TILE_W - 40, 24, Ui.DIM, Ui.SANS, 3);
        if (focused && row * COLS + col == i && row < APP_ROW) Ui.focus(c, l, t, r, b, Ui.CYAN, now);
    }

    private void drawTicker(Canvas c, long now) {
        float top = DeckView.Y0 + 2 * (TILE_H + GAP);
        Ui.card(c, DeckView.X0, top, DeckView.X1, top + 44, Ui.PANEL);
        if (ticker.isEmpty()) return;
        int saved = c.save();
        c.clipRect(DeckView.X0 + 8, top, DeckView.X1 - 8, top + 44);
        float loop = tickerWidth + 200;
        float x = DeckView.X1 - tickerTravel(now, loop + (DeckView.X1 - DeckView.X0));
        Ui.mono(c, ticker, x, top + 31, 23, Ui.AMBER);
        c.restoreToCount(saved);
    }

    /** How far the ticker has moved within its loop. In double: today's time in ms times a speed loses all motion in a float. */
    static float tickerTravel(long now, float span) {
        return (float) ((now * TICKER_SPEED) % span);
    }

    private void drawApps(Canvas c, long now) {
        List<Deck.App> apps = deck.apps;
        if (apps.isEmpty()) {
            Ui.mono(c, "Finding apps...", DeckView.X0, APP_TOP + 60, 26, Ui.FAINT);
            return;
        }
        app = Math.min(app, apps.size() - 1);
        float step = APP_W + GAP;
        float wanted = Math.max(0, app * step - 2 * step);
        appScroll += (wanted - appScroll) * 0.25f;
        int saved = c.save();
        c.clipRect(DeckView.X0 - 12, APP_TOP - 18, DeckView.X1 + 12, DeckView.Y1 + 10);
        for (int i = 0; i < apps.size(); i++) {
            float l = DeckView.X0 + i * step - appScroll;
            if (l > DeckView.X1 || l + APP_W < DeckView.X0 - 12) continue;
            Deck.App a = apps.get(i);
            boolean on = focused && row == APP_ROW && i == app;
            Bitmap banner = a.banner;
            source.set(0, 0, banner.getWidth(), banner.getHeight());
            dest.set(l, APP_TOP, l + APP_W, APP_TOP + APP_H);
            bitmapPaint.setAlpha(on ? 255 : 170);
            c.drawBitmap(banner, source, dest, bitmapPaint);
            if (on) Ui.focus(c, l, APP_TOP, l + APP_W, APP_TOP + APP_H, Ui.CYAN, now);
            Ui.line(c, a.label, l + 2, APP_TOP + APP_H + 34, APP_W - 4, 23, on ? Ui.TEXT : Ui.DIM, Ui.SANS);
        }
        c.restoreToCount(saved);
    }

    private void refresh(long now) {
        Store s = deck.store;
        int total = s.services.size();
        int ok = 0;
        int known = 0;
        String worst = "";
        for (Store.Service v : s.services) {
            if (v.indicator.equals("unknown")) continue;
            known++;
            if (v.indicator.equals("none")) ok++;
            else worst = v.name + ": " + v.description;
        }
        if (known > 0) {
            value[0] = ok + "/" + total + " UP";
            sub[0] = worst.isEmpty() ? "All tracked platforms healthy" : worst;
            tone[0] = ok == total ? Ui.GREEN : Ui.AMBER;
        }
        if (s.kevTotal >= 0) {
            value[1] = Ui.number(s.kevTotal);
            List<Headline> vulns = s.headlines(Store.VULNS);
            sub[1] = vulns.isEmpty() ? "CISA known exploited list" : vulns.get(0).title;
            tone[1] = Ui.RED;
        }
        List<Headline> history = s.headlines(Store.HISTORY);
        if (!history.isEmpty()) {
            Headline h = history.get(0);
            value[2] = h.source.startsWith("On this day ") ? h.source.substring(12) : h.source;
            sub[2] = h.title;
            tone[2] = Ui.MAGENTA;
        }
        List<Parsers.Quake> quakes = s.quakes;
        if (!quakes.isEmpty()) {
            Parsers.Quake top = quakes.get(0);
            for (Parsers.Quake q : quakes) if (q.magnitude > top.magnitude) top = q;
            value[3] = String.valueOf(quakes.size());
            sub[3] = String.format(Locale.US, "Largest M%.1f, %s", top.magnitude, top.place);
            tone[3] = Ui.AMBER;
        }
        float[] kp = s.kp;
        Parsers.Iss iss = s.iss;
        String station = iss == null ? "" : String.format(Locale.US, "ISS at %.0f%s %.0f%s, %.0f km up, %s.",
                Math.abs(iss.lat), iss.lat >= 0 ? "N" : "S", Math.abs(iss.lon), iss.lon >= 0 ? "E" : "W",
                iss.altitudeKm, iss.visibility);
        if (kp.length > 0) {
            float k = kp[kp.length - 1];
            value[4] = String.format(Locale.US, "Kp %.1f", k);
            sub[4] = PlanetPanel.stormName(k) + ". " + station;
            tone[4] = k >= 5 ? Ui.RED : k >= 4 ? Ui.AMBER : Ui.GREEN;
        } else if (iss != null) {
            value[4] = "ISS";
            sub[4] = station;
            tone[4] = Ui.BLUE;
        }
        List<AirData.Plane> squawks = s.emergencies;
        airAlert = !squawks.isEmpty();
        if (s.flightsUp >= 0) value[5] = Ui.number(s.flightsUp);
        if (s.flightsUp >= 0 || airAlert) {
            sub[5] = (s.flightsUp >= 0 ? "flights up. " : "") + AirData.squawkSummary(squawks);
            tone[5] = airAlert ? Ui.RED : Ui.BLUE;
        }
        Parsers.Calendar cal = s.calendar;
        if (cal != null) {
            value[6] = Ui.number(cal.total);
            sub[6] = "public contributions by " + s.githubUser + ", last 12 months";
            tone[6] = cal.total > 0 ? Ui.GREEN : Ui.FAINT;
        }
        value[7] = Ui.number(GamePanel.best(deck));
        sub[7] = "Incident Commander. Can you hold the SLO?";
        tone[7] = Ui.CYAN;

        StringBuilder t = new StringBuilder();
        for (int category = 0; category < Store.CATEGORIES.length; category++) {
            List<Headline> items = s.headlines(category);
            for (int i = 0; i < Math.min(3, items.size()); i++) {
                t.append(Store.CATEGORIES[category]).append(" // ").append(items.get(i).title).append("     ");
            }
        }
        String next = t.toString();
        if (!next.equals(ticker)) {
            ticker = next;
            Ui.TXT.setTypeface(Ui.MONO);
            Ui.TXT.setTextSize(23);
            tickerWidth = Ui.TXT.measureText(ticker);
        }
    }
}
