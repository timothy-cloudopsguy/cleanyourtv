package io.github.timothy_cloudopsguy.opsdeck;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class AircraftTest {
    private static final long NOW = 1_800_000_000_000L;
    private static final AirData.Water ALL_WATER = (lat, lon) -> true;

    private static AirData.Plane plane(String hex, int feet, int knots, long at) {
        return new AirData.Plane(hex, "", "", "", false, feet, knots, 90, 0, "7700", false, 40, -40, at, "");
    }

    @Test
    public void typeCodesBeatCategoriesAndUnknownCodesFallBack() {
        assertEquals(Aircraft.NARROW, Aircraft.kind("a320", ""));
        assertEquals(Aircraft.FOUR_JET, Aircraft.kind("K35R", "A5"));
        assertEquals(Aircraft.AIRLIFT, Aircraft.kind("C17", ""));
        assertEquals(Aircraft.HELICOPTER, Aircraft.kind("ZZZZ", "A7"));
        assertEquals(Aircraft.UNKNOWN, Aircraft.kind("", ""));
    }

    @Test
    public void towersAndGroundVehiclesAreNotAircraft() throws Exception {
        assertTrue(Aircraft.notAnAircraft("TWR", ""));
        assertTrue(Aircraft.notAnAircraft("", "C2"));
        assertFalse(Aircraft.notAnAircraft("C17", "A5"));
        assertEquals(1, AirData.parseRegional("{\"ac\":[{\"hex\":\"t\",\"t\":\"TWR\",\"lat\":1,\"lon\":1},"
                + "{\"hex\":\"c\",\"t\":\"C17\",\"lat\":1,\"lon\":1}]}", NOW).size());
    }

    @Test
    public void everyKindHasANameAnOutlineOnTheAxisAndAValidModel() {
        for (int kind = 0; kind < Aircraft.KINDS; kind++) {
            assertFalse(Aircraft.name(kind).isEmpty());
            float[] outline = Aircraft.outline(kind);
            assertEquals(0, outline.length % 2);
            assertEquals(0f, outline[0], 0);
            assertEquals(0f, outline[outline.length - 2], 0);
            assertEquals(0, Aircraft.pods(kind).length % 4);
            Aircraft.Mesh mesh = Aircraft.model(kind);
            assertEquals(0, mesh.faces.length % 4);
            assertTrue("few enough faces to draw each frame", mesh.faces.length / 4 < 200);
            for (int index : mesh.faces) assertTrue(index >= 0 && index * 3 < mesh.vertices.length);
        }
    }

    @Test
    public void landAndOpenWaterAreToldApart() {
        assertTrue("mid Atlantic", WorldMap.isWater(35, -45));
        assertTrue("mid Pacific", WorldMap.isWater(25, -140));
        assertFalse("Kansas", WorldMap.isWater(39, -98));
        assertFalse("central Africa", WorldMap.isWater(5, 20));
        assertTrue("past the grid's edge", WorldMap.isWater(85, 0));
    }

    @Test
    public void aCruisingAircraftThatDropsOutIsFlownOnWithoutItsOldSquawk() {
        AirData.Estimates estimates = new AirData.Estimates(ALL_WATER);
        estimates.update(Arrays.asList(plane("a", 35000, 450, NOW), plane("b", 35000, 450, NOW)), NOW);
        assertTrue("nothing has dropped out yet", estimates.current.isEmpty());
        estimates.update(Collections.singletonList(plane("b", 35000, 450, NOW)), NOW + 60_000);
        List<AirData.Plane> found = estimates.current;
        assertEquals(1, found.size());
        assertEquals("a", found.get(0).hex);
        assertTrue(found.get(0).estimated);
        assertFalse(found.get(0).emergency());
        // Live aircraft stop after three minutes; an estimate carries on.
        assertTrue(found.get(0).xAt(NOW + 3_600_000) > found.get(0).xAt(NOW + 600_000));
    }

    @Test
    public void onlyCruisersAreCarriedForwardAndTheyEndWhenHeardAgainOrTimedOut() {
        AirData.Estimates estimates = new AirData.Estimates(ALL_WATER);
        estimates.update(Arrays.asList(plane("low", 3000, 200, NOW), plane("a", 35000, 450, NOW), plane("c", 35000, 450, NOW)), NOW);
        estimates.update(Collections.singletonList(plane("c", 35000, 450, NOW)), NOW + 60_000);
        assertEquals(1, estimates.current.size());
        assertEquals("a", estimates.current.get(0).hex);
        estimates.update(Arrays.asList(plane("a", 35000, 450, NOW), plane("c", 35000, 450, NOW)), NOW + 120_000);
        assertTrue(estimates.current.isEmpty());
        estimates.update(Collections.singletonList(plane("c", 35000, 450, NOW)), NOW + 180_000);
        assertEquals(1, estimates.current.size());
        estimates.update(Collections.singletonList(plane("c", 35000, 450, NOW)), NOW + 91 * 60_000);
        assertTrue(estimates.current.isEmpty());
    }

    @Test
    public void anEstimateEndsOverLandAndAPartialSnapshotIsIgnored() {
        AirData.Estimates land = new AirData.Estimates((lat, lon) -> false);
        land.update(Arrays.asList(plane("a", 35000, 450, NOW), plane("b", 35000, 450, NOW)), NOW);
        land.update(Collections.singletonList(plane("b", 35000, 450, NOW)), NOW + 60_000);
        assertTrue(land.current.isEmpty());

        AirData.Estimates partial = new AirData.Estimates(ALL_WATER);
        partial.update(Arrays.asList(plane("a", 35000, 450, NOW), plane("b", 35000, 450, NOW), plane("c", 35000, 450, NOW), plane("d", 35000, 450, NOW)), NOW);
        partial.update(Collections.singletonList(plane("c", 35000, 450, NOW)), NOW + 60_000);
        assertTrue(partial.current.isEmpty());
    }

    @Test
    public void mergeKeepsOneOfEachWithMilitaryFirstAndEstimatesOnlyFillingGaps() {
        AirData.Plane live = plane("a", 1000, 100, NOW);
        AirData.Plane military = plane("a", 2000, 100, NOW);
        AirData.Plane estimate = plane("a", 3000, 100, NOW).asEstimate();
        AirData.Plane other = plane("z", 3000, 100, NOW).asEstimate();
        List<AirData.Plane> merged = AirData.merge(Collections.singletonList(live), Collections.singletonList(military),
                Arrays.asList(estimate, other));
        assertEquals(2, merged.size());
        assertEquals(2000, merged.get(0).altitudeFeet);
        assertEquals("z", merged.get(1).hex);
    }
}
