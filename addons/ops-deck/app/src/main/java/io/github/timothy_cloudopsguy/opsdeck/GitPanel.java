package io.github.timothy_cloudopsguy.opsdeck;

import android.graphics.Canvas;
import android.view.KeyEvent;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/** The contribution chart, with a cursor to walk day by day, the account's repositories and a watched repo's commits. */
final class GitPanel extends Panel {
    private static final int[] LEVELS = {0xFF16243A, 0xFF0E5A4E, 0xFF12897A, 0xFF1AB9A0, Ui.CYAN};
    private static final DateTimeFormatter DAY_NAME = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.US);
    private static final float CELL = 29f;
    private static final float CELL_GAP = 1f;
    private static final float GRID_T = DeckView.Y0 + 118;
    private static final float GRID_L = DeckView.X0 + 8;

    private int cursor = -1;

    GitPanel(Deck deck) {
        super(deck);
    }

    @Override String railName() { return "GIT"; }
    @Override String title() { return "GIT TELEMETRY"; }
    @Override int accent() { return Ui.BLUE; }

    @Override
    boolean key(int key) {
        Parsers.Calendar cal = deck.store.calendar;
        if (cal == null || cal.days.isEmpty()) return false;
        int step;
        if (key == KeyEvent.KEYCODE_DPAD_UP) step = -1;
        else if (key == KeyEvent.KEYCODE_DPAD_DOWN) step = 1;
        else if (key == KeyEvent.KEYCODE_DPAD_LEFT) step = -7;
        else if (key == KeyEvent.KEYCODE_DPAD_RIGHT) step = 7;
        else return false;
        int at = cursor < 0 ? cal.days.size() - 1 : cursor;
        int next = at + step;
        // Left off the oldest week goes back to the rail; the other edges just stop.
        if (next < 0 || next >= cal.days.size()) return key != KeyEvent.KEYCODE_DPAD_LEFT;
        cursor = next;
        deck.sfx.tick();
        return true;
    }

    @Override
    void draw(Canvas c, long now) {
        Parsers.Calendar cal = deck.store.calendar;
        Ui.bold(c, "@" + deck.store.githubUser, DeckView.X0, DeckView.Y0 + 34, 34, Ui.BLUE);
        if (cal == null || cal.days.isEmpty()) {
            Ui.mono(c, "Fetching the chart...", DeckView.X0, DeckView.Y0 + 90, 26, Ui.FAINT);
        } else {
            drawStats(c, cal);
            drawGrid(c, cal, now);
            drawCursorLine(c, cal);
        }
        drawRepos(c, now);
        drawWeeks(c);
    }

    private void drawStats(Canvas c, Parsers.Calendar cal) {
        float y = DeckView.Y0 + 34;
        String stats = Ui.number(cal.total) + " contributions   " + cal.activeDays + " active days   longest streak "
                + cal.longestStreak + "   now " + cal.currentStreak;
        Ui.right(c, stats, DeckView.X1, y, 24, Ui.DIM);
    }

    private void drawGrid(Canvas c, Parsers.Calendar cal, long now) {
        List<Parsers.Day> days = cal.days;
        LocalDate first = days.get(0).date;
        int lead = first.getDayOfWeek() == DayOfWeek.SUNDAY ? 0 : first.getDayOfWeek().getValue();
        // An empty chart still scans: a soft band sweeps across, so the screen reads as live.
        float sweep = cal.total == 0 ? ((now % 6000) / 6000f) * 55 - 1 : -100;
        for (int i = 0; i < days.size(); i++) {
            int slot = i + lead;
            int col = slot / 7;
            int row = slot % 7;
            float x = GRID_L + col * (CELL + CELL_GAP);
            float y = GRID_T - 58 + row * (CELL + CELL_GAP);
            int color = LEVELS[Math.max(0, Math.min(4, days.get(i).level))];
            if (Math.abs(col - sweep) < 1.5f) color = LEVELS[1];
            Ui.R.set(x, y, x + CELL - 3, y + CELL - 3);
            Ui.FILL.setColor(color);
            c.drawRoundRect(Ui.R, 5, 5, Ui.FILL);
        }
        int at = cursor < 0 ? days.size() - 1 : cursor;
        if (focused) {
            int slot = at + lead;
            float x = GRID_L + (slot / 7) * (CELL + CELL_GAP);
            float y = GRID_T - 58 + (slot % 7) * (CELL + CELL_GAP);
            Ui.focus(c, x, y, x + CELL - 3, y + CELL - 3, Ui.TEXT, now);
        }
    }

    private void drawCursorLine(Canvas c, Parsers.Calendar cal) {
        float y = GRID_T + 190;
        int at = cursor < 0 ? cal.days.size() - 1 : cursor;
        Parsers.Day day = cal.days.get(at);
        String line = day.date.format(DAY_NAME) + ":  " + day.count + (day.count == 1 ? " contribution" : " contributions");
        Ui.bold(c, line, DeckView.X0, y, 27, day.count > 0 ? Ui.CYAN : Ui.DIM);
        if (cal.total == 0) {
            Ui.right(c, "ZERO PUBLIC COMMITS. THE QUIET BEFORE THE DEPLOY.", DeckView.X1, y, 23, Ui.AMBER);
        } else if (cal.busiest != null) {
            Ui.right(c, "Best day " + cal.busiest.date.format(DAY_NAME) + ": " + cal.busiest.count, DeckView.X1, y, 23, Ui.DIM);
        }
    }

    private void drawRepos(Canvas c, long now) {
        float t = GRID_T + 216;
        float r = DeckView.X0 + 720;
        Ui.card(c, DeckView.X0, t, r, DeckView.Y1, Ui.PANEL);
        Ui.heading(c, "PUBLIC REPOSITORIES", DeckView.X0 + 22, t + 42);
        List<Parsers.Repo> repos = deck.store.repos;
        if (repos == null) return;
        if (repos.isEmpty()) {
            Ui.mono(c, "None yet.", DeckView.X0 + 22, t + 90, 24, Ui.FAINT);
            return;
        }
        for (int i = 0; i < Math.min(8, repos.size()); i++) {
            Parsers.Repo repo = repos.get(i);
            float y = t + 90 + i * 52;
            Ui.line(c, repo.name + (repo.fork ? " (fork)" : ""), DeckView.X0 + 22, y, 380, 25, Ui.TEXT, Ui.MONO);
            Ui.mono(c, repo.language, DeckView.X0 + 410, y, 21, Ui.BLUE);
            Ui.right(c, Parsers.ago(repo.pushed, now) + " ago", r - 22, y, 21, Ui.FAINT);
        }
    }

    private void drawWeeks(Canvas c) {
        float t = GRID_T + 216;
        float l = DeckView.X0 + 740;
        float r = DeckView.X1;
        float b = DeckView.Y1;
        Ui.card(c, l, t, r, b, Ui.PANEL);
        Ui.heading(c, "WATCHING " + deck.store.watchRepo.toUpperCase(Locale.ROOT), l + 22, t + 42);
        int[] weeks = deck.store.weeks;
        if (weeks == null || weeks.length == 0) {
            Ui.mono(c, "GitHub is still counting...", l + 22, t + 90, 24, Ui.FAINT);
            return;
        }
        int max = 1;
        int total = 0;
        for (int w : weeks) {
            max = Math.max(max, w);
            total += w;
        }
        Ui.mono(c, Ui.number(total) + " commits in " + weeks.length + " weeks, peak " + Ui.number(max) + " a week",
                l + 22, t + 78, 22, Ui.DIM);
        float base = b - 24;
        float height = base - t - 104;
        float barW = (r - l - 44) / weeks.length;
        Ui.FILL.setColor(Ui.BLUE);
        for (int i = 0; i < weeks.length; i++) {
            float h = Math.max(2, height * weeks[i] / max);
            float x = l + 22 + i * barW;
            c.drawRect(x + 1, base - h, x + barW - 1, base, Ui.FILL);
        }
    }
}
