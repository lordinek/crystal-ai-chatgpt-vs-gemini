package com.crystalai.plugin.arena;

/** Per-side counters for the running episode. Index 0 = side A, 1 = side B. */
final class EpisodeTally {

    final double[] dealt = new double[2];
    final double[] taken = new double[2];
    final double[] selfDamage = new double[2];
    final int[] totemsPopped = new int[2];

    void damage(int victim, int attacker, double amount) {
        taken[victim] += amount;
        if (attacker == victim) {
            selfDamage[victim] += amount;
        } else if (attacker >= 0) {
            dealt[attacker] += amount;
        }
    }
}
