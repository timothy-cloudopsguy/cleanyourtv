package io.github.timothy_cloudopsguy.opsdeck;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Volcano reports and the list of cameras, without Android so the tests can run them. */
final class VolcanoData {
    private static final Pattern ITEM = Pattern.compile("<item>(.*?)</item>", Pattern.DOTALL);
    private static final Pattern TITLE = Pattern.compile("<title>(.*?)</title>", Pattern.DOTALL);
    private static final Pattern DESCRIPTION = Pattern.compile("<description>(.*?)</description>", Pattern.DOTALL);
    private static final Pattern POINT = Pattern.compile("<georss:point>\\s*(-?[\\d.]+)\\s+(-?[\\d.]+)\\s*</georss:point>");
    private static final Pattern NAME = Pattern.compile("^(.*?) \\((.*?)\\) - (Report for .*?) - (.*)$");
    private static final Pattern TAG = Pattern.compile("<[^>]+>");
    private static final String WEEKLY_REPORT = "https://volcano.si.edu/reports_weekly.cfm";

    private VolcanoData() {}

    static final class Report {
        final String name;
        final String place;
        final String status;
        final String summary;
        final String link;
        /** RED, ORANGE or YELLOW from an observatory; NEW or ONGOING from the weekly report. */
        final String level;
        final double lat;
        final double lon;

        Report(String name, String place, String status, String summary, String link, String level, double lat, double lon) {
            this.name = name;
            this.place = place;
            this.status = status;
            this.summary = summary;
            this.link = link;
            this.level = level;
            this.lat = lat;
            this.lon = lon;
        }

        boolean located() {
            return !Double.isNaN(lat) && !Double.isNaN(lon);
        }
    }

    static final class Cam {
        final String id;
        final String volcano;
        final String view;
        /** The image to show: a snapshot from an observatory, or a live stream's thumbnail. */
        final String imageUrl;
        /** The stream to play; empty for a snapshot-only camera. */
        final String videoUrl;
        /** Where to look when a stream's address has changed. */
        final String channelUrl;
        final long refreshMs;
        final String source;
        /** True when imageUrl is a channel's artwork rather than a current picture. */
        final boolean poster;

        Cam(String id, String volcano, String view, String imageUrl, String videoUrl, String channelUrl, long refreshMs, String source, boolean poster) {
            this.poster = poster;
            this.id = id;
            this.volcano = volcano;
            this.view = view;
            this.imageUrl = imageUrl;
            this.videoUrl = videoUrl;
            this.channelUrl = channelUrl;
            this.refreshMs = refreshMs;
            this.source = source;
        }

        boolean video() {
            return !videoUrl.isEmpty();
        }
    }

    private static final long SNAPSHOT_MS = 60_000;
    private static final long ALASKA_MS = 5 * 60_000;
    // YouTube's "live" thumbnails turned out to be each channel's artwork, not a frame from the stream: fetched rarely
    // and never presented as current.
    private static final long POSTER_MS = 6 * 3600_000;
    private static final String USGS_CHANNEL = "https://www.youtube.com/@USGS/streams";
    private static final String AFAR_CHANNEL = "https://www.youtube.com/@afartv/streams";

    /** Checked live on 31 Oct 2026. Members-only and VR180 streams are left out: they do not play well on a TV. */
    static final Cam[] CAMS = {
            usgs("f9-oSpYpubg", "V2cam", "Kilauea", "East Halemaumau crater"),
            usgs("HggWKlZv9yk", "V1cam", "Kilauea", "West Halemaumau crater"),
            usgs("gXKuUyKt8mc", "V3cam", "Kilauea", "South Halemaumau crater"),
            afar("uC6nVnMfRDY", "Etna", "Sicily, Italy"),
            afar("R98Zzr-H2FA", "Iceland", "Volcano watch, cam A"),
            afar("RgvikFbN5Js", "El Reventador", "Amazon Basin, Ecuador"),
            afar("w6WsMTQw9ac", "Popocatepetl", "From San Pedro, Mexico"),
            afar("3nAgms-Xtms", "Semeru", "Java, Indonesia, cam A"),
            afar("csMCgAuB5gc", "Semeru", "Java, Indonesia, cam B"),
            afar("2E7jzu5mP-o", "Bromo and Semeru", "Java, Indonesia"),
            afar("yqz0GC-Sd50", "Taal", "Philippines, cam A"),
            afar("LjhN6FZWqi0", "Kanlaon", "Philippines"),
            hvo("F1cam", "Kilauea", "Thermal camera, west rim"),
            hvo("KWcam", "Kilauea", "Summit panorama"),
            hvo("B1cam", "Kilauea", "Caldera and Halemaumau"),
            hvo("MLcam", "Mauna Loa", "Summit"),
            snapshot("sthelens", "Mount St. Helens", "Johnston Ridge",
                    "https://volcanoes.usgs.gov/vsc/captures/st_helens/jro-webcam.jpg", SNAPSHOT_MS, "USGS"),
            alaska("gsitkin_gsig", "Great Sitkin", "Alaska"),
            alaska("shishaldin_islz", "Shishaldin", "Alaska"),
            alaska("spurr_spcr", "Spurr", "Alaska"),
    };

    /** The USGS streams have a matching observatory camera, so their picture really is current. */
    private static Cam usgs(String id, String camera, String volcano, String view) {
        return new Cam(id, volcano, view, "https://volcanoes.usgs.gov/observatories/hvo/cams/" + camera + "/images/M.jpg",
                "https://www.youtube.com/watch?v=" + id, USGS_CHANNEL, SNAPSHOT_MS, "USGS", false);
    }

    private static Cam afar(String id, String volcano, String view) {
        return new Cam(id, volcano, view, "https://i.ytimg.com/vi/" + id + "/hqdefault_live.jpg",
                "https://www.youtube.com/watch?v=" + id, AFAR_CHANNEL, POSTER_MS, "AfarTV", true);
    }

    private static Cam hvo(String code, String volcano, String view) {
        return snapshot(code, volcano, view, "https://volcanoes.usgs.gov/observatories/hvo/cams/" + code + "/images/M.jpg",
                SNAPSHOT_MS, "USGS");
    }

    private static Cam alaska(String code, String volcano, String view) {
        return snapshot(code, volcano, view, "https://avo.alaska.edu/ashcam-api/images/" + code + "/current-medium.jpg",
                ALASKA_MS, "Alaska Volcano Observatory");
    }

    private static Cam snapshot(String id, String volcano, String view, String image, long refresh, String source) {
        return new Cam(id, volcano, view, image, "", "", refresh, source, false);
    }

    static Cam cam(String id) {
        return cam(CAMS, id);
    }

    static Cam cam(Cam[] cams, String id) {
        for (Cam c : cams) if (c.id.equals(id)) return c;
        return null;
    }

    /** A stream with no picture of its own: YouTube's thumbnail, which is often the channel's artwork. */
    static Cam stream(String id, String name, String view, String source, String channel) {
        return new Cam(id, name, view, "https://i.ytimg.com/vi/" + id + "/hqdefault_live.jpg",
                "https://www.youtube.com/watch?v=" + id, channel, POSTER_MS, source, true);
    }

    /**
     * US observatories' raised alerts, from the list of elevated volcanoes and the alerts that carry coordinates and a
     * plain-language synopsis, joined on the volcano number. Worst first.
     */
    static List<Report> parseElevated(String elevated, String located) throws JSONException {
        Map<String, JSONObject> details = new HashMap<>();
        JSONArray cap = new JSONArray(located);
        for (int i = 0; i < cap.length(); i++) {
            JSONObject o = cap.getJSONObject(i);
            details.put(o.optString("vnum"), o);
        }
        List<Report> out = new ArrayList<>();
        JSONArray list = new JSONArray(elevated);
        for (int i = 0; i < list.length(); i++) {
            JSONObject o = list.getJSONObject(i);
            JSONObject more = details.get(o.optString("vnum"));
            String color = o.optString("color_code", "");
            String alert = o.optString("alert_level", "");
            String synopsis = more == null ? "" : more.optString("synopsis", "");
            String observatory = o.optString("obs_fullname", "");
            out.add(new Report(o.optString("volcano_name", ""), observatory, color + " / " + alert,
                    synopsis.isEmpty() ? "Alert raised by the " + observatory + "." : synopsis,
                    o.optString("notice_url", ""), color,
                    more == null ? Double.NaN : more.optDouble("latitude"),
                    more == null ? Double.NaN : more.optDouble("longitude")));
        }
        out.sort((a, b) -> rank(a.level) - rank(b.level));
        return out;
    }

    /** The Smithsonian / USGS Weekly Volcanic Activity Report; new eruptive activity first. */
    static List<Report> parseWeekly(String rss) {
        List<Report> fresh = new ArrayList<>();
        List<Report> ongoing = new ArrayList<>();
        Matcher items = ITEM.matcher(rss);
        while (items.find()) {
            String item = items.group(1);
            Matcher title = TITLE.matcher(item);
            if (!title.find()) continue;
            Matcher name = NAME.matcher(plain(title.group(1)));
            if (!name.find()) continue;
            Matcher description = DESCRIPTION.matcher(item);
            Matcher point = POINT.matcher(item);
            boolean located = point.find();
            boolean isNew = name.group(4).startsWith("New");
            Report report = new Report(name.group(1), name.group(2), name.group(4),
                    description.find() ? plain(description.group(1)) : "", WEEKLY_REPORT, isNew ? "NEW" : "ONGOING",
                    located ? Double.parseDouble(point.group(1)) : Double.NaN,
                    located ? Double.parseDouble(point.group(2)) : Double.NaN);
            (isNew ? fresh : ongoing).add(report);
        }
        fresh.addAll(ongoing);
        return fresh;
    }

    private static int rank(String level) {
        switch (level) {
            case "RED": return 0;
            case "ORANGE": return 1;
            case "YELLOW": return 2;
            default: return 3;
        }
    }

    /** The feed's text is HTML escaped, tags included. */
    private static String plain(String text) {
        // "&amp;" first as well as last, in case an entity is escaped a second time.
        String unescaped = text.replace("<![CDATA[", "").replace("]]>", "").replace("&amp;", "&")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&nbsp;", " ").replace("&deg;", "\u00B0").replace("&amp;", "&");
        return TAG.matcher(unescaped).replaceAll(" ").replaceAll("\\s+", " ").trim();
    }

    /** Ids stored as a comma list: unknown ones dropped, as a stream may leave the list in an update. */
    static List<String> readWall(String stored, int max) {
        return readWall(CAMS, stored, max);
    }

    static List<String> readWall(Cam[] cams, String stored, int max) {
        List<String> out = new ArrayList<>();
        for (String id : stored.split(",")) {
            if (out.size() < max && cam(cams, id) != null && !out.contains(id)) out.add(id);
        }
        return out;
    }
}
