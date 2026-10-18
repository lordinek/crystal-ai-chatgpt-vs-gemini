package com.crystalai.plugin.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crystalai.plugin.combat.ExplosionMath.DifficultyLevel;
import org.junit.jupiter.api.Test;

class ExplosionMathTest {

    private static final float POWER = ExplosionMath.CRYSTAL_POWER;
    private static final float EPS = 1e-4F;

    @Test
    void pointBlankFullExposureIs85() {
        // impact 1: (1 + 1) / 2 * 7 * 12 + 1
        assertEquals(85.0F, ExplosionMath.rawDamage(0.0, 1.0, POWER), EPS);
    }

    @Test
    void halfRadiusFullExposure() {
        // ratio 0.5 -> impact 0.5: (0.25 + 0.5) / 2 * 84 + 1 = 32.5
        assertEquals(32.5F, ExplosionMath.rawDamage(6.0, 1.0, POWER), EPS);
    }

    @Test
    void exposureScalesImpact() {
        // ratio 0.25, exposure 0.5 -> impact 0.375: (0.140625 + 0.375) / 2 * 84 + 1 = 22.65625
        assertEquals(22.65625F, ExplosionMath.rawDamage(3.0, 0.5, POWER), EPS);
    }

    @Test
    void edgeOfRadiusStillDealsOne() {
        // vanilla hurts entities with ratio <= 1; impact 0 gives the constant +1
        assertEquals(1.0F, ExplosionMath.rawDamage(12.0, 1.0, POWER), EPS);
        assertEquals(1.0F, ExplosionMath.rawDamage(3.0, 0.0, POWER), EPS);
    }

    @Test
    void outsideRadiusIsZero() {
        assertEquals(0.0F, ExplosionMath.rawDamage(12.0001, 1.0, POWER), EPS);
    }

    @Test
    void damageFallsWithDistance() {
        float previous = Float.MAX_VALUE;
        for (double d = 0; d <= 12; d += 0.5) {
            float damage = ExplosionMath.rawDamage(d, 1.0, POWER);
            assertTrue(damage < previous, "not decreasing at " + d);
            previous = damage;
        }
    }

    @Test
    void difficultyScaling() {
        assertEquals(0.0F, ExplosionMath.scaleForDifficulty(10F, DifficultyLevel.PEACEFUL), EPS);
        assertEquals(6.0F, ExplosionMath.scaleForDifficulty(10F, DifficultyLevel.EASY), EPS);
        assertEquals(1.0F, ExplosionMath.scaleForDifficulty(1F, DifficultyLevel.EASY), EPS); // min(1.5, 1)
        assertEquals(10.0F, ExplosionMath.scaleForDifficulty(10F, DifficultyLevel.NORMAL), EPS);
        assertEquals(15.0F, ExplosionMath.scaleForDifficulty(10F, DifficultyLevel.HARD), EPS);
    }

    @Test
    void hurtCooldownOnlyLetsTheExcessThrough() {
        assertEquals(10.0F, ExplosionMath.afterHurtCooldown(10F, 10, 8F), EPS); // cooldown over (<= 10)
        assertEquals(2.0F, ExplosionMath.afterHurtCooldown(10F, 15, 8F), EPS);
        assertEquals(0.0F, ExplosionMath.afterHurtCooldown(8F, 15, 8F), EPS);
        assertEquals(0.0F, ExplosionMath.afterHurtCooldown(5F, 20, 8F), EPS);
    }

    @Test
    void resistanceRemovesTwentyPercentPerLevel() {
        assertEquals(8.0F, ExplosionMath.afterResistance(10F, 0), EPS);
        assertEquals(6.0F, ExplosionMath.afterResistance(10F, 1), EPS);
        assertEquals(0.0F, ExplosionMath.afterResistance(10F, 4), EPS);
        assertEquals(0.0F, ExplosionMath.afterResistance(10F, 9), EPS);
    }
}
