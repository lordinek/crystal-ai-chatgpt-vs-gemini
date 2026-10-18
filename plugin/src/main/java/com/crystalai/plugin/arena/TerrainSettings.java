package com.crystalai.plugin.arena;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;

/**
 * The {@code arenas.natural} section of config.yml: fights in a generated overworld at random
 * spots, like /rtp on a real crystal PvP server (docs/tam_kit_plani.md, section 1b).
 */
public record TerrainSettings(
        String worldName,
        long seed,
        /** Sites are picked within this many blocks of 0,0. */
        int regionRadius,
        /** Leaving this horizontal distance from the site centre loses the episode. */
        int boundaryRadius,
        /** Minimum distance between the sites of two arenas. */
        int minSiteSeparation,
        /** Chunks around the site kept loaded (and generated in advance). */
        int loadRadiusChunks,
        /** Max surface height difference across the spawn line and its surroundings. */
        int maxSlope,
        /** Blocks around the centre that must be free of water, lava, leaves and logs. */
        int clearRadius,
        /** A site is used for this many episodes (random in [min, max]), then a fresh one is taken. */
        int episodesPerSiteMin,
        int episodesPerSiteMax,
        /** Tries per target biome before any listed biome is accepted. */
        int attemptsPerBiome,
        /** View/simulation distance of the arena world, keeps 16 fake players from loading huge areas. */
        int viewDistance,
        /** Biome key (e.g. "desert") -> relative weight. */
        Map<String, Double> biomes,
        /**
         * World reset (disk): every generated site stays on disk, a long run grew the world by
         * several GB per hour. The world is thrown away and regenerated with a new seed after this
         * many minutes or once its folder is above this size (0 = off).
         */
        int resetEveryMinutes,
        int resetAboveMb,
        /** The boundary shrinks linearly from boundaryRadius to shrinkMinRadius between these episode ticks (end <= start = off). */
        int shrinkStartTicks,
        int shrinkEndTicks,
        int shrinkMinRadius,
        /** Outside the current border: this much damage (armor-piercing, like the world border) every interval. */
        float zoneDamage,
        int zoneDamageIntervalTicks,
        /** Only this far beyond the full boundary radius counts as having left the arena (a loss). */
        int escapeMargin) {

    public static TerrainSettings from(ConfigurationSection section) {
        Map<String, Double> biomes = new LinkedHashMap<>();
        ConfigurationSection weights = section.getConfigurationSection("biomes");
        if (weights != null) {
            for (String key : weights.getKeys(false)) {
                double weight = weights.getDouble(key);
                if (weight > 0) {
                    biomes.put(key.toLowerCase(Locale.ROOT), weight);
                }
            }
        }
        if (biomes.isEmpty()) {
            biomes.put("plains", 1.0);
        }
        int min = Math.max(1, section.getInt("episodes-per-site-min", 1));
        return new TerrainSettings(
                section.getString("world", "crystalai_dunya"),
                section.getLong("seed", 20260926L),
                Math.max(256, section.getInt("region-radius", 4000)),
                Math.max(8, section.getInt("boundary-radius", 32)),
                Math.max(64, section.getInt("min-site-separation", 200)),
                Math.max(1, section.getInt("load-radius-chunks", 3)),
                Math.max(0, section.getInt("max-slope", 3)),
                Math.max(2, section.getInt("clear-radius", 6)),
                min,
                Math.max(min, section.getInt("episodes-per-site-max", 4)),
                Math.max(1, section.getInt("attempts-per-biome", 24)),
                Math.max(2, section.getInt("view-distance", 3)),
                biomes,
                Math.max(0, section.getInt("reset-every-minutes", 90)),
                Math.max(0, section.getInt("reset-above-mb", 3000)),
                Math.max(0, section.getInt("shrink-start-ticks", 400)),
                Math.max(0, section.getInt("shrink-end-ticks", 2000)),
                Math.max(4, section.getInt("shrink-min-radius", 8)),
                (float) Math.max(0.0, section.getDouble("zone-damage", 2.0)),
                Math.max(1, section.getInt("zone-damage-interval-ticks", 10)),
                Math.max(0, section.getInt("escape-margin", 16)));
    }
}
