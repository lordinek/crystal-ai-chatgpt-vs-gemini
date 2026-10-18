package com.crystalai.plugin.combat;

import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Explosion exposure (fraction of the hitbox the centre can see) with one block treated as air.
 *
 * A respawn anchor is removed right before it explodes, so its own block never shadows the blast.
 * Predicting beforehand with ServerExplosion.getSeenPercent would count the anchor as a wall (all
 * rays start inside it). Same algorithm as ServerExplosion.getSeenPercent in 1.21.11 (checked in
 * the bytecode: grid step 1/(size*2+1), x/z centring offsets, COLLIDER clip, fluids ignored).
 */
public final class Exposure {

    private Exposure() {
    }

    public static float seenPercentIgnoring(Vec3 center, Entity entity, BlockPos ignored) {
        AABB box = entity.getBoundingBox();
        double stepX = 1.0 / ((box.maxX - box.minX) * 2.0 + 1.0);
        double stepY = 1.0 / ((box.maxY - box.minY) * 2.0 + 1.0);
        double stepZ = 1.0 / ((box.maxZ - box.minZ) * 2.0 + 1.0);
        double offsetX = (1.0 - Math.floor(1.0 / stepX) * stepX) / 2.0;
        double offsetZ = (1.0 - Math.floor(1.0 / stepZ) * stepZ) / 2.0;
        if (stepX < 0.0 || stepY < 0.0 || stepZ < 0.0) {
            return 0.0F;
        }
        BlockGetter world = new Without(entity.level(), ignored.immutable());
        int seen = 0;
        int total = 0;
        for (double fx = 0.0; fx <= 1.0; fx += stepX) {
            for (double fy = 0.0; fy <= 1.0; fy += stepY) {
                for (double fz = 0.0; fz <= 1.0; fz += stepZ) {
                    Vec3 point = new Vec3(Mth.lerp(fx, box.minX, box.maxX) + offsetX,
                            Mth.lerp(fy, box.minY, box.maxY),
                            Mth.lerp(fz, box.minZ, box.maxZ) + offsetZ);
                    if (world.clip(new ClipContext(point, center, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, entity))
                            .getType() == HitResult.Type.MISS) {
                        seen++;
                    }
                    total++;
                }
            }
        }
        return (float) seen / (float) total;
    }

    /** The level as it will be once {@code ignored} is gone. */
    private record Without(Level level, BlockPos ignored) implements BlockGetter {
        @Override
        public @Nullable BlockEntity getBlockEntity(BlockPos pos) {
            return pos.equals(ignored) ? null : level.getBlockEntity(pos);
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return pos.equals(ignored) ? Blocks.AIR.defaultBlockState() : level.getBlockState(pos);
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return pos.equals(ignored) ? Fluids.EMPTY.defaultFluidState() : level.getFluidState(pos);
        }

        // Paper additions to BlockGetter
        @Override
        public @Nullable BlockState getBlockStateIfLoaded(BlockPos pos) {
            return pos.equals(ignored) ? Blocks.AIR.defaultBlockState() : level.getBlockStateIfLoaded(pos);
        }

        @Override
        public @Nullable FluidState getFluidIfLoaded(BlockPos pos) {
            return pos.equals(ignored) ? Fluids.EMPTY.defaultFluidState() : level.getFluidIfLoaded(pos);
        }

        @Override
        public int getHeight() {
            return level.getHeight();
        }

        @Override
        public int getMinY() {
            return level.getMinY();
        }
    }
}
