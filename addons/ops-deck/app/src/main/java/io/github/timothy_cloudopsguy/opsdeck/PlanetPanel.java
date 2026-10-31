package io.github.timothy_cloudopsguy.opsdeck;

import android.graphics.Canvas;
import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** A dot-matrix world with the day's earthquakes and the ISS live on it, plus the geomagnetic index. */
final class PlanetPanel extends Panel {
    private static final float MAP_L = DeckView.X0;
    private static final float MAP_T = DeckView.Y0;
    private static final float MAP_W = 1120f;
    private static final float MAP_H = MAP_W * (WorldMap.SPAN_LAT / 3f) / 120f;
    private static final float SIDE_L = MAP_L + MAP_W + 20;
    private static final int LIST_ROWS = 8;

    private final float[] land;
    private int selected;
    private String qrFor = "";
    private boolean[][] qr;

    PlanetPanel(Deck deck) {
        super(deck);
        land = landPoints();
    }

    @Override String railName() { return "PLANET"; }
    @Override String title() { return "PLANET WATCH"; }
    @Override int accent() { return Ui.AMBER; }

    /** The G scale NOAA uses, by Kp. */
    static String stormName(float kp) {
        if (kp >= 9) return "G5 extreme geomagnetic storm";
        if (kp >= 8) return "G4 severe geomagnetic storm";
        if (kp >= 7) return "G3 strong geomagnetic storm";
        if (kp >= 6) return "G2 moderate geomagnetic storm";
        if (kp >= 5) return "G1 minor storm, aurora possible";
        if (kp >= 4) return "Unsettled, active field";
        return "Quiet geomagnetic field";
    }

    @Override
    boolean key(int key) {
        int last = deck.store.quakes.size() - 1;
        if (key == KeyEvent.KEYCODE_DPAD_UP) {
            if (selected > 0) {
                selected--;
                deck.sfx.tick();
            }
            return true;
        }
        if (key == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (selected < last) {
                selected++;
                deck.sfx.tick();
            }
            return true;
        }
        if (Ui.isOk(key) && selected <= last && last >= 0) {
            deck.sfx.confirm();
            deck.actions.openLink(deck.store.quakes.get(selected).url);
            return true;
        }
        return false;
    }

    @Override
    void draw(Canvas c, long now) {
        Ui.card(c, MAP_L, MAP_T, MAP_L + MAP_W, MAP_T + MAP_H, Ui.PANEL);
        Ui.FILL.setColor(0xFF2C4663);
        Ui.FILL.setStrokeWidth(MAP_W / 120f * 0.62f);
        Ui.FILL.setStrokeCap(android.graphics.Paint.Cap.ROUND);
        c.drawPoints(land, Ui.FILL);
        drawVolcanoes(c);
        List<Parsers.Quake> quakes = deck.store.quakes;
        selected = Math.max(0, Math.min(selected, quakes.size() - 1));
        for (int i = quakes.size() - 1; i >= 0; i--) {
            drawQuake(c, quakes.get(i), i == selected && focused, now);
        }
        drawIss(c, now);
        drawSelectedQuake(c, quakes, now);
        drawIssReadout(c);
        drawKp(c);
        drawQuakeList(c, quakes, now);
    }

    private static float mapX(double lon) {
        return MAP_L + (float) ((lon + 180) / 360) * MAP_W;
    }

    private static float mapY(double lat) {
        return MAP_T + (float) ((WorldMap.TOP_LAT - lat) / WorldMap.SPAN_LAT) * MAP_H;
    }

    private static boolean onMap(double lat) {
        return lat <= WorldMap.TOP_LAT && lat >= WorldMap.TOP_LAT - WorldMap.SPAN_LAT;
    }

    private void drawQuake(Canvas c, Parsers.Quake q, boolean on, long now) {
        if (!onMap(q.lat)) return;
        float x = mapX(q.lon);
        float y = mapY(q.lat);
        float radius = (float) Math.max(5, (q.magnitude - 3.5) * 5);
        Ui.FILL.setColor(on ? Ui.TEXT : q.magnitude >= 6 ? Ui.RED : Ui.AMBER);
        c.drawCircle(x, y, radius, Ui.FILL);
        // A ring leaves each quake on its own beat, so they do not pulse in step.
        float phase = ((now + (long) (q.lon * 40)) % 2400) / 2400f;
        Ui.STROKE.setColor(on ? Ui.TEXT : Ui.AMBER);
        Ui.STROKE.setAlpha((int) (170 * (1 - phase)));
        Ui.STROKE.setStrokeWidth(2);
        c.drawCircle(x, y, radius + phase * 26, Ui.STROKE);
        if (on) {
            Ui.STROKE.setColor(Ui.TEXT);
            c.drawLine(MAP_L, y, MAP_L + MAP_W, y, Ui.STROKE);
            c.drawLine(x, MAP_T, x, MAP_T + MAP_H, Ui.STROKE);
        }
    }

    /** A triangle for each volcano reported active. The Volcano screen has the detail. */
    private void drawVolcanoes(Canvas c) {
        for (VolcanoData.Report r : deck.store.volcanoReports()) {
            if (!r.located() || !onMap(r.lat)) continue;
            Ui.FILL.setColor(r.level.equals("ONGOING") ? 0xFFB5532F : Ui.RED);
            VolcanoPanel.drawTriangle(c, mapX(r.lon), mapY(r.lat), 8);
        }
    }

    private void drawIss(Canvas c, long now) {
        List<double[]> trail;
        synchronized (deck.store.issTrail) {
            trail = new ArrayList<>(deck.store.issTrail);
        }
        Ui.FILL.setColor(Ui.BLUE);
        for (double[] p : trail) {
            if (onMap(p[0])) c.drawCircle(mapX(p[1]), mapY(p[0]), 3, Ui.FILL);
        }
        Parsers.Iss iss = deck.store.iss;
        if (iss == null || !onMap(iss.lat)) return;
        float x = mapX(iss.lon);
        float y = mapY(iss.lat);
        float pulse = (float) (Math.sin(now / 200.0) * 0.5 + 0.5);
        Ui.STROKE.setColor(Ui.BLUE);
        Ui.STROKE.setStrokeWidth(3);
        c.drawCircle(x, y, 14 + pulse * 6, Ui.STROKE);
        c.drawCircle(x, y, 6, Ui.FILL);
        Ui.bold(c, "ISS", x + 24, y + 8, 24, Ui.BLUE);
    }

    private void drawIssReadout(Canvas c) {
        float top = MAP_T + MAP_H + 14;
        float l = MAP_L + MAP_W / 2 + 8;
        float r = MAP_L + MAP_W;
        Ui.card(c, l, top, r, DeckView.Y1, Ui.PANEL);
        Ui.heading(c, "SPACE STATION", l + 24, top + 44);
        Parsers.Iss iss = deck.store.iss;
        if (iss == null) return;
        Ui.bold(c, String.format(Locale.US, "%.2f, %.2f", iss.lat, iss.lon), l + 24, top + 116, 50, Ui.BLUE);
        Ui.mono(c, String.format(Locale.US, "ALTITUDE  %.0f km", iss.altitudeKm), l + 24, top + 186, 27, Ui.TEXT);
        Ui.mono(c, String.format(Locale.US, "SPEED     %,.0f km/h", iss.speedKmh), l + 24, top + 234, 27, Ui.TEXT);
        Ui.mono(c, "LIGHT     " + iss.visibility, l + 24, top + 282, 27, Ui.TEXT);
    }

    private void drawSelectedQuake(Canvas c, List<Parsers.Quake> quakes, long now) {
        float top = MAP_T + MAP_H + 14;
        float l = MAP_L;
        float r = MAP_L + MAP_W / 2 - 8;
        Ui.card(c, l, top, r, DeckView.Y1, Ui.PANEL);
        Ui.heading(c, "SELECTED QUAKE", l + 24, top + 44);
        if (quakes.isEmpty()) return;
        Parsers.Quake q = quakes.get(selected);
        Ui.bold(c, String.format(Locale.US, "M%.1f", q.magnitude), l + 24, top + 116, 56, q.magnitude >= 6 ? Ui.RED : Ui.AMBER);
        Ui.mono(c, Parsers.ago(q.time, now) + " ago", l + 24, top + 158, 24, Ui.DIM);
        Ui.para(c, q.place, l + 24, top + 206, 300, 27, Ui.TEXT, Ui.SANS, 3);
        if (!q.url.equals(qrFor)) {
            qrFor = q.url;
            qr = q.url.isEmpty() ? null : Qr.encode(q.url);
        }
        if (qr != null) Ui.qr(c, qr, r - 216, top + 72, 192);
    }

    private void drawKp(Canvas c) {
        float r = DeckView.X1;
        float t = DeckView.Y0;
        float b = t + 270;
        Ui.card(c, SIDE_L, t, r, b, Ui.PANEL);
        float[] kp = deck.store.kp;
        Ui.heading(c, "SPACE WEATHER", SIDE_L + 20, t + 40);
        if (kp.length == 0) return;
        float k = kp[kp.length - 1];
        Ui.right(c, String.format(Locale.US, "Kp %.1f", k), r - 20, t + 42, 30, kpColor(k));
        Ui.line(c, stormName(k), SIDE_L + 20, t + 76, r - SIDE_L - 40, 22, Ui.DIM, Ui.SANS);
        float barBase = b - 20;
        float barW = (r - SIDE_L - 40) / kp.length;
        for (int i = 0; i < kp.length; i++) {
            float h = Math.max(3, kp[i] / 9f * 150);
            Ui.FILL.setColor(kpColor(kp[i]));
            float x = SIDE_L + 20 + i * barW;
            c.drawRect(x + 2, barBase - h, x + barW - 2, barBase, Ui.FILL);
        }
    }

    private static int kpColor(float kp) {
        return kp >= 5 ? Ui.RED : kp >= 4 ? Ui.AMBER : Ui.GREEN;
    }

    private void drawQuakeList(Canvas c, List<Parsers.Quake> quakes, long now) {
        float r = DeckView.X1;
        float t = DeckView.Y0 + 288;
        Ui.card(c, SIDE_L, t, r, DeckView.Y1, Ui.PANEL);
        Ui.heading(c, quakes.size() + " QUAKES, 24 H", SIDE_L + 20, t + 40);
        int first = Math.max(0, Math.min(selected - LIST_ROWS / 2, quakes.size() - LIST_ROWS));
        for (int i = 0; i < LIST_ROWS && first + i < quakes.size(); i++) {
            Parsers.Quake q = quakes.get(first + i);
            float top = t + 58 + i * 72;
            boolean on = first + i == selected && focused;
            if (on) Ui.card(c, SIDE_L + 10, top, r - 10, top + 66, Ui.PANEL_HI);
            Ui.bold(c, String.format(Locale.US, "%.1f", q.magnitude), SIDE_L + 22, top + 44, 32,
                    q.magnitude >= 6 ? Ui.RED : Ui.AMBER);
            Ui.line(c, q.place, SIDE_L + 94, top + 30, r - SIDE_L - 124, 22, Ui.TEXT, Ui.SANS);
            Ui.mono(c, Parsers.ago(q.time, now) + " ago", SIDE_L + 94, top + 56, 18, Ui.FAINT);
            if (on) Ui.focus(c, SIDE_L + 10, top, r - 10, top + 66, Ui.AMBER, now);
        }
    }

    private static float[] landPoints() {
        int count = 0;
        for (String row : WorldMap.LAND) {
            for (int i = 0; i < row.length(); i++) if (row.charAt(i) == '#') count++;
        }
        float[] points = new float[count * 2];
        float cellX = MAP_W / WorldMap.COLS;
        float cellY = MAP_H / WorldMap.ROWS;
        int n = 0;
        for (int y = 0; y < WorldMap.ROWS; y++) {
            String row = WorldMap.LAND[y];
            for (int x = 0; x < row.length(); x++) {
                if (row.charAt(x) != '#') continue;
                points[n++] = MAP_L + (x + 0.5f) * cellX;
                points[n++] = MAP_T + (y + 0.5f) * cellY;
            }
        }
        return points;
    }
}
