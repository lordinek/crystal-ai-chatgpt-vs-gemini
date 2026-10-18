package com.crystalai.plugin.bridge;

import com.crystalai.plugin.action.NpcActions;
import com.crystalai.plugin.combat.AnchorCandidate;
import com.crystalai.plugin.combat.CrystalCandidate;
import com.crystalai.plugin.combat.DamageCalculator;
import com.crystalai.plugin.control.ActionSpace;
import com.crystalai.plugin.npc.CrystalNpc;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * Builds the observation vector described in protocol/schema.json (v2). Positions and
 * velocities are expressed in the agent's frame (right, up, forward), forward = horizontal
 * direction to the opponent, the same frame the movement and pearl actions use.
 */
public final class ObservationBuilder {

    private static final int CANDIDATES = 8;
    private static final int SCENE_CRYSTALS = 4;
    private static final int ANCHOR_CANDIDATES = 4;
    private static final int SCENE_ANCHORS = 2;
    private static final int HEIGHTMAP_RADIUS = 4;
    private static final int HEIGHT_UP = 4;
    private static final int HEIGHT_DOWN = 8;
    private static final EquipmentSlot[] ARMOR = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
            EquipmentSlot.FEET};
    private static final String[] ARMOR_FIELDS = {"helmet", "chestplate", "leggings", "boots"};

    private final ObservationSchema schema;

    public ObservationBuilder(ObservationSchema schema) {
        this.schema = schema;
    }

    /** Horizontal frame of an agent. */
    record Frame(double fx, double fz) {
        static Frame of(ServerPlayer self, ServerPlayer opponent) {
            double dx = opponent.getX() - self.getX();
            double dz = opponent.getZ() - self.getZ();
            double length = Math.sqrt(dx * dx + dz * dz);
            if (length < 1.0E-4) {
                Vec3 look = Vec3.directionFromRotation(0, self.getYRot());
                return new Frame(look.x, look.z);
            }
            return new Frame(dx / length, dz / length);
        }

        double right(Vec3 v) {
            // Facing +z (south) the right hand points to -x: right = (-fz, fx).
            return v.x * -fz + v.z * fx;
        }

        double forward(Vec3 v) {
            return v.x * fx + v.z * fz;
        }

        /** World x/z of a point {@code right} and {@code forward} blocks away from (x, z). */
        double worldX(double x, double right, double forward) {
            return x + forward * fx + right * -fz;
        }

        double worldZ(double z, double right, double forward) {
            return z + forward * fz + right * fx;
        }
    }

    public float[] build(CrystalNpc selfNpc, ServerPlayer opponent, ActionSpace.Context context, ObservationFrame arena) {
        return build(selfNpc.handle(), selfNpc.mining(), selfNpc.miningProgress(), opponent, context, arena);
    }

    /** Same observation for any player (a recorded human): mining state comes from the caller. */
    public float[] build(ServerPlayer self, boolean mining, float miningProgress, ServerPlayer opponent,
                         ActionSpace.Context context, ObservationFrame arena) {
        ServerLevel level = self.level();
        Frame frame = Frame.of(self, opponent);
        ObservationSchema.Writer w = schema.writer();

        // self
        Vec3 velocity = self.getDeltaMovement();
        w.put("self", "health", self.getHealth())
                .put("self", "absorption", self.getAbsorptionAmount())
                .put("self", "totems", self.getInventory().countItem(Items.TOTEM_OF_UNDYING))
                .put("self", "offhand_totem", self.getOffhandItem().is(Items.TOTEM_OF_UNDYING))
                .put("self", "mainhand_totem", self.getMainHandItem().is(Items.TOTEM_OF_UNDYING))
                .put("self", "gapples", self.getInventory().countItem(Items.GOLDEN_APPLE))
                .put("self", "crystals", self.getInventory().countItem(Items.END_CRYSTAL))
                .put("self", "obsidian", self.getInventory().countItem(Items.OBSIDIAN))
                .put("self", "anchors", self.getInventory().countItem(Items.RESPAWN_ANCHOR))
                .put("self", "glowstone", self.getInventory().countItem(Items.GLOWSTONE))
                .put("self", "pearls", self.getInventory().countItem(Items.ENDER_PEARL))
                .put("self", "xp_bottles", self.getInventory().countItem(Items.EXPERIENCE_BOTTLE));
        armor(w, "self", self);
        // Remaining fraction of the 20 tick pearl cooldown, as ticks.
        float pearlCooldown = self.getCooldowns().getCooldownPercent(new ItemStack(Items.ENDER_PEARL), 0.0F) * 20.0F;
        w.put("self", "on_ground", self.onGround())
                .put("self", "vel_right", frame.right(velocity))
                .put("self", "vel_up", velocity.y)
                .put("self", "vel_forward", frame.forward(velocity))
                .put("self", "eating", self.isUsingItem())
                .put("self", "eat_progress", self.isUsingItem() ? self.getTicksUsingItem() : 0)
                .put("self", "food", self.getFoodData().getFoodLevel())
                .put("self", "hurt_cooldown", self.invulnerableTime)
                .put("self", "last_hurt", self.invulnerableTime > 10 ? self.lastHurt : 0)
                .put("self", "attack_charge", self.getAttackStrengthScale(0.5F))
                .put("self", "pearl_cooldown", pearlCooldown)
                .put("self", "mining", mining)
                .put("self", "mining_progress", mining ? miningProgress : 0)
                .put("self", "on_fire", self.isOnFire());
        double[] center = arena.siteCenter();
        if (center != null && arena.boundaryRadius() > 0) {
            Vec3 toCenter = new Vec3(center[0] - self.getX(), 0, center[1] - self.getZ());
            w.put("self", "center_right", frame.right(toCenter))
                    .put("self", "center_forward", frame.forward(toCenter))
                    .put("self", "boundary", toCenter.horizontalDistance() / arena.boundaryRadius());
        } else {
            w.put("self", "center_right", 0).put("self", "center_forward", 0).put("self", "boundary", 0);
        }
        w.put("self", "episode_time", arena.episodeTick());

        // opponent
        Vec3 relative = opponent.position().subtract(self.position());
        Vec3 relativeVelocity = opponent.getDeltaMovement().subtract(velocity);
        Vec3 toMe = self.getEyePosition().subtract(opponent.getEyePosition()).normalize();
        w.put("opponent", "rel_right", frame.right(relative))
                .put("opponent", "rel_up", relative.y)
                .put("opponent", "rel_forward", frame.forward(relative))
                .put("opponent", "vel_right", frame.right(relativeVelocity))
                .put("opponent", "vel_up", relativeVelocity.y)
                .put("opponent", "vel_forward", frame.forward(relativeVelocity))
                .put("opponent", "health", opponent.getHealth())
                .put("opponent", "absorption", opponent.getAbsorptionAmount())
                .put("opponent", "offhand_totem", opponent.getOffhandItem().is(Items.TOTEM_OF_UNDYING))
                .put("opponent", "mainhand_totem", opponent.getMainHandItem().is(Items.TOTEM_OF_UNDYING))
                .put("opponent", "totems", opponent.getInventory().countItem(Items.TOTEM_OF_UNDYING));
        armor(w, "opponent", opponent);
        w.put("opponent", "looking_at_me", opponent.getLookAngle().dot(toMe))
                .put("opponent", "hurt_cooldown", opponent.invulnerableTime)
                .put("opponent", "last_hurt", opponent.invulnerableTime > 10 ? opponent.lastHurt : 0)
                .put("opponent", "eating", opponent.isUsingItem())
                .put("opponent", "on_ground", opponent.onGround())
                .put("opponent", "on_fire", opponent.isOnFire());

        // crystal candidates
        for (int i = 0; i < CANDIDATES; i++) {
            if (i < context.candidates().size()) {
                CrystalCandidate c = context.candidates().get(i);
                Vec3 rel = c.crystalPos().subtract(self.position());
                w.put("candidates", "exists", 1)
                        .put("candidates", "rel_right", frame.right(rel))
                        .put("candidates", "rel_up", rel.y)
                        .put("candidates", "rel_forward", frame.forward(rel))
                        .put("candidates", "damage_opponent", c.targetDamage())
                        .put("candidates", "damage_self", c.selfDamage())
                        .put("candidates", "has_base", c.hasBase())
                        .put("candidates", "placeable", c.placeable());
            } else {
                pad(w, "candidates", "exists", "rel_right", "rel_up", "rel_forward", "damage_opponent", "damage_self",
                        "has_base", "placeable");
            }
        }

        // scene crystals
        for (int i = 0; i < SCENE_CRYSTALS; i++) {
            if (i < context.sceneCrystals().size()) {
                EndCrystal crystal = context.sceneCrystals().get(i);
                Vec3 rel = crystal.position().subtract(self.position());
                w.put("scene_crystals", "exists", 1)
                        .put("scene_crystals", "rel_right", frame.right(rel))
                        .put("scene_crystals", "rel_up", rel.y)
                        .put("scene_crystals", "rel_forward", frame.forward(rel))
                        .put("scene_crystals", "damage_opponent",
                                DamageCalculator.crystalDamage(opponent, crystal.position(), true))
                        .put("scene_crystals", "damage_self",
                                DamageCalculator.crystalDamage(self, crystal.position(), true));
            } else {
                pad(w, "scene_crystals", "exists", "rel_right", "rel_up", "rel_forward", "damage_opponent", "damage_self");
            }
        }

        // anchor candidates
        for (int i = 0; i < ANCHOR_CANDIDATES; i++) {
            if (i < context.anchorCandidates().size()) {
                AnchorCandidate c = context.anchorCandidates().get(i);
                Vec3 rel = c.pos().getCenter().subtract(self.position());
                w.put("anchor_candidates", "exists", 1)
                        .put("anchor_candidates", "rel_right", frame.right(rel))
                        .put("anchor_candidates", "rel_up", rel.y)
                        .put("anchor_candidates", "rel_forward", frame.forward(rel))
                        .put("anchor_candidates", "damage_opponent", c.targetDamage())
                        .put("anchor_candidates", "damage_self", c.selfDamage())
                        .put("anchor_candidates", "placeable", c.placeable());
            } else {
                pad(w, "anchor_candidates", "exists", "rel_right", "rel_up", "rel_forward", "damage_opponent",
                        "damage_self", "placeable");
            }
        }

        // scene anchors
        for (int i = 0; i < SCENE_ANCHORS; i++) {
            if (i < context.sceneAnchors().size()) {
                BlockPos anchor = context.sceneAnchors().get(i);
                Vec3 rel = anchor.getCenter().subtract(self.position());
                int charge = Math.max(0, NpcActions.anchorCharge(level, anchor));
                w.put("scene_anchors", "exists", 1)
                        .put("scene_anchors", "rel_right", frame.right(rel))
                        .put("scene_anchors", "rel_up", rel.y)
                        .put("scene_anchors", "rel_forward", frame.forward(rel))
                        .put("scene_anchors", "charge", charge)
                        .put("scene_anchors", "damage_opponent",
                                charge > 0 ? DamageCalculator.anchorDamage(opponent, anchor, true) : 0)
                        .put("scene_anchors", "damage_self",
                                charge > 0 ? DamageCalculator.anchorDamage(self, anchor, true) : 0);
            } else {
                pad(w, "scene_anchors", "exists", "rel_right", "rel_up", "rel_forward", "charge", "damage_opponent",
                        "damage_self");
            }
        }

        // heightmap: forward -4..4 (rows), right -4..4 (right fastest)
        int feetY = (int) Math.floor(self.getY());
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int forward = -HEIGHTMAP_RADIUS; forward <= HEIGHTMAP_RADIUS; forward++) {
            for (int right = -HEIGHTMAP_RADIUS; right <= HEIGHTMAP_RADIUS; right++) {
                int x = (int) Math.floor(frame.worldX(self.getX(), right, forward));
                int z = (int) Math.floor(frame.worldZ(self.getZ(), right, forward));
                double height = -HEIGHT_DOWN;
                for (int y = feetY + HEIGHT_UP; y >= feetY - HEIGHT_DOWN; y--) {
                    cursor.set(x, y, z);
                    if (!level.getBlockState(cursor).getCollisionShape(level, cursor).isEmpty()) {
                        height = y + 1 - self.getY(); // top face of that block
                        break;
                    }
                }
                w.put("heightmap", "height", height);
            }
        }

        // body: up -1..2, forward -1..1, right -1..1 (right fastest)
        for (int up = -1; up <= 2; up++) {
            for (int forward = -1; forward <= 1; forward++) {
                for (int right = -1; right <= 1; right++) {
                    cursor.set(Math.floor(frame.worldX(self.getX(), right, forward)), feetY + up,
                            Math.floor(frame.worldZ(self.getZ(), right, forward)));
                    w.put("body", "solid", !level.getBlockState(cursor).getCollisionShape(level, cursor).isEmpty());
                }
            }
        }
        return w.finish();
    }

    private static void armor(ObservationSchema.Writer w, String group, ServerPlayer player) {
        for (int i = 0; i < ARMOR.length; i++) {
            ItemStack stack = player.getItemBySlot(ARMOR[i]);
            double left = stack.isEmpty() || stack.getMaxDamage() <= 0
                    ? 0.0 : 1.0 - (double) stack.getDamageValue() / stack.getMaxDamage();
            w.put(group, ARMOR_FIELDS[i], left);
        }
    }

    private static void pad(ObservationSchema.Writer w, String group, String... fields) {
        for (String field : fields) {
            w.put(group, field, 0);
        }
    }
}
