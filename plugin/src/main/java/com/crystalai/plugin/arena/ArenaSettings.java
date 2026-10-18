package com.crystalai.plugin.arena;

import java.util.Locale;
import javax.annotation.Nullable;
import org.bukkit.Difficulty;
import org.bukkit.configuration.ConfigurationSection;

/** The {@code arenas} section of config.yml. */
public record ArenaSettings(
        String worldName,
        int count,
        int spacing,
        int floorY,
        int floorRadius,
        int wallHeight,
        int spawnDistance,
        int episodeTimeoutTicks,
        int decisionIntervalTicks,
        Difficulty difficulty,
        String controllerA,
        String controllerB,
        boolean logEpisodes,
        ConfigurationSection randomSettings,
        /** Null: small bedrock arenas in a void world. Else: random sites in a generated overworld. */
        @Nullable TerrainSettings natural) {

    public static ArenaSettings from(ConfigurationSection section) {
        ConfigurationSection random = section.getConfigurationSection("random");
        if (random == null) {
            random = section.createSection("random");
        }
        String terrain = section.getString("terrain", "flat").toLowerCase(Locale.ROOT);
        ConfigurationSection naturalSection = section.getConfigurationSection("natural");
        if (naturalSection == null) {
            naturalSection = section.createSection("natural");
        }
        return new ArenaSettings(
                section.getString("world", "crystalai_arenas"),
                Math.max(1, section.getInt("count", 8)),
                Math.max(32, section.getInt("spacing", 64)),
                section.getInt("floor-y", 64),
                Math.max(4, section.getInt("floor-radius", 10)),
                Math.max(3, section.getInt("wall-height", 6)),
                Math.max(2, section.getInt("spawn-distance", 6)),
                Math.max(20, section.getInt("episode-timeout-ticks", 1200)),
                Math.max(1, section.getInt("decision-interval-ticks", 2)),
                Difficulty.valueOf(section.getString("difficulty", "hard").toUpperCase(Locale.ROOT)),
                section.getString("controllers.a", "random"),
                section.getString("controllers.b", "random"),
                section.getBoolean("log-episodes", true),
                random,
                terrain.equals("natural") ? TerrainSettings.from(naturalSection) : null);
    }

    public ArenaSettings withCount(int newCount) {
        return new ArenaSettings(worldName, Math.max(1, newCount), spacing, floorY, floorRadius, wallHeight,
                spawnDistance, episodeTimeoutTicks, decisionIntervalTicks, difficulty, controllerA, controllerB,
                logEpisodes, randomSettings, natural);
    }
}
