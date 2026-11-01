package io.github.timothy_cloudopsguy.opsdeck;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class VolcanoDataTest {
    @Test
    public void weeklyReportReadsNamePlaceStatusPointAndPlainText() {
        List<VolcanoData.Report> reports = VolcanoData.parseWeekly("<rss><item>"
                + "<title>Taal (Philippines) - Report for 8 October-14 October 2026 - Continuing Eruptive Activity</title>"
                + "<description>&lt;p&gt;Steam &amp;amp; ash rose.&lt;/p&gt;</description>"
                + "<georss:point>14.002 120.993</georss:point></item><item>"
                + "<title>Krakatau (Indonesia) - Report for 8 October-14 October 2026 - New Eruptive Activity</title>"
                + "<description>&lt;p&gt;Ash plumes.&lt;/p&gt;</description>"
                + "<georss:point>-6.1009 105.4233</georss:point></item></rss>");
        assertEquals(2, reports.size());
        VolcanoData.Report first = reports.get(0);
        assertEquals("new eruptions come first", "Krakatau", first.name);
        assertEquals("Indonesia", first.place);
        assertEquals("NEW", first.level);
        assertEquals(-6.1009, first.lat, 0);
        assertEquals(105.4233, first.lon, 0);
        assertEquals("Ash plumes.", first.summary);
        assertEquals("ONGOING", reports.get(1).level);
        assertEquals("Steam & ash rose.", reports.get(1).summary);
    }

    @Test
    public void aReportWithoutAPointIsKeptButNotLocated() {
        List<VolcanoData.Report> reports = VolcanoData.parseWeekly(
                "<item><title>Etna (Italy) - Report for x - New Eruptive Activity</title></item>");
        assertEquals(1, reports.size());
        assertFalse(reports.get(0).located());
        assertEquals("", reports.get(0).summary);
    }

    @Test
    public void anItemThatDoesNotParseIsSkipped() {
        assertTrue(VolcanoData.parseWeekly("<item><title>Weekly note</title></item>").isEmpty());
    }

    @Test
    public void elevatedAlertsJoinOnVolcanoNumberWorstFirst() throws Exception {
        List<VolcanoData.Report> reports = VolcanoData.parseElevated(
                "[{\"volcano_name\":\"Kilauea\",\"vnum\":\"1\",\"color_code\":\"YELLOW\",\"alert_level\":\"ADVISORY\","
                        + "\"obs_fullname\":\"Hawaiian Volcano Observatory\",\"notice_url\":\"https://x/1\"},"
                        + "{\"volcano_name\":\"Great Sitkin\",\"vnum\":\"2\",\"color_code\":\"ORANGE\",\"alert_level\":\"WATCH\","
                        + "\"obs_fullname\":\"Alaska Volcano Observatory\",\"notice_url\":\"https://x/2\"}]",
                "[{\"vnum\":\"2\",\"latitude\":52.07,\"longitude\":-176.11,\"synopsis\":\"Slow eruption of lava.\"}]");
        assertEquals("Great Sitkin", reports.get(0).name);
        assertEquals("ORANGE / WATCH", reports.get(0).status);
        assertEquals("Slow eruption of lava.", reports.get(0).summary);
        assertEquals(52.07, reports.get(0).lat, 0);
        assertTrue(reports.get(0).located());
        assertFalse(reports.get(1).located());
        assertEquals("Alert raised by the Hawaiian Volcano Observatory.", reports.get(1).summary);
    }

    @Test
    public void everyCameraHasAUniqueIdAndAnHttpsPicture() {
        Set<String> ids = new HashSet<>();
        for (VolcanoData.Cam cam : VolcanoData.CAMS) {
            assertTrue(cam.id, ids.add(cam.id));
            assertTrue(cam.id, cam.imageUrl.startsWith("https://"));
            assertFalse(cam.id, cam.id.contains(","));
            if (cam.video()) {
                assertTrue(cam.id, ReaderActivity.allowed(cam.videoUrl));
                assertTrue(cam.id, ReaderActivity.allowed(cam.channelUrl));
            }
        }
        assertNotNull(VolcanoData.cam("F1cam"));
    }

    @Test
    public void onlyChannelArtworkIsMarkedAsAPoster() {
        assertFalse("USGS streams use the observatory's current image", VolcanoData.cam("f9-oSpYpubg").poster);
        assertTrue(VolcanoData.cam("f9-oSpYpubg").imageUrl.contains("V2cam"));
        assertTrue(VolcanoData.cam("uC6nVnMfRDY").poster);
        assertFalse(VolcanoData.cam("F1cam").poster);
    }

    @Test
    public void wallDropsUnknownAndRepeatedCamerasAndStopsAtItsLimit() {
        assertEquals(Arrays.asList("F1cam", "KWcam"), VolcanoData.readWall("F1cam,gone,F1cam,KWcam,MLcam", 2));
        assertTrue(VolcanoData.readWall("", 6).isEmpty());
    }

    @Test
    public void everySpotterStreamIsUniqueAllowedAndItsStartingWallIsReal() {
        Set<String> ids = new HashSet<>();
        for (VolcanoData.Cam cam : AirData.SPOTTER_CAMS) {
            assertTrue(cam.id, ids.add(cam.id));
            assertTrue(cam.id, cam.video());
            assertTrue(cam.id, cam.poster);
            assertTrue(cam.id, ReaderActivity.allowed(cam.videoUrl));
            assertTrue(cam.id, ReaderActivity.allowed(cam.channelUrl));
        }
        assertEquals(6, VolcanoData.readWall(AirData.SPOTTER_CAMS, AirData.SPOTTER_START_WALL, 6).size());
        assertTrue("the two lists do not mix", VolcanoData.readWall(AirData.SPOTTER_CAMS, "F1cam", 6).isEmpty());
    }
}
