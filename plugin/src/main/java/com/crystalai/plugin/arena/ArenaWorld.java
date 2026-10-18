package com.crystalai.plugin.arena;

import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.generator.ChunkGenerator;

/** Empty (void) world that holds the training arenas. */
final class ArenaWorld {

    private ArenaWorld() {
    }

    /** All vanilla generation steps default to off, so this generates nothing. */
    private static final class VoidGenerator extends ChunkGenerator {
    }

    static World getOrCreate(ArenaSettings settings) {
        World world = Bukkit.getWorld(settings.worldName());
        if (world == null) {
            world = new WorldCreator(settings.worldName())
                    .generator(new VoidGenerator())
                    .generateStructures(false)
                    .createWorld();
            if (world == null) {
                throw new IllegalStateException("Could not create world " + settings.worldName());
            }
        }
        applyRules(world, settings.difficulty());
        return world;
    }

    /** A normal generated overworld for the natural terrain mode (random fight sites). */
    static World getOrCreateNatural(TerrainSettings settings, org.bukkit.Difficulty difficulty) {
        return getOrCreateNatural(settings.worldName(), settings.seed(), settings, difficulty);
    }

    /** Same, under another name and seed (world reset: a fresh map each time). */
    static World getOrCreateNatural(String name, long seed, TerrainSettings settings, org.bukkit.Difficulty difficulty) {
        World world = Bukkit.getWorld(name);
        if (world == null) {
            world = new WorldCreator(name)
                    .environment(World.Environment.NORMAL)
                    .type(org.bukkit.WorldType.NORMAL)
                    .seed(seed)
                    .generateStructures(false)
                    .createWorld();
            if (world == null) {
                throw new IllegalStateException("Could not create world " + name);
            }
        }
        applyRules(world, difficulty);
        // 16 fake players would otherwise keep a view-distance square of chunks each loaded and generated.
        world.setViewDistance(settings.viewDistance());
        world.setSimulationDistance(settings.viewDistance());
        return world;
    }

    private static void applyRules(World world, org.bukkit.Difficulty difficulty) {
        world.setDifficulty(difficulty);
        world.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        world.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
        world.setGameRule(GameRule.ANNOUNCE_ADVANCEMENTS, false);
        world.setGameRule(GameRule.SHOW_DEATH_MESSAGES, false);
        world.setGameRule(GameRule.DO_TILE_DROPS, false);
        world.setGameRule(GameRule.DO_ENTITY_DROPS, false);
        world.setTime(6000);
        world.setStorm(false);
    }
}
