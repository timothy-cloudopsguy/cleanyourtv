package io.github.timothy_cloudopsguy.opsdeck;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What kind of aircraft a type code means, its outline for the map and a small 3D model for the card. Everything is
 * generated from the numbers here, so there are no image files to load and all of it fits in a few kilobytes. The shapes
 * stand for a class of aircraft, not the exact type. No Android, so the tests can run it.
 */
final class Aircraft {
    static final int UNKNOWN = 0;
    static final int NARROW = 1;
    static final int WIDE = 2;
    static final int FOUR_JET = 3;
    static final int AIRLIFT = 4;
    static final int REGIONAL = 5;
    static final int BIZJET = 6;
    static final int TURBOPROP = 7;
    static final int LIGHT = 8;
    static final int HELICOPTER = 9;
    static final int FIGHTER = 10;
    static final int DRONE = 11;
    static final int KINDS = 12;

    private static final String[] NAMES = {"Type not reported", "Narrow-body jet", "Wide-body jet", "Four-engine jet",
            "Military airlifter", "Regional jet", "Business jet", "Twin turboprop", "Light aircraft", "Helicopter",
            "Fast jet", "Drone"};

    private static final Map<String, Integer> TYPES = new HashMap<>();

    private static void types(int kind, String codes) {
        for (String code : codes.split(" ")) TYPES.put(code, kind);
    }

    static {
        types(NARROW, "A19N A20N A21N A318 A319 A320 A321 B37M B38M B39M B3XM B731 B732 B733 B734 B735 B736 B737 B738 B739 "
                + "B752 B753 BCS1 BCS3 C919 P8 E170 E75L E75S E190 E195 E290 E295 SU95 T204 B461 B462 B463");
        types(WIDE, "A306 A30B A310 A332 A333 A337 A338 A339 A359 A35K B762 B763 B764 B772 B77L B773 B77W B778 B779 B788 B789 "
                + "B78X K46 MD11 DC10 L101 IL86 A3ST");
        types(FOUR_JET, "A388 A342 A343 A345 A346 B741 B742 B743 B744 B748 B74S B703 K35E K35R C135 R135 E3TF E3CF E6 C5 C5M "
                + "IL96 P1");
        types(AIRLIFT, "C17 C130 C30J A400 C295 CN35 C27J AN12 AN26 AN124 AN225 IL76 C2");
        types(REGIONAL, "CRJ1 CRJ2 CRJ7 CRJ9 CRJX E135 E145 E35L E45X F70 F100 B712 MD81 MD82 MD83 MD87 MD88 MD90 AJ27");
        types(BIZJET, "GLF4 GLF5 GLF6 GA5C GA6C GL5T GL7T GLEX G150 G280 CL30 CL35 CL60 LJ31 LJ35 LJ40 LJ45 LJ60 LJ75 C25A C25B "
                + "C25C C25M C500 C510 C525 C550 C55B C560 C56X C650 C680 C68A C700 C750 E50P E55P E545 E550 FA7X FA8X F900 FA50 "
                + "F2TH H25B PC24 HDJT PRM1 BE40 SF50");
        types(TURBOPROP, "AT43 AT45 AT46 AT72 AT73 AT75 AT76 DH8A DH8B DH8C DH8D SF34 SB20 B190 BE20 BE30 BE9L BE99 E120 D228 "
                + "D328 DHC6 L410 P180 PA31 PAY2 PAY3 C441 SW4 JS41");
        types(LIGHT, "C150 C152 C162 C172 C177 C180 C182 C185 C206 C207 C208 C210 PA18 PA22 PA24 PA28 P28A P28R P28B PA32 PA34 PA44 "
                + "PA46 SR20 SR22 BE33 BE35 BE36 BE55 BE58 DA20 DA40 DA42 DA62 M20P M20T PC12 PC21 TEX2 T6 TBM7 TBM8 TBM9 RV7 RV8 "
                + "J3 AA5 C42 DV20 Z42 G115 P149");
        types(HELICOPTER, "H60 S61 S70 S76 S92 H47 H53 H64 UH1 UH1Y EC20 EC30 EC35 EC45 EC55 EC75 H135 H145 H160 H175 B06 B06T "
                + "B212 B407 B412 B429 B505 AS32 AS50 AS55 AS65 A109 A119 A139 A169 A189 R22 R44 R66 NH90 V22 PUMA MI8 LYNX");
        types(FIGHTER, "F15 F16 F18 F18H F18S F22 F35 EUFI RFAL A10 TOR F5 T38 T45 T7 HAWK M346 L39 SU27 SU30 SU35 MG29 MIR2 JF17 "
                + "J10 GRIF AJET");
        types(DRONE, "Q1 Q4 Q9 MQ9 RQ4 HRON TB2");
    }

    private Aircraft() {}

    /** From the ICAO type code, then the broadcast's emitter category when the code is missing or not one we know. */
    static int kind(String type, String category) {
        Integer known = TYPES.get(type.toUpperCase(Locale.ROOT));
        if (known != null) return known;
        switch (category) {
            case "A1": return LIGHT;
            case "A2": return TURBOPROP;
            case "A3": case "A4": return NARROW;
            case "A5": return WIDE;
            case "A6": return FIGHTER;
            case "A7": return HELICOPTER;
            case "B1": case "B4": return LIGHT;
            case "B6": return DRONE;
            default: return UNKNOWN;
        }
    }

    /** Ground vehicles and towers broadcast too, some flagged as military: none of them are aircraft. */
    static boolean notAnAircraft(String type, String category) {
        return "TWR".equals(type) || "GND".equals(type) || category.startsWith("C");
    }

    static String name(int kind) {
        return NAMES[kind];
    }

    /** Half an outline for the map, nose up: x, y pairs from the nose round the right side to the tail, on the axis at both ends. */
    private static final float[][] OUTLINES = {
            {0, -15, 11, 12, 0, 6},
            {0, -21, 2.2f, -18, 2.6f, -5, 20, 7, 20, 9.5f, 2.6f, 3.5f, 2.2f, 13, 8, 18, 8, 20, 1, 18, 0, 21},
            {0, -26, 3.4f, -22, 3.6f, -7, 27, 8, 27, 11, 3.6f, 3, 3, 17, 11, 23, 11, 25, 1.2f, 23, 0, 26},
            {0, -29, 3.6f, -25, 3.8f, -9, 31, 8, 31, 11, 3.8f, 2, 3, 19, 12, 25, 12, 27, 1.2f, 25, 0, 29},
            {0, -24, 3.6f, -21, 4, -10, 28, -4, 28, -0.5f, 4, -2, 3, 14, 11, 19, 11, 22, 1, 20, 0, 24},
            {0, -20, 2, -17, 2.2f, -3, 16, 5, 16, 7, 2.2f, 3, 2, 13, 7, 18, 7, 20, 1, 18, 0, 20},
            {0, -16, 1.6f, -13, 1.8f, -2, 12, 4, 12, 5.5f, 1.8f, 2, 1.4f, 10, 5, 14, 5, 15.5f, 0.8f, 14, 0, 16},
            {0, -18, 1.8f, -15, 2, -4, 20, -2, 20, 0.5f, 2, 1, 1.5f, 12, 7, 15, 7, 17, 0.8f, 15.5f, 0, 18},
            {0, -13, 1.3f, -10, 1.4f, -4, 15, -3, 15, -0.5f, 1.4f, 0, 1, 9, 5, 10, 5, 12, 0.6f, 11, 0, 13},
            {0, -13, 4, -9, 4.2f, -2, 1.2f, 5, 1, 14, 4, 15, 4, 17, 0, 17},
            {0, -22, 1.4f, -16, 2.2f, -6, 13, 7, 13, 9.5f, 3.5f, 8, 3, 13, 7.5f, 16, 7.5f, 18, 1.4f, 16, 0, 18},
            {0, -13, 1.3f, -10, 1.3f, -2, 28, 0, 28, 1.4f, 1.3f, 3, 1, 11, 5, 14, 5, 15, 0, 13},
    };

    /** Engines and rotor blades, drawn on each side of the outline: centre x, centre y, half width, half height. */
    private static final float[][] PODS = {
            {},
            {8, -2, 1.4f, 2.6f},
            {11, -3, 2, 3.4f},
            {11, -5, 1.8f, 3, 21, 1, 1.8f, 3},
            {10, -9.5f, 1.7f, 3, 19, -7, 1.7f, 3},
            {3.6f, 9, 1.5f, 3.2f},
            {3, 7, 1.2f, 2.5f},
            {7, -5, 1.3f, 3},
            {},
            {0, -5, 14, 0.9f, 0, -5, 0.9f, 14},
            {},
            {},
    };

    static float[] outline(int kind) {
        return OUTLINES[kind];
    }

    static float[] pods(int kind) {
        return PODS[kind];
    }

    /** A flat-shaded model as vertices (x right, y forward, z up, about 1 long) and four-sided faces. */
    static final class Mesh {
        final float[] vertices;
        final int[] faces;

        Mesh(float[] vertices, int[] faces) {
            this.vertices = vertices;
            this.faces = faces;
        }
    }

    private static final class Builder {
        final List<float[]> points = new ArrayList<>();
        final List<int[]> quads = new ArrayList<>();

        int point(float x, float y, float z) {
            points.add(new float[]{x, y, z});
            return points.size() - 1;
        }

        /** A body of round sections along y: y, radius pairs from front to back. */
        void tube(float cx, float cz, int sides, float... sections) {
            int first = points.size();
            int rings = sections.length / 2;
            for (int r = 0; r < rings; r++) {
                for (int s = 0; s < sides; s++) {
                    double angle = 2 * Math.PI * s / sides;
                    float radius = sections[r * 2 + 1];
                    point(cx + (float) Math.cos(angle) * radius, sections[r * 2], cz + (float) Math.sin(angle) * radius);
                }
            }
            for (int r = 0; r + 1 < rings; r++) {
                for (int s = 0; s < sides; s++) {
                    int next = (s + 1) % sides;
                    quads.add(new int[]{first + r * sides + s, first + r * sides + next,
                            first + (r + 1) * sides + next, first + (r + 1) * sides + s});
                }
            }
        }

        /** A thin slab between a root and a tip chord: each edge as x, front y, back y, z. */
        void slab(float[] root, float[] tip, float thickness) {
            int a = point(root[0], root[1], root[3] + thickness);
            int b = point(tip[0], tip[1], tip[3] + thickness / 2);
            int c = point(tip[0], tip[2], tip[3] + thickness / 2);
            int d = point(root[0], root[2], root[3] + thickness);
            int e = point(root[0], root[1], root[3] - thickness);
            int f = point(tip[0], tip[1], tip[3] - thickness / 2);
            int g = point(tip[0], tip[2], tip[3] - thickness / 2);
            int h = point(root[0], root[2], root[3] - thickness);
            quads.add(new int[]{a, b, c, d});
            quads.add(new int[]{e, f, g, h});
            quads.add(new int[]{a, b, f, e});
            quads.add(new int[]{c, d, h, g});
            quads.add(new int[]{b, c, g, f});
        }

        /** A pair of wings or tailplanes, mirrored. */
        void wings(float rootX, float rootFront, float rootBack, float rootZ, float tipX, float tipFront, float tipBack, float tipZ, float thickness) {
            slab(new float[]{rootX, rootFront, rootBack, rootZ}, new float[]{tipX, tipFront, tipBack, tipZ}, thickness);
            slab(new float[]{-rootX, rootFront, rootBack, rootZ}, new float[]{-tipX, tipFront, tipBack, tipZ}, thickness);
        }

        /** An upright fin, its base and top as front y, back y, z. */
        void fin(float x, float baseFront, float baseBack, float baseZ, float topFront, float topBack, float topZ) {
            int a = point(x, baseFront, baseZ);
            int b = point(x, topFront, topZ);
            int c = point(x, topBack, topZ);
            int d = point(x, baseBack, baseZ);
            quads.add(new int[]{a, b, c, d});
        }

        void jet(float x, float y, float z, float radius, float length) {
            tube(x, z, 6, y + length / 2, radius * 0.85f, y, radius, y - length / 2, radius * 0.7f);
            if (x != 0) tube(-x, z, 6, y + length / 2, radius * 0.85f, y, radius, y - length / 2, radius * 0.7f);
        }

        /** Nose at y=0.5, tail at y=-0.5. */
        void fuselage(float radius, float tailZ) {
            int first = points.size();
            tube(0, 0, 8, 0.5f, 0.004f, 0.46f, radius * 0.6f, 0.38f, radius, -0.18f, radius, -0.38f, radius * 0.6f, -0.5f, radius * 0.18f);
            // The rear lifts toward the fin, as real tails do.
            for (int i = first; i < points.size(); i++) {
                float[] p = points.get(i);
                if (p[1] < -0.18f) p[2] += tailZ * (-0.18f - p[1]) / 0.32f;
            }
        }

        Mesh build() {
            float[] vertices = new float[points.size() * 3];
            for (int i = 0; i < points.size(); i++) System.arraycopy(points.get(i), 0, vertices, i * 3, 3);
            int[] faces = new int[quads.size() * 4];
            for (int i = 0; i < quads.size(); i++) System.arraycopy(quads.get(i), 0, faces, i * 4, 4);
            return new Mesh(vertices, faces);
        }
    }

    static Mesh model(int kind) {
        Builder m = new Builder();
        switch (kind) {
            case WIDE:
                m.fuselage(0.058f, 0.03f);
                m.wings(0.05f, 0.14f, -0.06f, -0.03f, 0.48f, -0.14f, -0.2f, 0.03f, 0.012f);
                m.wings(0.01f, -0.34f, -0.45f, 0.04f, 0.18f, -0.44f, -0.49f, 0.05f, 0.005f);
                m.fin(0, -0.3f, -0.47f, 0.06f, -0.44f, -0.5f, 0.2f);
                m.jet(0.17f, 0.08f, -0.06f, 0.034f, 0.11f);
                break;
            case FOUR_JET:
                m.fuselage(0.056f, 0.03f);
                m.wings(0.05f, 0.14f, -0.08f, -0.03f, 0.47f, -0.16f, -0.21f, 0.04f, 0.012f);
                m.wings(0.01f, -0.33f, -0.44f, 0.04f, 0.17f, -0.43f, -0.48f, 0.05f, 0.005f);
                m.fin(0, -0.3f, -0.47f, 0.06f, -0.43f, -0.5f, 0.19f);
                m.jet(0.17f, 0.06f, -0.05f, 0.026f, 0.09f);
                m.jet(0.31f, -0.04f, -0.03f, 0.024f, 0.08f);
                break;
            case AIRLIFT:
                m.fuselage(0.075f, 0.05f);
                m.wings(0.04f, 0.12f, -0.04f, 0.075f, 0.5f, 0.02f, -0.04f, 0.05f, 0.012f);
                m.wings(0.01f, -0.38f, -0.47f, 0.22f, 0.17f, -0.42f, -0.49f, 0.22f, 0.005f);
                m.fin(0, -0.28f, -0.47f, 0.08f, -0.4f, -0.49f, 0.22f);
                m.jet(0.17f, 0.12f, 0.03f, 0.028f, 0.09f);
                m.jet(0.32f, 0.08f, 0.04f, 0.026f, 0.09f);
                break;
            case REGIONAL:
            case BIZJET:
                m.fuselage(0.05f, 0.02f);
                m.wings(0.04f, 0.1f, -0.08f, -0.03f, 0.42f, -0.1f, -0.16f, 0f, 0.01f);
                m.wings(0.005f, -0.42f, -0.5f, 0.2f, 0.15f, -0.47f, -0.52f, 0.2f, 0.004f);
                m.fin(0, -0.28f, -0.47f, 0.05f, -0.42f, -0.5f, 0.2f);
                m.jet(0.075f, -0.26f, 0.05f, 0.03f, 0.12f);
                break;
            case TURBOPROP:
                m.fuselage(0.055f, 0.03f);
                m.wings(0.03f, 0.1f, -0.03f, 0.055f, 0.48f, 0.05f, 0f, 0.05f, 0.009f);
                m.wings(0.005f, -0.38f, -0.46f, 0.1f, 0.15f, -0.41f, -0.47f, 0.1f, 0.004f);
                m.fin(0, -0.26f, -0.46f, 0.05f, -0.4f, -0.48f, 0.19f);
                m.jet(0.16f, 0.1f, 0.03f, 0.024f, 0.14f);
                m.slab(new float[]{0.16f, 0.18f, 0.17f, 0.11f}, new float[]{0.16f, 0.18f, 0.17f, -0.05f}, 0.004f);
                m.slab(new float[]{-0.16f, 0.18f, 0.17f, 0.11f}, new float[]{-0.16f, 0.18f, 0.17f, -0.05f}, 0.004f);
                break;
            case LIGHT:
                m.fuselage(0.07f, 0.02f);
                m.wings(0.04f, 0.14f, 0f, 0.07f, 0.5f, 0.13f, 0.03f, 0.07f, 0.008f);
                m.wings(0.005f, -0.38f, -0.47f, 0.05f, 0.17f, -0.39f, -0.47f, 0.05f, 0.004f);
                m.fin(0, -0.28f, -0.47f, 0.05f, -0.4f, -0.49f, 0.22f);
                m.slab(new float[]{0, 0.51f, 0.5f, 0.1f}, new float[]{0, 0.51f, 0.5f, -0.1f}, 0.004f);
                break;
            case HELICOPTER:
                m.tube(0, 0, 8, 0.5f, 0.02f, 0.42f, 0.09f, 0.18f, 0.11f, -0.02f, 0.08f, -0.1f, 0.025f, -0.46f, 0.015f);
                m.tube(0, 0.14f, 6, 0.06f, 0.012f, 0f, 0.012f);
                m.wings(0.01f, 0.05f, 0.01f, 0.14f, 0.48f, 0.05f, 0.01f, 0.13f, 0.002f);
                m.slab(new float[]{0, 0.5f, 0.01f, 0.14f}, new float[]{0, -0.42f, -0.46f, 0.14f}, 0.002f);
                m.fin(0, -0.38f, -0.47f, 0, -0.45f, -0.5f, 0.11f);
                m.slab(new float[]{0.03f, -0.46f, -0.47f, 0.08f}, new float[]{0.03f, -0.46f, -0.47f, -0.06f}, 0.003f);
                break;
            case FIGHTER:
                m.fuselage(0.045f, 0f);
                m.wings(0.03f, 0.12f, -0.26f, -0.01f, 0.3f, -0.22f, -0.3f, -0.015f, 0.006f);
                m.wings(0.02f, -0.34f, -0.46f, -0.01f, 0.14f, -0.44f, -0.5f, -0.015f, 0.004f);
                m.fin(0.04f, -0.26f, -0.46f, 0.03f, -0.42f, -0.5f, 0.17f);
                m.fin(-0.04f, -0.26f, -0.46f, 0.03f, -0.42f, -0.5f, 0.17f);
                m.jet(0, -0.46f, 0, 0.032f, 0.06f);
                break;
            case DRONE:
                m.fuselage(0.05f, 0f);
                m.wings(0.03f, 0.04f, -0.06f, 0.04f, 0.62f, 0f, -0.04f, 0.06f, 0.005f);
                m.wings(0.005f, -0.4f, -0.48f, 0.04f, 0.13f, -0.45f, -0.49f, 0.11f, 0.003f);
                m.jet(0, -0.2f, 0.065f, 0.028f, 0.14f);
                break;
            default:
                m.fuselage(0.052f, 0.03f);
                m.wings(0.04f, 0.12f, -0.06f, -0.03f, 0.47f, -0.13f, -0.18f, 0.03f, 0.011f);
                m.wings(0.01f, -0.34f, -0.44f, 0.04f, 0.17f, -0.43f, -0.48f, 0.05f, 0.005f);
                m.fin(0, -0.3f, -0.47f, 0.05f, -0.44f, -0.5f, 0.2f);
                m.jet(0.16f, 0.09f, -0.055f, 0.028f, 0.09f);
                break;
        }
        return m.build();
    }
}
