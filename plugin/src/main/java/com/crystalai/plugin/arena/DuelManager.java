package com.crystalai.plugin.arena;

import com.crystalai.plugin.ai.MlpPolicy;
import com.crystalai.plugin.bridge.ObservationBuilder;
import com.crystalai.plugin.bridge.ObservationFrame;
import com.crystalai.plugin.control.ActionSpace;
import com.crystalai.plugin.control.NpcAction;
import com.crystalai.plugin.kit.Kit;
import com.crystalai.plugin.npc.CrystalNpc;
import com.crystalai.plugin.npc.NpcManager;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Level;
import javax.annotation.Nullable;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Player vs. AI duel ({@code /crystalai duel}): a trained generation fights a real player on a
 * fresh natural-terrain site, decided every tick by {@link MlpPolicy} inside the plugin, so the
 * training bridge and the training itself keep running. Only the tick sprint is paused, a human
 * cannot fight at sprint speed.
 */
public final class DuelManager implements Listener, ObservationFrame {

    private static final int COUNTDOWN_TICKS = 60;
    private static final double BOUNDARY = 32.0;
    private static final int SPAWN_DISTANCE = 6;
    private static final double ESCAPE_MARGIN = 16.0;
    private static final float ZONE_DAMAGE = 2.0F;
    private static final int ZONE_DAMAGE_INTERVAL = 10;

    private final JavaPlugin plugin;
    private final NpcManager npcs;
    private final ArenaManager arenas;
    private final Supplier<ActionSpace> actionSpace;
    private final ObservationBuilder builder;
    private final Map<String, MlpPolicy> policies = new HashMap<>();
    private final Random random = new Random();
    private @Nullable SiteFinder finder;
    private @Nullable World finderWorld;
    private @Nullable BukkitTask task;

    // the running duel
    private @Nullable Player human;
    private @Nullable CrystalNpc npc;
    private @Nullable MlpPolicy policy;
    private @Nullable ActionSpace space;
    private @Nullable SiteFinder.Site site;
    private @Nullable GameMode previousMode;
    private @Nullable DuelRecorder recorder;
    // player vs. player (/crystalai pvp): the second human takes the AI's side, nobody is decided by a policy
    private @Nullable Player rival;
    private @Nullable GameMode rivalPreviousMode;
    private @Nullable DuelRecorder rivalRecorder;
    private boolean greedy;
    // Preparation: fighters sort their inventory and type /ready; the countdown starts when all have.
    private final java.util.Set<UUID> waitingReady = new java.util.HashSet<>();
    private int prepTicks;
    private int tick;
    private boolean resumeSprint;
    private final int[] totems = new int[2]; // [0] AI, [1] player

    public DuelManager(JavaPlugin plugin, NpcManager npcs, ArenaManager arenas, Supplier<ActionSpace> actionSpace,
                       ObservationBuilder builder) {
        this.plugin = plugin;
        this.npcs = npcs;
        this.arenas = arenas;
        this.actionSpace = actionSpace;
        this.builder = builder;
    }

    public boolean running() {
        return human != null;
    }

    // ---------------------------------------------------------------- start / stop

    /** @param model "latest" or a generation number */
    public void start(Player player, String model) {
        if (running()) {
            player.sendMessage(Component.text("Zaten bir düello sürüyor. Bitirmek için: /crystalai duel stop", NamedTextColor.RED));
            return;
        }
        World world = arenas.arenaWorld();
        if (world == null || arenas.terrain() == null) {
            player.sendMessage(Component.text("Arenalar doğal arazide çalışmıyor: önce /crystalai arena start", NamedTextColor.RED));
            return;
        }
        MlpPolicy loaded = policy(model, player);
        if (loaded == null) {
            return;
        }
        SiteFinder.Site next = nextSite(world);
        if (next == null) {
            player.sendMessage(Component.text("Düello sahası hazırlanıyor (arazi üretiliyor). 10-20 saniye sonra aynı komutu tekrar yaz.",
                    NamedTextColor.YELLOW));
            return;
        }
        begin(player, loaded, world, next);
    }

    /** Two humans on a duel site with the same kit and rules, both recorded for imitation learning. */
    public void startPvp(org.bukkit.command.CommandSender sender, Player first, Player second) {
        if (running()) {
            sender.sendMessage(Component.text("Zaten bir düello sürüyor. Bitirmek için: /crystalai duel stop", NamedTextColor.RED));
            return;
        }
        if (first.equals(second)) {
            sender.sendMessage(Component.text("İki farklı oyuncu gerekli.", NamedTextColor.RED));
            return;
        }
        World world = arenas.arenaWorld();
        if (world == null || arenas.terrain() == null) {
            sender.sendMessage(Component.text("Arenalar doğal arazide çalışmıyor: önce /crystalai arena start", NamedTextColor.RED));
            return;
        }
        SiteFinder.Site next = nextSite(world);
        if (next == null) {
            sender.sendMessage(Component.text("Düello sahası hazırlanıyor (arazi üretiliyor). 10-20 saniye sonra aynı komutu tekrar yaz.",
                    NamedTextColor.YELLOW));
            return;
        }
        human = first;
        rival = second;
        policy = null;
        npc = null;
        site = next;
        space = actionSpace.get();
        tick = -COUNTDOWN_TICKS;
        totems[0] = totems[1] = 0;
        resumeSprint = arenas.running();
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "tick sprint stop");
        Kit kit = npcs.combatKit();
        previousMode = prepare(first, spawn(world, 1), kit);
        rivalPreviousMode = prepare(second, spawn(world, 0), kit);
        boolean record = plugin.getConfig().getBoolean("duel.record", true);
        recorder = record ? DuelRecorder.open(plugin, space, builder, this, first, handle(second), "pvp_" + second.getName()) : null;
        rivalRecorder = record ? DuelRecorder.open(plugin, space, builder, this, second, handle(first), "pvp_" + first.getName()) : null;
        awaitReady(first, second);
        plugin.getLogger().info("[duel] " + first.getName() + " vs " + second.getName() + " (pvp) at " + next.x() + " "
                + next.y() + " " + next.z() + " (" + next.biome() + ")" + (record ? ", recording" : ""));
        for (Player player : new Player[] {first, second}) {
            player.sendMessage(Component.text("Düello: " + first.getName() + " vs " + second.getName()
                    + ". Sınır 32 blok, süre 5 dakika. Bitirmek için /crystalai duel stop", NamedTextColor.AQUA));
        }
        ensureTicking();
    }

    /** Puts the fighters in the preparation phase (inventory sorting) when duel.require-ready is on. */
    private void awaitReady(Player... fighters) {
        waitingReady.clear();
        prepTicks = 0;
        if (!plugin.getConfig().getBoolean("duel.require-ready", true)) {
            return;
        }
        for (Player fighter : fighters) {
            waitingReady.add(fighter.getUniqueId());
            fighter.sendMessage(Component.text("Envanterini düzenle. Hazır olunca /ready (ya da /hazir) yaz, geri sayım o zaman başlar.",
                    NamedTextColor.YELLOW));
        }
    }

    /** /ready: true if the player was one the duel was waiting for. */
    public boolean ready(Player player) {
        if (!running() || !waitingReady.remove(player.getUniqueId())) {
            return false;
        }
        Component line = waitingReady.isEmpty()
                ? Component.text(player.getName() + " hazır. Geri sayım başlıyor!", NamedTextColor.GREEN)
                : Component.text(player.getName() + " hazır, rakip bekleniyor.", NamedTextColor.GREEN);
        for (Player fighter : new Player[] {human, rival}) {
            if (fighter != null && fighter.isOnline()) {
                fighter.sendMessage(line);
            }
        }
        plugin.getLogger().info("[duel] " + player.getName() + " hazır");
        return true;
    }

    private static net.minecraft.server.level.ServerPlayer handle(Player player) {
        return ((org.bukkit.craftbukkit.entity.CraftPlayer) player).getHandle();
    }

    public void stop(String reason) {
        if (running()) {
            finish(null, reason);
        }
    }

    private @Nullable MlpPolicy policy(String model, Player player) {
        String file = model.equalsIgnoreCase("latest") ? "latest" : model.matches("\\d+")
                ? String.format("gen_%04d", Integer.parseInt(model)) : model;
        MlpPolicy cached = policies.get(file);
        if (cached != null && !file.equals("latest")) {
            return cached;
        }
        Path path = plugin.getDataFolder().toPath().resolve("models").resolve(file + ".json");
        try {
            MlpPolicy loaded = MlpPolicy.load(path);
            policies.put(file, loaded);
            return loaded;
        } catch (java.nio.file.NoSuchFileException e) {
            player.sendMessage(Component.text("Model yok: " + path.getFileName()
                    + ". Önce: .venv\\Scripts\\python trainer\\export_policy.py --gen " + model, NamedTextColor.RED));
        } catch (IOException | RuntimeException e) {
            player.sendMessage(Component.text("Model yüklenemedi: " + e.getMessage(), NamedTextColor.RED));
            plugin.getLogger().log(Level.WARNING, "duel model " + path, e);
        }
        return null;
    }

    /** A validated fight site in the arena world, searched in the background; null while not ready. */
    private @Nullable SiteFinder.Site nextSite(World world) {
        if (finder == null || finderWorld != world) {
            finder = new SiteFinder(world, arenas.terrain(), random.nextLong());
            finderWorld = world;
        }
        SiteFinder.Site ready = finder.take();
        if (ready == null) {
            ensureTicking();
        }
        return ready;
    }

    private void begin(Player player, MlpPolicy loaded, World world, SiteFinder.Site fightSite) {
        human = player;
        policy = loaded;
        site = fightSite;
        space = actionSpace.get();
        tick = -COUNTDOWN_TICKS;
        totems[0] = totems[1] = 0;
        resumeSprint = arenas.running();
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "tick sprint stop");

        Kit kit = npcs.combatKit();
        previousMode = prepare(player, spawn(world, 1), kit);
        rival = null;

        int generation = parseGeneration(loaded.name());
        npc = npcs.spawn(spawn(world, 0), generation, "duel", "CrystalAI");
        kit.apply(npc.bukkit().getInventory());
        npc.lockFocus(((org.bukkit.craftbukkit.entity.CraftPlayer) player).getHandle());
        greedy = plugin.getConfig().getBoolean("duel.greedy", false);
        recorder = plugin.getConfig().getBoolean("duel.record", true)
                ? DuelRecorder.open(plugin, space, builder, this, player, npc.handle(), loaded.name()) : null;

        plugin.getLogger().info("[duel] " + player.getName() + " vs " + loaded.name() + " at " + fightSite.x() + " "
                + fightSite.y() + " " + fightSite.z() + " (" + fightSite.biome() + ")"
                + (greedy ? ", greedy" : "") + (recorder != null ? ", recording" : ""));
        player.sendMessage(Component.text("Düello: sen vs " + loaded.name() + ". Sınır 32 blok, süre 5 dakika. "
                + "Bitirmek için /crystalai duel stop", NamedTextColor.AQUA));
        awaitReady(player);
        ensureTicking();
    }

    /** Teleports, kits and heals a fighter; returns the game mode to restore afterwards. */
    private static GameMode prepare(Player player, Location at, Kit kit) {
        GameMode before = player.getGameMode();
        player.teleport(at);
        player.setGameMode(GameMode.SURVIVAL);
        player.getInventory().clear();
        kit.apply(player.getInventory());
        player.setHealth(player.getMaxHealth());
        player.setAbsorptionAmount(0);
        player.setFoodLevel(20);
        player.setSaturation(5);
        player.setFireTicks(0);
        player.getActivePotionEffects().forEach(effect -> player.removePotionEffect(effect.getType()));
        return before;
    }

    private Location spawn(World world, int side) {
        int x = site.x() + (side == 0 ? -SPAWN_DISTANCE / 2 : SPAWN_DISTANCE / 2);
        int y = world.getHighestBlockYAt(x, site.z(), HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1;
        Location location = new Location(world, x + 0.5, y, site.z() + 0.5);
        location.setYaw(side == 0 ? -90 : 90);
        return location;
    }

    private static int parseGeneration(String name) {
        try {
            return Integer.parseInt(name.replaceAll("\\D", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ---------------------------------------------------------------- ticking

    private void ensureTicking() {
        if (task == null) {
            task = Bukkit.getScheduler().runTaskTimer(plugin, this::tickAll, 1L, 1L);
        }
    }

    private void tickAll() {
        if (!running()) {
            if (finder != null) {
                finder.poll(java.util.List.of()); // keep a site ready for the next duel
            }
            return;
        }
        Player player = human;
        if (!waitingReady.isEmpty()) {
            if (prepTicks++ % 40 == 0) {
                for (Player fighter : new Player[] {human, rival}) {
                    if (fighter != null && fighter.isOnline() && waitingReady.contains(fighter.getUniqueId())) {
                        fighter.sendActionBar(Component.text("Envanterini düzenle, hazır olunca /ready yaz", NamedTextColor.YELLOW));
                    }
                }
            }
            return; // the AI stands still and the clock does not run while someone is sorting
        }
        tick++;
        if (tick < 0) {
            int seconds = (-tick + 19) / 20;
            if (-tick % 20 == 0 || tick == -COUNTDOWN_TICKS + 1) {
                title(Title.title(Component.text(String.valueOf(seconds), NamedTextColor.GOLD),
                        Component.text("Hazırlan", NamedTextColor.GRAY), Title.Times.times(Duration.ZERO, Duration.ofMillis(900), Duration.ZERO)));
            }
            return;
        }
        if (tick == 0) {
            title(Title.title(Component.text("Dövüş!", NamedTextColor.RED), Component.empty(),
                    Title.Times.times(Duration.ZERO, Duration.ofMillis(800), Duration.ofMillis(300))));
        }
        if (rival != null) {
            tickPvp(player, rival);
            return;
        }
        if (npc == null || !npc.handle().isAlive() || npc.handle().isRemoved()) {
            finish(player, "Yapay zekâ öldü");
            return;
        }
        if (!player.isOnline() || !player.getWorld().equals(npc.bukkit().getWorld())) {
            finish(null, "Oyuncu düellodan ayrıldı");
            return;
        }
        if (escaped(handle(player))) {
            finish(null, "Sen alandan kaçtın");
            return;
        }
        if (escaped(npc.handle())) {
            finish(player, "Yapay zekâ alandan kaçtı");
            return;
        }
        if (tick >= plugin.getConfig().getInt("duel.timeout-ticks", 6000)) {
            finish(null, "Süre doldu");
            return;
        }
        recorder = tickRecorder(recorder);
        try {
            var opponent = ((org.bukkit.craftbukkit.entity.CraftPlayer) player).getHandle();
            ActionSpace.Context context = space.observe(npc, opponent);
            boolean[][] mask = space.mask(npc, context);
            float[] obs = builder.build(npc, opponent, context, this);
            NpcAction action = NpcAction.of(policy.act(obs, mask, random, greedy));
            space.execute(npc, action, context, 1);
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.SEVERE, "duel tick failed", e);
            finish(null, "Hata: " + e.getMessage());
        }
    }

    private void tickPvp(Player first, Player second) {
        for (Player player : new Player[] {first, second}) {
            Player other = player == first ? second : first;
            if (!player.isOnline() || !player.getWorld().equals(other.getWorld())) {
                finish(null, player.getName() + " düellodan ayrıldı");
                return;
            }
            if (escaped(handle(player))) {
                finish(other, player.getName() + " alandan kaçtı");
                return;
            }
        }
        if (tick >= plugin.getConfig().getInt("duel.timeout-ticks", 6000)) {
            finish(null, "Süre doldu");
            return;
        }
        recorder = tickRecorder(recorder);
        rivalRecorder = tickRecorder(rivalRecorder);
    }

    private @Nullable DuelRecorder tickRecorder(@Nullable DuelRecorder which) {
        if (which == null) {
            return null;
        }
        try {
            which.tick(tick);
            return which;
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "duel recording stopped", e);
            which.close(null, "Kayıt hatası: " + e.getMessage(), tick);
            return null;
        }
    }

    private void title(Title title) {
        for (Player player : new Player[] {human, rival}) {
            if (player != null && player.isOnline()) {
                player.showTitle(title);
            }
        }
    }

    private double distance(double x, double z) {
        double dx = x - (site.x() + 0.5);
        double dz = z - (site.z() + 0.5);
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * Outside the border hurts (armor-piercing zone damage, like the training arenas); only
     * running more than ESCAPE_MARGIN beyond it ends the duel. Knockback out of the border
     * no longer decides a fight.
     */
    private boolean escaped(net.minecraft.server.level.ServerPlayer fighter) {
        double distance = distance(fighter.getX(), fighter.getZ());
        if (distance > BOUNDARY + ESCAPE_MARGIN) {
            return true;
        }
        if (distance > BOUNDARY && tick % ZONE_DAMAGE_INTERVAL == 0) {
            fighter.hurtServer(fighter.level(), fighter.damageSources().outOfBorder(), ZONE_DAMAGE);
        }
        return false;
    }

    /**
     * @param winner the player if they won, null for "AI won / no result" (reason says which); in
     *               player vs. player the winning player or null for no result
     */
    private void finish(@Nullable Player winner, String reason) {
        waitingReady.clear();
        if (rival != null) {
            finishPvp(winner, reason);
            return;
        }
        Player player = human;
        boolean playerWon = winner != null;
        boolean aiWon = !playerWon && (reason.startsWith("Sen") || reason.equals("Öldün"));
        String headline = playerWon ? "Kazandın!" : aiWon ? "Yapay zekâ kazandı" : "Düello bitti";
        String detail = reason + String.format(" | %d sn | patlayan totem: sen %d, YZ %d",
                Math.max(0, tick) / 20, totems[1], totems[0]);
        plugin.getLogger().info("[duel] " + headline + ": " + detail);
        if (recorder != null) {
            recorder.close(playerWon ? "self" : aiWon ? "opponent" : null, reason, Math.max(0, tick));
            recorder = null;
        }
        if (player != null && player.isOnline()) {
            player.showTitle(Title.title(Component.text(headline, playerWon ? NamedTextColor.GREEN : NamedTextColor.RED),
                    Component.text(reason, NamedTextColor.GRAY)));
            player.sendMessage(Component.text(headline + ": " + detail, NamedTextColor.AQUA));
        }
        CrystalNpc leftover = npc;
        human = null;
        npc = null;
        policy = null;
        site = null;
        if (leftover != null) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (npcs.get(leftover.handle().getUUID()) != null) {
                    npcs.remove(leftover);
                }
            }, 60L);
        }
        if (player != null && player.isOnline() && previousMode != null && previousMode != GameMode.SURVIVAL) {
            GameMode mode = previousMode;
            Bukkit.getScheduler().runTaskLater(plugin, () -> player.setGameMode(mode), 100L);
        }
        if (resumeSprint && arenas.running()) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "tick sprint 100000000");
        }
    }

    private void finishPvp(@Nullable Player winner, String reason) {
        Player first = human;
        Player second = rival;
        String headline = winner != null ? winner.getName() + " kazandı" : "Düello bitti";
        String detail = reason + String.format(" | %d sn | patlayan totem: %s %d, %s %d", Math.max(0, tick) / 20,
                first.getName(), totems[1], second.getName(), totems[0]);
        plugin.getLogger().info("[duel] " + headline + ": " + detail);
        int ticks = Math.max(0, tick);
        if (recorder != null) {
            recorder.close(winner == null ? null : winner.equals(first) ? "self" : "opponent", reason, ticks);
        }
        if (rivalRecorder != null) {
            rivalRecorder.close(winner == null ? null : winner.equals(second) ? "self" : "opponent", reason, ticks);
        }
        GameMode[] modes = {previousMode, rivalPreviousMode};
        Player[] players = {first, second};
        human = null;
        rival = null;
        recorder = null;
        rivalRecorder = null;
        site = null;
        for (int i = 0; i < players.length; i++) {
            Player player = players[i];
            if (!player.isOnline()) {
                continue;
            }
            player.showTitle(Title.title(Component.text(headline, NamedTextColor.GOLD), Component.text(reason, NamedTextColor.GRAY)));
            player.sendMessage(Component.text(headline + ": " + detail, NamedTextColor.AQUA));
            GameMode mode = modes[i];
            if (mode != null && mode != GameMode.SURVIVAL) {
                Bukkit.getScheduler().runTaskLater(plugin, () -> player.setGameMode(mode), 100L);
            }
        }
        if (resumeSprint && arenas.running()) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "tick sprint 100000000");
        }
    }

    // ---------------------------------------------------------------- events

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        if (!running()) {
            return;
        }
        if (rival != null) {
            if (event.getEntity().equals(human)) {
                finish(rival, human.getName() + " öldü");
            } else if (event.getEntity().equals(rival)) {
                finish(human, rival.getName() + " öldü");
            }
        } else if (event.getEntity().equals(human)) {
            finish(null, "Öldün");
        }
    }

    /** Nobody gets hurt while the fighters are still sorting their inventories. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPrepDamage(org.bukkit.event.entity.EntityDamageEvent event) {
        if (!running() || waitingReady.isEmpty()) {
            return;
        }
        UUID id = event.getEntity().getUniqueId();
        if (id.equals(human.getUniqueId()) || rival != null && id.equals(rival.getUniqueId())
                || npc != null && id.equals(npc.handle().getUUID())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTotem(EntityResurrectEvent event) {
        if (!running()) {
            return;
        }
        UUID id = event.getEntity().getUniqueId();
        if (npc != null && id.equals(npc.handle().getUUID()) || rival != null && id.equals(rival.getUniqueId())) {
            totems[0]++;
        } else if (id.equals(human.getUniqueId())) {
            totems[1]++;
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (running() && (event.getPlayer().equals(human) || event.getPlayer().equals(rival))) {
            finish(null, event.getPlayer().getName() + " oyundan çıktı");
        }
    }

    // ---------------------------------------------------------------- ObservationFrame

    @Override
    public @Nullable double[] siteCenter() {
        return site == null ? null : new double[] {site.x() + 0.5, site.z() + 0.5};
    }

    @Override
    public double boundaryRadius() {
        return BOUNDARY;
    }

    @Override
    public int episodeTick() {
        return Math.max(0, tick);
    }
}
