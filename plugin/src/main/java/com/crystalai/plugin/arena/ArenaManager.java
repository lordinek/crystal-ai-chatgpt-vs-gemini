package com.crystalai.plugin.arena;

import com.crystalai.plugin.bridge.TrainerLink;
import com.crystalai.plugin.control.ActionSpace;
import com.crystalai.plugin.control.NpcAction;
import com.crystalai.plugin.npc.CrystalNpc;
import com.crystalai.plugin.control.NpcController;
import com.crystalai.plugin.kit.Kit;
import com.crystalai.plugin.npc.NpcManager;
import com.crystalai.plugin.util.Profiler;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Level;
import javax.annotation.Nullable;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Runs N arenas in parallel: builds them, drives both NPCs of each arena every decision step,
 * ends episodes on death/timeout, records outcomes and resets. Also turns lethal hits on arena
 * NPCs into an episode end instead of a real death.
 */
public final class ArenaManager implements Listener {

    private final JavaPlugin plugin;
    private final NpcManager npcs;
    private final Supplier<ActionSpace> actionSpace;
    private final TrainerLink link;
    private long globalTick;

    private final List<Arena> arenas = new ArrayList<>();
    private final ArenaStats stats = new ArenaStats();
    private final java.util.Random executionOrder = new java.util.Random();
    private @Nullable BukkitTask task;
    private @Nullable ArenaSettings settings;
    private @Nullable ActionSpace space;
    private @Nullable Kit kit;

    // Natural terrain world reset (TerrainSettings.resetEveryMinutes / resetAboveMb)
    private @Nullable World arenaWorld;
    private long worldBornMillis;
    private long lastSizeCheckMillis;
    private int worldResets;

    public ArenaManager(JavaPlugin plugin, NpcManager npcs, Supplier<ActionSpace> actionSpace, TrainerLink link) {
        this.plugin = plugin;
        this.npcs = npcs;
        this.actionSpace = actionSpace;
        this.link = link;
    }

    public boolean running() {
        return task != null;
    }

    /** The world the arenas fight in right now (changes on a natural-terrain world reset). */
    public @Nullable World arenaWorld() {
        return task == null ? null : arenaWorld;
    }

    /** Natural terrain settings of the running arenas, null in flat mode or when stopped. */
    public @Nullable TerrainSettings terrain() {
        return settings == null || task == null ? null : settings.natural();
    }

    /** Builds (or rebuilds) {@code count} arenas and starts running episodes. */
    public void start(@Nullable Integer countOverride) {
        stop();
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("arenas");
        if (section == null) {
            throw new IllegalStateException("config.yml has no 'arenas' section");
        }
        ArenaSettings base = ArenaSettings.from(section);
        settings = countOverride == null ? base : base.withCount(countOverride);
        space = actionSpace.get();
        kit = npcs.combatKit();

        World world = settings.natural() != null
                ? ArenaWorld.getOrCreateNatural(settings.natural(), settings.difficulty())
                : ArenaWorld.getOrCreate(settings);
        for (int i = 0; i < settings.count(); i++) {
            Arena arena = new Arena(i, world, settings,
                    NpcController.create(settings.controllerA(), settings.randomSettings(), 31L * i + 1),
                    NpcController.create(settings.controllerB(), settings.randomSettings(), 31L * i + 2),
                    plugin, this::otherSites);
            arena.forceLoadChunks();
            arena.build();
            arenas.add(arena);
            if (arena.ready()) {
                arena.ensureNpcs(npcs);
                arena.startEpisode(kit);
            }
        }
        World previous = arenaWorld;
        arenaWorld = world;
        worldBornMillis = lastSizeCheckMillis = System.currentTimeMillis();
        if (settings.natural() != null) {
            // A restart (arena stop/start) goes back to the base world; the reset world it was in
            // stayed loaded and was never deleted.
            if (previous != null && !previous.equals(world) && previous.getName().startsWith(settings.natural().worldName() + "_r")
                    && previous.getPlayers().isEmpty()) {
                java.io.File folder = previous.getWorldFolder();
                if (Bukkit.unloadWorld(previous, false)) {
                    deleteLater(folder);
                }
            }
            deleteStaleResetWorlds(settings.natural().worldName());
        }
        stats.start(settings.count());
        link.configure(arenas);
        globalTick = 0;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        plugin.getLogger().info("Started " + settings.count() + " arenas in world " + world.getName()
                + (link.active() ? " (" + link.agentCount() + " trainer agents, waiting for the trainer)" : ""));
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (Arena arena : arenas) {
            arena.removeNpcs(npcs);
            arena.releaseSite();
        }
        arenas.clear();
    }

    // ---------------------------------------------------------------- world reset

    /** Natural terrain only: the world is old enough or big enough to be thrown away. */
    private boolean worldResetDue() {
        TerrainSettings terrain = settings == null ? null : settings.natural();
        if (terrain == null || arenaWorld == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (terrain.resetEveryMinutes() > 0 && now - worldBornMillis >= terrain.resetEveryMinutes() * 60_000L) {
            return true;
        }
        if (terrain.resetAboveMb() > 0 && now - lastSizeCheckMillis >= 60_000L) {
            lastSizeCheckMillis = now;
            return folderSize(arenaWorld.getWorldFolder().toPath()) >= terrain.resetAboveMb() * 1024L * 1024L;
        }
        return false;
    }

    /**
     * Throws the arena world away and continues in a freshly generated one with a new seed (a new
     * map every time). Running episodes end as aborted, the trainer stays connected and simply
     * waits while the new sites are generated. The arenas keep their identity, so the trainer's
     * agent order does not change.
     */
    private void resetWorld() {
        TerrainSettings terrain = settings.natural();
        World old = arenaWorld;
        long seed = new java.util.Random().nextLong();
        worldResets++;
        // Unique per reset (seconds): a restarted server must never reopen a half-deleted old world.
        String name = terrain.worldName() + "_r" + System.currentTimeMillis() / 1000;
        plugin.getLogger().info("World reset #" + worldResets + ": " + old.getName() + " ("
                + folderSize(old.getWorldFolder().toPath()) / (1024 * 1024) + " MB, "
                + (System.currentTimeMillis() - worldBornMillis) / 60_000 + " min) -> " + name + ", seed " + seed);
        for (Arena arena : arenas) {
            if (arena.ready() && arena.npc(0) != null && arena.npc(1) != null) {
                stats.record(arena, Arena.Outcome.ABORTED, arena.episodeTick(), arena.tally(), false);
                link.recordEpisodeEnd(arena, Arena.Outcome.ABORTED, space);
            }
            arena.removeNpcs(npcs);
            arena.releaseSite();
        }
        World fallback = Bukkit.getWorlds().get(0);
        for (org.bukkit.entity.Player player : old.getPlayers()) {
            player.teleport(fallback.getSpawnLocation()); // e.g. a spectator watching; a world with players cannot unload
        }
        World fresh = ArenaWorld.getOrCreateNatural(name, seed, terrain, settings.difficulty());
        for (Arena arena : arenas) {
            arena.moveToWorld(fresh, seed);
        }
        arenaWorld = fresh;
        worldBornMillis = lastSizeCheckMillis = System.currentTimeMillis();
        java.io.File folder = old.getWorldFolder();
        if (Bukkit.unloadWorld(old, false)) {
            deleteLater(folder);
        } else {
            plugin.getLogger().warning("Could not unload " + old.getName() + "; its folder stays on disk");
        }
    }

    /** Old reset worlds ({@code <base>_r<seconds>}) left behind by a stop or crash, not loaded now. */
    private void deleteStaleResetWorlds(String base) {
        java.io.File[] folders = Bukkit.getWorldContainer().listFiles(
                file -> file.isDirectory() && file.getName().startsWith(base + "_r") && Bukkit.getWorld(file.getName()) == null);
        if (folders != null) {
            for (java.io.File folder : folders) {
                deleteLater(folder);
            }
        }
    }

    /** Deletes a world folder off the main thread, retrying while the chunk system lets go of the files. */
    private void deleteLater(java.io.File folder) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            for (int attempt = 0; attempt < 30 && folder.exists(); attempt++) {
                try (var paths = java.nio.file.Files.walk(folder.toPath())) {
                    paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
                } catch (java.io.IOException | java.io.UncheckedIOException ignored) {
                    // retry below
                }
                if (folder.exists()) {
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
            plugin.getLogger().info(folder.exists() ? "Could not delete " + folder : "Deleted old arena world " + folder.getName());
        });
    }

    private static long folderSize(java.nio.file.Path folder) {
        try (var paths = java.nio.file.Files.walk(folder)) {
            return paths.filter(java.nio.file.Files::isRegularFile).mapToLong(path -> path.toFile().length()).sum();
        } catch (java.io.IOException | java.io.UncheckedIOException e) {
            return 0L;
        }
    }

    /** Advances the first-site search of natural arenas; true once all arenas are ready. */
    private boolean prepareSites() {
        boolean allReady = true;
        for (Arena arena : arenas) {
            if (arena.ready()) {
                continue;
            }
            if (arena.prepare()) {
                arena.ensureNpcs(npcs);
                arena.startEpisode(kit);
                SiteFinder.Site site = arena.site();
                plugin.getLogger().info("Arena " + (arena.index() + 1) + " ready at " + site.x() + " " + site.y() + " "
                        + site.z() + " (" + site.biome() + ")");
            } else {
                allReady = false;
            }
        }
        return allReady;
    }

    /** Current natural-terrain sites of all arenas, so new sites keep their distance. */
    private java.util.Collection<SiteFinder.Site> otherSites() {
        List<SiteFinder.Site> sites = new ArrayList<>();
        for (Arena arena : arenas) {
            if (arena.site() != null) {
                sites.add(arena.site());
            }
        }
        return sites;
    }

    private void tick() {
        if (settings == null || space == null || kit == null) {
            return;
        }
        if (!prepareSites()) {
            return; // natural terrain: frozen (and no trainer accepted) until every arena has a site
        }
        if (worldResetDue()) {
            resetWorld();
            return;
        }
        if (link.active()) {
            try {
                if (link.pollNewTrainer(settings.decisionIntervalTicks())) {
                    restartAllEpisodes();
                    globalTick = 0;
                }
            } catch (java.io.IOException e) {
                plugin.getLogger().warning("Trainer handshake failed: " + e.getMessage());
            }
            if (!link.connected()) {
                return; // frozen until a trainer connects
            }
        }
        for (Arena arena : arenas) {
            try {
                if (arena.ensureNpcs(npcs)) {
                    // An NPC vanished (e.g. /kill): restart this arena's episode.
                    stats.record(arena, Arena.Outcome.ABORTED, arena.episodeTick(), arena.tally(), false);
                    link.recordEpisodeEnd(arena, Arena.Outcome.ABORTED, space);
                    arena.startEpisode(kit);
                    continue;
                }
                Arena.Outcome outcome = arena.advance();
                if (outcome != null) {
                    finish(arena, outcome);
                    arena.removeDeadNpcs(npcs);
                    arena.ensureNpcs(npcs);
                    arena.startEpisode(kit);
                }
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.SEVERE, "Arena " + (arena.index() + 1) + " tick failed, restarting episode", e);
                arena.removeNpcs(npcs);
            }
        }
        stats.ticks++;
        globalTick++;
        if (globalTick % settings.decisionIntervalTicks() == 0) {
            decide();
        }
        Profiler.tick();
        if (globalTick % Math.max(1, plugin.getConfig().getInt("debug.profile-every-ticks", 400)) == 0
                && plugin.getConfig().getBoolean("debug.profile", false)) {
            plugin.getLogger().info(Profiler.report());
        }
    }

    private void restartAllEpisodes() {
        for (Arena arena : arenas) {
            arena.removeDeadNpcs(npcs);
            arena.ensureNpcs(npcs);
            arena.startEpisode(kit);
        }
    }

    /**
     * One decision step for every NPC; trainer-controlled ones are batched into one exchange.
     * Everyone decides on the same snapshot, then the actions are applied in a random order:
     * a fixed A-then-B order let side A explode first on every tie (A beat its own copy 95%).
     */
    private void decide() {
        List<Runnable> pending = new ArrayList<>();
        List<TrainerLink.Decision> external = new ArrayList<>();
        for (Arena arena : arenas) {
            for (int side = 0; side < 2; side++) {
                CrystalNpc self = arena.npc(side);
                CrystalNpc opponent = arena.npc(1 - side);
                if (self == null || opponent == null || !self.handle().isAlive()) {
                    continue;
                }
                ActionSpace.Context context = space.observe(self, opponent.handle());
                long t0 = Profiler.start();
                boolean[][] mask = space.mask(self, context);
                Profiler.stop("mask", t0);
                NpcController controller = arena.controllerOf(side);
                if (controller instanceof NpcController.External) {
                    external.add(new TrainerLink.Decision(link.agentFor(arena, side), context, mask));
                } else {
                    NpcAction action = controller.decide(self, context, mask);
                    pending.add(() -> space.execute(self, action, context, settings.decisionIntervalTicks()));
                }
            }
        }
        if (external.isEmpty() || !link.connected()) {
            runShuffled(pending);
            return;
        }
        try {
            long t0 = Profiler.start();
            List<NpcAction> actions = link.exchange(globalTick, external);
            Profiler.stop("exchange(obs+py)", t0);
            for (int i = 0; i < external.size(); i++) {
                TrainerLink.Decision decision = external.get(i);
                CrystalNpc self = arenaNpc(decision);
                NpcAction action = actions.get(i);
                if (self != null) {
                    pending.add(() -> space.execute(self, action, decision.context(), settings.decisionIntervalTicks()));
                }
            }
            t0 = Profiler.start();
            runShuffled(pending);
            Profiler.stop("execute", t0);
            link.maybeReport(5000);
        } catch (java.io.IOException | RuntimeException e) {
            link.disconnect(e.getMessage());
            plugin.getLogger().warning("Trainer step failed (" + e.getMessage() + "); arenas frozen until it reconnects");
        }
    }

    private void runShuffled(List<Runnable> pending) {
        Collections.shuffle(pending, executionOrder);
        for (Runnable run : pending) {
            run.run();
        }
    }

    private static @Nullable CrystalNpc arenaNpc(TrainerLink.Decision decision) {
        return decision.agent().arena().npc(decision.agent().side());
    }

    private void finish(Arena arena, Arena.Outcome outcome) {
        link.recordEpisodeEnd(arena, outcome, space);
        EpisodeTally tally = arena.tally();
        stats.record(arena, outcome, arena.episodeTick(), tally, arena.selfKill());
        if (settings != null && settings.logEpisodes()) {
            String result = switch (outcome) {
                case WIN_A -> "A kazandı" + (arena.selfKill() ? " (B kendi kristaliyle)" : "");
                case WIN_B -> "B kazandı" + (arena.selfKill() ? " (A kendi kristaliyle)" : "");
                case TIMEOUT -> "zaman aşımı";
                case ABORTED -> "iptal";
            };
            plugin.getLogger().info(String.format(Locale.ROOT,
                    "[arena %d] bölüm %d: %s, %d tick | hasar A→B %.1f, B→A %.1f | kendine A %.1f B %.1f | totem A %d B %d",
                    arena.index() + 1, arena.episode(), result, arena.episodeTick(), tally.dealt[0], tally.dealt[1],
                    tally.selfDamage[0], tally.selfDamage[1], tally.totemsPopped[0], tally.totemsPopped[1]));
        }
    }

    // ---------------------------------------------------------------- events

    private record Side(Arena arena, int side) {
    }

    private @Nullable Side sideOf(UUID uuid) {
        for (Arena arena : arenas) {
            int side = arena.sideOf(uuid);
            if (side >= 0) {
                return new Side(arena, side);
            }
        }
        return null;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        Side victim = sideOf(player.getUniqueId());
        if (victim == null) {
            return;
        }
        Arena arena = victim.arena();
        if (arena.ended()) {
            event.setCancelled(true); // episode already decided; freeze until the reset
            return;
        }
        int attackerSide = -1;
        org.bukkit.entity.Entity causing = event.getDamageSource().getCausingEntity();
        if (causing != null) {
            attackerSide = arena.sideOf(causing.getUniqueId());
        }
        // Health + absorption actually lost (the ABSORPTION modifier is negative).
        @SuppressWarnings("deprecation")
        double absorbed = -event.getDamage(EntityDamageEvent.DamageModifier.ABSORPTION);
        double lost = Math.min(event.getFinalDamage() + absorbed, player.getHealth() + player.getAbsorptionAmount());
        arena.tally().damage(victim.side(), attackerSide, lost);

        boolean lethal = event.getFinalDamage() >= player.getHealth();
        if (lethal && !holdsTotem(player.getInventory()) && !bypassesTotem(event.getCause())) {
            event.setCancelled(true);
            arena.markDeath(victim.side(), attackerSide == victim.side());
        }
    }

    private static boolean holdsTotem(PlayerInventory inventory) {
        return inventory.getItemInMainHand().getType() == Material.TOTEM_OF_UNDYING
                || inventory.getItemInOffHand().getType() == Material.TOTEM_OF_UNDYING;
    }

    /** Damage that kills through totems (void, /kill): let it happen for real. */
    private static boolean bypassesTotem(EntityDamageEvent.DamageCause cause) {
        return cause == EntityDamageEvent.DamageCause.VOID || cause == EntityDamageEvent.DamageCause.KILL
                || cause == EntityDamageEvent.DamageCause.SUICIDE;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onResurrect(EntityResurrectEvent event) {
        Side side = sideOf(event.getEntity().getUniqueId());
        if (side != null) {
            side.arena().tally().totemsPopped[side.side()]++;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Side side = sideOf(event.getEntity().getUniqueId());
        if (side != null) {
            side.arena().markDeath(side.side(), false);
        }
    }

    // ---------------------------------------------------------------- status

    public List<String> status() {
        if (!running()) {
            return List.of("§7Arenalar çalışmıyor. /cai arena start [sayı]");
        }
        List<String> lines = new ArrayList<>(stats.summary());
        lines.addAll(link.status());
        for (Arena arena : arenas) {
            lines.add(String.format(Locale.ROOT, "§7arena %d: bölüm %d, tick %d", arena.index() + 1,
                    arena.episode(), arena.episodeTick()));
        }
        return lines;
    }

    public List<Arena> arenas() {
        return Collections.unmodifiableList(arenas);
    }
}
