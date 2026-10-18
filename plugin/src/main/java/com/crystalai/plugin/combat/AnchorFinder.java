package com.crystalai.plugin.combat;

import com.crystalai.plugin.action.NpcActions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Anchor spots worth offering to the model: empty cells around the target where the NPC could
 * place a respawn anchor now, ranked like crystal candidates (damage to the target minus a share
 * of the damage to itself). Unlike crystals an anchor needs no special base, so it can go right at
 * the opponent's feet.
 */
public final class AnchorFinder {

    public record Settings(int count, int horizontalRadius, int minDy, int maxDy, int exactTopK,
                           double selfDamageWeight) {

        public static Settings from(ConfigurationSection section) {
            return new Settings(
                    section.getInt("count", 4),
                    section.getInt("horizontal-radius", 2),
                    section.getInt("min-dy", -1),
                    section.getInt("max-dy", 1),
                    section.getInt("exact-top-k", 8),
                    section.getDouble("self-damage-weight", 0.5));
        }
    }

    private final Settings settings;
    private final NpcActions actions;

    public AnchorFinder(Settings settings, NpcActions actions) {
        this.settings = settings;
        this.actions = actions;
    }

    public int count() {
        return settings.count();
    }

    public List<AnchorCandidate> find(ServerPlayer self, LivingEntity target) {
        ServerLevel level = self.level();
        BlockPos feet = target.blockPosition();
        List<AnchorCandidate> rough = new ArrayList<>();
        int r = settings.horizontalRadius();
        // Cheap filters before ranking: the best-damage cells are the ones the opponent stands in,
        // and they used to crowd every placeable cell out of the exact top-k.
        BlockState anchor = Blocks.RESPAWN_ANCHOR.defaultBlockState();
        Vec3 eye = self.getEyePosition();
        double maxReach = actions.settings().blockReach() + 1.0; // support face may be ~1 block closer
        for (int dy = settings.minDy(); dy <= settings.maxDy(); dy++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos pos = feet.offset(dx, dy, dz);
                    if (!level.getBlockState(pos).canBeReplaced()
                            || eye.distanceToSqr(pos.getCenter()) > maxReach * maxReach
                            || !level.isUnobstructed(anchor, pos, CollisionContext.empty())) {
                        continue;
                    }
                    float bound = DamageCalculator.anchorDamageUpperBound(target, pos);
                    if (bound > 0.0F) {
                        rough.add(new AnchorCandidate(pos, false, 0.0F, 0.0F, bound));
                    }
                }
            }
        }
        rough.sort(Comparator.comparingDouble(AnchorCandidate::score).reversed());
        List<AnchorCandidate> exact = new ArrayList<>();
        for (AnchorCandidate c : rough.subList(0, Math.min(settings.exactTopK(), rough.size()))) {
            boolean placeable = NpcActions.hasItem(self, net.minecraft.world.item.Items.RESPAWN_ANCHOR)
                    && actions.planBlock(self, c.pos(), Blocks.RESPAWN_ANCHOR.defaultBlockState()).result().success();
            float toTarget = DamageCalculator.anchorDamage(target, c.pos(), false);
            float toSelf = DamageCalculator.anchorDamage(self, c.pos(), false);
            exact.add(new AnchorCandidate(c.pos(), placeable, toTarget, toSelf,
                    toTarget - settings.selfDamageWeight() * toSelf));
        }
        exact.sort(Comparator.comparing(AnchorCandidate::placeable).reversed()
                .thenComparing(Comparator.comparingDouble(AnchorCandidate::score).reversed()));
        return exact.subList(0, Math.min(settings.count(), exact.size()));
    }
}
