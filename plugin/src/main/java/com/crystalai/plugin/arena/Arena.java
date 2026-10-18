package com.crystalai.plugin.arena;

import com.crystalai.plugin.control.NpcController;
import com.crystalai.plugin.kit.Kit;
import com.crystalai.plugin.npc.CrystalNpc;
import com.crystalai.plugin.npc.NpcManager;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Random;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

/**
 * One small arena: a bedrock floor with a barrier ring, two NPCs (side A and B) and the state of
 * the running episode. An episode ends when a side dies or the timeout hits; then everything is
 * reset in place and the next episode starts on the following tick.
 */
public final class Arena implements com.crystalai.plugin.bridge.ObservationFrame {

    public enum Outcome { WIN_A, WIN_B, TIMEOUT, ABORTED }

    private final int index;
    /** Not final: a natural-terrain world reset moves the arena into the freshly generated world. */
    private World world;
    private final ArenaSettings settings;
    private final int centerX;
    private final int centerZ;

    private final CrystalNpc[] npcs = new CrystalNpc[2];
    private final NpcController[] controllers = new NpcController[2];

    private int episode;
    private int tick;
    private EpisodeTally tally = new EpisodeTally();
    private @Nullable Outcome pendingOutcome;
    private boolean selfKill;

    // Natural terrain mode (settings.natural() != null): the fight moves between random sites.
    private final @Nullable TerrainSettings terrain;
    private final JavaPlugin plugin;
    private final Supplier<Collection<SiteFinder.Site>> otherSites;
    private @Nullable SiteFinder finder;
    private final Random random;
    private @Nullable SiteFinder.Site site;
    private int episodesLeftOnSite;
    private final List<long[]> tickets = new ArrayList<>();

    Arena(int index, World world, ArenaSettings settings, NpcController controllerA, NpcController controllerB,
          JavaPlugin plugin, Supplier<Collection<SiteFinder.Site>> otherSites) {
        this.index = index;
        this.world = world;
        this.settings = settings;
        this.centerX = index * settings.spacing();
        this.centerZ = 0;
        this.controllers[0] = controllerA;
        this.controllers[1] = controllerB;
        this.terrain = settings.natural();
        this.plugin = plugin;
        this.otherSites = otherSites;
        this.random = new Random(7919L * (index + 1));
        this.finder = terrain == null ? null : new SiteFinder(world, terrain, 104729L * (index + 1) + terrain.seed());
    }

    // ---------------------------------------------------------------- natural terrain sites

    /**
     * World reset: the caller has already ended the episode and removed the NPCs. Drops the old
     * site and starts looking for a first site in {@code newWorld} (see {@link #prepare()}).
     */
    void moveToWorld(World newWorld, long seed) {
        releaseSite();
        world = newWorld;
        site = null;
        episodesLeftOnSite = 0;
        finder = terrain == null ? null : new SiteFinder(newWorld, terrain, 104729L * (index + 1) + seed);
    }

    public @Nullable SiteFinder.Site site() {
        return site;
    }

    /** Horizontal centre {x, z} of the current natural fight site, or null in flat arenas. */
    public @Nullable double[] siteCenter() {
        return site == null ? null : new double[] {site.x() + 0.5, site.z() + 0.5};
    }

    /**
     * Leaving this horizontal distance from {@link #siteCenter()} loses; 0 in flat arenas. The
     * border closes in during the episode (docs/reward_log.md 2026-09-27: without it both sides
     * learned to hold position and wait for the timeout).
     */
    public double boundaryRadius() {
        if (terrain == null) {
            return 0.0;
        }
        double full = terrain.boundaryRadius();
        int start = terrain.shrinkStartTicks();
        int end = terrain.shrinkEndTicks();
        if (end <= start || tick <= start) {
            return full;
        }
        double progress = Math.min(1.0, (tick - start) / (double) (end - start));
        return full + (Math.min(full, terrain.shrinkMinRadius()) - full) * progress;
    }

    /** False while a natural arena is still waiting for its first site. */
    boolean ready() {
        return terrain == null || site != null;
    }

    /**
     * Main thread, before {@link #ready()}: advances the background search for the first site.
     * Returns true on the call that made the arena ready (then NPCs can be spawned).
     */
    boolean prepare() {
        if (ready() || finder == null) {
            return false;
        }
        finder.poll(otherSites.get());
        SiteFinder.Site first = finder.take();
        if (first == null) {
            return false;
        }
        useSite(first);
        return true;
    }

    /** Moves the fight to a fresh site when this one has been used enough and a new one is ready. */
    private void maybeChangeSite() {
        if (terrain == null || finder == null || site == null) {
            return;
        }
        if (episodesLeftOnSite > 0) {
            episodesLeftOnSite--;
            return;
        }
        SiteFinder.Site next = finder.take();
        if (next != null) {
            removeLooseEntities(); // crystals etc. left at the old site
            useSite(next);
            if (settings.logEpisodes()) {
                plugin.getLogger().info("[arena " + (index + 1) + "] new site " + next.x() + " " + next.y() + " "
                        + next.z() + " (" + next.biome() + ")");
            }
        }
        // else: keep fighting in the (by now cratered) old site until the next one is generated
    }

    private void useSite(SiteFinder.Site next) {
        releaseSite();
        site = next;
        episodesLeftOnSite = terrain.episodesPerSiteMin() - 1
                + random.nextInt(terrain.episodesPerSiteMax() - terrain.episodesPerSiteMin() + 1);
        int r = terrain.loadRadiusChunks();
        for (int cx = (next.x() >> 4) - r; cx <= (next.x() >> 4) + r; cx++) {
            for (int cz = (next.z() >> 4) - r; cz <= (next.z() >> 4) + r; cz++) {
                world.addPluginChunkTicket(cx, cz, plugin);
                tickets.add(new long[]{cx, cz});
            }
        }
    }

    /** Drops the chunk tickets of the current site (arena stop or site change). */
    void releaseSite() {
        for (long[] chunk : tickets) {
            world.removePluginChunkTicket((int) chunk[0], (int) chunk[1], plugin);
        }
        tickets.clear();
    }

    private int groundY(int x, int z) {
        return world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
    }

    private void removeLooseEntities() {
        for (Entity entity : world.getNearbyEntities(bounds())) {
            if (!(entity instanceof Player)) {
                entity.remove();
            }
        }
    }

    // ---------------------------------------------------------------- building

    void forceLoadChunks() {
        if (terrain != null) {
            return; // natural sites hold their chunks with plugin tickets
        }
        int r = settings.floorRadius() + 1;
        for (int cx = (centerX - r) >> 4; cx <= (centerX + r) >> 4; cx++) {
            for (int cz = (centerZ - r) >> 4; cz <= (centerZ + r) >> 4; cz++) {
                world.getChunkAt(cx, cz).setForceLoaded(true);
            }
        }
    }

    /** Bedrock floor and a barrier ring; clears everything inside. */
    void build() {
        if (terrain != null) {
            return; // natural: the first site is prepared in the background (see prepare())
        }
        int r = settings.floorRadius();
        int y = settings.floorY();
        for (int x = -r - 1; x <= r + 1; x++) {
            for (int z = -r - 1; z <= r + 1; z++) {
                boolean edge = Math.abs(x) == r + 1 || Math.abs(z) == r + 1;
                world.getBlockAt(centerX + x, y, centerZ + z).setType(edge ? Material.BARRIER : Material.BEDROCK, false);
                for (int dy = 1; dy <= settings.wallHeight(); dy++) {
                    world.getBlockAt(centerX + x, y + dy, centerZ + z).setType(edge ? Material.BARRIER : Material.AIR, false);
                }
                // Ceiling: crystal knockback can launch a player over any wall.
                world.getBlockAt(centerX + x, y + settings.wallHeight() + 1, centerZ + z).setType(Material.BARRIER, false);
            }
        }
    }

    /**
     * True if the NPC really left the arena (then it loses the episode): another world, the void,
     * or on natural terrain more than escape-margin blocks beyond the full boundary. Standing
     * outside the (closing) border is not a loss, it only hurts: see {@link #inZone}.
     */
    boolean outside(CrystalNpc npc) {
        var pos = npc.handle().position();
        if (terrain != null && site != null) {
            double dx = pos.x - (site.x() + 0.5);
            double dz = pos.z - (site.z() + 0.5);
            double escape = terrain.boundaryRadius() + terrain.escapeMargin();
            return !npc.bukkit().getWorld().equals(world)
                    || dx * dx + dz * dz > escape * escape
                    || pos.y < site.y() - 64;
        }
        int r = settings.floorRadius() + 1;
        return !npc.bukkit().getWorld().equals(world)
                || Math.abs(pos.x - (centerX + 0.5)) > r + 0.5 || Math.abs(pos.z - (centerZ + 0.5)) > r + 0.5
                || pos.y < settings.floorY() - 1 || pos.y > settings.floorY() + settings.wallHeight() + 2;
    }

    /**
     * Beyond the current (closing) border on natural terrain. Like a battle-royale zone it hurts
     * instead of ending the episode: 97% of the episodes had ended by someone leaving the border
     * (explosion knockback), which never decides a real crystal PvP fight.
     */
    private boolean inZone(CrystalNpc npc) {
        if (terrain == null || site == null) {
            return false;
        }
        var pos = npc.handle().position();
        double dx = pos.x - (site.x() + 0.5);
        double dz = pos.z - (site.z() + 0.5);
        return dx * dx + dz * dz > boundaryRadius() * boundaryRadius();
    }

    /** Removes placed blocks, crystals and drops inside the arena. */
    private void clearInside() {
        if (terrain != null) {
            removeLooseEntities(); // the terrain itself keeps its craters (wear)
            return;
        }
        int r = settings.floorRadius();
        int y = settings.floorY();
        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                if (world.getBlockAt(centerX + x, y, centerZ + z).getType() != Material.BEDROCK) {
                    world.getBlockAt(centerX + x, y, centerZ + z).setType(Material.BEDROCK, false);
                }
                for (int dy = 1; dy <= settings.wallHeight(); dy++) {
                    var block = world.getBlockAt(centerX + x, y + dy, centerZ + z);
                    if (!block.getType().isAir()) {
                        block.setType(Material.AIR, false);
                    }
                }
            }
        }
        for (Entity entity : world.getNearbyEntities(bounds())) {
            if (!(entity instanceof Player)) {
                entity.remove();
            }
        }
    }

    BoundingBox bounds() {
        if (terrain != null && site != null) {
            int r = terrain.boundaryRadius() + terrain.escapeMargin() + 4;
            return new BoundingBox(site.x() - r, site.y() - 64, site.z() - r, site.x() + r + 1, site.y() + 64, site.z() + r + 1);
        }
        int r = settings.floorRadius() + 2;
        return new BoundingBox(centerX - r, settings.floorY() - 2, centerZ - r,
                centerX + r + 1, settings.floorY() + settings.wallHeight() + 4, centerZ + r + 1);
    }

    Location spawnPoint(int side) {
        double offset = settings.spawnDistance() / 2.0 * (side == 0 ? -1 : 1);
        if (terrain != null && site != null) {
            int x = (int) Math.floor(site.x() + 0.5 + offset);
            // Current ground (a reused site may be cratered by now), standing on top of it.
            int ground = groundY(x, site.z());
            if (ground < site.y() - 16 || ground > site.y() + 24) {
                // Implausible ground (seen: y -5 at a y 63 site right after the arenas restarted, the
                // NPC then lost every episode in 1 tick by being "below the site"). Stand at site
                // height and move to a fresh site as soon as one is ready.
                plugin.getLogger().warning("[arena " + (index + 1) + "] spawn ground " + ground + " at site y "
                        + site.y() + ", using the site height and changing site");
                ground = site.y();
                episodesLeftOnSite = 0;
            }
            Location location = new Location(world, x + 0.5, ground + 1, site.z() + 0.5);
            location.setYaw(side == 0 ? -90 : 90);
            return location;
        }
        Location location = new Location(world, centerX + 0.5 + offset, settings.floorY() + 1, centerZ + 0.5);
        location.setYaw(side == 0 ? -90 : 90); // face each other along x
        return location;
    }

    public Location center() {
        if (terrain != null && site != null) {
            return new Location(world, site.x() + 0.5, groundY(site.x(), site.z()) + 1, site.z() + 0.5);
        }
        return new Location(world, centerX + 0.5, settings.floorY() + 1, centerZ + 0.5);
    }

    // ---------------------------------------------------------------- NPCs & episodes

    /** Spawns missing NPCs (first start, or after something removed one). Returns true if any was spawned. */
    boolean ensureNpcs(NpcManager manager) {
        boolean spawned = false;
        for (int side = 0; side < 2; side++) {
            CrystalNpc npc = npcs[side];
            if (npc == null || npc.handle().isRemoved() || manager.get(npc.handle().getUUID()) == null) {
                npcs[side] = manager.spawn(spawnPoint(side), 0, "orta", "Arena" + (index + 1) + "_" + (side == 0 ? "A" : "B"));
                spawned = true;
            }
        }
        if (spawned) {
            npcs[0].lockFocus(npcs[1].handle());
            npcs[1].lockFocus(npcs[0].handle());
        }
        return spawned;
    }

    /** Removes NPCs that really died (e.g. /kill) so {@link #ensureNpcs} replaces them before the next episode. */
    void removeDeadNpcs(NpcManager manager) {
        for (int side = 0; side < 2; side++) {
            if (npcs[side] != null && !npcs[side].handle().isAlive()) {
                if (manager.get(npcs[side].handle().getUUID()) != null) {
                    manager.remove(npcs[side]);
                }
                npcs[side] = null;
            }
        }
    }

    void removeNpcs(NpcManager manager) {
        for (int side = 0; side < 2; side++) {
            if (npcs[side] != null && manager.get(npcs[side].handle().getUUID()) != null) {
                manager.remove(npcs[side]);
            }
            npcs[side] = null;
        }
    }

    void startEpisode(Kit kit) {
        maybeChangeSite();
        clearInside();
        for (int side = 0; side < 2; side++) {
            resetNpc(npcs[side], spawnPoint(side), kit);
            controllers[side].reset();
        }
        episode++;
        tick = 0;
        tally = new EpisodeTally();
        pendingOutcome = null;
        selfKill = false;
    }

    private static void resetNpc(CrystalNpc npc, Location spawn, Kit kit) {
        Player player = npc.bukkit();
        ServerPlayer handle = npc.handle();
        handle.stopUsingItem();
        npc.setMovement(0, 0, false);
        player.teleport(spawn);
        player.setVelocity(new Vector());
        player.setFallDistance(0);
        player.setFireTicks(0);
        player.getActivePotionEffects().forEach(effect -> player.removePotionEffect(effect.getType()));
        kit.apply(player.getInventory());
        player.setHealth(player.getMaxHealth());
        player.setAbsorptionAmount(0);
        player.setFoodLevel(20);
        player.setSaturation(5);
        player.setExhaustion(0);
        handle.invulnerableTime = 0;
        handle.lastHurt = 0;
        handle.hurtTime = 0;
    }

    /**
     * One server tick of this arena. Returns the finished outcome (then the caller records it and
     * starts the next episode), or null while running.
     */
    /**
     * Advances this arena by one server tick (timeout and out-of-bounds checks). Returns the
     * finished outcome (the caller records it and starts the next episode), or null while running.
     * Decisions are made by the ArenaManager on decision ticks.
     */
    @Nullable Outcome advance() {
        if (pendingOutcome != null) {
            return pendingOutcome;
        }
        if (finder != null && tick % 10 == 0) {
            finder.poll(otherSites.get()); // prepare the next site in the background
        }
        tick++;
        for (int side = 0; side < 2; side++) {
            if (outside(npcs[side])) {
                org.bukkit.Bukkit.getLogger().warning("[CrystalAI] " + npcs[side].name() + " left arena " + (index + 1)
                        + " at " + npcs[side].handle().position() + ", counted as a loss");
                markDeath(side, false);
                return pendingOutcome;
            }
            if (terrain != null && terrain.zoneDamage() > 0 && tick % terrain.zoneDamageIntervalTicks() == 0
                    && inZone(npcs[side])) {
                var handle = npcs[side].handle();
                handle.hurtServer(handle.level(), handle.damageSources().outOfBorder(), terrain.zoneDamage());
                if (pendingOutcome != null) {
                    return pendingOutcome; // the zone finished it (no totem left)
                }
            }
        }
        if (tick >= settings.episodeTimeoutTicks()) {
            return Outcome.TIMEOUT;
        }
        return null;
    }

    public NpcController controllerOf(int side) {
        return controllers[side];
    }

    public boolean endedBySelfKill() {
        return selfKill;
    }

    public double[] tallyDealt() {
        return tally.dealt.clone();
    }

    public double[] tallyTaken() {
        return tally.taken.clone();
    }

    public double[] tallySelfDamage() {
        return tally.selfDamage.clone();
    }

    public int[] tallyTotems() {
        return tally.totemsPopped.clone();
    }

    /** Called from the damage listener when a side takes a hit it cannot survive. */
    void markDeath(int victimSide, boolean killedBySelf) {
        if (pendingOutcome == null) {
            pendingOutcome = victimSide == 0 ? Outcome.WIN_B : Outcome.WIN_A;
            selfKill = killedBySelf;
        }
    }

    boolean ended() {
        return pendingOutcome != null;
    }

    /** 0 or 1 if the UUID is one of this arena's NPCs, else -1. */
    int sideOf(java.util.UUID uuid) {
        for (int side = 0; side < 2; side++) {
            if (npcs[side] != null && npcs[side].handle().getUUID().equals(uuid)) {
                return side;
            }
        }
        return -1;
    }

    EpisodeTally tally() {
        return tally;
    }

    public int index() {
        return index;
    }

    public int episode() {
        return episode;
    }

    public int episodeTick() {
        return tick;
    }

    boolean selfKill() {
        return selfKill;
    }

    public @Nullable CrystalNpc npc(int side) {
        return npcs[side];
    }
}
