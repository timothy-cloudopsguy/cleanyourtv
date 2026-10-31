package io.github.timothy_cloudopsguy.opsdeck;

import android.graphics.Canvas;
import android.graphics.Path;
import android.view.KeyEvent;

import java.util.List;
import java.util.Locale;

/** The status board: platforms with a light and a latency trace measured from this TV; select one for its incidents. */
final class StatusPanel extends Panel {
    private static final float LIST_R = 1000f;
    private static final float ROW_H = 180f;
    private final Path trace = new Path();
    private int selected;

    StatusPanel(Deck deck) {
        super(deck);
    }

    @Override String railName() { return "STATUS"; }
    @Override String title() { return "PLATFORM STATUS"; }
    @Override int accent() { return Ui.GREEN; }

    @Override
    boolean key(int key) {
        int last = deck.store.services.size() - 1;
        if (key == KeyEvent.KEYCODE_DPAD_UP && selected > 0) {
            selected--;
        } else if (key == KeyEvent.KEYCODE_DPAD_DOWN && selected < last) {
            selected++;
        } else {
            return key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN;
        }
        deck.sfx.tick();
        return true;
    }

    @Override
    void draw(Canvas c, long now) {
        List<Store.Service> services = deck.store.services;
        for (int i = 0; i < services.size(); i++) {
            drawService(c, services.get(i), DeckView.Y0 + i * ROW_H, i == selected, now);
        }
        drawDetail(c, services.get(selected));
    }

    private void drawService(Canvas c, Store.Service s, float top, boolean on, long now) {
        float bottom = top + ROW_H - 16;
        Ui.card(c, DeckView.X0, top, LIST_R, bottom, on ? Ui.PANEL_HI : Ui.PANEL);
        int color = Ui.statusColor(s.indicator);
        Ui.FILL.setColor(color);
        boolean calm = s.indicator.equals("none") || s.indicator.equals("unknown");
        float pulse = calm ? 0 : (float) (Math.sin(now / 180.0) * 0.5 + 0.5);
        c.drawCircle(DeckView.X0 + 38, top + 44, 15 + pulse * 5, Ui.FILL);
        Ui.bold(c, s.name.toUpperCase(Locale.ROOT), DeckView.X0 + 68, top + 56, 34, Ui.TEXT);
        Ui.line(c, s.description, DeckView.X0 + 26, top + 118, 330, 23, color, Ui.SANS);
        int ms = s.lastLatency();
        Ui.right(c, ms < 0 ? "-- ms" : ms + " ms", LIST_R - 24, top + 56, 28, Ui.AMBER);
        drawTrace(c, s, DeckView.X0 + 370, top + 72, LIST_R - 24, bottom - 18);
        if (on && focused) Ui.focus(c, DeckView.X0, top, LIST_R, bottom, color, now);
    }

    private void drawTrace(Canvas c, Store.Service s, float l, float t, float r, float b) {
        int n;
        int[] copy = new int[Store.Service.HISTORY_SIZE];
        int samples;
        synchronized (s) {
            samples = s.samples;
            n = Math.min(samples, copy.length);
            for (int i = 0; i < n; i++) copy[i] = s.latency[(samples - n + i) % copy.length];
        }
        Ui.FILL.setColor(Ui.LINE);
        c.drawRect(l, b, r, b + 1, Ui.FILL);
        if (n < 2) {
            Ui.mono(c, "collecting...", l, b - 12, 18, Ui.FAINT);
            return;
        }
        int max = 1;
        for (int i = 0; i < n; i++) max = Math.max(max, copy[i]);
        trace.rewind();
        for (int i = 0; i < n; i++) {
            float x = l + (r - l) * i / (Store.Service.HISTORY_SIZE - 1);
            float y = b - (b - t) * copy[i] / max;
            if (i == 0) trace.moveTo(x, y);
            else trace.lineTo(x, y);
        }
        Ui.STROKE.setColor(Ui.CYAN);
        Ui.STROKE.setStrokeWidth(3);
        c.drawPath(trace, Ui.STROKE);
    }

    private void drawDetail(Canvas c, Store.Service s) {
        float l = LIST_R + 22;
        float r = DeckView.X1;
        float width = r - l - 48;
        Ui.card(c, l, DeckView.Y0, r, DeckView.Y1 - 22, Ui.PANEL);
        Ui.heading(c, s.name.toUpperCase(Locale.ROOT) + " INCIDENTS", l + 24, DeckView.Y0 + 46);
        Parsers.Summary summary = s.summary;
        if (summary == null) {
            Ui.mono(c, "Waiting for the summary...", l + 24, DeckView.Y0 + 100, 24, Ui.FAINT);
            return;
        }
        float y = DeckView.Y0 + 96;
        if (summary.incidents.isEmpty()) {
            Ui.bold(c, "NO OPEN INCIDENTS", l + 24, y, 32, Ui.GREEN);
            y += 50;
        }
        for (int i = 0; i < Math.min(3, summary.incidents.size()); i++) {
            Parsers.Incident incident = summary.incidents.get(i);
            y = Ui.para(c, incident.name, l + 24, y, width, 28, Ui.AMBER, Ui.SANS, 2);
            Ui.mono(c, (incident.impact + " / " + incident.status).toUpperCase(Locale.ROOT), l + 24, y, 19, Ui.DIM);
            y = Ui.para(c, incident.update, l + 24, y + 34, width, 22, Ui.TEXT, Ui.SANS, 4) + 12;
        }
        int healthy = summary.components - summary.degraded.size();
        if (summary.components > 0) {
            Ui.mono(c, healthy + " of " + summary.components + " components operational", l + 24, y + 18, 24, Ui.DIM);
            Ui.bar(c, l + 24, y + 36, r - 24, y + 50, healthy / (float) summary.components,
                    summary.degraded.isEmpty() ? Ui.GREEN : Ui.AMBER);
            y += 90;
        }
        for (int i = 0; i < Math.min(8, summary.degraded.size()) && y < DeckView.Y1 - 50; i++) {
            Ui.line(c, "! " + summary.degraded.get(i), l + 24, y, width, 23, Ui.RED, Ui.MONO);
            y += 32;
        }
    }
}
