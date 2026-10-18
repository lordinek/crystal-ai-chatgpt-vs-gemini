package com.crystalai.plugin.llm;

import com.crystalai.plugin.action.NpcActions;
import com.crystalai.plugin.npc.CrystalNpc;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Plays a move label every tick between two decisions: running, facing, hit timing, the shield.
 * The model picks the intent every few seconds; this is the "muscle memory" part.
 * Directions are CrystalNpc.MOVE_DIRECTIONS (relative to the opponent).
 */
final class MoveExecutor {

    static final int STOP = 0, TOWARD = 1, RIGHT = 3, AWAY = 5, LEFT = 7;
    /** Movement input is renewed every tick, so a short hold is enough. */
    private static final int HOLD = 2;
    /** Golden apples back to back only delay the fight: after one, the next waits this long. */
    private static final int EAT_COOLDOWN_TICKS = 300;
    /** Melee reach is 3 blocks to the hitbox; stop a bit inside it. */
    private static final double MELEE_RANGE = 2.6;
    /** Jump for a crit only when a charged hit is close (the jump takes ~6 ticks to peak). */
    private static final double CRIT_JUMP_RANGE = 3.4;
    private static final float CRIT_JUMP_CHARGE = 0.6F;
    /** Chance to jump on the tick a hit lands (jump reset); not every time, so it is not a pattern. */
    private static final double JUMP_RESET_CHANCE = 0.7;
    /** Block-hit: shield window after a landed hit (a raised shield only blocks after ~5 ticks). */
    private static final int BLOCK_HIT_TICKS = 8;
    /** Waiting out an opponent's raised shield: give up after this many ticks in a row (1.5 s) and swing anyway. */
    private static final int SHIELD_WAIT_CAP = 30;
    /** Gap eating: farther than this from the opponent, eat GAP_EAT_BONUS hearts earlier. */
    private static final double GAP_DISTANCE = 5.0;
    private static final double GAP_EAT_BONUS = 3.0;

    private final NpcActions actions;
    private final LlmSettings settings;
    /** Set per match: true on hand-built arenas (step jumping, edge brake). */
    boolean customArena;
    /** Set per match: the edge brake, off when llm.yml kenar-zekasi is false. */
    boolean edgeBrake;

    MoveExecutor(NpcActions actions, LlmSettings settings) {
        this.actions = actions;
        this.settings = settings;
    }

    /** One tick of {@code me}'s current move against {@code opponent}. Returns true when a sword hit landed. */
    boolean tick(Fighter me, Fighter opponent) {
        return opponent.npc != null && tick(me, opponent.npc.handle());
    }

    /** Same, against any server player: a human in a duel. */
    boolean tick(Fighter me, ServerPlayer target) {
        CrystalNpc npc = me.npc;
        if (npc == null || !npc.handle().isAlive()) {
            return false;
        }
        ServerPlayer self = npc.handle();
        double distance = self.distanceTo(target);
        Move move = me.move;
        if (self.onGround() && self.fallDistance > 0) {
            self.fallDistance = 0; // what landing does for a real client (see KRITIK)
        }
        if (me.eatCooldown > 0) {
            me.eatCooldown--;
        }
        // Every move goes through the card: kac/iyiles/blokla used to stand still (STOP) at low health, and the
        // card already eats, shields and backs off on its own.
        if (me.card != null) {
            if (customArena && self.horizontalCollision && self.onGround()) {
                npc.requestJump();
            }
            return cardFight(me, npc, self, target, distance);
        }

        boolean shield = move == Move.BLOKLA && hasShield(self);
        setShield(self, shield);
        self.setShiftKeyDown(move == Move.BLOKLA && !hasShield(self));

        if (move == null) {
            npc.setMovement(STOP, 0, false);
            return false;
        }
        // Hand-built arenas have steps: walking into a block wall jumps it, as a player holding W and space would.
        // Off in the flat box, where the only walls are the barrier and hopping at it looks silly.
        if (customArena && self.horizontalCollision && self.onGround() && move != Move.BLOKLA) {
            npc.requestJump();
        }
        switch (move) {
            case SALDIR -> {
                go(npc, self, target, distance > MELEE_RANGE ? TOWARD : STOP, HOLD, true);
                return hit(self, target);
            }
            case YAKLAS -> go(npc, self, target, distance > settings.approachStopDistance() ? TOWARD : STOP, HOLD, true);
            case KAC -> go(npc, self, target, distance < settings.retreatDistance() ? AWAY : STOP, HOLD, false);
            case BLOKLA -> go(npc, self, target, STOP, 0, false);
            case YAN_ADIM -> {
                if (++me.strafeTicks >= settings.strafeSwitchTicks()) {
                    me.strafeTicks = 0;
                    me.strafeLeft = !me.strafeLeft;
                }
                go(npc, self, target, me.strafeLeft ? LEFT : RIGHT, HOLD, false);
            }
            case IYILES -> {
                // Eat once per decision; back off while chewing (32 ticks) and afterwards.
                if (!me.ateThisDecision && !self.isUsingItem() && me.eatCooldown <= 0) {
                    me.ateThisDecision = actions.eatGoldenApple(self).success();
                    if (me.ateThisDecision) {
                        me.eatCooldown = EAT_COOLDOWN_TICKS;
                    }
                }
                go(npc, self, target, distance < settings.retreatDistance() ? AWAY : STOP, HOLD, false);
            }
            case KRITIK -> {
                // No sprint: a sprinting hit is a knockback hit, never a crit.
                go(npc, self, target, distance > MELEE_RANGE ? TOWARD : STOP, HOLD, false);
                boolean falling = !self.onGround() && self.getDeltaMovement().y < 0;
                if (falling) {
                    // Vanilla crit needs fallDistance > 0. A server player's fall distance is fed by
                    // the client's movement packets, which a fake player never sends: do it here.
                    if (self.fallDistance <= 0) {
                        self.fallDistance = 1;
                    }
                    return hit(self, target);
                }
                if (self.onGround() && distance <= CRIT_JUMP_RANGE && self.getAttackStrengthScale(0.5F) >= CRIT_JUMP_CHARGE) {
                    npc.requestJump();
                }
            }
        }
        return false;
    }

    /**
     * The trained body: plays {@code me.card} every tick. The model's live move only decides whether the
     * fight is on (attacking moves) or something else (kac, blokla, iyiles: the plain moves above).
     */
    private boolean cardFight(Fighter me, CrystalNpc npc, ServerPlayer self, ServerPlayer target, double distance) {
        FightCard c = me.card;
        float charge = self.getAttackStrengthScale(0.5F);
        double reach = reachTo(self, target);

        // Jump reset: hopping the tick a hit lands turns part of the knockback into height, so the next swing is not lost.
        if (self.hurtTime == self.hurtDuration - 1 && self.onGround() && !me.critArmed && !self.isUsingItem()
                && me.rng.nextDouble() < JUMP_RESET_CHANCE) {
            npc.requestJump();
        }

        // Chewing an apple: keep backing off until it is down.
        if (self.isUsingItem() && self.getUsedItemHand() == InteractionHand.MAIN_HAND) {
            go(npc, self, target, AWAY, HOLD, false);
            return false;
        }
        // Gap eating: knocked clear of the opponent, an apple is cheap, so eat a little earlier than the card says.
        double health = self.getHealth() + self.getAbsorptionAmount();
        boolean gap = distance > GAP_DISTANCE;
        if (health <= c.eatAt() + (gap ? GAP_EAT_BONUS : 0.0) && !me.critArmed && self.onGround()
                && me.eatCooldown <= 0 && actions.eatGoldenApple(self).success()) {
            if (health > c.eatAt()) {
                me.techGapEats++;
            }
            me.eatCooldown = EAT_COOLDOWN_TICKS;
            setShield(self, false);
            go(npc, self, target, AWAY, HOLD, false);
            return false;
        }

        // Opponent chewing an apple: no shield, no backing off, run in and hit while his hands are busy.
        boolean punish = target.isUsingItem() && target.getUseItem().is(Items.GOLDEN_APPLE) && !me.critArmed;
        // Opponent behind a raised shield: a swing into it is wasted, so wait (circling) for it to drop. Capped, so a
        // player who never lowers it still gets hit.
        boolean waitShield = target.isBlocking() && me.shieldWaitStreak < SHIELD_WAIT_CAP && !punish;
        if (waitShield) {
            me.shieldWaitStreak++;
            me.techWaitTicks++;
        } else if (!target.isBlocking()) {
            me.shieldWaitStreak = 0;
        }
        if (punish) {
            me.techPunishTicks++;
        }
        // Block-hit: the shield comes up right after our hit, while the sword recharges.
        boolean blockHit = me.blockHitLeft > 0 && hasShield(self) && !punish && !me.critArmed && charge < c.charge();
        if (me.blockHitLeft > 0) {
            me.blockHitLeft--;
        }

        // Shield up while the opponent's swing is ready and ours is not.
        if (--me.shieldRollTicks <= 0) {
            me.shieldRollTicks = 10;
            me.shieldRoll = me.rng.nextDouble();
        }
        boolean wantShield = !punish && (blockHit || (c.playedShield() > 0 && hasShield(self) && !me.critArmed && distance < 3.6
                && target.getAttackStrengthScale(0.5F) >= 0.9F && charge < c.charge() && me.shieldRoll < c.playedShield()));
        setShield(self, wantShield);
        if (wantShield) {
            go(npc, self, target, me.cardStrafeRight ? RIGHT : LEFT, HOLD, false);
            return false;
        }

        // A crit jump in progress: swing on the way down.
        if (me.critArmed) {
            me.critTicks++;
            if (!self.onGround()) {
                me.critAirborne = true;
            }
            go(npc, self, target, reach > c.reach() - 0.3 ? TOWARD : STOP, HOLD, false);
            boolean falling = !self.onGround() && self.getDeltaMovement().y < 0;
            if (falling && reach <= c.reach() && charge >= c.charge()) {
                if (self.fallDistance <= 0) {
                    self.fallDistance = 1; // see KRITIK
                }
                me.critArmed = false;
                return afterSwing(me, c, swing(self, target, charge));
            }
            if ((me.critAirborne && self.onGround()) || me.critTicks > 20) {
                me.critArmed = false; // landed without a swing
            }
            return false;
        }

        // Footwork.
        if (punish) {
            go(npc, self, target, reach > c.reach() - 0.4 ? TOWARD : STOP, HOLD, true);
        } else if (me.backOffLeft > 0) {
            me.backOffLeft--;
            go(npc, self, target, AWAY, HOLD, false);
        } else {
            if (++me.cardStrafeTicks >= (me.cardStrafeLimit > 0 ? me.cardStrafeLimit : c.strafeSwitch())) {
                me.cardStrafeTicks = 0;
                me.cardStrafeRight = !me.cardStrafeRight;
                me.circling = waitShield || me.rng.nextDouble() < c.strafe();
                // Uneven switch times, so the sidestep cannot be timed.
                me.cardStrafeLimit = Math.max(2, (int) Math.round(c.strafeSwitch() * (0.6 + me.rng.nextDouble() * 0.9)));
            }
            boolean circle = me.circling || waitShield;
            int direction;
            if (reach > c.reach() + 0.4) {
                direction = circle ? (me.cardStrafeRight ? 2 : 8) : TOWARD; // 2 toward-right, 8 toward-left
            } else if (circle) {
                // Keep pressing forward while sidestepping; only go pure sideways when already too close.
                boolean room = reach > c.reach() - 0.4;
                direction = me.cardStrafeRight ? (room ? 2 : RIGHT) : (room ? 8 : LEFT);
            } else {
                direction = reach > c.reach() - 0.4 ? TOWARD : STOP;
            }
            if (me.wTapLeft > 0) {
                me.wTapLeft--;
                // W released: the forward part of the input goes, sideways stays.
                direction = direction == TOWARD ? STOP : direction == 2 ? RIGHT : direction == 8 ? LEFT : direction;
            }
            boolean sprint = c.sprint() && me.wTapLeft == 0;
            go(npc, self, target, direction, HOLD, sprint);
        }

        // Swing, or jump for a crit.
        if (waitShield) {
            return false;
        }
        double crit = c.playedCrit();
        if (charge >= c.charge() && self.onGround() && crit > 0 && charge >= 0.9F
                && reach <= c.reach() + 0.4 && me.rng.nextDouble() < crit) {
            npc.requestJump();
            me.critArmed = true;
            me.critAirborne = false;
            me.critTicks = 0;
            return false;
        }
        if (charge >= c.charge() && reach <= c.reach()) {
            return afterSwing(me, c, swing(self, target, charge));
        }
        return false;
    }

    private static boolean afterSwing(Fighter me, FightCard c, boolean hit) {
        if (hit) {
            me.wTapLeft = c.wTap();
            me.backOffLeft = c.backOff();
            me.blockHitLeft = BLOCK_HIT_TICKS;
            me.techBlockHits++;
        }
        return hit;
    }

    /**
     * A sword swing at the card's charge. At 0.9 and above it goes through NpcActions (which also puts the
     * sword in hand); below that, as the card allows, it is the same attack when the sword is already held.
     */
    private boolean swing(ServerPlayer self, ServerPlayer target, float charge) {
        if (charge >= 0.9F) {
            return hit(self, target);
        }
        if (!target.isAlive() || reachTo(self, target) > 3.0 || !self.hasLineOfSight(target)
                || !(self.getMainHandItem().is(Items.DIAMOND_SWORD) || self.getMainHandItem().is(Items.NETHERITE_SWORD))) {
            return false;
        }
        self.lookAt(EntityAnchorArgument.Anchor.EYES, target.getBoundingBox().getCenter());
        self.attack(target);
        self.swing(InteractionHand.MAIN_HAND, true);
        return true;
    }

    /** Eye to the target's hitbox, the distance vanilla reach is measured by. */
    private static double reachTo(ServerPlayer self, ServerPlayer target) {
        Vec3 eye = self.getEyePosition();
        AABB box = target.getBoundingBox();
        double dx = Math.max(Math.max(box.minX - eye.x, 0.0), eye.x - box.maxX);
        double dy = Math.max(Math.max(box.minY - eye.y, 0.0), eye.y - box.maxY);
        double dz = Math.max(Math.max(box.minZ - eye.z, 0.0), eye.z - box.maxZ);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * Edge brake (kenar-zekasi): on a hand-built arena the NPC never walks itself into lava or off a drop, as a player
     * would stop at the edge. Being knocked in is still possible, and that is the danger the models weigh.
     */
    private void go(CrystalNpc npc, ServerPlayer self, ServerPlayer target, int direction, int hold, boolean sprint) {
        if (edgeBrake && direction != STOP && self.onGround() && safeSteps(self, target, direction, 1) == 0) {
            direction = STOP;
        }
        npc.setMovement(direction, direction == STOP ? 0 : hold, sprint);
    }

    /**
     * How many whole blocks {@code self} can walk in {@code direction} (relative to {@code target}, see
     * CrystalNpc.MOVE_DIRECTIONS) before lava, fire or a drop of more than 3 blocks, up to {@code max}.
     */
    static int safeSteps(ServerPlayer self, ServerPlayer target, int direction, int max) {
        float[] input = CrystalNpc.MOVE_DIRECTIONS[direction];
        double fx = target.getX() - self.getX();
        double fz = target.getZ() - self.getZ();
        double length = Math.sqrt(fx * fx + fz * fz);
        if (length < 1e-6) {
            fx = 0;
            fz = 1;
        } else {
            fx /= length;
            fz /= length;
        }
        // Left of facing (fx, fz) is (fz, -fx): facing south (+z), left is east (+x).
        double vx = fx * input[0] + fz * input[1];
        double vz = fz * input[0] - fx * input[1];
        double v = Math.sqrt(vx * vx + vz * vz);
        if (v < 1e-6) {
            return max;
        }
        Level level = self.level();
        int feetY = Mth.floor(self.getY() + 0.01);
        for (int step = 1; step <= max; step++) {
            BlockPos column = BlockPos.containing(self.getX() + vx / v * step, feetY, self.getZ() + vz / v * step);
            if (!safeColumn(level, column)) {
                return step - 1;
            }
        }
        return max;
    }

    /** Standing at {@code feet} or falling from it lands on something solid within 3 blocks, not in lava or fire. */
    private static boolean safeColumn(Level level, BlockPos feet) {
        for (int down = 0; down <= 4; down++) {
            BlockPos pos = feet.below(down);
            BlockState state = level.getBlockState(pos);
            if (state.getFluidState().is(FluidTags.LAVA) || state.is(BlockTags.FIRE) || state.is(Blocks.MAGMA_BLOCK)) {
                return false;
            }
            if (down > 0 && !state.getCollisionShape(level, pos).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private boolean hit(ServerPlayer self, ServerPlayer target) {
        return actions.swordHit(self, target).success();
    }

    private static boolean hasShield(ServerPlayer player) {
        return player.getItemBySlot(EquipmentSlot.OFFHAND).is(Items.SHIELD);
    }

    /** Raise or lower the offhand shield, as holding/releasing right click would. */
    private static void setShield(ServerPlayer player, boolean up) {
        boolean raised = player.isUsingItem() && player.getUsedItemHand() == InteractionHand.OFF_HAND;
        if (up && !raised && !player.isUsingItem()) {
            ItemStack offhand = player.getItemBySlot(EquipmentSlot.OFFHAND);
            player.gameMode.useItem(player, player.level(), offhand, InteractionHand.OFF_HAND);
        } else if (!up && raised) {
            player.releaseUsingItem();
        }
    }
}
