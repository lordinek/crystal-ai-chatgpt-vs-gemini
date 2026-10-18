package com.crystalai.plugin.action;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nullable;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * Discrete NPC actions, executed through the same server code paths a real client's
 * packets end up in (ServerPlayerGameMode.useItemOn / useItem, Player.attack).
 * Reach and line of sight are checked here because the packet handler normally does it.
 */
public final class NpcActions {

    /** Support blocks tried when placing obsidian: below first, like a player placing on the floor. */
    private static final Direction[] SUPPORT_ORDER = {
            Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP
    };

    private final ActionSettings settings;

    public NpcActions(ActionSettings settings) {
        this.settings = settings;
    }

    public ActionSettings settings() {
        return settings;
    }

    // ---------------------------------------------------------------- blocks

    /** Where to click to place a block: the neighbouring solid block and the face of it. */
    public record BlockClick(BlockPos clicked, Direction face) {
    }

    /** Result of {@link #planObsidian}: either a click to perform, or why it is impossible. */
    public record ObsidianPlan(ActionResult result, @Nullable BlockClick click) {
    }

    /**
     * Checks, without changing anything, whether obsidian can be placed at {@code pos} right now:
     * space free of blocks and entities, a solid neighbour to click, in reach and in sight.
     * Does not check the inventory.
     */
    public ObsidianPlan planObsidian(ServerPlayer player, BlockPos pos) {
        return planBlock(player, pos, Blocks.OBSIDIAN.defaultBlockState());
    }

    /** {@link #planObsidian} for any block (anchor, glowstone). */
    public ObsidianPlan planBlock(ServerPlayer player, BlockPos pos, BlockState block) {
        ServerLevel level = player.level();
        if (!level.getBlockState(pos).canBeReplaced()) {
            return new ObsidianPlan(ActionResult.fail("occupied"), null);
        }
        if (!level.isUnobstructed(block, pos, CollisionContext.empty())) {
            return new ObsidianPlan(ActionResult.fail("entity_in_way"), null);
        }
        String lastFailure = "no_support";
        for (Direction toSupport : SUPPORT_ORDER) {
            BlockPos support = pos.relative(toSupport);
            BlockState supportState = level.getBlockState(support);
            // Clicking an anchor uses it (charge / explode) instead of placing against it.
            if (supportState.canBeReplaced() || supportState.is(Blocks.RESPAWN_ANCHOR)) {
                continue;
            }
            Direction face = toSupport.getOpposite();
            ActionResult reachable = checkBlockTarget(player, support, face);
            if (reachable.success()) {
                return new ObsidianPlan(ActionResult.OK, new BlockClick(support, face));
            }
            lastFailure = reachable.reason();
        }
        return new ObsidianPlan(ActionResult.fail(lastFailure), null);
    }

    /** Places obsidian so that it occupies {@code pos}, clicking a face of a neighbouring solid block. */
    public ActionResult placeObsidian(ServerPlayer player, BlockPos pos) {
        ObsidianPlan plan = planObsidian(player, pos);
        if (plan.click() == null) {
            return plan.result();
        }
        return useOnBlock(player, Items.OBSIDIAN, plan.click().clicked(), plan.click().face());
    }

    /** Places a block item (glowstone shield, topblock) so that it occupies {@code pos}. */
    public ActionResult placeBlock(ServerPlayer player, BlockPos pos, Item item, BlockState block) {
        if (!hasItem(player, item)) {
            return ActionResult.fail("no_item");
        }
        ObsidianPlan plan = planBlock(player, pos, block);
        if (plan.click() == null) {
            return plan.result();
        }
        return useOnBlock(player, item, plan.click().clicked(), plan.click().face());
    }

    // ---------------------------------------------------------------- pickaxe

    /** Face to mine {@code pos} from, or why not. Mining itself runs over ticks in CrystalNpc. */
    public record MinePlan(ActionResult result, Direction face) {
    }

    public MinePlan planMining(ServerPlayer player, BlockPos pos) {
        ServerLevel level = player.level();
        BlockState state = level.getBlockState(pos);
        if (state.isAir() || state.canBeReplaced()) {
            return new MinePlan(ActionResult.fail("nothing_to_mine"), Direction.UP);
        }
        if (state.getDestroySpeed(level, pos) < 0.0F) {
            return new MinePlan(ActionResult.fail("unbreakable"), Direction.UP); // bedrock, barrier
        }
        if (!hasItem(player, Items.NETHERITE_PICKAXE) && !hasItem(player, Items.DIAMOND_PICKAXE)) {
            return new MinePlan(ActionResult.fail("no_item"), Direction.UP);
        }
        Direction face = visibleFace(player, pos);
        if (face == null) {
            Direction nearest = nearestFace(player, pos);
            return new MinePlan(checkBlockTarget(player, pos, nearest).success()
                    ? ActionResult.fail("no_line_of_sight") : checkBlockTarget(player, pos, nearest), nearest);
        }
        ActionResult selected = selectAnyOf(player, Items.NETHERITE_PICKAXE, Items.DIAMOND_PICKAXE);
        return new MinePlan(selected, face);
    }

    // ---------------------------------------------------------------- respawn anchor

    /** Places a respawn anchor at {@code pos}. */
    public ActionResult placeAnchor(ServerPlayer player, BlockPos pos) {
        return placeBlock(player, pos, Items.RESPAWN_ANCHOR, Blocks.RESPAWN_ANCHOR.defaultBlockState());
    }

    public static int anchorCharge(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.is(Blocks.RESPAWN_ANCHOR) ? state.getValue(RespawnAnchorBlock.CHARGE) : -1;
    }

    /** Adds one charge with glowstone (vanilla: max 4). */
    public ActionResult chargeAnchor(ServerPlayer player, BlockPos pos) {
        int charge = anchorCharge(player.level(), pos);
        if (charge < 0) {
            return ActionResult.fail("no_anchor");
        }
        if (charge >= RespawnAnchorBlock.MAX_CHARGES) {
            return ActionResult.fail("full");
        }
        if (!hasItem(player, Items.GLOWSTONE)) {
            return ActionResult.fail("no_item");
        }
        Direction face = visibleFace(player, pos);
        if (face == null) {
            return checkBlockTarget(player, pos, nearestFace(player, pos));
        }
        return useOnBlock(player, Items.GLOWSTONE, pos, face);
    }

    /**
     * Detonates a charged anchor (outside the nether a charged anchor explodes when used with
     * anything but glowstone): power 5 with fire. Holds the sword, or any non-glowstone item.
     */
    public ActionResult detonateAnchor(ServerPlayer player, BlockPos pos) {
        int charge = anchorCharge(player.level(), pos);
        if (charge < 0) {
            return ActionResult.fail("no_anchor");
        }
        if (charge == 0) {
            // An empty anchor does nothing, and the held item would be used instead (e.g. placed).
            return ActionResult.fail("not_charged");
        }
        Direction face = visibleFace(player, pos);
        if (face == null) {
            return checkBlockTarget(player, pos, nearestFace(player, pos));
        }
        if (player.getMainHandItem().is(Items.GLOWSTONE) || player.getOffhandItem().is(Items.GLOWSTONE)) {
            ActionResult selected = selectAnyOf(player, Items.NETHERITE_SWORD, Items.DIAMOND_SWORD,
                    Items.TOTEM_OF_UNDYING, Items.END_CRYSTAL);
            if (!selected.success()) {
                return ActionResult.fail("glowstone_in_hand");
            }
        }
        Vec3 hitVec = faceCenter(pos, face);
        player.lookAt(EntityAnchorArgument.Anchor.EYES, hitVec);
        InteractionResult result = player.gameMode.useItemOn(player, player.level(), player.getMainHandItem(),
                InteractionHand.MAIN_HAND, new BlockHitResult(hitVec, face, pos, false));
        if (!result.consumesAction()) {
            return ActionResult.fail("rejected_" + result.getClass().getSimpleName().toLowerCase(java.util.Locale.ROOT));
        }
        player.swing(InteractionHand.MAIN_HAND, true);
        return ActionResult.OK;
    }

    /** Anchors within block reach, nearest first. */
    public List<BlockPos> anchorsInReach(ServerPlayer player) {
        int r = (int) Math.ceil(settings.blockReach()) + 1;
        Vec3 eye = player.getEyePosition();
        return BlockPos.betweenClosedStream(player.blockPosition().offset(-r, -r, -r), player.blockPosition().offset(r, r, r))
                .filter(pos -> player.level().getBlockState(pos).is(Blocks.RESPAWN_ANCHOR))
                .map(BlockPos::immutable)
                .sorted(Comparator.comparingDouble(pos -> eye.distanceToSqr(Vec3.atCenterOf(pos))))
                .toList();
    }

    /** Face of {@code pos} that is in reach and in sight, nearest to the eyes first; null if none. */
    public @Nullable Direction visibleFace(ServerPlayer player, BlockPos pos) {
        Vec3 eye = player.getEyePosition();
        return java.util.Arrays.stream(Direction.values())
                .filter(face -> player.level().getBlockState(pos.relative(face)).canBeReplaced())
                .sorted(Comparator.comparingDouble(face -> eye.distanceToSqr(faceCenter(pos, face))))
                .filter(face -> checkBlockTarget(player, pos, face).success())
                .findFirst().orElse(null);
    }

    private static Direction nearestFace(ServerPlayer player, BlockPos pos) {
        Vec3 eye = player.getEyePosition();
        return java.util.Arrays.stream(Direction.values())
                .min(Comparator.comparingDouble(face -> eye.distanceToSqr(faceCenter(pos, face))))
                .orElse(Direction.UP);
    }

    /** Whether the crystal space above {@code base} is free (vanilla EndCrystalItem rules). */
    public static boolean crystalSpaceFree(ServerLevel level, BlockPos base) {
        BlockPos above = base.above();
        if (!level.isEmptyBlock(above)) {
            return false;
        }
        AABB space = new AABB(above.getX(), above.getY(), above.getZ(),
                above.getX() + 1.0, above.getY() + 2.0, above.getZ() + 1.0);
        return level.getEntities((net.minecraft.world.entity.Entity) null, space).isEmpty();
    }

    public static boolean hasItem(ServerPlayer player, Item item) {
        // Not Inventory.contains(ItemStack): since 1.20.5 that also compares components, so an
        // enchanted sword would not count as a sword.
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (inventory.getItem(slot).is(item)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Places an end crystal on top of {@code base}. When the base is not obsidian/bedrock and
     * {@code placeObsidianFirst} is set, obsidian is placed there first (same tick, two clicks).
     */
    public ActionResult placeCrystal(ServerPlayer player, BlockPos base, boolean placeObsidianFirst) {
        ActionResult plan = planCrystal(player, base, placeObsidianFirst);
        if (!plan.success()) {
            return plan;
        }
        if (!isCrystalBase(player.level().getBlockState(base))) {
            ActionResult obsidian = placeObsidian(player, base);
            if (!obsidian.success()) {
                return ActionResult.fail("obsidian_" + obsidian.reason());
            }
        }
        return useOnBlock(player, Items.END_CRYSTAL, base, Direction.UP);
    }

    /**
     * Checks, without changing anything, whether {@link #placeCrystal} would succeed now:
     * items, base (or placeable obsidian), free crystal space, reach and line of sight.
     */
    public ActionResult planCrystal(ServerPlayer player, BlockPos base, boolean placeObsidianFirst) {
        ServerLevel level = player.level();
        if (!crystalSpaceFree(level, base)) {
            return ActionResult.fail("blocked");
        }
        if (!hasItem(player, Items.END_CRYSTAL)) {
            return ActionResult.fail("no_crystal_item");
        }
        if (!isCrystalBase(level.getBlockState(base))) {
            if (!placeObsidianFirst) {
                return ActionResult.fail("no_base");
            }
            if (!hasItem(player, Items.OBSIDIAN)) {
                return ActionResult.fail("no_obsidian_item");
            }
            ObsidianPlan obsidian = planObsidian(player, base);
            if (!obsidian.result().success()) {
                return ActionResult.fail("obsidian_" + obsidian.result().reason());
            }
        }
        return checkBlockTarget(player, base, Direction.UP);
    }

    public static boolean isCrystalBase(BlockState state) {
        return state.is(Blocks.OBSIDIAN) || state.is(Blocks.BEDROCK);
    }

    private ActionResult useOnBlock(ServerPlayer player, Item item, BlockPos clicked, Direction face) {
        ActionResult selected = selectItem(player, item);
        if (!selected.success()) {
            return selected;
        }
        Vec3 hitVec = faceCenter(clicked, face);
        player.lookAt(EntityAnchorArgument.Anchor.EYES, hitVec);
        BlockHitResult hit = new BlockHitResult(hitVec, face, clicked, false);
        InteractionResult result = player.gameMode.useItemOn(
                player, player.level(), player.getMainHandItem(), InteractionHand.MAIN_HAND, hit);
        if (!result.consumesAction()) {
            return ActionResult.fail("rejected_" + result.getClass().getSimpleName().toLowerCase(java.util.Locale.ROOT));
        }
        player.swing(InteractionHand.MAIN_HAND, true);
        return ActionResult.OK;
    }

    /** Reach + line of sight to the centre of {@code face} of block {@code clicked}. */
    public ActionResult checkBlockTarget(ServerPlayer player, BlockPos clicked, Direction face) {
        Vec3 eye = player.getEyePosition();
        Vec3 hitVec = faceCenter(clicked, face);
        if (eye.distanceTo(hitVec) > settings.blockReach()) {
            return ActionResult.fail("out_of_reach");
        }
        if (settings.requireLineOfSight()) {
            // Aim slightly inside the block so the ray reliably ends on it.
            Vec3 inside = hitVec.relative(face, -0.01);
            BlockHitResult ray = player.level().clip(
                    new ClipContext(eye, inside, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
            if (ray.getType() == HitResult.Type.BLOCK && !ray.getBlockPos().equals(clicked)) {
                return ActionResult.fail("no_line_of_sight");
            }
        }
        return ActionResult.OK;
    }

    private static Vec3 faceCenter(BlockPos pos, Direction face) {
        return Vec3.atCenterOf(pos).relative(face, 0.5);
    }

    // ---------------------------------------------------------------- crystals

    /** Crystals whose hitbox is within entity reach, nearest first. */
    public List<EndCrystal> crystalsInReach(ServerPlayer player) {
        double reach = settings.entityReach();
        Vec3 eye = player.getEyePosition();
        return player.level().getEntitiesOfClass(EndCrystal.class, player.getBoundingBox().inflate(reach + 2.0),
                        crystal -> crystal.isAlive() && distanceToBox(eye, crystal.getBoundingBox()) <= reach)
                .stream()
                .sorted(Comparator.comparingDouble(crystal -> distanceToBox(eye, crystal.getBoundingBox())))
                .toList();
    }

    /** Whether {@link #attackCrystal} would hit now (alive, in reach, in sight). */
    public ActionResult checkAttack(ServerPlayer player, EndCrystal crystal) {
        if (!crystal.isAlive()) {
            return ActionResult.fail("gone");
        }
        if (distanceToBox(player.getEyePosition(), crystal.getBoundingBox()) > settings.entityReach()) {
            return ActionResult.fail("out_of_reach");
        }
        if (settings.requireLineOfSight() && !player.hasLineOfSight(crystal)) {
            return ActionResult.fail("no_line_of_sight");
        }
        return ActionResult.OK;
    }

    public ActionResult attackCrystal(ServerPlayer player, EndCrystal crystal) {
        ActionResult check = checkAttack(player, crystal);
        if (!check.success()) {
            return check;
        }
        player.lookAt(EntityAnchorArgument.Anchor.EYES, crystal.getBoundingBox().getCenter());
        player.attack(crystal);
        player.swing(InteractionHand.MAIN_HAND, true);
        return ActionResult.OK;
    }

    /** Attacks the nearest crystal in reach and line of sight. */
    public ActionResult attackNearestCrystal(ServerPlayer player) {
        Optional<EndCrystal> target = crystalsInReach(player).stream()
                .filter(crystal -> !settings.requireLineOfSight() || player.hasLineOfSight(crystal))
                .findFirst();
        return target.map(crystal -> attackCrystal(player, crystal)).orElse(ActionResult.fail("no_crystal"));
    }

    // ---------------------------------------------------------------- sword

    /** Whether a sword hit on {@code target} is possible now: reach, sight and a charged swing. */
    public ActionResult checkSwordHit(ServerPlayer player, LivingEntity target) {
        if (!target.isAlive()) {
            return ActionResult.fail("gone");
        }
        if (distanceToBox(player.getEyePosition(), target.getBoundingBox()) > settings.entityReach()) {
            return ActionResult.fail("out_of_reach");
        }
        if (settings.requireLineOfSight() && !player.hasLineOfSight(target)) {
            return ActionResult.fail("no_line_of_sight");
        }
        if (player.getAttackStrengthScale(0.5F) < settings.swordMinCharge()) {
            return ActionResult.fail("cooldown");
        }
        if (!hasItem(player, Items.NETHERITE_SWORD) && !hasItem(player, Items.DIAMOND_SWORD)) {
            return ActionResult.fail("no_item");
        }
        return ActionResult.OK;
    }

    /**
     * Hits {@code target} with the sword (Player.attack: knockback, sprint knockback, crits while
     * falling, sweep). This is what lifts the opponent for a hit crystal.
     */
    public ActionResult swordHit(ServerPlayer player, LivingEntity target) {
        ActionResult check = checkSwordHit(player, target);
        if (!check.success()) {
            return check;
        }
        // Selecting a slot does not reset the swing timer on the server; only the attack does.
        ActionResult selected = selectAnyOf(player, Items.NETHERITE_SWORD, Items.DIAMOND_SWORD);
        if (!selected.success()) {
            return selected;
        }
        player.lookAt(EntityAnchorArgument.Anchor.EYES, target.getBoundingBox().getCenter());
        player.attack(target);
        player.swing(InteractionHand.MAIN_HAND, true);
        return ActionResult.OK;
    }

    static double distanceToBox(Vec3 point, AABB box) {
        double dx = Math.max(Math.max(box.minX - point.x, 0.0), point.x - box.maxX);
        double dy = Math.max(Math.max(box.minY - point.y, 0.0), point.y - box.maxY);
        double dz = Math.max(Math.max(box.minZ - point.z, 0.0), point.z - box.maxZ);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    // ---------------------------------------------------------------- items

    /** Moves a totem from the inventory into the offhand, swapping out whatever was there. */
    public ActionResult totemToOffhand(ServerPlayer player) {
        if (player.getItemBySlot(EquipmentSlot.OFFHAND).is(Items.TOTEM_OF_UNDYING)) {
            return ActionResult.fail("already_holding");
        }
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.is(Items.TOTEM_OF_UNDYING)) {
                ItemStack previous = player.getItemBySlot(EquipmentSlot.OFFHAND);
                player.setItemSlot(EquipmentSlot.OFFHAND, stack);
                inventory.setItem(slot, previous);
                return ActionResult.OK;
            }
        }
        return ActionResult.fail("no_item");
    }

    /** Starts eating a golden apple from the main hand. Finishing takes 32 ticks, like a player. */
    public ActionResult eatGoldenApple(ServerPlayer player) {
        if (player.isUsingItem()) {
            return ActionResult.fail("busy");
        }
        ActionResult selected = selectItem(player, Items.GOLDEN_APPLE);
        if (!selected.success()) {
            return selected;
        }
        InteractionResult result = player.gameMode.useItem(
                player, player.level(), player.getMainHandItem(), InteractionHand.MAIN_HAND);
        return result.consumesAction() ? ActionResult.OK : ActionResult.fail("rejected");
    }

    /** Holds a totem in the main hand too ("double hand"), so two lethal hits in a row are survivable. */
    public ActionResult totemToMainHand(ServerPlayer player) {
        if (player.getMainHandItem().is(Items.TOTEM_OF_UNDYING)) {
            return ActionResult.fail("already_holding");
        }
        return selectItem(player, Items.TOTEM_OF_UNDYING);
    }

    /**
     * Throws an ender pearl at an absolute yaw/pitch (degrees, Minecraft convention: pitch -90 is
     * up). Vanilla: 20 tick cooldown, 5 damage to the thrower on landing.
     */
    public ActionResult throwPearl(ServerPlayer player, float yaw, float pitch) {
        if (!hasItem(player, Items.ENDER_PEARL)) {
            return ActionResult.fail("no_item");
        }
        if (player.getCooldowns().isOnCooldown(new ItemStack(Items.ENDER_PEARL))) {
            return ActionResult.fail("cooldown");
        }
        return useInAir(player, Items.ENDER_PEARL, yaw, pitch);
    }

    /** Throws an experience bottle at the feet (mending repairs 2 durability per xp point). */
    public ActionResult throwXp(ServerPlayer player) {
        if (!hasItem(player, Items.EXPERIENCE_BOTTLE)) {
            return ActionResult.fail("no_item");
        }
        return useInAir(player, Items.EXPERIENCE_BOTTLE, player.getYRot(), 90.0F);
    }

    /** Right click in the air with {@code item}, looking in the given direction. */
    private ActionResult useInAir(ServerPlayer player, Item item, float yaw, float pitch) {
        if (player.isUsingItem()) {
            return ActionResult.fail("busy");
        }
        ActionResult selected = selectItem(player, item);
        if (!selected.success()) {
            return selected;
        }
        player.setYRot(yaw);
        player.setYHeadRot(yaw);
        player.setXRot(Math.max(-90.0F, Math.min(90.0F, pitch)));
        InteractionResult result = player.gameMode.useItem(
                player, player.level(), player.getMainHandItem(), InteractionHand.MAIN_HAND);
        if (!result.consumesAction()) {
            return ActionResult.fail("rejected");
        }
        player.swing(InteractionHand.MAIN_HAND, true);
        return ActionResult.OK;
    }

    /** {@link #selectItem} for the first of {@code items} the player has. */
    public ActionResult selectAnyOf(ServerPlayer player, Item... items) {
        for (Item item : items) {
            if (hasItem(player, item)) {
                return selectItem(player, item);
            }
        }
        return ActionResult.fail("no_item");
    }

    /**
     * Selects a hotbar slot holding {@code item}. If it only exists in the main inventory it is
     * swapped into the fallback hotbar slot (a player's number-key swap).
     */
    public ActionResult selectItem(ServerPlayer player, Item item) {
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) {
            if (inventory.getItem(slot).is(item)) {
                setSelectedSlot(player, slot);
                return ActionResult.OK;
            }
        }
        for (int slot = Inventory.getSelectionSize(); slot < Inventory.INVENTORY_SIZE; slot++) {
            if (inventory.getItem(slot).is(item)) {
                int target = settings.fallbackHotbarSlot();
                ItemStack moved = inventory.getItem(slot);
                inventory.setItem(slot, inventory.getItem(target));
                inventory.setItem(target, moved);
                setSelectedSlot(player, target);
                return ActionResult.OK;
            }
        }
        return ActionResult.fail("no_item");
    }

    private static void setSelectedSlot(ServerPlayer player, int slot) {
        Inventory inventory = player.getInventory();
        if (inventory.getSelectedSlot() != slot) {
            // Same rule as the carried-item packet handler: switching slots cancels main-hand use.
            if (player.isUsingItem() && player.getUsedItemHand() == InteractionHand.MAIN_HAND) {
                player.stopUsingItem();
            }
            inventory.setSelectedSlot(slot);
        }
    }
}
