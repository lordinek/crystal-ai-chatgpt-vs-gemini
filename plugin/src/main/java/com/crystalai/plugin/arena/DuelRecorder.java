package com.crystalai.plugin.arena;

import com.crystalai.plugin.action.NpcActions;
import com.crystalai.plugin.bridge.ObservationBuilder;
import com.crystalai.plugin.bridge.ObservationFrame;
import com.crystalai.plugin.combat.AnchorCandidate;
import com.crystalai.plugin.combat.CrystalCandidate;
import com.crystalai.plugin.control.ActionSpace;
import com.crystalai.plugin.control.NpcAction;
import com.crystalai.plugin.npc.CrystalNpc;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.logging.Level;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import org.bukkit.Input;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Player;
import org.bukkit.entity.ThrownExpBottle;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageAbortEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Records the human's side of a duel for imitation learning: every tick the observation the
 * policy would see in the human's place (against the AI or another human), its action mask, and the human's action translated into
 * the policy's action space (schema v2). The translation is inferred from the input keys and from
 * events between two ticks. A choice the policy could not have made (a crystal on a block that is
 * not a candidate, a mask that forbids it) is written as -1 so training can ignore that branch.
 *
 * <p>Output: {@code plugins/CrystalAI/recordings/<time>_<player>_<model>.jsonl}, a header line,
 * one {@code {"t","o","m","a"}} line per tick, and an {@code "end"} line.
 */
final class DuelRecorder implements Listener {

    private static final float[] PEARL_YAW_OFFSETS = {0, 0, 45, 90, 135, 180, -135, -90, -45};
    private static final int UNMAPPED = -1;

    private final JavaPlugin plugin;
    private final ActionSpace space;
    private final ObservationBuilder builder;
    private final ObservationFrame frame;
    private final Player human;
    private final ServerPlayer self;
    private final ServerPlayer opponent;
    private final BufferedWriter out;
    private final Path file;

    // snapshot taken at the start of a tick, labeled with what the human did until the next one
    private @Nullable ActionSpace.Context context;
    private @Nullable float[] obs;
    private @Nullable boolean[][] mask;
    private int snapshotTick;
    private boolean offhandTotem;
    private boolean mainhandTotem;
    private boolean eating;
    private final int[] action = new int[NpcAction.BRANCH_SIZES.length];

    // the human's mining, as the server sees it
    private @Nullable BlockPos digging;
    private int diggingTicks;

    private int samples;
    private final int[] used = new int[NpcAction.BRANCH_SIZES.length];
    private final int[] unmapped = new int[NpcAction.BRANCH_SIZES.length];

    private DuelRecorder(JavaPlugin plugin, ActionSpace space, ObservationBuilder builder, ObservationFrame frame,
                         Player human, ServerPlayer opponent, Path file, BufferedWriter out) {
        this.plugin = plugin;
        this.space = space;
        this.builder = builder;
        this.frame = frame;
        this.human = human;
        this.self = ((CraftPlayer) human).getHandle();
        this.opponent = opponent;
        this.file = file;
        this.out = out;
    }

    static @Nullable DuelRecorder open(JavaPlugin plugin, ActionSpace space, ObservationBuilder builder,
                                       ObservationFrame frame, Player human, ServerPlayer opponent, String model) {
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
        Path file = plugin.getDataFolder().toPath().resolve("recordings")
                .resolve(stamp + "_" + human.getName() + "_" + model + ".jsonl");
        try {
            Files.createDirectories(file.getParent());
            BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
            out.write("{\"version\":1,\"protocol\":2,\"player\":\"" + human.getName() + "\",\"model\":\"" + model
                    + "\",\"branches\":" + Arrays.toString(NpcAction.BRANCH_SIZES) + "}\n");
            DuelRecorder recorder = new DuelRecorder(plugin, space, builder, frame, human, opponent, file, out);
            plugin.getServer().getPluginManager().registerEvents(recorder, plugin);
            return recorder;
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "duel recording could not start: " + file, e);
            return null;
        }
    }

    // ---------------------------------------------------------------- ticking

    /** Call once per fight tick, before the AI acts: closes the previous sample, opens the next one. */
    void tick(int tick) {
        if (digging != null) {
            diggingTicks++;
        }
        if (context != null) {
            label();
            write();
        }
        context = space.observe(self, opponent);
        mask = space.mask(self, digging, context);
        obs = builder.build(self, digging != null, miningProgress(), opponent, context, frame);
        snapshotTick = tick;
        offhandTotem = self.getOffhandItem().is(Items.TOTEM_OF_UNDYING);
        mainhandTotem = self.getMainHandItem().is(Items.TOTEM_OF_UNDYING);
        eating = eatingGapple();
        Arrays.fill(action, 0);
    }

    /** Ends the recording; {@code winner} is "self", "opponent" (from the recorded player's side) or null. */
    void close(@Nullable String winner, String reason, int ticks) {
        org.bukkit.event.HandlerList.unregisterAll(this);
        try {
            out.write("{\"end\":\"" + reason.replace("\"", "'") + "\",\"winner\":"
                    + (winner == null ? "null" : "\"" + winner + "\"") + ",\"ticks\":" + ticks + ",\"samples\":"
                    + samples + ",\"used\":" + Arrays.toString(used) + ",\"unmapped\":" + Arrays.toString(unmapped) + "}\n");
            out.close();
            plugin.getLogger().info("[duel] kayıt: " + file.getFileName() + " (" + samples + " örnek)");
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "duel recording could not be closed: " + file, e);
        }
    }

    private boolean eatingGapple() {
        return self.isUsingItem() && self.getUseItem().is(Items.GOLDEN_APPLE);
    }

    private float miningProgress() {
        if (digging == null) {
            return 0.0F;
        }
        BlockState state = self.level().getBlockState(digging);
        return Math.min(1.0F, diggingTicks * state.getDestroyProgress(self, self.level(), digging));
    }

    // ---------------------------------------------------------------- labeling

    /** Movement, jump, sprint and hand changes, from the state now; the rest came from events. */
    private void label() {
        Input input = human.getCurrentInput();
        int forward = (input.isForward() ? 1 : 0) - (input.isBackward() ? 1 : 0);
        int left = (input.isLeft() ? 1 : 0) - (input.isRight() ? 1 : 0);
        action[NpcAction.MOVE] = moveDirection(forward, left);
        action[NpcAction.JUMP] = input.isJump() ? 1 : 0;
        action[NpcAction.SPRINT] = forward > 0 && (input.isSprint() || self.isSprinting()) ? 1 : 0;
        if (action[NpcAction.UTIL] == 0) {
            if (!offhandTotem && self.getOffhandItem().is(Items.TOTEM_OF_UNDYING)) {
                action[NpcAction.UTIL] = NpcAction.UTIL_TOTEM;
            } else if (!mainhandTotem && self.getMainHandItem().is(Items.TOTEM_OF_UNDYING)) {
                action[NpcAction.UTIL] = NpcAction.UTIL_TOTEM_MAIN;
            } else if (!eating && eatingGapple()) {
                action[NpcAction.UTIL] = NpcAction.UTIL_EAT;
            }
        }
        for (int branch = 0; branch < action.length; branch++) {
            int choice = action[branch];
            if (choice > 0 && !mask[branch][choice]) {
                action[branch] = UNMAPPED; // the policy could not have done this right now
            }
            if (action[branch] > 0) {
                used[branch]++;
            } else if (action[branch] == UNMAPPED) {
                unmapped[branch]++;
            }
        }
    }

    /**
     * Held keys as one of CrystalNpc.MOVE_DIRECTIONS: the NPC always faces the opponent, so the
     * human's key direction (relative to where they look) is turned into the opponent frame.
     */
    private int moveDirection(int forward, int left) {
        if (forward == 0 && left == 0) {
            return 0;
        }
        float yaw = self.getYRot() * Mth.DEG_TO_RAD;
        // Minecraft: facing yaw 0 = +z, forward = (-sin, cos), left = (cos, sin)
        double wx = forward * -Mth.sin(yaw) + left * Mth.cos(yaw);
        double wz = forward * Mth.cos(yaw) + left * Mth.sin(yaw);
        float toOpponent = context.yawToOpponent() * Mth.DEG_TO_RAD;
        double fx = -Mth.sin(toOpponent);
        double fz = Mth.cos(toOpponent);
        double along = wx * fx + wz * fz;          // toward the opponent
        double leftward = wx * fz - wz * fx;       // left of "toward the opponent" = (fz, -fx)
        double length = Math.sqrt(along * along + leftward * leftward);
        int best = 0;
        double bestDot = -2;
        float[][] directions = CrystalNpc.MOVE_DIRECTIONS;
        for (int i = 1; i < directions.length; i++) {
            double dz = directions[i][0];
            double dl = directions[i][1];
            double dot = (along * dz + leftward * dl) / (length * Math.sqrt(dz * dz + dl * dl));
            if (dot > bestDot) {
                bestDot = dot;
                best = i;
            }
        }
        return best;
    }

    private void set(int branch, int choice) {
        if (context != null && action[branch] == 0) {
            action[branch] = choice;
        }
    }

    private boolean mine(Player player) {
        return context != null && player.equals(human);
    }

    private static BlockPos pos(Block block) {
        return new BlockPos(block.getX(), block.getY(), block.getZ());
    }

    private int crystalCandidate(BlockPos base) {
        for (int i = 0; i < context.candidates().size() && i + 1 < NpcAction.BRANCH_SIZES[NpcAction.CRYSTAL]; i++) {
            CrystalCandidate candidate = context.candidates().get(i);
            if (candidate.base().equals(base)) {
                return i + 1;
            }
        }
        return UNMAPPED;
    }

    // ---------------------------------------------------------------- events

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityPlace(EntityPlaceEvent event) {
        if (event.getPlayer() != null && mine(event.getPlayer()) && event.getEntity() instanceof EnderCrystal) {
            set(NpcAction.CRYSTAL, crystalCandidate(pos(event.getBlock())));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!mine(event.getPlayer())) {
            return;
        }
        BlockPos pos = pos(event.getBlockPlaced());
        Material type = event.getBlockPlaced().getType();
        if (type == Material.OBSIDIAN) {
            // The policy's crystal choice places the obsidian base first when a candidate has none.
            int candidate = crystalCandidate(pos);
            if (candidate != UNMAPPED) {
                set(NpcAction.CRYSTAL, candidate);
            }
        } else if (type == Material.RESPAWN_ANCHOR) {
            int choice = UNMAPPED;
            for (int i = 0; i < context.anchorCandidates().size() && i + 1 < NpcAction.BRANCH_SIZES[NpcAction.ANCHOR]; i++) {
                AnchorCandidate candidate = context.anchorCandidates().get(i);
                if (candidate.pos().equals(pos)) {
                    choice = i + 1;
                    break;
                }
            }
            set(NpcAction.ANCHOR, choice);
        } else if (type == Material.GLOWSTONE) {
            set(NpcAction.BLOCK, pos.equals(context.shield()) ? NpcAction.BLOCK_SHIELD
                    : pos.equals(context.topblock()) ? NpcAction.BLOCK_TOPBLOCK : UNMAPPED);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player) || !mine(player)) {
            return;
        }
        if (event.getEntity() instanceof EnderCrystal crystal) {
            int choice = UNMAPPED;
            for (int i = 0; i < context.sceneCrystals().size(); i++) {
                EndCrystal scene = context.sceneCrystals().get(i);
                if (scene.getUUID().equals(crystal.getUniqueId())) {
                    choice = i + 1;
                    break;
                }
            }
            set(NpcAction.EXPLODE, choice);
        } else if (event.getEntity().getUniqueId().equals(opponent.getUUID())
                && (event.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK
                || event.getCause() == EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK)) {
            set(NpcAction.ATTACK, 1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteract(PlayerInteractEvent event) {
        if (!mine(event.getPlayer()) || event.getHand() != EquipmentSlot.HAND
                || event.getAction() != Action.RIGHT_CLICK_BLOCK && event.getAction() != Action.RIGHT_CLICK_AIR) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block != null && block.getType() == Material.RESPAWN_ANCHOR) {
            BlockPos anchor = pos(block);
            int index = context.sceneAnchors().indexOf(anchor);
            int charge = NpcActions.anchorCharge(self.level(), anchor);
            boolean charging = event.getMaterial() == Material.GLOWSTONE && charge < 4;
            if (index < 0) {
                set(NpcAction.ANCHOR_USE, UNMAPPED);
            } else if (charging) {
                set(NpcAction.ANCHOR_USE, 1 + index);
            } else if (charge > 0) {
                set(NpcAction.ANCHOR_USE, 1 + NpcAction.SCENE_ANCHORS + index);
            }
            return;
        }
        if (event.getMaterial() == Material.GOLDEN_APPLE && !self.isUsingItem()) {
            set(NpcAction.UTIL, NpcAction.UTIL_EAT);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLaunch(ProjectileLaunchEvent event) {
        if (!(event.getEntity().getShooter() instanceof Player player) || !mine(player)) {
            return;
        }
        if (event.getEntity() instanceof ThrownExpBottle) {
            set(NpcAction.UTIL, NpcAction.UTIL_XP);
        } else if (event.getEntity() instanceof EnderPearl) {
            float pitch = player.getLocation().getPitch();
            if (pitch > 60) {
                set(NpcAction.PEARL, NpcAction.PEARL_DOWN);
                return;
            }
            float relative = Mth.wrapDegrees(player.getLocation().getYaw() - context.yawToOpponent());
            int best = 1;
            float bestGap = Float.MAX_VALUE;
            for (int i = 1; i < PEARL_YAW_OFFSETS.length; i++) {
                float gap = Math.abs(Mth.wrapDegrees(relative - PEARL_YAW_OFFSETS[i]));
                if (gap < bestGap) {
                    bestGap = gap;
                    best = i;
                }
            }
            set(NpcAction.PEARL, best);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDig(BlockDamageEvent event) {
        if (!mine(event.getPlayer())) {
            return;
        }
        BlockPos pos = pos(event.getBlock());
        digging = pos;
        diggingTicks = 0;
        BlockPos[] targets = context.mineTargets();
        int choice = UNMAPPED;
        for (int i = 1; i < targets.length; i++) {
            if (pos.equals(targets[i])) {
                choice = i;
                break;
            }
        }
        set(NpcAction.MINE, choice);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDigAbort(BlockDamageAbortEvent event) {
        if (event.getPlayer().equals(human)) {
            digging = null;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBreak(BlockBreakEvent event) {
        if (event.getPlayer().equals(human)) {
            digging = null;
        }
    }

    // ---------------------------------------------------------------- output

    private void write() {
        StringBuilder line = new StringBuilder(2600);
        line.append("{\"t\":").append(snapshotTick).append(",\"o\":[");
        for (int i = 0; i < obs.length; i++) {
            if (i > 0) {
                line.append(',');
            }
            float v = obs[i];
            if (v == (int) v) {
                line.append((int) v);
            } else {
                line.append(Math.round(v * 10000.0F) / 10000.0F);
            }
        }
        line.append("],\"m\":\"");
        for (boolean[] branch : mask) {
            for (boolean allowed : branch) {
                line.append(allowed ? '1' : '0');
            }
        }
        line.append("\",\"a\":").append(Arrays.toString(action).replace(" ", "")).append("}\n");
        try {
            out.write(line.toString());
            samples++;
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "duel recording write failed: " + file, e);
        }
    }
}
