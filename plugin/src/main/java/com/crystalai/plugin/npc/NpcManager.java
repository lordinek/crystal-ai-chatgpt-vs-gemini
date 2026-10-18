package com.crystalai.plugin.npc;

import com.crystalai.plugin.kit.Kit;
import com.mojang.authlib.GameProfile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import javax.annotation.Nullable;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.level.storage.LevelResource;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

/** Spawns, ticks and removes fake-player NPCs. */
public final class NpcManager {

    private static final String TEAM_PREFIX = "cai_";

    private final JavaPlugin plugin;
    private final Map<UUID, CrystalNpc> npcs = new LinkedHashMap<>();
    /** UUIDs that are joining right now; lets listeners silence join messages before registration. */
    private final Set<UUID> joining = new HashSet<>();
    /** Where a joining NPC should appear (applied in PlayerSpawnLocationEvent, avoids a cross-world teleport). */
    private final Map<UUID, Location> spawnLocations = new HashMap<>();
    private @Nullable BukkitTask tickTask;
    private int nameCounter;
    private long tickCounter;

    public NpcManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickAll, 1L, 1L);
    }

    public void shutdown() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        removeAll();
    }

    private void tickAll() {
        int interval = Math.max(1, plugin.getConfig().getInt("npc.chunk-update-interval-ticks", 10));
        boolean updateChunks = tickCounter++ % interval == 0;
        boolean autoFocus = plugin.getConfig().getBoolean("npc.auto-focus", true);
        double focusRange = plugin.getConfig().getDouble("npc.focus-range", 32.0);
        for (CrystalNpc npc : new ArrayList<>(npcs.values())) {
            if (npc.handle().isRemoved() && !npc.handle().isDeadOrDying()) {
                // Removed by something other than us (e.g. /kick): forget it.
                forget(npc);
                continue;
            }
            if (!npc.handle().isAlive()) {
                continue;
            }
            if (autoFocus && !npc.focusLocked()) {
                npc.setFocus(nearestOtherPlayer(npc.handle(), focusRange));
            }
            try {
                npc.tick(updateChunks);
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.SEVERE, "NPC tick failed for " + npc.name() + ", removing it", e);
                remove(npc);
            }
        }
    }

    /** Stand-in for the arena opponent until stage 4: nearest living, non-spectator player (NPC or human). */
    private static @Nullable ServerPlayer nearestOtherPlayer(ServerPlayer self, double range) {
        ServerPlayer best = null;
        double bestDistance = range * range;
        for (ServerPlayer other : self.level().players()) {
            if (other == self || !other.isAlive() || other.isSpectator()) {
                continue;
            }
            double distance = other.distanceToSqr(self);
            if (distance <= bestDistance) {
                best = other;
                bestDistance = distance;
            }
        }
        return best;
    }

    public CrystalNpc spawn(Location location, int generation, String difficulty) {
        return spawn(location, generation, difficulty, null);
    }

    /** @param name player name (max 16 chars), or null for prefix + counter */
    public CrystalNpc spawn(Location location, int generation, String difficulty, @Nullable String name) {
        MinecraftServer server = MinecraftServer.getServer();
        ServerLevel level = levelOf(server, location);

        if (name == null) {
            name = nextName();
        } else if (name.length() > 16 || Bukkit.getPlayerExact(name) != null) {
            throw new IllegalArgumentException("NPC name too long or taken: " + name);
        }
        GameProfile profile = new GameProfile(UUID.randomUUID(), name);
        ServerPlayer player = new ServerPlayer(server, level, profile, ClientInformation.createDefault());
        FakeConnection connection = new FakeConnection();

        joining.add(profile.id());
        spawnLocations.put(profile.id(), location.clone());
        try {
            server.getPlayerList().placeNewPlayer(connection, player, CommonListenerCookie.createInitial(profile, false));
        } finally {
            joining.remove(profile.id());
            spawnLocations.remove(profile.id());
        }

        // 1.21.4+: a joining player is invulnerable (and frozen) until its client reports that it has
        // loaded the world. There is no client here, so report it ourselves right away.
        player.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
        // Optional spawn protection as a hurt cooldown (config; 0 = none, arenas want none).
        player.invulnerableTime = Math.max(0, plugin.getConfig().getInt("npc.spawn-invulnerable-ticks", 0));

        CrystalNpc npc = new CrystalNpc(player, connection, generation, difficulty);
        npcs.put(profile.id(), npc);

        Player bukkit = npc.bukkit();
        bukkit.teleport(location);
        bukkit.setGameMode(GameMode.SURVIVAL);
        applySpawnKit(bukkit.getInventory());
        applyNameTag(npc);
        return npc;
    }

    private static ServerLevel levelOf(MinecraftServer server, Location location) {
        return levelOf(location.getWorld());
    }

    /** NMS level for a Bukkit world, without touching versioned CraftBukkit classes. */
    public static ServerLevel levelOf(org.bukkit.World world) {
        for (ServerLevel level : MinecraftServer.getServer().getAllLevels()) {
            if (level.getWorld().equals(world)) {
                return level;
            }
        }
        throw new IllegalArgumentException("Unknown world: " + world);
    }

    private String nextName() {
        String prefix = plugin.getConfig().getString("npc.name-prefix", "CrystalAI_");
        String name;
        do {
            name = prefix + (++nameCounter);
        } while (Bukkit.getPlayerExact(name) != null);
        if (name.length() > 16) {
            throw new IllegalStateException("NPC name exceeds 16 chars: " + name + " (shorten npc.name-prefix)");
        }
        return name;
    }

    /** Spawn kit per config: "full" = combat kit, "totem" = offhand totem only, "none". */
    private void applySpawnKit(PlayerInventory inventory) {
        String mode = plugin.getConfig().getString("npc.spawn-kit", "totem").toLowerCase(Locale.ROOT);
        switch (mode) {
            case "full" -> combatKit().apply(inventory);
            case "totem" -> inventory.setItemInOffHand(new ItemStack(Material.TOTEM_OF_UNDYING));
            case "none" -> { }
            default -> plugin.getLogger().warning("Unknown npc.spawn-kit: " + mode);
        }
    }

    public Kit combatKit() {
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("kit");
        if (section == null) {
            throw new IllegalStateException("config.yml has no 'kit' section");
        }
        return Kit.from(section);
    }

    private void applyNameTag(CrystalNpc npc) {
        String format = plugin.getConfig().getString("npc.tag-prefix-format", "Gen %04d · ");
        Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
        String teamName = teamName(npc);
        Team team = board.getTeam(teamName);
        if (team == null) {
            team = board.registerNewTeam(teamName);
        }
        team.prefix(Component.text(String.format(format, npc.generation()), NamedTextColor.AQUA));
        team.addEntry(npc.name());
    }

    private static String teamName(CrystalNpc npc) {
        return TEAM_PREFIX + npc.handle().getUUID().toString().substring(0, 8);
    }

    /** Removes the NPC from the server and deletes the player files it left behind. */
    public void remove(CrystalNpc npc) {
        MinecraftServer server = MinecraftServer.getServer();
        forget(npc);
        if (!npc.handle().hasDisconnected()) {
            server.getPlayerList().remove(npc.handle());
        }
        deletePlayerFiles(server, npc.handle().getUUID());
    }

    private void forget(CrystalNpc npc) {
        npcs.remove(npc.handle().getUUID());
        Team team = Bukkit.getScoreboardManager().getMainScoreboard().getTeam(teamName(npc));
        if (team != null) {
            team.unregister();
        }
    }

    public int removeAll() {
        Collection<CrystalNpc> all = new ArrayList<>(npcs.values());
        all.forEach(this::remove);
        return all.size();
    }

    private void deletePlayerFiles(MinecraftServer server, UUID uuid) {
        Path[] files = {
                server.getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(uuid + ".dat"),
                server.getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(uuid + ".dat_old"),
                server.getWorldPath(LevelResource.PLAYER_STATS_DIR).resolve(uuid + ".json"),
                server.getWorldPath(LevelResource.PLAYER_ADVANCEMENTS_DIR).resolve(uuid + ".json"),
        };
        for (Path file : files) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                plugin.getLogger().warning("Could not delete " + file + ": " + e.getMessage());
            }
        }
    }

    /** Spawn location requested for an NPC that is joining right now, or null. */
    public @Nullable Location spawnLocation(UUID uuid) {
        Location location = spawnLocations.get(uuid);
        return location == null ? null : location.clone();
    }

    public boolean isNpc(UUID uuid) {
        return npcs.containsKey(uuid) || joining.contains(uuid);
    }

    public @Nullable CrystalNpc get(UUID uuid) {
        return npcs.get(uuid);
    }

    public @Nullable CrystalNpc byName(String name) {
        for (CrystalNpc npc : npcs.values()) {
            if (npc.name().equalsIgnoreCase(name)) {
                return npc;
            }
        }
        return null;
    }

    public Collection<CrystalNpc> all() {
        return Collections.unmodifiableCollection(npcs.values());
    }
}
