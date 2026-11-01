package io.github.timothy_cloudopsguy.opsdeck;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class ParsersTest {
    private static final long NOW = 1_800_000_000_000L;

    @Test
    public void calendarReadsCountsFromTooltipsWhateverTheAttributeOrder() {
        String html = "<td data-date=\"2026-01-03\" id=\"c2\" data-level=\"2\" class=\"ContributionCalendar-day\"></td>"
                + "<td class=\"ContributionCalendar-day\" id=\"c1\" data-level=\"1\" data-date=\"2026-01-02\"></td>"
                + "<td data-date=\"2026-01-01\" id=\"c0\" data-level=\"0\" class=\"ContributionCalendar-day\"></td>"
                + "<tool-tip id=\"t0\" for=\"c0\" class=\"sr-only\">No contributions on January 1st.</tool-tip>"
                + "<tool-tip id=\"t1\" for=\"c1\" class=\"sr-only\">1 contribution on January 2nd.</tool-tip>"
                + "<tool-tip id=\"t2\" for=\"c2\" class=\"sr-only\">1,204 contributions on January 3rd.</tool-tip>";
        Parsers.Calendar c = Parsers.calendar(html);
        assertEquals(3, c.days.size());
        assertEquals("2026-01-01", c.days.get(0).date.toString());
        assertEquals(1205, c.total);
        assertEquals(2, c.activeDays);
        assertEquals(2, c.longestStreak);
        assertEquals(2, c.currentStreak);
        assertEquals(1204, c.busiest.count);
    }

    @Test
    public void todayWithoutACommitDoesNotEndTheStreakButYesterdayDoes() {
        assertEquals(2, streak(0, 1, 1, 0));
        assertEquals(0, streak(1, 1, 0, 0));
    }

    @Test
    public void anEmptyCalendarHasNoBusiestDay() {
        Parsers.Calendar c = Parsers.calendar("<td class=\"ContributionCalendar-day\" data-date=\"2026-01-01\" id=\"a\"></td>");
        assertEquals(0, c.total);
        assertEquals(null, c.busiest);
    }

    @Test
    public void statusPageSummaryCountsLeafComponentsAndToleratesNoIncidents() throws Exception {
        Parsers.Summary s = Parsers.summary("{\"components\":["
                + "{\"name\":\"Group\",\"group\":true,\"status\":\"major_outage\"},"
                + "{\"name\":\"API\",\"status\":\"operational\"},"
                + "{\"name\":\"Git\",\"status\":\"degraded_performance\"}]}");
        assertEquals(2, s.components);
        assertEquals(List.of("Git"), s.degraded);
        assertEquals(0, s.incidents.size());
    }

    @Test
    public void statusPageIncidentCarriesItsNewestUpdate() throws Exception {
        Parsers.Summary s = Parsers.summary("{\"incidents\":[{\"name\":\"Voice down\",\"impact\":\"minor\","
                + "\"status\":\"investigating\",\"incident_updates\":[{\"body\":\"Looking into it\"},{\"body\":\"old\"}]}]}");
        assertEquals("Looking into it", s.incidents.get(0).update);
    }

    @Test
    public void quakeCoordinatesAreLongitudeFirstInGeoJson() throws Exception {
        List<Parsers.Quake> q = Parsers.quakes("{\"features\":[{\"properties\":{\"mag\":5.1,\"place\":\"Offshore\","
                + "\"time\":5},\"geometry\":{\"coordinates\":[120.5,-3.25,10]}}]}");
        assertEquals(-3.25, q.get(0).lat, 0);
        assertEquals(120.5, q.get(0).lon, 0);
    }

    @Test
    public void kpKeepsTheNewestReadingsInOrder() throws Exception {
        float[] kp = Parsers.kp("[{\"Kp\":1.0},{\"Kp\":2.5},{\"Kp\":7.0}]", 2);
        assertArrayEquals(new float[]{2.5f, 7f}, kp, 0);
    }

    @Test
    public void onThisDayCarriesYearLinkAndExtract() throws Exception {
        List<Headline> h = Parsers.onThisDay("{\"selected\":[{\"year\":1517,\"text\":\"Theses posted\",\"pages\":[{"
                + "\"extract\":\"Luther\",\"content_urls\":{\"desktop\":{\"page\":\"https://en.wikipedia.org/wiki/x\"}}}]}]}");
        assertEquals("On this day 1517", h.get(0).source);
        assertEquals("https://en.wikipedia.org/wiki/x", h.get(0).link);
        assertEquals("Luther", h.get(0).summary);
    }

    @Test
    public void onThisDayPutsTheOldestYearFirst() throws Exception {
        List<Headline> h = Parsers.onThisDay("{\"selected\":[{\"year\":2017,\"text\":\"new\"},{\"year\":1517,\"text\":\"old\"}]}");
        assertEquals("old", h.get(0).title);
    }

    @Test
    public void kevLinksToTheVendorAdvisoryAndElseTheCveRecord() {
        assertEquals("https://hub.ivanti.com/a?x=1", Parsers.kevLink(
                "https://hub.ivanti.com/a?x=1 ; ; BOD 26-04: https://www.cisa.gov/b ; https://nvd.nist.gov/c", "CVE-1"));
        assertEquals("https://www.cve.org/CVERecord?id=CVE-1", Parsers.kevLink("", "CVE-1"));
        assertEquals("https://www.cve.org/CVERecord?id=CVE-1", Parsers.kevLink("see vendor ; BOD 26-04: https://x", "CVE-1"));
    }

    @Test
    public void repoWithoutALanguageIsEmptyNotTheWordNull() throws Exception {
        List<Parsers.Repo> r = Parsers.repos("[{\"name\":\"a\",\"language\":null,\"fork\":true,"
                + "\"pushed_at\":\"2020-12-16T14:09:40Z\"}]");
        assertEquals("", r.get(0).language);
        assertEquals(1608127780000L, r.get(0).pushed);
    }

    @Test
    public void interleaveTakesEachSourceInTurnAndSkipsShortOnes() {
        Headline a1 = h("a1");
        Headline a2 = h("a2");
        Headline b1 = h("b1");
        List<Headline> mixed = Parsers.interleave(Arrays.asList(List.of(a1, a2), List.of(b1)), 5);
        assertEquals(Arrays.asList(a1, b1, a2), mixed);
    }

    @Test
    public void agoPicksTheLargestUnit() {
        assertEquals("", Parsers.ago(0, NOW));
        assertEquals("5s", Parsers.ago(NOW - 5_000, NOW));
        assertEquals("3m", Parsers.ago(NOW - 180_000, NOW));
        assertEquals("2h", Parsers.ago(NOW - 7_200_000, NOW));
        assertEquals("4d", Parsers.ago(NOW - 345_600_000, NOW));
    }

    private static int streak(int... counts) {
        StringBuilder html = new StringBuilder();
        for (int i = 0; i < counts.length; i++) {
            html.append("<td class=\"ContributionCalendar-day\" data-date=\"2026-01-0").append(i + 1)
                    .append("\" id=\"d").append(i).append("\"></td>")
                    .append("<tool-tip for=\"d").append(i).append("\">").append(counts[i])
                    .append(" contributions on x</tool-tip>");
        }
        return Parsers.calendar(html.toString()).currentStreak;
    }

    private static Headline h(String title) {
        return new Headline("s", title, "", 0, "");
    }

    @Test
    public void newestFirstPutsWhatJustBrokeOnTopAndUndatedLast() {
        Headline oldest = new Headline("A", "old", "", 100, "");
        Headline newest = new Headline("B", "new", "", 300, "");
        Headline middle = new Headline("A", "mid", "", 200, "");
        Headline undated = new Headline("C", "none", "", 0, "");
        List<Headline> out = Parsers.newestFirst(Arrays.asList(Arrays.asList(oldest, middle), Arrays.asList(newest), Arrays.asList(undated)), 2);
        assertEquals("new", out.get(0).title);
        assertEquals("mid", out.get(1).title);
        assertEquals("old", out.get(2).title);
        assertEquals("none", out.get(3).title);
    }

    @Test
    public void bridgeTilesFindTheirOwnSourceWhereverItSorts() {
        Headline news = new Headline("Smithsonian", "A story", "", 300, "");
        Headline day = new Headline("On this day 1141", "An event", "", 0, "");
        assertEquals("An event", Parsers.firstFrom(Arrays.asList(news, day), "On this day ").title);
        assertEquals(null, Parsers.firstFrom(Arrays.asList(news), "On this day "));
    }
}
