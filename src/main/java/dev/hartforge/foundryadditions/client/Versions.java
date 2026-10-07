package dev.hartforge.foundryadditions.client;

public final class Versions {

    private Versions() {}

    public static boolean atLeast(String have, String min) {
        return compare(have, min) >= 0;
    }

    public static int compare(String a, String b) {
        long[] x = parts(a);
        long[] y = parts(b);
        int n = Math.max(x.length, y.length);
        for (int i = 0; i < n; i++) {
            long p = i < x.length ? x[i] : 0;
            long q = i < y.length ? y[i] : 0;
            if (p != q) return Long.compare(p, q);
        }
        return 0;
    }

    // "1.10.3-beta" and "v2.0.1+build.7" both show up in the wild
    private static long[] parts(String v) {
        String s = v.trim();
        if (s.startsWith("v") || s.startsWith("V")) s = s.substring(1);
        String[] segs = s.split("\\.");
        long[] out = new long[segs.length];
        for (int i = 0; i < segs.length; i++) {
            int end = 0;
            // 18 digits max so parseLong can't overflow
            while (end < segs[i].length() && end < 18 && Character.isDigit(segs[i].charAt(end))) end++;
            out[i] = end == 0 ? 0 : Long.parseLong(segs[i].substring(0, end));
        }
        return out;
    }
}
