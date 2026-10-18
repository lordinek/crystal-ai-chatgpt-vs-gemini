package com.crystalai.plugin.combat;

import net.minecraft.core.BlockPos;

/**
 * One candidate anchor spot.
 *
 * @param pos          the (empty) cell the anchor would occupy; the blast centre is its centre
 * @param placeable    the NPC can place it right now (item, reach, sight, support, free space)
 * @param targetDamage estimated damage to the target for a fresh hit (anchor detonated there)
 * @param selfDamage   estimated damage to the NPC itself for a fresh hit
 * @param score        ranking score: targetDamage - weight * selfDamage
 */
public record AnchorCandidate(BlockPos pos, boolean placeable, float targetDamage, float selfDamage, double score) {
}
