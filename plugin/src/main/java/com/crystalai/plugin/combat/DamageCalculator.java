package com.crystalai.plugin.combat;

import com.crystalai.plugin.combat.ExplosionMath.DifficultyLevel;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.CombatRules;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.phys.Vec3;

/**
 * Predicts how much health (incl. absorption) an entity loses from an end crystal explosion,
 * following the vanilla pipeline: exposure -> raw damage -> difficulty -> hurt cooldown ->
 * armor -> resistance -> protection enchantments. Shield blocking is not modelled.
 */
public final class DamageCalculator {

    private DamageCalculator() {
    }

    /** Crystal entity position for a crystal standing on {@code base}. */
    public static Vec3 crystalPosition(net.minecraft.core.BlockPos base) {
        return new Vec3(base.getX() + 0.5, base.getY() + 1.0, base.getZ() + 0.5);
    }

    /**
     * @param respectHurtCooldown true for an explosion happening now (uses the target's current
     *                            damage cooldown), false for a "fresh hit" estimate
     */
    public static float crystalDamage(LivingEntity target, Vec3 center, boolean respectHurtCooldown) {
        double distance = Math.sqrt(target.distanceToSqr(center));
        if (distance / (ExplosionMath.CRYSTAL_POWER * 2.0) > 1.0) {
            return 0.0F;
        }
        double exposure = ServerExplosion.getSeenPercent(center, target);
        return explosionDamage(target, distance, exposure, ExplosionMath.CRYSTAL_POWER, respectHurtCooldown);
    }

    /** Upper bound assuming full exposure; cheap, used to pre-filter candidates. */
    public static float crystalDamageUpperBound(LivingEntity target, Vec3 center) {
        double distance = Math.sqrt(target.distanceToSqr(center));
        return explosionDamage(target, distance, 1.0, ExplosionMath.CRYSTAL_POWER, false);
    }

    /**
     * Charged respawn anchor detonated outside the nether (RespawnAnchorBlock.explode, checked in the
     * 1.21.11 bytecode): block removed first, centre = block centre, power 5, fire, damage source
     * badRespawnPointExplosion.
     */
    public static float anchorDamage(LivingEntity target, net.minecraft.core.BlockPos anchor, boolean respectHurtCooldown) {
        Vec3 center = anchor.getCenter();
        double distance = Math.sqrt(target.distanceToSqr(center));
        if (distance / (ExplosionMath.ANCHOR_POWER * 2.0) > 1.0) {
            return 0.0F;
        }
        // The anchor block is removed before the blast; it must not shadow its own explosion.
        double exposure = Exposure.seenPercentIgnoring(center, target, anchor);
        DamageSource source = target.damageSources().badRespawnPointExplosion(center);
        return explosionDamage(target, distance, exposure, ExplosionMath.ANCHOR_POWER, respectHurtCooldown,
                source, source.scalesWithDifficulty());
    }

    public static float anchorDamageUpperBound(LivingEntity target, net.minecraft.core.BlockPos anchor) {
        Vec3 center = anchor.getCenter();
        DamageSource source = target.damageSources().badRespawnPointExplosion(center);
        return explosionDamage(target, Math.sqrt(target.distanceToSqr(center)), 1.0, ExplosionMath.ANCHOR_POWER,
                false, source, source.scalesWithDifficulty());
    }

    static float explosionDamage(LivingEntity target, double distance, double exposure, float power,
                                 boolean respectHurtCooldown) {
        return explosionDamage(target, distance, exposure, power, respectHurtCooldown,
                target.damageSources().explosion(null, null), true);
    }

    static float explosionDamage(LivingEntity target, double distance, double exposure, float power,
                                 boolean respectHurtCooldown, DamageSource source, boolean scalesWithDifficulty) {
        if (!target.isAlive() || target.isInvulnerable()) {
            return 0.0F;
        }
        float amount = ExplosionMath.rawDamage(distance, exposure, power);
        if (amount <= 0.0F) {
            return 0.0F;
        }
        if (target instanceof Player player) {
            if (player.getAbilities().invulnerable) {
                return 0.0F;
            }
            if (scalesWithDifficulty) {
                amount = ExplosionMath.scaleForDifficulty(amount, difficulty(target));
            }
            if (amount == 0.0F) {
                return 0.0F;
            }
        }
        if (respectHurtCooldown) {
            amount = ExplosionMath.afterHurtCooldown(amount, target.invulnerableTime, target.lastHurt);
            if (amount <= 0.0F) {
                return 0.0F;
            }
        }
        amount = CombatRules.getDamageAfterAbsorb(target, amount, source, (float) target.getArmorValue(),
                (float) target.getAttributeValue(Attributes.ARMOR_TOUGHNESS));
        MobEffectInstance resistance = target.getEffect(MobEffects.RESISTANCE);
        if (resistance != null) {
            amount = ExplosionMath.afterResistance(amount, resistance.getAmplifier());
        }
        if (amount <= 0.0F) {
            return 0.0F;
        }
        // 1.21: protection comes from enchantment effect components and needs a server level
        float protection = target.level() instanceof ServerLevel level
                ? EnchantmentHelper.getDamageProtection(level, target, source) : 0.0F;
        if (protection > 0.0F) {
            amount = CombatRules.getDamageAfterMagicAbsorb(amount, protection);
        }
        return amount;
    }

    private static DifficultyLevel difficulty(LivingEntity entity) {
        return switch (entity.level().getDifficulty()) {
            case PEACEFUL -> DifficultyLevel.PEACEFUL;
            case EASY -> DifficultyLevel.EASY;
            case NORMAL -> DifficultyLevel.NORMAL;
            case HARD -> DifficultyLevel.HARD;
        };
    }
}
