package com.crystalai.plugin.arena;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nullable;
import org.bukkit.Chunk;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.util.BiomeSearchResult;

/**
 * Finds fight sites in the natural arena world without blocking the server thread: chunks are
 * generated through Paper's async chunk API while an episode runs, and the site is validated on
 * the main thread once they are loaded. Polled by the arena, also for the first site
 * (a synchronous search froze the server for many seconds).
 */
final class SiteFinder {

    /** A validated fight site: centre column and its surface height. */
    record Site(int x, int y, int z, String biome) {
        double distanceSq(Site other) {
            double dx = x - other.x;
            double dz = z - other.z;
            return dx * dx + dz * dz;
        }
    }

    private enum Stage { IDLE, AREA }

    /** Biome search around a random point (blocks) and its sampling step, as /locate biome does. */
    private static final int BIOME_SEARCH_RADIUS = 768;
    private static final int BIOME_SEARCH_STEP = 32;
    /** Grid step (blocks) when looking for a good centre inside the generated area. */
    private static final int CENTER_STEP = 4;

    private final World world;
    private final TerrainSettings settings;
    private final Random rng;

    private Stage stage = Stage.IDLE;
    private int x;
    private int z;
    private @Nullable String targetBiome;
    private int attempts;
    private final List<CompletableFuture<Chunk>> pending = new ArrayList<>();
    private @Nullable Site ready;

    SiteFinder(World world, TerrainSettings settings, long seed) {
        this.world = world;
        this.settings = settings;
        this.rng = new Random(seed);
    }

    /** Main thread. Advances the background search by one step. */
    void poll(Collection<Site> avoid) {
        if (ready != null) {
            return;
        }
        switch (stage) {
            case IDLE -> {
                // Find the biome from the biome noise alone, like /locate biome: no chunk is
                // generated for rejected spots (probing real chunks generated 31k of them).
                if (!pickPoint(avoid) || !moveToBiome() || tooClose(avoid)) {
                    retry();
                    return;
                }
                request(settings.loadRadiusChunks());
                stage = Stage.AREA;
            }
            case AREA -> {
                if (!allDone()) {
                    return;
                }
                Site site = failed() ? null : validate(avoid);
                if (site == null) {
                    retry();
                } else {
                    ready = site;
                    targetBiome = null;
                    attempts = 0;
                    stage = Stage.IDLE;
                }
            }
        }
    }

    /** A validated site, if the background search has found one; hands it over only once. */
    @Nullable Site take() {
        Site site = ready;
        ready = null;
        return site;
    }

    // ---------------------------------------------------------------- search steps

    /** Moves (x, z) to the nearest spot of the wanted biome(s); false if none is near. */
    private boolean moveToBiome() {
        List<Biome> wanted = new ArrayList<>();
        for (String key : targetBiome != null ? List.of(targetBiome) : settings.biomes().keySet()) {
            Biome biome = biome(key);
            if (biome != null) {
                wanted.add(biome);
            }
        }
        if (wanted.isEmpty()) {
            return false;
        }
        BiomeSearchResult result = world.locateNearestBiome(new Location(world, x, 64, z), BIOME_SEARCH_RADIUS,
                BIOME_SEARCH_STEP, 64, wanted.toArray(new Biome[0]));
        if (result == null) {
            return false;
        }
        x = result.getLocation().getBlockX();
        z = result.getLocation().getBlockZ();
        return true;
    }

    @SuppressWarnings("deprecation")
    private static @Nullable Biome biome(String key) {
        return Registry.BIOME.get(NamespacedKey.minecraft(key));
    }

    private boolean tooClose(Collection<Site> avoid) {
        Site probe = new Site(x, 0, z, "");
        for (Site other : avoid) {
            if (other != null && probe.distanceSq(other) < (double) settings.minSiteSeparation() * settings.minSiteSeparation()) {
                return true;
            }
        }
        return false;
    }

    private boolean pickPoint(Collection<Site> avoid) {
        if (targetBiome == null && attempts == 0) {
            targetBiome = weightedBiome();
        }
        int radius = settings.regionRadius();
        for (int i = 0; i < 16; i++) {
            int px = rng.nextInt(2 * radius + 1) - radius;
            int pz = rng.nextInt(2 * radius + 1) - radius;
            Site probe = new Site(px, 0, pz, "");
            boolean clear = true;
            for (Site other : avoid) {
                if (other != null && probe.distanceSq(other) < (double) settings.minSiteSeparation() * settings.minSiteSeparation()) {
                    clear = false;
                    break;
                }
            }
            if (clear) {
                x = px;
                z = pz;
                return true;
            }
        }
        return false;
    }

    private void request(int radiusChunks) {
        pending.clear();
        for (int cx = (x >> 4) - radiusChunks; cx <= (x >> 4) + radiusChunks; cx++) {
            for (int cz = (z >> 4) - radiusChunks; cz <= (z >> 4) + radiusChunks; cz++) {
                pending.add(world.getChunkAtAsync(cx, cz, true));
            }
        }
    }

    private boolean allDone() {
        for (CompletableFuture<Chunk> future : pending) {
            if (!future.isDone()) {
                return false;
            }
        }
        return true;
    }

    private boolean failed() {
        for (CompletableFuture<Chunk> future : pending) {
            if (future.isCompletedExceptionally()) {
                return true;
            }
        }
        return false;
    }

    private void retry() {
        attempts++;
        if (attempts >= settings.attemptsPerBiome()) {
            targetBiome = null; // give up on the rare biome, any listed one will do
        }
        stage = Stage.IDLE;
    }

    private String weightedBiome() {
        double total = 0;
        for (double w : settings.biomes().values()) {
            total += w;
        }
        double pick = rng.nextDouble() * total;
        for (Map.Entry<String, Double> entry : settings.biomes().entrySet()) {
            pick -= entry.getValue();
            if (pick <= 0) {
                return entry.getKey();
            }
        }
        return settings.biomes().keySet().iterator().next();
    }

    @SuppressWarnings("deprecation")
    private String biomeAt(int bx, int bz) {
        int y = world.getHighestBlockYAt(bx, bz, HeightMap.MOTION_BLOCKING_NO_LEAVES);
        return world.getBiome(bx, y, bz).getKey().getKey().toLowerCase(Locale.ROOT);
    }

    /**
     * Main thread, chunks loaded. Looks for a centre near the located spot (closest first) whose
     * surroundings are dry, free of trees and huge mushrooms and roughly flat, in the drawn biome
     * (or any listed one once that biome was given up). Checking only the located spot rejected
     * almost every desert (cacti), taiga (trees) and badlands (slopes) site.
     */
    private @Nullable Site validate(Collection<Site> avoid) {
        int reach = Math.max(0, settings.loadRadiusChunks() * 16 - settings.boundaryRadius());
        List<int[]> offsets = new ArrayList<>();
        for (int dx = -reach; dx <= reach; dx += CENTER_STEP) {
            for (int dz = -reach; dz <= reach; dz += CENTER_STEP) {
                offsets.add(new int[]{dx, dz});
            }
        }
        offsets.sort((a, b) -> Integer.compare(a[0] * a[0] + a[1] * a[1], b[0] * b[0] + b[1] * b[1]));
        for (int[] offset : offsets) {
            int cx = x + offset[0];
            int cz = z + offset[1];
            String biome = biomeAt(cx, cz); // the surface biome can differ from the noise sample
            boolean wanted = targetBiome != null ? targetBiome.equals(biome) : settings.biomes().containsKey(biome);
            if (wanted && clearAndFlat(cx, cz)) {
                int y = world.getHighestBlockYAt(cx, cz, HeightMap.MOTION_BLOCKING_NO_LEAVES);
                return new Site(cx, y, cz, biome);
            }
        }
        return null;
    }

    private boolean clearAndFlat(int cx, int cz) {
        int r = settings.clearRadius();
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int ground = world.getHighestBlockYAt(cx + dx, cz + dz, HeightMap.MOTION_BLOCKING_NO_LEAVES);
                int withLeaves = world.getHighestBlockYAt(cx + dx, cz + dz, HeightMap.MOTION_BLOCKING);
                if (withLeaves > ground || !acceptableGround(world.getBlockAt(cx + dx, ground, cz + dz))) {
                    return false; // leaves overhead, water, trunks, cacti...
                }
                min = Math.min(min, ground);
                max = Math.max(max, ground);
                if (max - min > settings.maxSlope()) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean acceptableGround(Block block) {
        Material type = block.getType();
        if (block.isLiquid() || type == Material.ICE || type == Material.PACKED_ICE || type == Material.BLUE_ICE) {
            return false;
        }
        if (Tag.LOGS.isTagged(type) || Tag.LEAVES.isTagged(type)) {
            return false;
        }
        return type != Material.MUSHROOM_STEM && type != Material.RED_MUSHROOM_BLOCK && type != Material.BROWN_MUSHROOM_BLOCK
                && type != Material.CACTUS && type != Material.POWDER_SNOW;
    }
}
