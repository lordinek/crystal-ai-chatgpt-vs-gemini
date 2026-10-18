package com.crystalai.plugin.util;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Tiny main-thread profiler for the decision loop: named sections accumulate nanoseconds and
 * call counts; {@link #report} prints milliseconds per server tick and per call, then resets.
 */
public final class Profiler {

    private static final Map<String, long[]> SECTIONS = new LinkedHashMap<>();
    private static long ticks;

    private Profiler() {
    }

    public static long start() {
        return System.nanoTime();
    }

    public static void stop(String section, long startNanos) {
        long[] entry = SECTIONS.computeIfAbsent(section, key -> new long[2]);
        entry[0] += System.nanoTime() - startNanos;
        entry[1]++;
    }

    public static void tick() {
        ticks++;
    }

    /** One line per section: ms per tick, ms per call, calls per tick. Resets the counters. */
    public static String report() {
        StringBuilder out = new StringBuilder("profile over " + ticks + " ticks:");
        for (Map.Entry<String, long[]> e : SECTIONS.entrySet()) {
            long[] v = e.getValue();
            out.append(String.format(Locale.ROOT, "%n  %-18s %7.3f ms/tick  %7.4f ms/call  %6.1f calls/tick",
                    e.getKey(), v[0] / 1e6 / Math.max(1, ticks), v[0] / 1e6 / Math.max(1, v[1]),
                    (double) v[1] / Math.max(1, ticks)));
        }
        SECTIONS.clear();
        ticks = 0;
        return out.toString();
    }
}
