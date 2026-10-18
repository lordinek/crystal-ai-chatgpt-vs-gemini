package com.crystalai.plugin.combat;

/**
 * Pure explosion formulas from vanilla 1.20.4 (Paper), kept free of server classes so they can be unit tested.
 * Source: ExplosionDamageCalculator.getEntityDamageAmount and Player.hurt.
 */
public final class ExplosionMath {

    /** End crystal explosion power. */
    public static final float CRYSTAL_POWER = 6.0F;

    /** Charged respawn anchor explosion power (outside the nether). */
    public static final float ANCHOR_POWER = 5.0F;

    public enum DifficultyLevel { PEACEFUL, EASY, NORMAL, HARD }

    private ExplosionMath() {
    }

    /**
     * Damage before difficulty, armor and enchantments.
     *
     * @param distance distance from the explosion centre to the entity's position (feet)
     * @param exposure fraction of the entity's hitbox visible from the centre, 0..1
     * @return 0 when the entity is outside the damage radius (vanilla does not hurt it at all)
     */
    public static float rawDamage(double distance, double exposure, float power) {
        double diameter = power * 2.0F;
        double ratio = distance / diameter;
        if (ratio > 1.0) {
            return 0.0F;
        }
        double impact = (1.0 - ratio) * exposure;
        return (float) ((impact * impact + impact) / 2.0 * 7.0 * diameter + 1.0);
    }

    /** Players only: explosion damage types scale with difficulty. */
    public static float scaleForDifficulty(float amount, DifficultyLevel difficulty) {
        return switch (difficulty) {
            case PEACEFUL -> 0.0F;
            case EASY -> Math.min(amount / 2.0F + 1.0F, amount);
            case NORMAL -> amount;
            case HARD -> amount * 3.0F / 2.0F;
        };
    }

    /**
     * Damage-cooldown rule of LivingEntity.hurt: while invulnerableTime > 10 only the part of a hit
     * exceeding the last hit lands.
     */
    public static float afterHurtCooldown(float amount, int invulnerableTime, float lastHurt) {
        if (invulnerableTime > 10) {
            return amount <= lastHurt ? 0.0F : amount - lastHurt;
        }
        return amount;
    }

    /** Resistance effect: each level removes 20 %. */
    public static float afterResistance(float amount, int amplifier) {
        int reduction = (amplifier + 1) * 5;
        return Math.max(amount * (25 - reduction) / 25.0F, 0.0F);
    }
}
