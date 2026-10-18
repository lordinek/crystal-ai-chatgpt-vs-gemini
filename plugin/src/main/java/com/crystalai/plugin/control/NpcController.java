package com.crystalai.plugin.control;

import com.crystalai.plugin.npc.CrystalNpc;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import org.bukkit.configuration.ConfigurationSection;

/** Chooses an NPC's action each decision step. Stage 5 adds the Python-driven controller. */
public interface NpcController {

    NpcAction decide(CrystalNpc self, ActionSpace.Context context, boolean[][] mask);

    /** Called when a new episode starts. */
    default void reset() {
    }

    /** Creates a controller by config name: python | idle | random. */
    static NpcController create(String name, ConfigurationSection randomSettings, long seed) {
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "python" -> new External();
            case "idle" -> new Idle();
            case "random" -> new RandomPolicy(new Random(seed), randomSettings.getDouble("act-probability", 0.3));
            default -> throw new IllegalArgumentException("Unknown controller: " + name + " (python | idle | random)");
        };
    }

    /** Marker: this side is driven by the trainer over the bridge (ArenaManager batches these). */
    final class External implements NpcController {
        @Override
        public NpcAction decide(CrystalNpc self, ActionSpace.Context context, boolean[][] mask) {
            throw new UnsupportedOperationException("External controllers are decided by the trainer");
        }
    }

    /** Stands still: the curriculum's stage-A dummy. */
    final class Idle implements NpcController {
        @Override
        public NpcAction decide(CrystalNpc self, ActionSpace.Context context, boolean[][] mask) {
            return NpcAction.IDLE;
        }
    }

    /**
     * Uniformly random among valid choices. Crystal/explode/util branches act only with
     * {@code actProbability}, otherwise they pick "none", so it does not spam every step.
     */
    final class RandomPolicy implements NpcController {
        private final Random random;
        private final double actProbability;

        public RandomPolicy(Random random, double actProbability) {
            this.random = random;
            this.actProbability = actProbability;
        }

        @Override
        public NpcAction decide(CrystalNpc self, ActionSpace.Context context, boolean[][] mask) {
            int[] choice = new int[mask.length];
            for (int branch = 0; branch < mask.length; branch++) {
                boolean gated = branch >= 3;
                if (gated && random.nextDouble() >= actProbability) {
                    continue; // 0 = none
                }
                List<Integer> valid = new ArrayList<>();
                for (int i = gated ? 1 : 0; i < mask[branch].length; i++) {
                    if (mask[branch][i]) {
                        valid.add(i);
                    }
                }
                choice[branch] = valid.isEmpty() ? 0 : valid.get(random.nextInt(valid.size()));
            }
            return NpcAction.of(choice);
        }
    }
}
