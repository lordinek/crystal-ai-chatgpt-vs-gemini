package com.crystalai.plugin.llm;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;
import javax.annotation.Nullable;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;

/**
 * Watches a human in /yzmac kapis and boils how they fight down to the same ten numbers a FightCard has
 * (ders-defteri/insan_&lt;isim&gt;.json). Counters add up across duels, so every recorded fight sharpens the
 * estimate. Put the card into llm.yml egitim.kukla (and kukla-zorlasir: false) and the models train against it.
 */
final class HumanProfile {

    private static final double CLOSE = 4.5;
    private static final int WINDOW = 12;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final File file;
    private final String name;
    private final Map<String, Double> t = new HashMap<>();

    private boolean hasLast;
    private double lastX;
    private double lastZ;
    private int lastLateralSign;
    private double lastCooldown = 1.0;
    private double lastReach = 3.0;
    private int sinceHit = WINDOW;
    private int nonSprint;
    private int movingAway;
    private boolean wasEating;

    HumanProfile(File folder, String name) {
        this.name = name;
        this.file = new File(folder, "insan_" + name + ".json");
        if (file.isFile()) {
            try {
                JsonObject json = GSON.fromJson(Files.readString(file.toPath(), StandardCharsets.UTF_8), JsonObject.class);
                JsonObject raw = json == null ? null : json.getAsJsonObject("ham");
                if (raw != null) {
                    raw.entrySet().forEach(e -> t.put(e.getKey(), e.getValue().getAsDouble()));
                }
            } catch (IOException | RuntimeException ignored) {
                // start over
            }
        }
    }

    private void add(String key, double v) {
        t.merge(key, v, Double::sum);
    }

    private double get(String key) {
        return t.getOrDefault(key, 0.0);
    }

    /** Once per fight tick. */
    void tick(Player human, Player opponent) {
        Location pos = human.getLocation();
        Location other = opponent.getLocation();
        double fx = other.getX() - pos.getX();
        double fz = other.getZ() - pos.getZ();
        double flen = Math.sqrt(fx * fx + fz * fz);
        double reach = reachTo(human, opponent);
        add("ticks", 1);

        if (hasLast && flen > 1e-6) {
            fx /= flen;
            fz /= flen;
            double dx = pos.getX() - lastX;
            double dz = pos.getZ() - lastZ;
            double speed = Math.sqrt(dx * dx + dz * dz);
            double forward = dx * fx + dz * fz;
            double lateral = fx * dz - fz * dx; // sign says which side
            if (reach <= CLOSE && speed > 0.05) {
                add("close_moving", 1);
                if (Math.abs(lateral) > Math.abs(forward) * 0.6) {
                    add("circling", 1);
                    int sign = lateral > 0 ? 1 : -1;
                    if (lastLateralSign != 0 && sign != lastLateralSign) {
                        add("switches", 1);
                    }
                    lastLateralSign = sign;
                }
            }
            if (sinceHit < WINDOW) {
                if (forward < -0.03) {
                    movingAway++;
                }
            }
        }
        lastX = pos.getX();
        lastZ = pos.getZ();
        hasLast = true;

        // Shield while the opponent's swing is ready and they are close.
        if (reach < 3.6 && opponent.getAttackCooldown() >= 0.9F) {
            add("shield_window", 1);
            if (human.isBlocking()) {
                add("shield_ticks", 1);
            }
        }

        // Golden apple: remember the health it was started at.
        boolean eating = human.isHandRaised() && human.getActiveItem().getType() == Material.GOLDEN_APPLE;
        if (eating && !wasEating) {
            add("eats", 1);
            add("eat_hp", human.getHealth() + human.getAbsorptionAmount());
        }
        wasEating = eating;

        if (sinceHit < WINDOW) {
            sinceHit++;
            if (sinceHit <= 8 && !human.isSprinting()) {
                nonSprint++;
            }
            if (sinceHit == WINDOW) {
                flushWindow();
            }
        }
        lastCooldown = human.getAttackCooldown();
        lastReach = reach;
    }

    /** The human landed a hit on the model. Uses last tick's charge and reach: this tick's are already reset. */
    void onHit(boolean critical) {
        if (sinceHit < WINDOW) {
            flushWindow();
        }
        add("hits", 1);
        add("charge", lastCooldown);
        add("reach", lastReach);
        if (critical) {
            add("crit_hits", 1);
        }
        sinceHit = 0;
        nonSprint = 0;
        movingAway = 0;
    }

    private void flushWindow() {
        add("window_hits", 1);
        add("non_sprint", nonSprint);
        add("away", movingAway);
        sinceHit = WINDOW;
    }

    /** Eye to the target's hitbox, as the executor measures it. */
    private static double reachTo(Player self, Player target) {
        Location eye = self.getEyeLocation();
        BoundingBox box = target.getBoundingBox();
        double dx = Math.max(Math.max(box.getMinX() - eye.getX(), 0.0), eye.getX() - box.getMaxX());
        double dy = Math.max(Math.max(box.getMinY() - eye.getY(), 0.0), eye.getY() - box.getMaxY());
        double dz = Math.max(Math.max(box.getMinZ() - eye.getZ(), 0.0), eye.getZ() - box.getMaxZ());
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** Needs a few hits before the numbers mean anything. */
    @Nullable FightCard estimate() {
        double hits = get("hits");
        if (hits < 5) {
            return null;
        }
        double windows = Math.max(1, get("window_hits"));
        double moving = Math.max(1, get("close_moving"));
        double circling = get("circling");
        double shieldWindow = Math.max(1, get("shield_window"));
        double eats = get("eats");
        return new FightCard(
                get("charge") / hits,
                get("crit_hits") / hits,
                (int) Math.round(get("non_sprint") / windows),
                circling / moving,
                (int) Math.round(circling / (get("switches") + 1)),
                (int) Math.round(get("away") / windows),
                get("shield_ticks") / shieldWindow,
                eats > 0 ? get("eat_hp") / eats : 6.0,
                get("reach") / hits,
                true);
    }

    void save(Logger log) {
        FightCard card = estimate();
        JsonObject json = new JsonObject();
        json.addProperty("oyuncu", name);
        json.addProperty("not", "egitim.kukla icin kart tahmini; sayaclar her kapismada birikir");
        if (card != null) {
            json.add("kart", card.toJson());
        }
        JsonObject raw = new JsonObject();
        t.forEach(raw::addProperty);
        json.add("ham", raw);
        try {
            Files.createDirectories(file.getParentFile().toPath());
            Files.writeString(file.toPath(), GSON.toJson(json), StandardCharsets.UTF_8);
            log.info("yz-mac kapis: " + name + " profili kaydedildi (" + (int) get("hits") + " vuruş, "
                    + (card == null ? "kart için veri az" : card.summary()) + ")");
        } catch (IOException e) {
            log.warning("yz-mac kapis: profil yazılamadı: " + e.getMessage());
        }
    }
}
