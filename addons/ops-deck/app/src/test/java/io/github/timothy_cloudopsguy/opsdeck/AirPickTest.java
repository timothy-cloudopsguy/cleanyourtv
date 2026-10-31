package io.github.timothy_cloudopsguy.opsdeck;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

public class AirPickTest {
    private static final long NOW = 1_800_000_000_000L;
    private static final double WORLD = 360 * 256;
    private static final int[][] ARROWS = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};

    private static AirData.PickView view(boolean shapesOnly, float[]... covered) {
        return new AirData.PickView(0.5, 0.5, WORLD, 0, 0, 1000, 1000, shapesOnly, covered);
    }

    /** An aircraft at a screen position in view(): 500, 500 is the middle. */
    private static AirData.Plane at(String hex, float x, float y, String squawk, boolean military, boolean ground) {
        double lon = AirData.lon(0.5 + (x - 500) / WORLD);
        double lat = AirData.lat(0.5 + (y - 500) / WORLD);
        return new AirData.Plane(hex, "", "", "", ground, 30000, 0, 0, 0, squawk, military, lat, lon, NOW, "");
    }

    private static AirData.Plane at(String hex, float x, float y) {
        return at(hex, x, y, "", false, false);
    }

    @Test
    public void onTheLineBeatsNearerButOffToTheSide() {
        AirData.Plane from = at("from", 500, 500);
        AirData.Plane line = at("line", 700, 500);
        AirData.Plane side = at("side", 600, 600);
        assertEquals("line", AirData.hop(Arrays.asList(from, side, line), from, 1, 0, view(false), NOW).hex);
    }

    @Test
    public void nothingThatWayIsNoHop() {
        AirData.Plane from = at("from", 500, 500);
        assertNull(AirData.hop(Arrays.asList(from, at("left", 300, 500)), from, 1, 0, view(false), NOW));
    }

    @Test
    public void aTieGoesToTheLowerHex() {
        AirData.Plane from = at("from", 500, 500);
        assertEquals("a", AirData.hop(Arrays.asList(from, at("b", 600, 460), at("a", 600, 540)), from, 1, 0, view(false), NOW).hex);
    }

    @Test
    public void stackedAircraftTakeTurnsBothWays() {
        AirData.Plane a = at("a", 500, 500);
        AirData.Plane b = at("b", 501, 500);
        AirData.Plane c = at("c", 500, 501);
        List<AirData.Plane> planes = Arrays.asList(a, b, c);
        assertEquals("b", AirData.hop(planes, a, 1, 0, view(false), NOW).hex);
        assertEquals("c", AirData.hop(planes, b, 1, 0, view(false), NOW).hex);
        assertEquals("a", AirData.hop(planes, c, 1, 0, view(false), NOW).hex);
        assertEquals("c", AirData.hop(planes, a, -1, 0, view(false), NOW).hex);
    }

    @Test
    public void groundOffScreenCoveredAndDotsAreNotEligible() {
        assertFalse(view(false).eligible(at("g", 600, 500, "", false, true), NOW));
        assertFalse(view(false).eligible(at("off", 1200, 500), NOW));
        assertFalse(view(false).eligible(at("edge", 990, 500), NOW));
        assertFalse(view(false, new float[]{550, 450, 650, 550}).eligible(at("hud", 600, 500), NOW));
        assertTrue(view(false).eligible(at("ok", 600, 500), NOW));
        assertFalse(view(true).eligible(at("dot", 600, 500), NOW));
        assertTrue(view(true).eligible(at("mil", 600, 500, "", true, false), NOW));
        assertTrue(view(true).eligible(at("sos", 600, 500, "7700", false, false), NOW));
    }

    @Test
    public void militaryWinsANearTieAndTheStartLeansTheSameWay() {
        AirData.Plane from = at("from", 500, 500);
        AirData.Plane civil = at("civil", 600, 500);
        AirData.Plane military = at("mil", 650, 500, "", true, false);
        assertEquals("mil", AirData.hop(Arrays.asList(from, civil, military), from, 1, 0, view(false), NOW).hex);
        assertEquals("mil", AirData.pickStart(Arrays.asList(at("civil", 600, 500), at("mil", 650, 500, "", true, false)), view(false), NOW).hex);
        assertNull(AirData.pickStart(Collections.singletonList(at("dot", 600, 500)), view(true), NOW));
    }

    @Test
    public void hoppingWorksAcrossTheDateLine() {
        AirData.PickView view = new AirData.PickView(0.0005, 0.5, WORLD, 0, 0, 1000, 1000, false, new float[0][]);
        AirData.Plane from = new AirData.Plane("from", "", "", "", false, 30000, 0, 0, 0, "", false, 0, AirData.lon(0.0005), NOW, "");
        AirData.Plane west = new AirData.Plane("west", "", "", "", false, 30000, 0, 0, 0, "", false, 0, AirData.lon(0.9995), NOW, "");
        assertTrue(view.eligible(west, NOW));
        assertEquals("west", AirData.hop(Arrays.asList(from, west), from, -1, 0, view, NOW).hex);
    }

    @Test
    public void priorityGoesThroughEmergenciesThenMilitaryAndRoundAgain() {
        List<AirData.Plane> planes = Arrays.asList(at("n", 1, 1), at("m", 1, 1, "", true, false),
                at("z", 1, 1, "7600", false, false), at("b", 1, 1, "7700", false, false));
        assertEquals("b", AirData.nextPriority(planes, "").hex);
        assertEquals("z", AirData.nextPriority(planes, "b").hex);
        assertEquals("m", AirData.nextPriority(planes, "z").hex);
        assertEquals("b", AirData.nextPriority(planes, "m").hex);
        assertEquals("b", AirData.nextPriority(planes, "n").hex);
        assertNull(AirData.nextPriority(planes.subList(0, 1), ""));
    }

    /** Busy screens with airport clusters. A few stay out of reach of a greedy rule, whatever its weights: zooming in spreads them out. */
    @Test
    public void almostEveryAircraftOnABusyScreenCanBeReached() {
        int total = 0;
        int missed = 0;
        for (int seed = 0; seed < 40; seed++) {
            List<AirData.Plane> planes = busyScreen(new Random(seed));
            AirData.PickView view = view(false);
            AirData.Plane start = AirData.pickStart(planes, view, NOW);
            assertNotNull(start);
            Set<String> seen = new HashSet<>(Collections.singleton(start.hex));
            ArrayDeque<AirData.Plane> queue = new ArrayDeque<>(Collections.singleton(start));
            while (!queue.isEmpty()) {
                AirData.Plane from = queue.poll();
                for (int[] arrow : ARROWS) {
                    AirData.Plane next = AirData.hop(planes, from, arrow[0], arrow[1], view, NOW);
                    if (next != null && seen.add(next.hex)) queue.add(next);
                }
            }
            total += planes.size();
            missed += planes.size() - seen.size();
        }
        assertTrue("missed " + missed + " of " + total, missed * 100 < total);
    }

    private static List<AirData.Plane> busyScreen(Random random) {
        List<AirData.Plane> planes = new ArrayList<>();
        for (int i = 0; i < 110; i++) planes.add(at(String.format("%04x", i), 30 + random.nextFloat() * 940, 30 + random.nextFloat() * 940));
        float[][] airports = {{520, 620}, {230, 750}, {700, 300}};
        for (int i = 0; i < 60; i++) {
            float[] airport = airports[i % airports.length];
            planes.add(at(String.format("c%03x", i), airport[0] + (float) random.nextGaussian() * 25, airport[1] + (float) random.nextGaussian() * 25));
        }
        Collections.shuffle(planes, random);
        return planes;
    }
}
