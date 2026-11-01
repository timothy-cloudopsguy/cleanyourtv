package io.github.timothy_cloudopsguy.opsdeck;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Which status pages are on the board and how to read each kind, without Android so the tests can run it. */
final class StatusData {
    /** The hosted Statuspage service, used by most companies: a base address with a small JSON API under it. */
    static final String STATUSPAGE = "statuspage";
    static final String AWS = "aws";
    static final String GOOGLE = "google";
    static final String AZURE = "azure";
    static final String SLACK = "slack";
    /** Any RSS or Atom feed of incidents, added by the owner. */
    static final String FEED = "feed";
    static final int MAX_CHECKS = 12;
    /** A feed cannot say whether something is down, only that it has been posting. */
    private static final long FEED_RECENT_MS = 24 * 3600_000L;
    /** AWS's events are regional and can stay open for months, so a few of them are not a global outage. */
    private static final int AWS_MAJOR_EVENTS = 5;
    private static final String SEPARATOR = "|";

    private StatusData() {}

    static final class Source {
        final String kind;
        final String name;
        final String url;

        Source(String kind, String name, String url) {
            this.kind = kind;
            this.name = name;
            this.url = url;
        }

        boolean custom() {
            for (Source s : CATALOG) if (s.name.equals(name)) return false;
            return true;
        }
    }

    private static Source page(String name, String base) {
        return new Source(STATUSPAGE, name, base);
    }

    /** Each checked and answering on 1 Nov 2026. */
    static final Source[] CATALOG = {
            page("GitHub", "https://www.githubstatus.com"),
            page("Cloudflare", "https://www.cloudflarestatus.com"),
            new Source(AWS, "AWS", "https://health.aws.amazon.com/public/currentevents"),
            new Source(GOOGLE, "Google Cloud", "https://status.cloud.google.com/incidents.json"),
            new Source(AZURE, "Azure", "https://azure.status.microsoft/en-us/status/feed/"),
            page("Claude", "https://status.claude.com"),
            page("OpenAI", "https://status.openai.com"),
            page("Discord", "https://discordstatus.com"),
            new Source(SLACK, "Slack", "https://slack-status.com/api/v2.0.0/current"),
            page("Atlassian", "https://status.atlassian.com"),
            page("Bitbucket", "https://bitbucket.status.atlassian.com"),
            page("CircleCI", "https://status.circleci.com"),
            page("DigitalOcean", "https://status.digitalocean.com"),
            page("Docker", "https://www.dockerstatus.com"),
            page("Dropbox", "https://status.dropbox.com"),
            page("HashiCorp", "https://status.hashicorp.com"),
            page("Linode", "https://status.linode.com"),
            page("Netlify", "https://www.netlifystatus.com"),
            page("npm", "https://status.npmjs.org"),
            page("Reddit", "https://www.redditstatus.com"),
            page("Sentry", "https://status.sentry.io"),
            page("Shopify", "https://status.shopify.com"),
            page("Supabase", "https://status.supabase.com"),
            page("Twilio", "https://status.twilio.com"),
            page("Twitch", "https://status.twitch.tv"),
            page("Vercel", "https://www.vercel-status.com"),
            page("Zoom", "https://status.zoom.us"),
    };

    private static final String[] STARTING = {"GitHub", "Cloudflare", "AWS", "Google Cloud", "Azure", "Claude", "OpenAI", "Discord"};

    static List<Source> startingSources() {
        List<Source> out = new ArrayList<>();
        for (String name : STARTING) out.add(fromCatalog(name));
        return out;
    }

    private static Source fromCatalog(String name) {
        for (Source s : CATALOG) if (s.name.equals(name)) return s;
        throw new IllegalArgumentException(name);
    }

    /** One a line: kind|name|address. */
    static String save(List<Source> sources) {
        StringBuilder out = new StringBuilder();
        for (Source s : sources) out.append(s.kind).append(SEPARATOR).append(s.name).append(SEPARATOR).append(s.url).append('\n');
        return out.toString();
    }

    /** Lines that do not read are dropped; none saved yet means the starting set. */
    static List<Source> load(String stored) {
        if (stored == null) return startingSources();
        List<Source> out = new ArrayList<>();
        for (String line : stored.split("\n")) {
            String[] parts = line.split("\\|", 3);
            if (parts.length == 3 && parts[2].startsWith("https://") && out.size() < MAX_CHECKS) out.add(new Source(parts[0], parts[1], parts[2]));
        }
        return out;
    }

    /** What an owner typed, as an https address with no trailing slash or Statuspage API path; empty if it is not usable. */
    static String normalise(String typed) {
        // The box starts with https://, so pasting a whole address doubles it.
        String url = typed.trim().replaceFirst("^(https?://)+(?=https?://)", "");
        if (url.isEmpty() || url.equals("https://") || url.contains(" ") || url.contains(SEPARATOR)) return "";
        if (url.startsWith("http://")) url = "https://" + url.substring(7);
        else if (!url.startsWith("https://")) url = "https://" + url;
        int api = url.indexOf("/api/v2/");
        if (api > 0) url = url.substring(0, api);
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        return url.indexOf('.') > 0 ? url : "";
    }

    /** The name a Statuspage site gives itself, without the word most of them add. */
    static String pageName(String json) throws JSONException {
        JSONObject page = new JSONObject(json).optJSONObject("page");
        String name = page == null ? "" : page.optString("name", "");
        return name.replaceAll("(?i)\\s+(status|services)$", "").trim();
    }

    /** One reading of a page that is not on Statuspage. */
    static final class Reading {
        final String indicator;
        final String description;
        final Parsers.Summary summary;

        Reading(String indicator, String description, Parsers.Summary summary) {
            this.indicator = indicator;
            this.description = description;
            this.summary = summary;
        }
    }

    static Reading read(String kind, byte[] body, long now) throws JSONException, IOException {
        switch (kind) {
            case AWS: return readAws(decode(body));
            case GOOGLE: return readGoogle(decode(body));
            case SLACK: return readSlack(decode(body));
            case AZURE: return readAzure(body);
            default: return readFeed(body, now);
        }
    }

    /** AWS answers in UTF-16 with a byte order mark; the others in UTF-8. */
    static String decode(byte[] body) {
        boolean wide = body.length >= 2 && ((body[0] == (byte) 0xFE && body[1] == (byte) 0xFF) || (body[0] == (byte) 0xFF && body[1] == (byte) 0xFE));
        return new String(body, wide ? StandardCharsets.UTF_16 : StandardCharsets.UTF_8);
    }

    private static String plural(int n, String one) {
        return n + " " + one + (n == 1 ? "" : "s");
    }

    private static Reading readAws(String json) throws JSONException {
        JSONArray events = new JSONArray(json);
        Parsers.Summary summary = new Parsers.Summary();
        StringBuilder regions = new StringBuilder();
        for (int i = 0; i < events.length(); i++) {
            JSONObject e = events.getJSONObject(i);
            String region = e.optString("region_name", "");
            JSONArray log = e.optJSONArray("event_log");
            JSONObject latest = log == null || log.length() == 0 ? null : log.getJSONObject(log.length() - 1);
            String update = latest == null ? "" : latest.optString("message", "");
            summary.incidents.add(new Parsers.Incident(e.optString("service_name", "") + ", " + region, e.optString("summary", ""), "open", update));
            if (!region.isEmpty()) regions.append(regions.length() == 0 ? "" : ", ").append(region);
        }
        int open = summary.incidents.size();
        if (open == 0) return new Reading("none", "No open events", summary);
        return new Reading(open >= AWS_MAJOR_EVENTS ? "major" : "minor", plural(open, "regional event") + ": " + regions, summary);
    }

    private static Reading readGoogle(String json) throws JSONException {
        JSONArray incidents = new JSONArray(json);
        Parsers.Summary summary = new Parsers.Summary();
        boolean high = false;
        for (int i = 0; i < incidents.length(); i++) {
            JSONObject o = incidents.getJSONObject(i);
            // The list keeps old incidents too: only one with no end is still open.
            if (o.has("end") && !o.isNull("end")) continue;
            String severity = o.optString("severity", "");
            high |= "high".equals(severity);
            JSONObject latest = o.optJSONObject("most_recent_update");
            summary.incidents.add(new Parsers.Incident(o.optString("external_desc", ""), severity, o.optString("status_impact", "").replace('_', ' ').toLowerCase(Locale.ROOT),
                    latest == null ? "" : latest.optString("text", "")));
        }
        int open = summary.incidents.size();
        if (open == 0) return new Reading("none", "No open incidents", summary);
        return new Reading(high ? "major" : "minor", plural(open, "open incident"), summary);
    }

    private static Reading readSlack(String json) throws JSONException {
        JSONArray incidents = new JSONObject(json).optJSONArray("active_incidents");
        Parsers.Summary summary = new Parsers.Summary();
        boolean outage = false;
        for (int i = 0; incidents != null && i < incidents.length(); i++) {
            JSONObject o = incidents.getJSONObject(i);
            outage |= "outage".equals(o.optString("type"));
            summary.incidents.add(new Parsers.Incident(o.optString("title", ""), o.optString("type", ""), o.optString("status", ""), ""));
        }
        int open = summary.incidents.size();
        if (open == 0) return new Reading("none", "No active incidents", summary);
        return new Reading(outage ? "major" : "minor", plural(open, "active incident"), summary);
    }

    /** Azure's feed only lists what is happening now, so an empty one is good news. */
    private static Reading readAzure(byte[] body) throws IOException {
        List<Headline> items = FeedParser.parse(new ByteArrayInputStream(body), "Azure").items;
        Parsers.Summary summary = new Parsers.Summary();
        for (Headline h : items) summary.incidents.add(new Parsers.Incident(h.title, "", "open", h.summary));
        if (items.isEmpty()) return new Reading("none", "No active events", summary);
        return new Reading("major", plural(items.size(), "active event"), summary);
    }

    /** A feed only says what it has posted lately, which may be an all-clear, so it never claims an outage. */
    private static Reading readFeed(byte[] body, long now) throws IOException {
        List<Headline> items = FeedParser.parse(new ByteArrayInputStream(body), "Feed").items;
        Parsers.Summary summary = new Parsers.Summary();
        for (Headline h : items) {
            if (h.time > 0 && now - h.time < FEED_RECENT_MS) summary.incidents.add(new Parsers.Incident(h.title, "", "posted " + Parsers.ago(h.time, now) + " ago", h.summary));
        }
        int recent = summary.incidents.size();
        if (recent == 0) return new Reading("none", "Nothing posted in 24 hours", summary);
        return new Reading("minor", plural(recent, "update") + " in 24 hours", summary);
    }
}
