package com.crystalai.plugin.llm;

import java.io.File;
import java.util.Locale;
import javax.annotation.Nullable;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.util.Vector;

/**
 * Where one match is fought: the generated bedrock box (a closed, symmetric square in its own void world,
 * built once and reused), or an arena built by hand (see {@link ArenaStore}).
 */
final class LlmArena {

    private static final class VoidGenerator extends ChunkGenerator {
    }

    private final String name;
    private final Location[] spawns;
    private final Location camera;
    private final String note;
    private final String armor;
    private final int goldenApples;
    private final boolean shield;

    private LlmArena(String name, Location spawn0, Location spawn1, Location camera, String note, String armor,
                     int goldenApples, boolean shield) {
        this.name = name;
        this.spawns = new Location[] {spawn0, spawn1};
        this.camera = camera;
        this.note = note;
        this.armor = armor;
        this.goldenApples = goldenApples;
        this.shield = shield;
    }

    /**
     * Loads a world an arena lives in: Paper only loads the main worlds at startup. The box world is
     * (re)opened with its void generator; any other world only if its folder already exists.
     * @return null when there is no such world
     */
    static @Nullable World loadWorld(String name, LlmSettings settings) {
        World world = Bukkit.getWorld(name);
        if (world != null) {
            return world;
        }
        if (name.equals(settings.world())) {
            return new WorldCreator(name).generator(new VoidGenerator()).generateStructures(false).createWorld();
        }
        if (new File(Bukkit.getWorldContainer(), name + File.separator + "level.dat").isFile()) {
            return new WorldCreator(name).createWorld();
        }
        return null;
    }

    static LlmArena box(LlmSettings settings) {
        return boxAt(settings, ArenaStore.BOX, 0, 0);
    }

    /** A copy of the box centred on ({@code cx}, {@code cz}) in the box world: the training lanes. */
    static LlmArena boxAt(LlmSettings settings, String name, int cx, int cz) {
        World world = loadWorld(settings.world(), settings);
        if (world == null) {
            throw new IllegalStateException("Could not create world " + settings.world());
        }
        world.setDifficulty(Difficulty.HARD);
        world.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        world.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
        world.setGameRule(GameRule.ANNOUNCE_ADVANCEMENTS, false);
        world.setGameRule(GameRule.SHOW_DEATH_MESSAGES, false);
        world.setGameRule(GameRule.KEEP_INVENTORY, true);
        world.setGameRule(GameRule.NATURAL_REGENERATION, settings.naturalRegeneration());
        world.setTime(6000);
        world.setStorm(false);
        build(world, settings, cx, cz);

        // Slot 0 west facing east, slot 1 east facing west; wide camera above and behind the south wall.
        double half = settings.spawnDistance() / 2.0;
        int y = settings.floorY() + 1;
        int r = settings.floorRadius();
        return new LlmArena(name,
                new Location(world, cx - half + 0.5, y, cz + 0.5, -90.0F, 0.0F),
                new Location(world, cx + half + 0.5, y, cz + 0.5, 90.0F, 0.0F),
                new Location(world, cx + 0.5, settings.floorY() + r * 0.7 + 3, cz + r + 6.5, 180.0F, 30.0F),
                "", settings.armorMaterial(), settings.goldenApples(), settings.shield());
    }

    /** Bedrock floor, barrier walls. Idempotent: setting a block to what it is costs nothing. */
    private static void build(World world, LlmSettings settings, int cx, int cz) {
        int r = settings.floorRadius();
        int y = settings.floorY();
        for (int x = -r - 1; x <= r + 1; x++) {
            for (int z = -r - 1; z <= r + 1; z++) {
                boolean wall = Math.abs(x) == r + 1 || Math.abs(z) == r + 1;
                world.getBlockAt(cx + x, y, cz + z).setType(Material.BEDROCK, false);
                for (int h = 1; h <= settings.wallHeight(); h++) {
                    world.getBlockAt(cx + x, y + h, cz + z).setType(wall ? Material.BARRIER : Material.AIR, false);
                }
            }
        }
    }

    /**
     * A hand-built arena. The world is left as it is (no game rules touched): regeneration, drops and
     * death messages are handled per fighter by {@link LlmMatchManager}.
     * @throws IllegalStateException when the arena is incomplete or its world is not loaded
     */
    static LlmArena custom(ArenaStore.Def def, LlmSettings settings) {
        def.loadWorlds(name -> loadWorld(name, settings));
        String problem = def.problem();
        if (problem != null) {
            throw new IllegalStateException(problem);
        }
        Location a = def.spawn1.resolve();
        Location b = def.spawn2.resolve();
        // The two fighters face each other whatever way the builder looked when saving the points.
        a.setDirection(b.toVector().subtract(a.toVector()).setY(0));
        b.setDirection(a.toVector().subtract(b.toVector()).setY(0));
        Location camera = def.camera != null ? def.camera.resolve() : sideCamera(a, b);
        String armor = def.armor != null ? def.armor : settings.armorMaterial();
        if (!armor.equalsIgnoreCase("none") && Material.matchMaterial(armor.toUpperCase(Locale.ROOT) + "_HELMET") == null) {
            throw new IllegalStateException(def.name + ": bilinmeyen zirh " + armor);
        }
        return new LlmArena(def.name, a, b, camera, def.note, armor,
                def.goldenApples != null ? Math.max(0, def.goldenApples) : settings.goldenApples(),
                def.shield != null ? def.shield : settings.shield());
    }

    /** Off to one side of the line between the spawns, raised, looking at the midpoint. */
    private static Location sideCamera(Location a, Location b) {
        Vector mid = a.toVector().add(b.toVector()).multiply(0.5);
        Vector line = b.toVector().subtract(a.toVector()).setY(0);
        double back = Math.max(8.0, line.length() * 0.9);
        Vector side = line.lengthSquared() < 1e-6 ? new Vector(0, 0, 1) : new Vector(-line.getZ(), 0, line.getX()).normalize();
        Location camera = mid.clone().add(side.multiply(back)).add(new Vector(0, back * 0.6, 0)).toLocation(a.getWorld());
        camera.setDirection(mid.clone().subtract(camera.toVector()));
        return camera;
    }

    String name() {
        return name;
    }

    World world() {
        return spawns[0].getWorld();
    }

    /** Spawn point of slot 0 or 1. */
    Location spawn(int slot) {
        return spawns[slot].clone();
    }

    Location camera() {
        return camera.clone();
    }

    String note() {
        return note;
    }

    String armor() {
        return armor;
    }

    int goldenApples() {
        return goldenApples;
    }

    boolean shield() {
        return shield;
    }
}
