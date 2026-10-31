package io.github.timothy_cloudopsguy.opsdeck;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class AirDataTest {
    private static final long NOW = 1_800_000_000_000L;
    private static final String REGIONAL = "{\"ac\":["
            + "{\"hex\":\"a1\",\"flight\":\"UAL1  \",\"t\":\"B789\",\"r\":\"N1\",\"lat\":40.0,\"lon\":-74.0,"
            + "\"alt_baro\":35000,\"gs\":450,\"track\":90,\"squawk\":\"7700\",\"seen_pos\":2,\"dbFlags\":1},"
            + "{\"hex\":\"a2\",\"lat\":41.0,\"lon\":-74.0,\"alt_baro\":\"ground\",\"gs\":5},"
            + "{\"hex\":\"a3\",\"flight\":\"NOPOS\"},"
            + "{\"hex\":\"a4\",\"lat\":1,\"lon\":1,\"seen_pos\":120}]}";

    @Test
    public void worldCoordinatesRoundTrip() {
        assertEquals(40.7, AirData.lat(AirData.worldY(40.7)), 1e-9);
        assertEquals(-74.0, AirData.lon(AirData.worldX(-74.0)), 1e-9);
        assertEquals(0.5, AirData.worldX(0), 1e-12);
        assertEquals(0.5, AirData.worldY(0), 1e-12);
    }

    @Test
    public void theShortWayRoundCrossesTheDateLine() {
        assertEquals(0.02, AirData.wrapDelta(0.01 - 0.99), 1e-9);
        assertEquals(-0.02, AirData.wrapDelta(0.99 - 0.01), 1e-9);
        assertEquals(0.25, AirData.wrapX(1.25), 1e-12);
        assertEquals(0.75, AirData.wrapX(-0.25), 1e-12);
    }

    @Test
    public void regionalParserKeepsPositionsDropsStaleAndReadsGround() throws Exception {
        List<AirData.Plane> planes = AirData.parseRegional(REGIONAL, NOW);
        assertEquals(2, planes.size());
        AirData.Plane a = planes.get(0);
        assertEquals("UAL1", a.callsign);
        assertEquals(35000, a.altitudeFeet);
        assertEquals(NOW - 2000, a.timeMs);
        assertTrue(a.emergency());
        assertTrue(a.military);
        assertTrue(planes.get(1).ground);
    }

    @Test
    public void adsbFiListNameWorksToo() throws Exception {
        assertEquals(1, AirData.parseRegional("{\"aircraft\":[{\"hex\":\"a\",\"lat\":1,\"lon\":2}]}", NOW).size());
    }

    @Test
    public void openSkyConvertsUnitsAndSkipsGroundAndNoPosition() throws Exception {
        long seconds = NOW / 1000;
        List<AirData.Plane> planes = AirData.parseOpenSky("{\"states\":["
                + "[\"abc\",\"TVF74 \",\"France\"," + seconds + "," + seconds + ",2.0,48.0,10000.0,false,100.0,90.0,0.0,null,10100.0,\"1000\"],"
                + "[\"gnd\",\"X\",\"F\"," + seconds + "," + seconds + ",2.0,48.0,null,true,0,0,0,null,null,null],"
                + "[\"nop\",\"Y\",\"F\",null," + seconds + ",null,null,null,false,0,0,0,null,null,null]]}", NOW);
        assertEquals(1, planes.size());
        assertEquals("TVF74", planes.get(0).callsign);
        assertEquals(32808, planes.get(0).altitudeFeet);
        assertEquals(194, planes.get(0).speedKnots);
    }

    @Test
    public void aReusedWorldSnapshotKeepsItsAircraftButDropsOnesStaleWhenItWasTaken() throws Exception {
        long taken = NOW / 1000 - 100;
        List<AirData.Plane> planes = AirData.parseOpenSky("{\"time\":" + taken + ",\"states\":["
                + "[\"fresh\",\"A\",\"F\"," + (taken - 5) + "," + taken + ",2.0,48.0,10000.0,false,100.0,90.0,0.0,null,null,null],"
                + "[\"old\",\"B\",\"F\"," + (taken - 120) + "," + taken + ",2.0,48.0,10000.0,false,100.0,90.0,0.0,null,null,null]]}", NOW);
        assertEquals(1, planes.size());
        assertEquals("fresh", planes.get(0).hex);
    }

    @Test
    public void aircraftFlyOnFromTheirLastReportButNotForever() throws Exception {
        AirData.Plane east = AirData.parseRegional(REGIONAL, NOW).get(0);
        assertTrue(east.xAt(NOW + 60_000) > east.xAt(NOW));
        assertEquals(east.yAt(NOW), east.yAt(NOW + 60_000), 1e-6);
        assertEquals(east.xAt(NOW + 400_000), east.xAt(NOW + 900_000), 0);
        // 450 kt for a minute is 7.5 nautical miles; check the scale against real distance.
        double miles = AirData.nauticalMiles(40, AirData.lon(east.xAt(NOW)), 40, AirData.lon(east.xAt(NOW + 60_000)));
        assertEquals(7.5, miles, 0.1);
    }

    @Test
    public void groundAircraftStayPut() throws Exception {
        AirData.Plane ground = AirData.parseRegional(REGIONAL, NOW).get(1);
        assertEquals(ground.xAt(NOW), ground.xAt(NOW + 60_000), 0);
    }

    @Test
    public void nearestSkipsGroundAndTheOneAlreadySelected() throws Exception {
        List<AirData.Plane> planes = AirData.parseRegional(
                "{\"ac\":[{\"hex\":\"far\",\"lat\":10,\"lon\":10,\"alt_baro\":1000},"
                        + "{\"hex\":\"near\",\"lat\":1,\"lon\":1,\"alt_baro\":1000},"
                        + "{\"hex\":\"gnd\",\"lat\":0,\"lon\":0,\"alt_baro\":\"ground\"}]}", NOW);
        double x = AirData.worldX(0);
        double y = AirData.worldY(0);
        assertEquals("near", AirData.nearest(planes, x, y, NOW, "").hex);
        assertEquals("far", AirData.nearest(planes, x, y, NOW, "near").hex);
        assertNull(AirData.nearest(planes.subList(2, 3), x, y, NOW, ""));
    }

    @Test
    public void trackKeepsThePathAltitudesAndDistance() throws Exception {
        long t = NOW / 1000;
        AirData.Track track = AirData.parseTrack("{\"path\":[[" + (t - 600) + ",40.0,-74.0,1000.0,90,false],"
                + "[" + (t - 500) + ",null,null,null,90,false],[" + (t - 400) + ",40.0,-73.0,null,90,false]]}", NOW);
        assertEquals(2, track.xs.length);
        assertEquals(3280, track.feet[0]);
        assertEquals(0, track.feet[1]);
        assertEquals(45, track.flownNm);
    }

    @Test
    public void anOldOrEmptyTrackIsNoTrack() throws Exception {
        long old = NOW / 1000 - 7200;
        assertNull(AirData.parseTrack("{\"path\":[[" + old + ",1,1,1,1,false],[" + (old + 60) + ",2,2,1,1,false]]}", NOW));
        assertNull(AirData.parseTrack("{\"path\":[]}", NOW));
        assertNull(AirData.parseTrack("{\"path\":null}", NOW));
    }

    @Test
    public void aLongTrackIsThinnedAndStillEndsNearItsEnd() throws Exception {
        StringBuilder path = new StringBuilder("{\"path\":[");
        long t = NOW / 1000;
        for (int i = 0; i < 2000; i++) path.append(i == 0 ? "" : ",").append("[").append(t).append(",").append(i / 100.0).append(",1,1,1,false]");
        AirData.Track track = AirData.parseTrack(path.append("]}").toString(), NOW);
        assertTrue(track.xs.length <= 1000);
        assertTrue(track.ys[track.ys.length - 1] < AirData.worldY(19));
    }

    @Test
    public void greatCircleRunsEndToEndAndCurvesNorthOnALongRoute() {
        double[][] line = AirData.greatCircle(40.6, -73.8, 51.5, -0.5, 32);
        assertEquals(33, line.length);
        assertEquals(AirData.worldX(-73.8), line[0][0], 1e-9);
        assertEquals(AirData.worldY(51.5), line[32][1], 1e-9);
        assertTrue("north of both ends mid-Atlantic", line[16][1] < AirData.worldY(51.5));
        assertEquals(AirData.worldX(10), AirData.greatCircle(10, 10, 10, 10, 2)[1][0], 1e-9);
    }

    @Test
    public void squawkSummaryNamesTheFirstAndCountsTheRest() throws Exception {
        List<AirData.Plane> planes = AirData.parseRegional("{\"ac\":["
                + "{\"hex\":\"a\",\"flight\":\"HK1\",\"lat\":1,\"lon\":1,\"squawk\":\"7600\"},"
                + "{\"hex\":\"b\",\"lat\":1,\"lon\":1,\"squawk\":\"7700\"},"
                + "{\"hex\":\"c\",\"lat\":1,\"lon\":1,\"squawk\":\"7500\"}]}", NOW);
        assertEquals("No emergency squawks", AirData.squawkSummary(planes.subList(0, 0)));
        assertEquals("7600 radio failure: HK1", AirData.squawkSummary(planes.subList(0, 1)));
        assertEquals("7600 radio failure: HK1, and 2 more", AirData.squawkSummary(planes));
        assertEquals("hijack", AirData.squawkMeaning("7500"));
        assertEquals("emergency", AirData.squawkMeaning("7700"));
    }

    @Test
    public void routeAndLocationAnswers() throws Exception {
        AirData.Route route = AirData.parseRoute("{\"response\":{\"flightroute\":{\"airline\":{\"name\":\"United\"},"
                + "\"origin\":{\"iata_code\":\"EWR\",\"municipality\":\"Newark\",\"latitude\":40.6,\"longitude\":-74.1},"
                + "\"destination\":{\"iata_code\":\"LHR\",\"municipality\":\"London\"}}}}");
        assertEquals("United", route.airline);
        assertEquals("EWR Newark", route.from);
        assertEquals("LHR London", route.to);
        assertNull(AirData.parseRoute("{\"response\":\"unknown callsign\"}"));
        assertEquals(1.5, AirData.parseLocation("{\"success\":true,\"latitude\":1.5,\"longitude\":2.5}")[0], 0);
        assertNull(AirData.parseLocation("{\"success\":false}"));
        assertFalse(AirData.parseRegional("{\"ac\":[]}", NOW).iterator().hasNext());
    }
}
