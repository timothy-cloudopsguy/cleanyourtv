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

    private static AirData.Route chicagoToDallas() {
        return new AirData.Route("", "ORD Chicago", "DFW Dallas", new double[]{41.98, -87.90}, new double[]{32.90, -97.04});
    }

    @Test
    public void aRouteThatFitsIsKeptAndOneFlownTheOtherWayIsSwapped() {
        List<AirData.Route> legs = java.util.Collections.singletonList(chicagoToDallas());
        AirData.RouteCheck going = AirData.checkRoute(legs, 37.5, -92.5, 220);
        assertEquals(AirData.ROUTE_FITS, going.state);
        assertEquals("DFW Dallas", going.route.to);
        AirData.RouteCheck back = AirData.checkRoute(legs, 37.5, -92.5, 40);
        assertEquals(AirData.ROUTE_FLIPPED, back.state);
        assertEquals("ORD Chicago", back.route.to);
        assertEquals(32.90, back.route.fromPoint[0], 0);
        assertTrue(back.trusted());
    }

    @Test
    public void aRouteFarFromTheAircraftIsNotShownAndSidewaysIsNotTrusted() {
        List<AirData.Route> legs = java.util.Collections.singletonList(chicagoToDallas());
        AirData.RouteCheck far = AirData.checkRoute(legs, 51.5, 0, 220);
        assertEquals(AirData.ROUTE_NO_FIT, far.state);
        assertNull(far.route);
        assertFalse(far.trusted());
        AirData.RouteCheck sideways = AirData.checkRoute(legs, 37.5, -92.5, 130);
        assertEquals(AirData.ROUTE_UNSURE, sideways.state);
        assertFalse(sideways.trusted());
        assertEquals(AirData.ROUTE_NO_FIT, AirData.checkRoute(new java.util.ArrayList<>(), 1, 1, 1).state);
    }

    @Test
    public void nearAnAirportTheHeadingIsNotHeldAgainstTheRoute() {
        List<AirData.Route> legs = java.util.Collections.singletonList(chicagoToDallas());
        assertEquals(AirData.ROUTE_FITS, AirData.checkRoute(legs, 41.9, -87.8, 40).state);
    }

    @Test
    public void theLegThatFitsIsChosenAndAFittingLegBeatsAFlippedOne() {
        AirData.Route elsewhere = new AirData.Route("", "LHR", "CDG", new double[]{51.47, -0.45}, new double[]{49.0, 2.55});
        assertEquals("DFW Dallas", AirData.checkRoute(java.util.Arrays.asList(elsewhere, chicagoToDallas()), 37.5, -92.5, 220).route.to);
        AirData.Route reverse = new AirData.Route("", "DFW Dallas", "ORD Chicago", new double[]{32.90, -97.04}, new double[]{41.98, -87.90});
        AirData.RouteCheck both = AirData.checkRoute(java.util.Arrays.asList(reverse, chicagoToDallas()), 37.5, -92.5, 220);
        assertEquals(AirData.ROUTE_FITS, both.state);
        assertEquals("DFW Dallas", both.route.to);
    }

    @Test
    public void vrsRoutesBecomeOneRoutePerLeg() throws Exception {
        List<AirData.Route> legs = AirData.parseVrsRoute("{\"_airports\":[{\"iata\":\"STL\",\"location\":\"St Louis\",\"lat\":38.7,\"lon\":-90.3},"
                + "{\"iata\":\"DAL\",\"location\":\"Dallas\",\"lat\":32.8,\"lon\":-96.8},{\"iata\":\"HOU\",\"lat\":29.6,\"lon\":-95.2}]}");
        assertEquals(2, legs.size());
        assertEquals("STL St Louis", legs.get(0).from);
        assertEquals("HOU", legs.get(1).to);
        assertEquals(-95.2, legs.get(1).toPoint[1], 0);
        assertTrue(AirData.parseVrsRoute("{}").isEmpty());
    }

    @Test
    public void landingTimeRoundsUpAndNeedsRealSpeed() {
        assertEquals("near landing, its speed now", 15, AirData.minutesToLand(60, 250, 430));
        assertEquals("far out, the approach is added", 70, AirData.minutesToLand(450, 450, 0));
        assertEquals("still climbing counts as cruising", 125, AirData.minutesToLand(814, 287, 430));
        assertEquals("a tailwind counts", 100, AirData.minutesToLand(814, 560, 430));
        assertEquals(-1, AirData.minutesToLand(100, 50, 430));
        assertEquals("45M", AirData.duration(45));
        assertEquals("2H 05M", AirData.duration(125));
    }

    @Test
    public void aTrackKnowsWhetherItSawTheTakeoff() throws Exception {
        long t = NOW / 1000;
        AirData.Track low = AirData.parseTrack("{\"path\":[[" + (t - 600) + ",40.0,-74.0,300.0,90,false],[" + (t - 400) + ",40.0,-73.0,9000,90,false]]}", NOW);
        assertTrue(low.sawTakeoff());
        assertEquals(NOW - 600_000, low.startMs);
        AirData.Track high = AirData.parseTrack("{\"path\":[[" + (t - 600) + ",40.0,-74.0,9000,90,false],[" + (t - 400) + ",40.0,-73.0,9000,90,false]]}", NOW);
        assertFalse(high.sawTakeoff());
    }

    @Test
    public void optionalFieldsAreReadAndTheAutopilotValueIsRounded() throws Exception {
        AirData.Plane p = AirData.parseRegional("{\"ac\":[{\"hex\":\"a\",\"lat\":1,\"lon\":1,\"nav_altitude_mcp\":20992,\"wd\":307,\"ws\":50,\"mach\":0.748}]}", NOW).get(0);
        assertEquals(21000, p.targetFeet);
        assertEquals(307, p.windFrom);
        assertEquals(50, p.windKnots);
        assertEquals(0.748, p.mach, 0);
        AirData.Plane bare = AirData.parseRegional("{\"ac\":[{\"hex\":\"b\",\"lat\":1,\"lon\":1}]}", NOW).get(0);
        assertEquals(0, bare.targetFeet);
        assertEquals(-1, bare.windKnots);
    }

    @Test
    public void airframeDetailsJoinMakerAndType() throws Exception {
        AirData.AircraftInfo info = AirData.parseAircraft("{\"response\":{\"aircraft\":{\"type\":\"A320 251NSL\",\"manufacturer\":\"Airbus\",\"registered_owner\":\"easyJet Airline\"}}}");
        assertEquals("Airbus A320 251NSL", info.name);
        assertEquals("easyJet Airline", info.owner);
        assertNull(AirData.parseAircraft("{\"response\":\"unknown aircraft\"}"));
    }

    @Test
    public void aSwappedRouteSwapsItsAirportCodesToo() {
        AirData.Route route = new AirData.Route("", "ORD", "DFW", new double[]{41.98, -87.90}, new double[]{32.90, -97.04}, "KORD", "KDFW");
        assertEquals("KORD", AirData.checkRoute(java.util.Collections.singletonList(route), 37.5, -92.5, 40).route.toIcao);
    }

    @Test
    public void weatherBecomesOneLineAndAnEmptyAnswerIsNone() throws Exception {
        AirData.Weather weather = AirData.parseWeather("[{\"temp\":12.8,\"wspd\":3,\"wgst\":18,\"wxString\":\"-RA\",\"cover\":\"BKN\",\"fltCat\":\"MVFR\"}]");
        assertEquals("55F, -RA, wind 3G18 kt, BKN", weather.line);
        assertEquals("MVFR", weather.category);
        assertEquals("wind 4 kt", AirData.parseWeather("[{\"temp\":null,\"wspd\":4}]").line);
        assertNull(AirData.parseWeather("[]"));
    }

    @Test
    public void aPhotoGivesItsFullSizeAddressAndItsPhotographer() throws Exception {
        AirData.Photo photo = AirData.parsePhoto("{\"status\":200,\"count\":1,\"data\":[{\"image\":\"https://airport-data.com/images/aircraft/thumbnails/001/912/001912792.jpg\","
                + "\"link\":\"https://airport-data.com/aircraft/photo/001912792\",\"photographer\":\"Shunn311\"}]}");
        assertEquals("https://image.airport-data.com/aircraft/001912792.jpg", photo.full);
        assertEquals("Shunn311", photo.photographer);
        assertNull(AirData.parsePhoto("{\"status\":404,\"error\":\"Aircraft thumbnail not found.\"}"));
        assertEquals("", AirData.parsePhoto("{\"data\":[{\"image\":\"https://x/1.jpg\",\"link\":\"nonsense\"}]}").full);
    }

    @Test
    public void aProfileShowsAltitudeAndSpeedOverTimeAndIgnoresNoise() throws Exception {
        long t = NOW / 1000;
        // 0.5 degrees of latitude is 30 nm: in 240 s that is 450 kt. The third point jumps too far to be real.
        AirData.Track track = AirData.parseTrack("{\"path\":[[" + (t - 960) + ",40.0,-74.0,0,0,false],[" + (t - 720) + ",40.5,-74.0,3000,0,false],"
                + "[" + (t - 480) + ",50.0,-74.0,6000,0,false],[" + (t - 240) + ",50.5,-74.0,9000,0,false]]}", NOW);
        AirData.Profile profile = AirData.profile(track, 4);
        assertEquals(4, profile.feet.length);
        assertEquals(29527, profile.maxFeet);
        assertEquals(450, profile.maxKnots, 3);
        assertEquals("a slice with no good speed holds the last", profile.knots[1], profile.knots[2]);
        assertEquals(0, profile.feet[0]);
    }

    @Test
    public void planespottersGivesTheLargerPictureAndItsPhotographer() throws Exception {
        AirData.Photo photo = AirData.parsePlanespotters("{\"photos\":[{\"thumbnail\":{\"src\":\"https://t.x/t.jpg\"},\"thumbnail_large\":{\"src\":\"https://t.x/280.jpg\"},\"photographer\":\"Wolfgang Kaiser\"}]}");
        assertEquals("https://t.x/280.jpg", photo.full);
        assertEquals("Wolfgang Kaiser", photo.photographer);
        assertFalse(photo.similarOnly());
        assertNull(AirData.parsePlanespotters("{\"photos\":[]}"));
    }

    @Test
    public void photoSearchesNarrowDownButNeverToJustTheMaker() {
        assertEquals(java.util.Arrays.asList("Boeing 737NG 7H4/W", "Boeing 737NG"), AirData.photoSearches("Boeing 737NG 7H4/W"));
        assertEquals(java.util.Collections.singletonList("Cessna 680A"), AirData.photoSearches("Cessna 680A"));
        assertTrue(AirData.photoSearches("").isEmpty());
    }

    private static String commonsPage(int index, String title, int width, int height, String licence) {
        return "\"" + index + "\":{\"index\":" + index + ",\"title\":\"File:" + title + "\",\"imageinfo\":[{\"width\":" + width + ",\"height\":" + height
                + ",\"thumburl\":\"https://thumb/" + index + ".jpg\",\"extmetadata\":{\"Artist\":{\"value\":\"<a href='x'>Matti</a>\"},\"LicenseShortName\":{\"value\":\"" + licence + "\"}}}]}";
    }

    @Test
    public void aCommonsSearchTakesTheBestWideWholeAircraftAndNeedsALicence() throws Exception {
        AirData.Photo photo = AirData.parseCommons("{\"query\":{\"pages\":{" + commonsPage(1, "Cessna cockpit.jpg", 800, 600, "CC0") + ","
                + commonsPage(2, "Tall.jpg", 600, 800, "CC0") + "," + commonsPage(4, "Later.jpg", 800, 600, "CC0") + ","
                + commonsPage(3, "N596QS.jpg", 800, 600, "CC BY 4.0") + "}}}");
        assertEquals("https://thumb/3.jpg", photo.thumbnail);
        assertEquals("Matti", photo.photographer);
        assertEquals("CC BY 4.0", photo.licence);
        assertTrue(photo.similarOnly());
        assertNull(AirData.parseCommons("{\"query\":{\"pages\":{" + commonsPage(1, "A.jpg", 800, 600, "") + "}}}"));
        assertNull(AirData.parseCommons("{\"batchcomplete\":\"\"}"));
    }

    @Test
    public void historyKeepsSpacedSamplesOfFlyingAircraftAndForgetsOldOnes() throws Exception {
        AirData.History history = new AirData.History();
        String json = "{\"ac\":[{\"hex\":\"a\",\"lat\":1,\"lon\":1,\"alt_baro\":9000},{\"hex\":\"g\",\"lat\":1,\"lon\":1,\"alt_baro\":\"ground\"}]}";
        List<AirData.Plane> planes = AirData.parseRegional(json, NOW);
        history.add(planes, NOW);
        history.add(planes, NOW + 5_000);
        assertNull("too close to count as a second sample", history.track("a"));
        history.add(planes, NOW + 31_000);
        AirData.Track track = history.track("a");
        assertEquals(2, track.times.length);
        assertEquals(9000, track.feet[1]);
        assertNull(history.track("g"));
        history.add(new java.util.ArrayList<>(), NOW + 20 * 60_000);
        assertNull(history.track("a"));
    }

    @Test
    public void planespottersKeepsItsPhotoPageAndHexdbNamesAType() throws Exception {
        assertEquals("https://p/1?utm_source=api", AirData.parsePlanespotters("{\"photos\":[{\"thumbnail\":{\"src\":\"https://t.x/t.jpg\"},\"link\":\"https://p/1?utm_source=api\"}]}").link);
        assertEquals("Cessna 680A", AirData.parseHexdbName("{\"Manufacturer\":\"Cessna\",\"Type\":\"680A\"}"));
        assertEquals("Cessna 680A", AirData.parseHexdbName("{\"Manufacturer\":\"Cessna\",\"Type\":\"Cessna 680A\"}"));
        assertEquals("", AirData.parseHexdbName("{\"status\":\"404\"}"));
    }
}
