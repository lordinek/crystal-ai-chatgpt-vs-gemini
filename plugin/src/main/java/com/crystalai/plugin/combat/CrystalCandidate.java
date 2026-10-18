package com.crystalai.plugin.combat;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * One candidate crystal spot.
 *
 * @param base         block the crystal stands on
 * @param crystalPos   where the crystal entity (and explosion centre) would be
 * @param hasBase      obsidian/bedrock already there (otherwise obsidian is placed first)
 * @param placeable    the NPC can place it right now (items, reach, sight, free space)
 * @param reason       "ok" or why it is not placeable
 * @param targetDamage estimated damage to the target for a fresh hit
 * @param selfDamage   estimated damage to the NPC itself for a fresh hit
 * @param score        ranking score: targetDamage - weight * selfDamage
 */
public record CrystalCandidate(BlockPos base, Vec3 crystalPos, boolean hasBase, boolean placeable, String reason,
                               float targetDamage, float selfDamage, double score) {
}
