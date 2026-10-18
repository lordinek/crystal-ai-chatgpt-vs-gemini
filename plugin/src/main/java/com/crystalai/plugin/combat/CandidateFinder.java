package com.crystalai.plugin.combat;

import com.crystalai.plugin.action.ActionResult;
import com.crystalai.plugin.action.NpcActions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Finds the crystal spots worth offering to the model: scans bases around the target, keeps the
 * ones where a crystal could stand (existing obsidian/bedrock, or a spot obsidian could go),
 * estimates damage to the target and to the NPC, and returns the best N.
 * Placeable-now spots rank first; the model learns which one (if any) to use.
 */
public final class CandidateFinder {

    public record Settings(int count, int horizontalRadius, int minDy, int maxDy, int exactTopK,
                           double selfDamageWeight, boolean allowNewObsidian) {

        public static Settings from(ConfigurationSection section) {
            return new Settings(
                    section.getInt("count", 8),
                    section.getInt("horizontal-radius", 4),
                    section.getInt("min-dy", -3),
                    section.getInt("max-dy", 1),
                    section.getInt("exact-top-k", 24),
                    section.getDouble("self-damage-weight", 0.5),
                    section.getBoolean("allow-new-obsidian", true));
        }
    }

    private final Settings settings;
    private final NpcActions actions;

    public CandidateFinder(Settings settings, NpcActions actions) {
        this.settings = settings;
        this.actions = actions;
    }

    public List<CrystalCandidate> find(ServerPlayer self, LivingEntity target) {
        ServerLevel level = self.level();
        BlockPos feet = target.blockPosition();

        // 1) Structurally valid bases, with a cheap full-exposure damage bound.
        List<Rough> rough = new ArrayList<>();
        int r = settings.horizontalRadius();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dy = settings.minDy(); dy <= settings.maxDy(); dy++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    // base sits one below the crystal; dy is the crystal's height relative to the target's feet
                    cursor.set(feet.getX() + dx, feet.getY() + dy - 1, feet.getZ() + dz);
                    boolean hasBase = NpcActions.isCrystalBase(level.getBlockState(cursor));
                    if (!hasBase && !(settings.allowNewObsidian() && level.getBlockState(cursor).canBeReplaced())) {
                        continue;
                    }
                    BlockPos base = cursor.immutable();
                    if (!hasBase && !hasSolidNeighbour(level, base)) {
                        continue; // obsidian needs something to be placed against
                    }
                    if (!NpcActions.crystalSpaceFree(level, base)) {
                        continue;
                    }
                    Vec3 center = DamageCalculator.crystalPosition(base);
                    float bound = DamageCalculator.crystalDamageUpperBound(target, center);
                    if (bound > 0.0F) {
                        rough.add(new Rough(base, hasBase, center, bound));
                    }
                }
            }
        }

        // 2) Exact damage (with exposure raycasts) only for the most promising ones.
        rough.sort(Comparator.comparingDouble(Rough::bound).reversed());
        List<CrystalCandidate> exact = new ArrayList<>();
        for (Rough candidate : rough.subList(0, Math.min(settings.exactTopK(), rough.size()))) {
            ActionResult plan = actions.planCrystal(self, candidate.base(), settings.allowNewObsidian());
            float toTarget = DamageCalculator.crystalDamage(target, candidate.center(), false);
            float toSelf = DamageCalculator.crystalDamage(self, candidate.center(), false);
            double score = toTarget - settings.selfDamageWeight() * toSelf;
            exact.add(new CrystalCandidate(candidate.base(), candidate.center(), candidate.hasBase(),
                    plan.success(), plan.reason(), toTarget, toSelf, score));
        }

        exact.sort(Comparator.comparing(CrystalCandidate::placeable).reversed()
                .thenComparing(Comparator.comparingDouble(CrystalCandidate::score).reversed()));
        return exact.subList(0, Math.min(settings.count(), exact.size()));
    }

    private static boolean hasSolidNeighbour(ServerLevel level, BlockPos pos) {
        for (Direction direction : Direction.values()) {
            if (!level.getBlockState(pos.relative(direction)).canBeReplaced()) {
                return true;
            }
        }
        return false;
    }

    private record Rough(BlockPos base, boolean hasBase, Vec3 center, float bound) {
    }
}
