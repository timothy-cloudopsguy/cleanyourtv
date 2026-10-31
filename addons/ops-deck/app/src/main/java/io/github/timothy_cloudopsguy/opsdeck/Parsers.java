package io.github.timothy_cloudopsguy.opsdeck;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The feeds' answers, turned into plain values. No Android here, so the tests can run them on a laptop. */
final class Parsers {
    private static final Pattern DAY_CELL = Pattern.compile("<td\\b[^>]*ContributionCalendar-day[^>]*>");
    private static final Pattern ATTR_DATE = Pattern.compile("data-date=\"(\\d{4}-\\d{2}-\\d{2})\"");
    private static final Pattern ATTR_ID = Pattern.compile("\\bid=\"([^\"]+)\"");
    private static final Pattern ATTR_LEVEL = Pattern.compile("data-level=\"(\\d)\"");
    private static final Pattern TOOLTIP = Pattern.compile("<tool-tip\\b[^>]*\\bfor=\"([^\"]+)\"[^>]*>([^<]*)</tool-tip>");
    private static final Pattern COUNT = Pattern.compile("^\\s*([\\d,]+) contribution");

    private Parsers() {}

    static final class Incident {
        final String name;
        final String impact;
        final String status;
        final String update;

        Incident(String name, String impact, String status, String update) {
            this.name = name;
            this.impact = impact;
            this.status = status;
            this.update = update;
        }
    }

    static final class Summary {
        final List<Incident> incidents = new ArrayList<>();
        final List<String> degraded = new ArrayList<>();
        int components;
    }

    static final class Quake {
        final double magnitude;
        final String place;
        final long time;
        final double lat;
        final double lon;
        final String url;

        Quake(double magnitude, String place, long time, double lat, double lon, String url) {
            this.magnitude = magnitude;
            this.place = place;
            this.time = time;
            this.lat = lat;
            this.lon = lon;
            this.url = url;
        }
    }

    static final class Iss {
        final double lat;
        final double lon;
        final double altitudeKm;
        final double speedKmh;
        final String visibility;

        Iss(double lat, double lon, double altitudeKm, double speedKmh, String visibility) {
            this.lat = lat;
            this.lon = lon;
            this.altitudeKm = altitudeKm;
            this.speedKmh = speedKmh;
            this.visibility = visibility;
        }
    }

    static final class Day {
        final LocalDate date;
        final int level;
        final int count;

        Day(LocalDate date, int level, int count) {
            this.date = date;
            this.level = level;
            this.count = count;
        }
    }

    static final class Calendar {
        final List<Day> days;
        final int total;
        final int longestStreak;
        final int currentStreak;
        final int activeDays;
        final Day busiest;

        Calendar(List<Day> days) {
            this.days = days;
            int sum = 0;
            int active = 0;
            int run = 0;
            int longest = 0;
            Day top = null;
            for (Day d : days) {
                sum += d.count;
                if (d.count > 0) {
                    active++;
                    run++;
                    longest = Math.max(longest, run);
                    if (top == null || d.count > top.count) top = d;
                } else {
                    run = 0;
                }
            }
            // Today may simply not have a commit yet: it does not end the streak.
            int current = 0;
            for (int i = days.size() - 1; i >= 0; i--) {
                if (days.get(i).count > 0) current++;
                else if (i != days.size() - 1) break;
            }
            total = sum;
            activeDays = active;
            longestStreak = longest;
            currentStreak = current;
            busiest = top;
        }
    }

    static final class Repo {
        final String name;
        final String language;
        final int stars;
        final boolean fork;
        final long pushed;

        Repo(String name, String language, int stars, boolean fork, long pushed) {
            this.name = name;
            this.language = language;
            this.stars = stars;
            this.fork = fork;
            this.pushed = pushed;
        }
    }

    /** A Statuspage status.json: {indicator, description}. */
    static String[] status(String json) throws JSONException {
        JSONObject s = new JSONObject(json).getJSONObject("status");
        return new String[]{s.optString("indicator", "unknown"), s.optString("description", "")};
    }

    /** A Statuspage summary.json. Some pages leave the incident list out. */
    static Summary summary(String json) throws JSONException {
        JSONObject root = new JSONObject(json);
        Summary out = new Summary();
        JSONArray incidents = root.optJSONArray("incidents");
        for (int i = 0; incidents != null && i < incidents.length(); i++) {
            JSONObject o = incidents.getJSONObject(i);
            JSONArray updates = o.optJSONArray("incident_updates");
            String update = updates != null && updates.length() > 0
                    ? updates.getJSONObject(0).optString("body", "") : "";
            out.incidents.add(new Incident(o.optString("name", ""), o.optString("impact", ""),
                    o.optString("status", ""), update));
        }
        JSONArray components = root.optJSONArray("components");
        for (int i = 0; components != null && i < components.length(); i++) {
            JSONObject c = components.getJSONObject(i);
            if (c.optBoolean("group", false)) continue;
            out.components++;
            if (!"operational".equals(c.optString("status"))) out.degraded.add(c.optString("name", ""));
        }
        return out;
    }

    /** USGS's GeoJSON summary, newest first. */
    static List<Quake> quakes(String json) throws JSONException {
        JSONArray features = new JSONObject(json).getJSONArray("features");
        List<Quake> out = new ArrayList<>();
        for (int i = 0; i < features.length(); i++) {
            JSONObject f = features.getJSONObject(i);
            JSONObject p = f.getJSONObject("properties");
            JSONArray c = f.getJSONObject("geometry").getJSONArray("coordinates");
            out.add(new Quake(p.optDouble("mag", 0), p.optString("place", "Unknown"), p.optLong("time", 0),
                    c.getDouble(1), c.getDouble(0), p.optString("url", "")));
        }
        return out;
    }

    /** NOAA's planetary K index, oldest first: the last `max` three-hour readings. */
    static float[] kp(String json, int max) throws JSONException {
        JSONArray a = new JSONArray(json);
        int n = Math.min(max, a.length());
        float[] out = new float[n];
        for (int i = 0; i < n; i++) {
            out[i] = (float) a.getJSONObject(a.length() - n + i).getDouble("Kp");
        }
        return out;
    }

    static Iss iss(String json) throws JSONException {
        JSONObject o = new JSONObject(json);
        return new Iss(o.getDouble("latitude"), o.getDouble("longitude"), o.optDouble("altitude", 0),
                o.optDouble("velocity", 0), o.optString("visibility", ""));
    }

    /** Wikipedia's selected "on this day" events, oldest first: the deep past reads better on a wall than last decade's news. */
    static List<Headline> onThisDay(String json) throws JSONException {
        JSONArray selected = new JSONObject(json).getJSONArray("selected");
        List<Headline> out = new ArrayList<>();
        for (int i = 0; i < selected.length(); i++) {
            JSONObject e = selected.getJSONObject(i);
            String link = "";
            JSONArray pages = e.optJSONArray("pages");
            String extract = "";
            if (pages != null && pages.length() > 0) {
                JSONObject page = pages.getJSONObject(0);
                extract = page.optString("extract", "");
                JSONObject urls = page.optJSONObject("content_urls");
                JSONObject desktop = urls == null ? null : urls.optJSONObject("desktop");
                if (desktop != null) link = desktop.optString("page", "");
            }
            out.add(new Headline("On this day " + e.optInt("year"), e.optString("text", ""), extract, 0, link));
        }
        Collections.reverse(out);
        return out;
    }

    /**
     * Where to read about a known exploited CVE: the vendor's advisory, the first address in CISA's notes, or else the
     * CVE record. Not NVD: it often has no page yet for a CVE this new.
     */
    static String kevLink(String notes, String cve) {
        for (String part : notes.split(";")) {
            String candidate = part.trim();
            int space = candidate.indexOf(' ');
            if (space > 0) candidate = candidate.substring(0, space);
            if (candidate.startsWith("https://")) return candidate;
        }
        return "https://www.cve.org/CVERecord?id=" + cve;
    }

    /** GitHub's public contribution calendar, an HTML table; there is no key-free JSON for it. */
    static Calendar calendar(String html) {
        Map<String, Integer> counts = new HashMap<>();
        Matcher tips = TOOLTIP.matcher(html);
        while (tips.find()) {
            Matcher count = COUNT.matcher(tips.group(2));
            counts.put(tips.group(1), count.find() ? Integer.parseInt(count.group(1).replace(",", "")) : 0);
        }
        List<Day> days = new ArrayList<>();
        Matcher cells = DAY_CELL.matcher(html);
        while (cells.find()) {
            String cell = cells.group();
            Matcher date = ATTR_DATE.matcher(cell);
            if (!date.find()) continue;
            Matcher id = ATTR_ID.matcher(cell);
            Matcher level = ATTR_LEVEL.matcher(cell);
            Integer count = id.find() ? counts.get(id.group(1)) : null;
            days.add(new Day(LocalDate.parse(date.group(1)),
                    level.find() ? Integer.parseInt(level.group(1)) : 0, count == null ? 0 : count));
        }
        Collections.sort(days, (a, b) -> a.date.compareTo(b.date));
        return new Calendar(days);
    }

    static List<Repo> repos(String json) throws JSONException {
        JSONArray a = new JSONArray(json);
        List<Repo> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject r = a.getJSONObject(i);
            String language = r.isNull("language") ? "" : r.optString("language", "");
            out.add(new Repo(r.optString("name", ""), language, r.optInt("stargazers_count"),
                    r.optBoolean("fork"), isoTime(r.optString("pushed_at", ""))));
        }
        return out;
    }

    /** GitHub's stats/commit_activity: commits per week, oldest first. */
    static int[] weeklyCommits(String json) throws JSONException {
        JSONArray a = new JSONArray(json);
        int[] out = new int[a.length()];
        for (int i = 0; i < out.length; i++) out[i] = a.getJSONObject(i).optInt("total");
        return out;
    }

    /** Mixes several sources in turn, so no one feed takes over the list. */
    static List<Headline> interleave(List<List<Headline>> sources, int perSource) {
        List<Headline> out = new ArrayList<>();
        for (int i = 0; i < perSource; i++) {
            for (List<Headline> source : sources) {
                if (i < source.size()) out.add(source.get(i));
            }
        }
        return out;
    }

    static long isoTime(String iso) {
        if (iso.isEmpty()) return 0;
        try {
            SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
            format.setTimeZone(TimeZone.getTimeZone("UTC"));
            return format.parse(iso).getTime();
        } catch (ParseException e) {
            return 0;
        }
    }

    /** "3m", "5h", "2d": how long ago, for tight columns. */
    static String ago(long then, long now) {
        if (then <= 0) return "";
        long seconds = Math.max(0, (now - then) / 1000);
        if (seconds < 60) return seconds + "s";
        if (seconds < 3600) return seconds / 60 + "m";
        if (seconds < 86400) return seconds / 3600 + "h";
        if (seconds < 86400L * 365) return seconds / 86400 + "d";
        return seconds / (86400L * 365) + "y";
    }
}
