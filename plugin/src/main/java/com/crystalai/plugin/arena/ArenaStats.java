package com.crystalai.plugin.arena;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.Bukkit;

/** Totals across all arenas since start, for /cai arena status. */
final class ArenaStats {

    private long startNanos;
    private int arenaCount;
    long ticks;
    private long episodes;
    private long winsA;
    private long winsB;
    private long timeouts;
    private long aborted;
    private long selfKills;
    private long episodeTicks;
    private long totems;
    private double damageDealt;

    void start(int arenaCount) {
        this.arenaCount = arenaCount;
        startNanos = System.nanoTime();
        ticks = episodes = winsA = winsB = timeouts = aborted = selfKills = episodeTicks = totems = 0;
        damageDealt = 0;
    }

    void record(Arena arena, Arena.Outcome outcome, int length, EpisodeTally tally, boolean selfKill) {
        switch (outcome) {
            case WIN_A -> winsA++;
            case WIN_B -> winsB++;
            case TIMEOUT -> timeouts++;
            case ABORTED -> {
                aborted++;
                return;
            }
        }
        episodes++;
        episodeTicks += length;
        totems += tally.totemsPopped[0] + tally.totemsPopped[1];
        damageDealt += tally.dealt[0] + tally.dealt[1];
        if (selfKill) {
            selfKills++;
        }
    }

    List<String> summary() {
        double seconds = Math.max(1e-9, (System.nanoTime() - startNanos) / 1e9);
        List<String> lines = new ArrayList<>();
        lines.add(String.format(Locale.ROOT, "§b%d arena §7| %.0f sn | %d tick (%.1f tick/sn) | MSPT %.1f",
                arenaCount, seconds, ticks, ticks / seconds, Bukkit.getServer().getAverageTickTime()));
        if (episodes == 0) {
            lines.add("§7Henüz biten bölüm yok.");
        } else {
            lines.add(String.format(Locale.ROOT,
                    "§f%d bölüm §7(%.1f/dk) | A %d, B %d, zaman aşımı %d, iptal %d | kendi kristaliyle ölüm %d",
                    episodes, episodes / seconds * 60, winsA, winsB, timeouts, aborted, selfKills));
            lines.add(String.format(Locale.ROOT, "§7ortalama bölüm %.0f tick | bölüm başı totem %.1f, hasar %.1f",
                    (double) episodeTicks / episodes, (double) totems / episodes, damageDealt / episodes));
        }
        return lines;
    }
}
