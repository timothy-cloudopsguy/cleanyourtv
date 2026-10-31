package io.github.timothy_cloudopsguy.opsdeck;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BridgePanelTest {
    private static final long NOW = 1_793_473_821_000L;
    private static final float SPAN = 12_000f;

    @Test
    public void theTickerMovesFrameByFrameAtTodaysClock() {
        float before = BridgePanel.tickerTravel(NOW, SPAN);
        float after = BridgePanel.tickerTravel(NOW + 33, SPAN);
        assertEquals(3f, after - before, 0.1f);
    }

    @Test
    public void theTickerStaysInsideItsLoop() {
        for (long t = 0; t < 400_000; t += 1000) {
            float travel = BridgePanel.tickerTravel(NOW + t, SPAN);
            assertTrue(travel >= 0 && travel < SPAN);
        }
    }
}
