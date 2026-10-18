package com.crystalai.plugin.control;

/**
 * One decision (schema v2, MultiDiscrete; protocol/schema.json is the reference):
 * <pre>
 *  0 move       0-8  (CrystalNpc.MOVE_DIRECTIONS, relative to the opponent)
 *  1 jump       0/1
 *  2 sprint     0/1
 *  3 attack     0 none / 1 sword hit on the opponent
 *  4 crystal    0 none / i+1 place at crystal candidate i (obsidian first if needed)
 *  5 explode    0 none / i+1 hit scene crystal i
 *  6 anchor     0 none / i+1 place an anchor at anchor candidate i
 *  7 anchor_use 0 none / 1-2 charge scene anchor 0-1 / 3-4 detonate scene anchor 0-1
 *  8 block      0 none / 1 glowstone shield toward the worst threat / 2 topblock the nearest base next to me
 *  9 pearl      0 none / 1-8 throw in a move direction / 9 throw at the feet
 * 10 util       0 none / 1 totem to offhand / 2 eat golden apple / 3 totem to main hand / 4 throw xp
 * 11 mine       0 none / 1 opponent's cover block / 2 block ahead at feet / 3 block ahead at head height
 * </pre>
 */
public record NpcAction(int move, int jump, int sprint, int attack, int crystal, int explode, int anchor,
                        int anchorUse, int block, int pearl, int util, int mine) {

    public static final int[] BRANCH_SIZES = {9, 2, 2, 2, 9, 5, 5, 5, 3, 10, 5, 4};

    public static final int MOVE = 0, JUMP = 1, SPRINT = 2, ATTACK = 3, CRYSTAL = 4, EXPLODE = 5, ANCHOR = 6,
            ANCHOR_USE = 7, BLOCK = 8, PEARL = 9, UTIL = 10, MINE = 11;

    public static final int SCENE_ANCHORS = 2;
    public static final int BLOCK_SHIELD = 1;
    public static final int BLOCK_TOPBLOCK = 2;
    public static final int PEARL_DOWN = 9;
    public static final int UTIL_TOTEM = 1;
    public static final int UTIL_EAT = 2;
    public static final int UTIL_TOTEM_MAIN = 3;
    public static final int UTIL_XP = 4;

    public static final NpcAction IDLE = new NpcAction(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

    public int get(int branch) {
        return switch (branch) {
            case MOVE -> move;
            case JUMP -> jump;
            case SPRINT -> sprint;
            case ATTACK -> attack;
            case CRYSTAL -> crystal;
            case EXPLODE -> explode;
            case ANCHOR -> anchor;
            case ANCHOR_USE -> anchorUse;
            case BLOCK -> block;
            case PEARL -> pearl;
            case UTIL -> util;
            case MINE -> mine;
            default -> throw new IndexOutOfBoundsException(branch);
        };
    }

    public static NpcAction of(int[] v) {
        return new NpcAction(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[8], v[9], v[10], v[11]);
    }

    /** Anchor-use choice: which scene anchor (0/1), or -1 for none. */
    public int anchorUseTarget() {
        return anchorUse == 0 ? -1 : (anchorUse - 1) % SCENE_ANCHORS;
    }

    public boolean anchorUseDetonates() {
        return anchorUse > SCENE_ANCHORS;
    }
}
