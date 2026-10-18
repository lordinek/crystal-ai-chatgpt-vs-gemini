package com.crystalai.plugin.llm;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Arenas built by hand in any world, saved from in-game positions: plugins/CrystalAI/arenalar.yml.
 * Kept apart from llm.yml so a save from a command never rewrites the API keys or their comments.
 */
final class ArenaStore {

    /** The generated bedrock box; always there, never stored. */
    static final String BOX = "kutu";

    record Point(String world, double x, double y, double z, float yaw, float pitch) {

        static Point of(Location l) {
            return new Point(l.getWorld().getName(), l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch());
        }

        @Nullable Location resolve() {
            World w = Bukkit.getWorld(world);
            return w == null ? null : new Location(w, x, y, z, yaw, pitch);
        }

        private static @Nullable Point from(@Nullable ConfigurationSection s) {
            if (s == null) {
                return null;
            }
            return new Point(s.getString("dunya", "world"), s.getDouble("x"), s.getDouble("y"), s.getDouble("z"),
                    (float) s.getDouble("yaw"), (float) s.getDouble("pitch"));
        }

        private void write(ConfigurationSection s) {
            s.set("dunya", world);
            s.set("x", round(x));
            s.set("y", round(y));
            s.set("z", round(z));
            s.set("yaw", round(yaw));
            s.set("pitch", round(pitch));
        }

        private static double round(double v) {
            return Math.round(v * 100.0) / 100.0;
        }
    }

    /** One arena. Kit fields left null fall back to llm.yml's kit. */
    static final class Def {
        final String name;
        @Nullable Point spawn1;
        @Nullable Point spawn2;
        @Nullable Point camera;
        /** Told to both models in the system prompt, e.g. "ortada lav çukuru, kenardan düşen ölür". */
        String note = "";
        @Nullable String armor;
        @Nullable Integer goldenApples;
        @Nullable Boolean shield;

        Def(String name) {
            this.name = name;
        }

        /** Loads every world this arena's points are in (see {@link LlmArena#loadWorld}). */
        void loadWorlds(Consumer<String> loader) {
            for (Point p : new Point[] {spawn1, spawn2, camera}) {
                if (p != null) {
                    loader.accept(p.world());
                }
            }
        }

        /** @return null when the arena can host a match, otherwise what is missing */
        @Nullable String problem() {
            if (spawn1 == null || spawn2 == null) {
                return name + ": " + (spawn1 == null ? "nokta 1" : "nokta 2") + " yok (/yzmac arena nokta " + name + " "
                        + (spawn1 == null ? "1" : "2") + ")";
            }
            for (Point p : new Point[] {spawn1, spawn2, camera}) {
                if (p != null && Bukkit.getWorld(p.world()) == null) {
                    return name + ": dünya yüklü değil (" + p.world() + ")";
                }
            }
            if (!spawn1.world().equals(spawn2.world())) {
                return name + ": iki nokta farklı dünyada";
            }
            return null;
        }
    }

    private final File file;
    private final Map<String, Def> arenas = new LinkedHashMap<>();

    ArenaStore(File file) {
        this.file = file;
        load();
    }

    void load() {
        arenas.clear();
        YamlConfiguration c = YamlConfiguration.loadConfiguration(file);
        for (String key : c.getKeys(false)) {
            ConfigurationSection s = c.getConfigurationSection(key);
            if (s == null) {
                continue;
            }
            Def d = new Def(key.toLowerCase(Locale.ROOT));
            d.spawn1 = Point.from(s.getConfigurationSection("nokta1"));
            d.spawn2 = Point.from(s.getConfigurationSection("nokta2"));
            d.camera = Point.from(s.getConfigurationSection("kamera"));
            d.note = s.getString("not", "");
            d.armor = s.getString("kit.zirh");
            d.goldenApples = s.contains("kit.altin-elma") ? s.getInt("kit.altin-elma") : null;
            d.shield = s.contains("kit.kalkan") ? s.getBoolean("kit.kalkan") : null;
            arenas.put(d.name, d);
        }
    }

    void save() throws IOException {
        YamlConfiguration c = new YamlConfiguration();
        c.options().setHeader(List.of(
                "Elle kurulan arenalar (/yzmac arena ...). Elle de düzenlenir, sonra /yzmac yenile.",
                "nokta1/nokta2: iki dövüşçünün doğduğu yer. kamera: /yzmac izle açısı (yoksa otomatik).",
                "not: iki modele de sistem promptunda söylenir (tuzak, lav, boşluk...).",
                "kit: bu arenaya özel; yazılmayan alan llm.yml'deki kit'i kullanır.",
                "  zirh: leather|chainmail|iron|diamond|netherite|none  altin-elma: sayı  kalkan: true|false"));
        for (Def d : arenas.values()) {
            ConfigurationSection s = c.createSection(d.name);
            if (d.spawn1 != null) {
                d.spawn1.write(s.createSection("nokta1"));
            }
            if (d.spawn2 != null) {
                d.spawn2.write(s.createSection("nokta2"));
            }
            if (d.camera != null) {
                d.camera.write(s.createSection("kamera"));
            }
            s.set("not", d.note);
            if (d.armor != null) {
                s.set("kit.zirh", d.armor);
            }
            if (d.goldenApples != null) {
                s.set("kit.altin-elma", d.goldenApples);
            }
            if (d.shield != null) {
                s.set("kit.kalkan", d.shield);
            }
        }
        c.save(file);
    }

    @Nullable Def get(String name) {
        return arenas.get(name.toLowerCase(Locale.ROOT));
    }

    Def getOrCreate(String name) {
        return arenas.computeIfAbsent(name.toLowerCase(Locale.ROOT), Def::new);
    }

    boolean remove(String name) {
        return arenas.remove(name.toLowerCase(Locale.ROOT)) != null;
    }

    List<String> names() {
        return new ArrayList<>(arenas.keySet());
    }
}
