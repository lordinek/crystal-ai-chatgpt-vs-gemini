package com.crystalai.plugin.control;

import com.crystalai.plugin.action.NpcActions;
import com.crystalai.plugin.combat.AnchorCandidate;
import com.crystalai.plugin.combat.AnchorFinder;
import com.crystalai.plugin.combat.CandidateFinder;
import com.crystalai.plugin.combat.CrystalCandidate;
import com.crystalai.plugin.combat.DamageCalculator;
import com.crystalai.plugin.npc.CrystalNpc;
import com.crystalai.plugin.util.Profiler;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Turns the world into what a controller chooses from (candidates, scene crystals and anchors,
 * shield/topblock/mining targets, action mask) and applies a chosen {@link NpcAction} through
 * {@link NpcActions}. Everything a mask allows is computed once per decision in {@link #observe},
 * so the mask and the execution agree. Computing the mask has no side effects.
 */
public final class ActionSpace {

    /** Scene crystals (explode targets) are the nearest ones within this distance. */
    private static final double SCENE_RADIUS = 12.0;
    private static final int SCENE_CRYSTALS = NpcAction.BRANCH_SIZES[NpcAction.EXPLODE] - 1;
    /** Anchors within this many blocks (cube) are observed, even out of reach (the opponent's threats). */
    private static final int ANCHOR_SCAN = 6;
    /** A shield is only offered against a threat that would hurt at least this much. */
    private static final float SHIELD_MIN_DAMAGE = 1.0F;
    /** Move directions as yaw offsets from "toward the opponent" (right = +90, Minecraft yaw). */
    private static final float[] PEARL_YAW_OFFSETS = {0, 0, 45, 90, 135, 180, -135, -90, -45};
    private static final Item[] PICKAXES = {Items.NETHERITE_PICKAXE, Items.DIAMOND_PICKAXE};

    private final NpcActions actions;
    private final CandidateFinder finder;
    private final AnchorFinder anchorFinder;
    private final float pearlPitch;

    public ActionSpace(NpcActions actions, CandidateFinder finder, AnchorFinder anchorFinder, float pearlPitch) {
        this.actions = actions;
        this.finder = finder;
        this.anchorFinder = anchorFinder;
        this.pearlPitch = pearlPitch;
    }

    /** What the NPC can see/choose this decision step. */
    public record Context(ServerPlayer opponent, float yawToOpponent,
                          List<CrystalCandidate> candidates, List<EndCrystal> sceneCrystals,
                          List<AnchorCandidate> anchorCandidates, List<BlockPos> sceneAnchors,
                          @Nullable BlockPos shield, @Nullable BlockPos topblock, @Nullable BlockPos[] mineTargets) {
    }

    public Context observe(CrystalNpc self, net.minecraft.world.entity.LivingEntity opponentEntity) {
        return observe(self.handle(), opponentEntity);
    }

    /** Same context for any player (a recorded human). */
    public Context observe(ServerPlayer player, net.minecraft.world.entity.LivingEntity opponentEntity) {
        ServerPlayer opponent = (ServerPlayer) opponentEntity;
        long t0 = Profiler.start();
        List<CrystalCandidate> candidates = finder.find(player, opponent);
        Profiler.stop("crystal_cands", t0);
        List<EndCrystal> scene = player.level().getEntitiesOfClass(EndCrystal.class,
                        player.getBoundingBox().inflate(SCENE_RADIUS), EndCrystal::isAlive).stream()
                .sorted(Comparator.comparingDouble(crystal -> crystal.distanceToSqr(player)))
                .limit(SCENE_CRYSTALS)
                .toList();
        t0 = Profiler.start();
        List<AnchorCandidate> anchorCandidates = anchorFinder.find(player, opponent);
        Profiler.stop("anchor_cands", t0);
        t0 = Profiler.start();
        List<BlockPos> sceneAnchors = nearbyAnchors(player);
        Profiler.stop("scene_anchors", t0);
        double dx = opponent.getX() - player.getX();
        double dz = opponent.getZ() - player.getZ();
        float yaw = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90.0F;
        t0 = Profiler.start();
        BlockPos[] mine = {null, coverBlock(player, opponent), ahead(player, dx, dz, 0), ahead(player, dx, dz, 1)};
        BlockPos shield = shieldCell(player, scene, sceneAnchors);
        BlockPos topblock = topblockCell(player);
        Profiler.stop("shield_top_mine", t0);
        return new Context(opponent, yaw, candidates, scene, anchorCandidates, sceneAnchors, shield, topblock, mine);
    }

    // ---------------------------------------------------------------- targets

    private static List<BlockPos> nearbyAnchors(ServerPlayer player) {
        ServerLevel level = player.level();
        BlockPos feet = player.blockPosition();
        List<BlockPos> found = new ArrayList<>();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -ANCHOR_SCAN; dx <= ANCHOR_SCAN; dx++) {
            for (int dy = -ANCHOR_SCAN; dy <= ANCHOR_SCAN; dy++) {
                for (int dz = -ANCHOR_SCAN; dz <= ANCHOR_SCAN; dz++) {
                    cursor.set(feet.getX() + dx, feet.getY() + dy, feet.getZ() + dz);
                    if (level.getBlockState(cursor).is(Blocks.RESPAWN_ANCHOR)) {
                        found.add(cursor.immutable());
                    }
                }
            }
        }
        Vec3 eye = player.getEyePosition();
        found.sort(Comparator.comparingDouble(pos -> eye.distanceToSqr(Vec3.atCenterOf(pos))));
        return found.subList(0, Math.min(NpcAction.SCENE_ANCHORS, found.size()));
    }

    /**
     * Empty cell next to the NPC, toward the explosive that would hurt it most right now (a charged
     * anchor or a standing crystal), at feet height, else head height. Null if nothing threatens.
     */
    private static @Nullable BlockPos shieldCell(ServerPlayer player, List<EndCrystal> crystals, List<BlockPos> anchors) {
        Vec3 threat = null;
        float worst = SHIELD_MIN_DAMAGE;
        for (EndCrystal crystal : crystals) {
            float damage = DamageCalculator.crystalDamage(player, crystal.position(), false);
            if (damage > worst) {
                worst = damage;
                threat = crystal.position();
            }
        }
        for (BlockPos anchor : anchors) {
            if (NpcActions.anchorCharge(player.level(), anchor) > 0) {
                float damage = DamageCalculator.anchorDamage(player, anchor, false);
                if (damage > worst) {
                    worst = damage;
                    threat = anchor.getCenter();
                }
            }
        }
        if (threat == null) {
            return null;
        }
        double tx = threat.x - player.getX();
        double tz = threat.z - player.getZ();
        BlockPos feet = player.blockPosition();
        BlockPos step = Math.abs(tx) >= Math.abs(tz)
                ? feet.offset((int) Math.signum(tx), 0, 0) : feet.offset(0, 0, (int) Math.signum(tz));
        for (BlockPos cell : new BlockPos[] {step, step.above()}) {
            if (player.level().getBlockState(cell).canBeReplaced()) {
                return cell;
            }
        }
        return null;
    }

    /** Cell on top of the nearest crystal base next to me (where the opponent could crystal me). */
    private static @Nullable BlockPos topblockCell(ServerPlayer player) {
        ServerLevel level = player.level();
        BlockPos feet = player.blockPosition();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -2; dy <= 0; dy++) {
                    BlockPos base = feet.offset(dx, dy, dz);
                    if (!NpcActions.isCrystalBase(level.getBlockState(base)) || !NpcActions.crystalSpaceFree(level, base)) {
                        continue;
                    }
                    double distance = player.distanceToSqr(Vec3.atCenterOf(base.above()));
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = base.above();
                    }
                }
            }
        }
        return best;
    }

    /** Obsidian or glowstone right next to the opponent (its shield or surround), nearest to me. */
    private static @Nullable BlockPos coverBlock(ServerPlayer player, ServerPlayer opponent) {
        ServerLevel level = player.level();
        BlockPos feet = opponent.blockPosition();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = 0; dy <= 1; dy++) {
                    BlockPos pos = feet.offset(dx, dy, dz);
                    BlockState state = level.getBlockState(pos);
                    if (!state.is(Blocks.OBSIDIAN) && !state.is(Blocks.GLOWSTONE) && !state.is(Blocks.CRYING_OBSIDIAN)) {
                        continue;
                    }
                    double distance = player.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos));
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = pos;
                    }
                }
            }
        }
        return best;
    }

    /** The block one step toward the opponent (dominant horizontal axis), at feet (0) or head (1) height. */
    private static BlockPos ahead(ServerPlayer player, double dx, double dz, int up) {
        BlockPos feet = player.blockPosition();
        BlockPos step = Math.abs(dx) >= Math.abs(dz)
                ? feet.offset((int) Math.signum(dx), 0, 0) : feet.offset(0, 0, (int) Math.signum(dz));
        return step.above(up);
    }

    // ---------------------------------------------------------------- mask

    /** mask[branch][choice]: which choices are valid right now. Choice 0 is always valid. */
    public boolean[][] mask(CrystalNpc self, Context context) {
        return mask(self.handle(), self.miningPos(), context);
    }

    /** @param miningPos the block this player is currently mining, or null */
    public boolean[][] mask(ServerPlayer player, @Nullable BlockPos miningPos, Context context) {
        ServerLevel level = player.level();
        int[] sizes = NpcAction.BRANCH_SIZES;
        boolean[][] mask = new boolean[sizes.length][];
        for (int branch = 0; branch < mask.length; branch++) {
            mask[branch] = new boolean[sizes[branch]];
            mask[branch][0] = true;
        }
        java.util.Arrays.fill(mask[NpcAction.MOVE], true);
        mask[NpcAction.JUMP][1] = true;   // ignored in the air, like vanilla
        mask[NpcAction.SPRINT][1] = true; // only applied when moving forward
        mask[NpcAction.ATTACK][1] = actions.checkSwordHit(player, context.opponent()).success();
        for (int i = 0; i < context.candidates().size() && i + 1 < sizes[NpcAction.CRYSTAL]; i++) {
            mask[NpcAction.CRYSTAL][i + 1] = context.candidates().get(i).placeable();
        }
        for (int i = 0; i < context.sceneCrystals().size() && i + 1 < sizes[NpcAction.EXPLODE]; i++) {
            mask[NpcAction.EXPLODE][i + 1] = actions.checkAttack(player, context.sceneCrystals().get(i)).success();
        }
        for (int i = 0; i < context.anchorCandidates().size() && i + 1 < sizes[NpcAction.ANCHOR]; i++) {
            mask[NpcAction.ANCHOR][i + 1] = context.anchorCandidates().get(i).placeable();
        }
        boolean glowstone = NpcActions.hasItem(player, Items.GLOWSTONE);
        for (int j = 0; j < context.sceneAnchors().size(); j++) {
            BlockPos anchor = context.sceneAnchors().get(j);
            int charge = NpcActions.anchorCharge(level, anchor);
            boolean visible = actions.visibleFace(player, anchor) != null;
            mask[NpcAction.ANCHOR_USE][1 + j] = visible && glowstone && charge >= 0 && charge < 4;
            mask[NpcAction.ANCHOR_USE][1 + NpcAction.SCENE_ANCHORS + j] = visible && charge > 0;
        }
        mask[NpcAction.BLOCK][NpcAction.BLOCK_SHIELD] = canPlaceGlowstone(player, context.shield(), glowstone);
        mask[NpcAction.BLOCK][NpcAction.BLOCK_TOPBLOCK] = canPlaceGlowstone(player, context.topblock(), glowstone);
        boolean pearl = !player.isUsingItem() && NpcActions.hasItem(player, Items.ENDER_PEARL)
                && !player.getCooldowns().isOnCooldown(new ItemStack(Items.ENDER_PEARL));
        java.util.Arrays.fill(mask[NpcAction.PEARL], 1, sizes[NpcAction.PEARL], pearl);
        mask[NpcAction.UTIL][NpcAction.UTIL_TOTEM] = !player.getItemBySlot(EquipmentSlot.OFFHAND).is(Items.TOTEM_OF_UNDYING)
                && hasInInventory(player, Items.TOTEM_OF_UNDYING);
        mask[NpcAction.UTIL][NpcAction.UTIL_EAT] = !player.isUsingItem() && hasInInventory(player, Items.GOLDEN_APPLE);
        mask[NpcAction.UTIL][NpcAction.UTIL_TOTEM_MAIN] = !player.getMainHandItem().is(Items.TOTEM_OF_UNDYING)
                && hasInInventory(player, Items.TOTEM_OF_UNDYING);
        mask[NpcAction.UTIL][NpcAction.UTIL_XP] = !player.isUsingItem() && NpcActions.hasItem(player, Items.EXPERIENCE_BOTTLE)
                && armorDamaged(player);
        for (int i = 1; i < sizes[NpcAction.MINE]; i++) {
            BlockPos target = context.mineTargets()[i];
            mask[NpcAction.MINE][i] = target != null && canMine(player, target) && !target.equals(miningPos);
        }
        return mask;
    }

    private boolean canPlaceGlowstone(ServerPlayer player, @Nullable BlockPos cell, boolean hasGlowstone) {
        return cell != null && hasGlowstone
                && actions.planBlock(player, cell, Blocks.GLOWSTONE.defaultBlockState()).result().success();
    }

    /** Side-effect-free version of NpcActions.planMining (which selects the pickaxe). */
    private boolean canMine(ServerPlayer player, BlockPos pos) {
        ServerLevel level = player.level();
        BlockState state = level.getBlockState(pos);
        if (state.isAir() || state.canBeReplaced() || state.getDestroySpeed(level, pos) < 0.0F) {
            return false;
        }
        for (Item pickaxe : PICKAXES) {
            if (NpcActions.hasItem(player, pickaxe)) {
                return actions.visibleFace(player, pos) != null;
            }
        }
        return false;
    }

    private static boolean armorDamaged(ServerPlayer player) {
        for (EquipmentSlot slot : new EquipmentSlot[] {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
                EquipmentSlot.FEET}) {
            if (player.getItemBySlot(slot).isDamaged()) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasInInventory(ServerPlayer player, Item item) {
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            if (inventory.getItem(slot).is(item)) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- execution

    /**
     * Applies an action. Order within a step: totems, explode, sword, anchor use, crystal, anchor
     * place, block, pearl, eat/xp, mining, movement. Invalid choices are skipped (the mask should
     * have prevented them).
     *
     * @param holdTicks how long the movement input stays on (the decision interval)
     */
    public void execute(CrystalNpc self, NpcAction action, Context context, int holdTicks) {
        ServerPlayer player = self.handle();
        if (action.util() == NpcAction.UTIL_TOTEM) {
            actions.totemToOffhand(player);
        } else if (action.util() == NpcAction.UTIL_TOTEM_MAIN) {
            actions.totemToMainHand(player);
        }
        if (action.explode() > 0 && action.explode() <= context.sceneCrystals().size()) {
            actions.attackCrystal(player, context.sceneCrystals().get(action.explode() - 1));
        }
        if (action.attack() == 1) {
            actions.swordHit(player, context.opponent());
        }
        int anchorTarget = action.anchorUseTarget();
        if (anchorTarget >= 0 && anchorTarget < context.sceneAnchors().size()) {
            BlockPos anchor = context.sceneAnchors().get(anchorTarget);
            if (action.anchorUseDetonates()) {
                actions.detonateAnchor(player, anchor);
            } else {
                actions.chargeAnchor(player, anchor);
            }
        }
        if (action.crystal() > 0 && action.crystal() <= context.candidates().size()) {
            actions.placeCrystal(player, context.candidates().get(action.crystal() - 1).base(), true);
        }
        if (action.anchor() > 0 && action.anchor() <= context.anchorCandidates().size()) {
            actions.placeAnchor(player, context.anchorCandidates().get(action.anchor() - 1).pos());
        }
        BlockPos blockCell = action.block() == NpcAction.BLOCK_SHIELD ? context.shield()
                : action.block() == NpcAction.BLOCK_TOPBLOCK ? context.topblock() : null;
        if (blockCell != null) {
            actions.placeBlock(player, blockCell, Items.GLOWSTONE, Blocks.GLOWSTONE.defaultBlockState());
        }
        if (action.pearl() == NpcAction.PEARL_DOWN) {
            actions.throwPearl(player, player.getYRot(), 90.0F);
        } else if (action.pearl() > 0) {
            actions.throwPearl(player, context.yawToOpponent() + PEARL_YAW_OFFSETS[action.pearl()], pearlPitch);
        }
        if (action.util() == NpcAction.UTIL_EAT) {
            actions.eatGoldenApple(player);
        } else if (action.util() == NpcAction.UTIL_XP) {
            actions.throwXp(player);
        }
        if (action.mine() > 0) {
            BlockPos target = context.mineTargets()[action.mine()];
            if (target != null) {
                NpcActions.MinePlan plan = actions.planMining(player, target);
                if (plan.result().success()) {
                    self.startMining(target, plan.face());
                }
            }
        }
        self.setMovement(action.move(), holdTicks, action.sprint() == 1);
        if (action.jump() == 1) {
            self.requestJump();
        }
    }
}
