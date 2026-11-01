package io.github.timothy_cloudopsguy.opsdeck;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class StatusDataTest {
    private static final long NOW = 1_800_000_000_000L;

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    public void theCatalogueHasNoRepeatsAndUsesSecureAddresses() {
        Set<String> names = new HashSet<>();
        for (StatusData.Source s : StatusData.CATALOG) {
            assertTrue(s.name, names.add(s.name));
            assertTrue(s.name, s.url.startsWith("https://"));
            assertFalse(s.name, s.custom());
        }
        assertTrue(StatusData.startingSources().size() <= StatusData.MAX_CHECKS);
    }

    @Test
    public void checksSaveAndLoadAndNothingSavedMeansTheStartingSet() {
        List<StatusData.Source> sources = Arrays.asList(new StatusData.Source(StatusData.FEED, "Mine", "https://x.example/feed"), StatusData.CATALOG[0]);
        List<StatusData.Source> loaded = StatusData.load(StatusData.save(sources));
        assertEquals(2, loaded.size());
        assertEquals("Mine", loaded.get(0).name);
        assertEquals(StatusData.FEED, loaded.get(0).kind);
        assertTrue(loaded.get(0).custom());
        assertEquals(StatusData.startingSources().size(), StatusData.load(null).size());
        assertTrue("an emptied board stays empty", StatusData.load("").isEmpty());
        assertTrue("insecure lines are dropped", StatusData.load("feed|A|http://x.example").isEmpty());
    }

    @Test
    public void typedAddressesAreTidiedAndNonsenseIsRefused() {
        assertEquals("https://www.githubstatus.com", StatusData.normalise(" www.githubstatus.com/ "));
        assertEquals("https://x.example", StatusData.normalise("http://x.example"));
        assertEquals("https://x.example", StatusData.normalise("https://https://x.example"));
        assertEquals("https://x.example", StatusData.normalise("https://x.example/api/v2/status.json"));
        assertEquals("https://x.example/feed.rss", StatusData.normalise("https://x.example/feed.rss"));
        assertEquals("", StatusData.normalise("https://"));
        assertEquals("", StatusData.normalise("not an address"));
        assertEquals("", StatusData.normalise("nodots"));
    }

    @Test
    public void aPageNameLosesTheWordMostOfThemAdd() throws Exception {
        assertEquals("Cloudflare", StatusData.pageName("{\"page\":{\"name\":\"Cloudflare Status\"}}"));
        assertEquals("HashiCorp", StatusData.pageName("{\"page\":{\"name\":\"HashiCorp Services\"}}"));
        assertEquals("", StatusData.pageName("{}"));
    }

    @Test
    public void awsAnswersInUtf16AndNamesTheRegionsWithoutCryingOutage() throws Exception {
        String json = "[{\"region_name\":\"UAE\",\"service_name\":\"Multiple services\",\"summary\":\"Region Availability\","
                + "\"event_log\":[{\"message\":\"first\"},{\"message\":\"latest\"}]},{\"region_name\":\"Bahrain\",\"service_name\":\"Multiple services\"}]";
        StatusData.Reading reading = StatusData.read(StatusData.AWS, json.getBytes(StandardCharsets.UTF_16), NOW);
        assertEquals("minor", reading.indicator);
        assertEquals("2 regional events: UAE, Bahrain", reading.description);
        assertEquals("latest", reading.summary.incidents.get(0).update);
        assertEquals("none", StatusData.read(StatusData.AWS, "[]".getBytes(StandardCharsets.UTF_16), NOW).indicator);
        StringBuilder many = new StringBuilder("[{\"region_name\":\"A\"}");
        for (int i = 0; i < 4; i++) many.append(",{\"region_name\":\"A\"}");
        assertEquals("major", StatusData.read(StatusData.AWS, utf8(many + "]"), NOW).indicator);
    }

    @Test
    public void googleCountsOnlyIncidentsWithNoEnd() throws Exception {
        String json = "[{\"external_desc\":\"old\",\"end\":\"2026-10-20T12:08:00+00:00\",\"severity\":\"high\"},"
                + "{\"external_desc\":\"now\",\"severity\":\"medium\",\"status_impact\":\"SERVICE_DISRUPTION\",\"most_recent_update\":{\"text\":\"looking\"}}]";
        StatusData.Reading reading = StatusData.read(StatusData.GOOGLE, utf8(json), NOW);
        assertEquals("minor", reading.indicator);
        assertEquals("1 open incident", reading.description);
        assertEquals("service disruption", reading.summary.incidents.get(0).status);
        assertEquals("none", StatusData.read(StatusData.GOOGLE, utf8("[{\"end\":\"x\"}]"), NOW).indicator);
    }

    @Test
    public void slackOutagesAreMajor() throws Exception {
        assertEquals("none", StatusData.read(StatusData.SLACK, utf8("{\"status\":\"ok\",\"active_incidents\":[]}"), NOW).indicator);
        assertEquals("major", StatusData.read(StatusData.SLACK, utf8("{\"active_incidents\":[{\"title\":\"t\",\"type\":\"outage\"}]}"), NOW).indicator);
        assertEquals("minor", StatusData.read(StatusData.SLACK, utf8("{\"active_incidents\":[{\"title\":\"t\",\"type\":\"incident\"}]}"), NOW).indicator);
    }
}
