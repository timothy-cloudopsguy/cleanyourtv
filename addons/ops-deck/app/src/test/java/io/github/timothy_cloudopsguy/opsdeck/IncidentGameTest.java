package io.github.timothy_cloudopsguy.opsdeck;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Random;

public class IncidentGameTest {
    private final IncidentGame game = new IncidentGame(new Random(1));

    private void set(int state) {
        game.state[game.cursor] = state;
        game.timer[game.cursor] = 100f;
    }

    @Test
    public void fixingACriticalPaysMoreThanAWarning() {
        set(IncidentGame.WARN);
        game.press();
        int warn = game.score;
        set(IncidentGame.CRIT);
        game.press();
        assertTrue(game.score - warn > warn);
        assertEquals(IncidentGame.IDLE, game.state[game.cursor]);
    }

    @Test
    public void aDownServerTakesThreePresses() {
        game.state[0] = IncidentGame.DOWN;
        game.reboots[0] = 3;
        game.press();
        game.press();
        assertEquals(IncidentGame.DOWN, game.state[0]);
        game.press();
        assertEquals(IncidentGame.IDLE, game.state[0]);
    }

    @Test
    public void ignoredAlertsEscalateToDownAndBurnTheBudgetToGameOver() {
        game.state[0] = IncidentGame.WARN;
        game.timer[0] = 0.1f;
        game.update(0.2f);
        assertEquals(IncidentGame.CRIT, game.state[0]);
        game.timer[0] = 0.1f;
        assertEquals(IncidentGame.EVENT_WENT_DOWN, game.update(0.2f));
        int event = IncidentGame.EVENT_NONE;
        for (int i = 0; i < 2000 && event != IncidentGame.EVENT_OVER; i++) event = game.update(0.1f);
        assertEquals(IncidentGame.EVENT_OVER, event);
        assertTrue(game.over);
        assertEquals(IncidentGame.EVENT_NONE, game.press());
    }

    @Test
    public void pressingAGhostCostsBudgetAndCombo() {
        game.combo = 4;
        set(IncidentGame.GHOST);
        assertEquals(IncidentGame.EVENT_MISS, game.press());
        assertEquals(94f, game.budget, 0);
        assertEquals(0, game.combo);
    }

    @Test
    public void aGhostFadesByItself() {
        game.state[0] = IncidentGame.GHOST;
        game.timer[0] = 0.1f;
        game.update(0.2f);
        assertEquals(IncidentGame.IDLE, game.state[0]);
        assertEquals(100f, game.budget, 0);
    }

    @Test
    public void theCursorStaysOnTheRack() {
        assertFalse(game.move(-1, 0));
        assertFalse(game.move(0, -1));
        assertTrue(game.move(1, 1));
        assertEquals(IncidentGame.COLS + 1, game.cursor);
    }
}
