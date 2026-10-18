package com.crystalai.plugin.npc;

import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.bukkit.entity.Player;

/** One fake-player NPC: an NMS ServerPlayer driven by the plugin instead of a client. */
public final class CrystalNpc {

    /**
     * Movement inputs relative to the focus target, as {forward, left}.
     * 0 stop, 1 toward, 2 toward-right, 3 right, 4 away-right, 5 away, 6 away-left, 7 left, 8 toward-left.
     */
    public static final float[][] MOVE_DIRECTIONS = {
            {0, 0}, {1, 0}, {1, -1}, {0, -1}, {-1, -1}, {-1, 0}, {-1, 1}, {0, 1}, {1, 1}
    };

    /** Vanilla: no sprinting at or below this food level. */
    private static final int MIN_SPRINT_FOOD = 6;

    private final ServerPlayer handle;
    private final FakeConnection connection;
    private final int generation;
    private final String difficulty;

    /** Velocity a real client would have applied (see {@link #onPacket}). */
    private @Nullable Vec3 pendingVelocity;

    /** Entity the NPC orients its movement around (the opponent). */
    private @Nullable Entity focus;
    /** Set by arenas: the focus is the fixed opponent, not the nearest player. */
    private boolean focusLocked;
    private int moveDirection;
    private int moveTicksLeft;
    private boolean sprint;
    private boolean jumpRequested;

    /** Block being mined (pickaxe), or null. Mining takes several ticks, like holding left click. */
    private @Nullable BlockPos miningPos;
    private Direction miningFace = Direction.UP;
    private float miningProgress;
    private int blockSequence;

    CrystalNpc(ServerPlayer handle, FakeConnection connection, int generation, String difficulty) {
        this.handle = handle;
        this.connection = connection;
        this.generation = generation;
        this.difficulty = difficulty;
        connection.setPacketSink(this::onPacket);
    }

    /**
     * Melee knockback on players is client-authoritative: the server sends the new velocity
     * to the victim's client and then restores its own copy. We play the client's role here.
     */
    private void onPacket(Packet<?> packet) {
        if (packet instanceof ClientboundSetEntityMotionPacket motion && motion.getId() == handle.getId()) {
            pendingVelocity = motion.getMovement();
        }
    }

    /** Called once per server tick. Applies inputs, then runs the tick a client connection would trigger. */
    void tick(boolean updateChunks) {
        if (handle.isChangingDimension()) {
            // A cross-world teleport makes a player immune to almost all damage until its client
            // accepts the teleport (1.21: ServerPlayer.isInvulnerableTo). Accept it like a client would.
            handle.hasChangedDimension();
        }
        if (pendingVelocity != null) {
            handle.setDeltaMovement(pendingVelocity);
            pendingVelocity = null;
        }
        applyMovementInput();
        continueMining();
        handle.doTick();
        handle.setJumping(false);
        if (updateChunks) {
            handle.level().getChunkSource().move(handle);
        }
    }

    private void applyMovementInput() {
        if (miningPos == null && focus != null && focus.isAlive() && focus.level() == handle.level()) {
            faceTowards(focus.getEyePosition());
        }
        if (moveTicksLeft > 0) {
            float[] input = MOVE_DIRECTIONS[moveDirection];
            handle.zza = input[0];
            handle.xxa = input[1];
            boolean forward = input[0] > 0;
            handle.setSprinting(sprint && forward && handle.getFoodData().getFoodLevel() > MIN_SPRINT_FOOD);
            moveTicksLeft--;
        } else {
            handle.zza = 0;
            handle.xxa = 0;
            handle.setSprinting(false);
        }
        if (jumpRequested) {
            handle.setJumping(true);
            jumpRequested = false;
        }
    }

    private void faceTowards(Vec3 target) {
        Vec3 eye = handle.getEyePosition();
        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;
        float yaw = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90.0F;
        float pitch = (float) -(Mth.atan2(dy, Math.sqrt(dx * dx + dz * dz)) * Mth.RAD_TO_DEG);
        handle.setYRot(yaw);
        handle.setYHeadRot(yaw);
        handle.setXRot(pitch);
    }

    // ---------------------------------------------------------------- mining

    /**
     * Starts mining {@code pos} through the same server entry point as a client's
     * START_DESTROY_BLOCK packet. The caller has selected the pickaxe and checked reach.
     * Instantly breakable blocks break right away (vanilla "insta mine").
     */
    public void startMining(BlockPos pos, Direction face) {
        if (miningPos != null) {
            abortMining();
        }
        miningPos = pos.immutable();
        miningFace = face;
        miningProgress = 0.0F;
        sendBlockAction(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK);
        handle.swing(InteractionHand.MAIN_HAND, true);
        if (handle.level().getBlockState(pos).isAir()) {
            miningPos = null;
        }
    }

    public boolean mining() {
        return miningPos != null;
    }

    public @Nullable BlockPos miningPos() {
        return miningPos;
    }

    /** 0..1 like the client's crack overlay; 0 when not mining. */
    public float miningProgress() {
        return miningPos == null ? 0.0F : Math.min(1.0F, miningProgress);
    }

    public void abortMining() {
        if (miningPos != null) {
            sendBlockAction(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK);
            miningPos = null;
        }
    }

    /**
     * The client's side of holding left click (MultiPlayerGameMode.continueDestroyBlock): progress
     * += destroy speed each tick, STOP_DESTROY_BLOCK once it reaches 1. The server then checks the
     * elapsed ticks itself (>= 70 %), so mining cannot be faster than a real player's.
     */
    private void continueMining() {
        if (miningPos == null) {
            return;
        }
        BlockState state = handle.level().getBlockState(miningPos);
        if (state.isAir()) {
            miningPos = null; // broken by an explosion or someone else
            return;
        }
        // A real client stops mining when it switches items or the block leaves its reach.
        if (!handle.getMainHandItem().is(ItemTags.PICKAXES) || !handle.isWithinBlockInteractionRange(miningPos, 1.0)) {
            abortMining();
            return;
        }
        faceTowards(Vec3.atCenterOf(miningPos).relative(miningFace, 0.5));
        miningProgress += state.getDestroyProgress(handle, handle.level(), miningPos);
        handle.swing(InteractionHand.MAIN_HAND, true);
        if (miningProgress >= 1.0F) {
            sendBlockAction(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK);
            miningPos = null;
        }
    }

    private void sendBlockAction(ServerboundPlayerActionPacket.Action action) {
        handle.gameMode.handleBlockBreakAction(miningPos, action, miningFace, handle.level().getMaxY(), ++blockSequence);
    }

    /** Hold a movement direction (index into {@link #MOVE_DIRECTIONS}) for {@code ticks} ticks. */
    public void setMovement(int direction, int ticks, boolean sprint) {
        if (direction < 0 || direction >= MOVE_DIRECTIONS.length) {
            throw new IllegalArgumentException("Move direction must be 0-8: " + direction);
        }
        this.moveDirection = direction;
        this.moveTicksLeft = direction == 0 ? 0 : Math.max(0, ticks);
        this.sprint = sprint;
    }

    public void requestJump() {
        jumpRequested = true;
    }

    public void setFocus(@Nullable Entity focus) {
        this.focus = focus;
    }

    public @Nullable Entity focus() {
        return focus;
    }

    public void lockFocus(@Nullable Entity opponent) {
        this.focus = opponent;
        this.focusLocked = opponent != null;
    }

    public boolean focusLocked() {
        return focusLocked;
    }

    public ServerPlayer handle() {
        return handle;
    }

    public Player bukkit() {
        return handle.getBukkitEntity();
    }

    public String name() {
        return handle.getGameProfile().name();
    }

    public int generation() {
        return generation;
    }

    public String difficulty() {
        return difficulty;
    }

    FakeConnection connection() {
        return connection;
    }
}
