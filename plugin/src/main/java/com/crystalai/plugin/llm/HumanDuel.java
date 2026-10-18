package com.crystalai.plugin.llm;

import com.crystalai.plugin.action.NpcActions;
import com.crystalai.plugin.npc.CrystalNpc;
import com.crystalai.plugin.npc.NpcManager;
import com.google.gson.JsonObject;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * /yzmac kapis <chatgpt|gemini> [arena|sirayla|rastgele] [1|3|5] [mock]: a human against one model.
 * Same kit, arenas, prompt and lesson notebook as the model-vs-model series; the model's state is built
 * against the human. The human never really dies: a killing blow is cancelled and loses the round (no
 * respawn screen, nothing dropped). Inventory, game mode and position come back when the duel ends.
 */
public final class HumanDuel implements Listener {

    private enum Phase { IDLE, COUNTDOWN, FIGHT, PAUSE }

    private final JavaPlugin plugin;
    private final NpcManager npcs;
    private final Supplier<NpcActions> actions;
    private final LlmMatchManager matches;
    private final BossBar bar = BossBar.bossBar(Component.empty(), 1.0F, BossBar.Color.WHITE, BossBar.Overlay.PROGRESS);

    private Phase phase = Phase.IDLE;
    private @Nullable UUID humanId;
    private @Nullable Fighter ai;
    private @Nullable MoveExecutor executor;
    private @Nullable BukkitTask task;
    private List<LlmArena> rotation = List.of();
    private @Nullable LlmArena arena;
    private LlmSettings settings;
    private int round;
    private int humanWins;
    private int aiWins;
    private int winsNeeded;
    private int phaseTicks;
    private int fightTicks;
    /** Bumped when a round ends, so a late answer for it is dropped. */
    private int token;
    /** llm.yml kapis: damage the model deals to the human, and the human to the model, times these. */
    private double aiDamageMultiplier = 1.0;
    private double humanDamageMultiplier = 1.0;

    // what the human had before the duel
    private @Nullable ItemStack[] savedContents;
    private @Nullable ItemStack[] savedArmor;
    private @Nullable ItemStack savedOffhand;
    private @Nullable GameMode savedMode;
    private @Nullable Location savedLocation;

    // admins who started the duel for someone else: spectator at the arena camera, put back at the end
    private final Map<UUID, GameMode> watcherModes = new HashMap<>();
    private final Map<UUID, Location> watcherSpots = new HashMap<>();

    /** How the human fights, written to ders-defteri/insan_&lt;isim&gt;.json when the duel ends. */
    private @Nullable HumanProfile profile;

    /** This round's counters, one line each in yz-mac/kapis-log.jsonl: what the bot could not do, in numbers. */
    private RoundLog roundLog = new RoundLog();

    private static final class RoundLog {
        boolean written;
        int aiShieldTicks;
        int aiIdleTicks;
        int aiLowTicks;
        int aiIdleLowTicks;
        int aiApples;
        int aiHitsBlocked;      // the model's hits the human's shield soaked
        int humanHits;
        int humanCrits;
        int humanHitsBlocked;   // the human's hits the model's shield soaked
        double humanDamage;
        double minAiHealth = 40.0;
        double distSum;
        int closeTicks;         // within sword reach of the human
        int firstAiHitTick = -1;
        int firstHumanHitTick = -1;
        boolean eating;
        boolean hasLast;
        double lastX;
        double lastZ;
    }

    public HumanDuel(JavaPlugin plugin, NpcManager npcs, Supplier<NpcActions> actions, LlmMatchManager matches) {
        this.plugin = plugin;
        this.npcs = npcs;
        this.actions = actions;
        this.matches = matches;
        this.settings = matches.reloadForDuel();
    }

    public boolean busy() {
        return phase != Phase.IDLE;
    }

    /** Puts {@code watcher} in spectator mode at the current arena's camera; follows the duel to each new arena. */
    public void watch(Player watcher) {
        if (phase == Phase.IDLE) {
            return;
        }
        watcherModes.putIfAbsent(watcher.getUniqueId(), watcher.getGameMode());
        watcherSpots.putIfAbsent(watcher.getUniqueId(), watcher.getLocation());
        moveWatchers();
    }

    private void moveWatchers() {
        LlmArena arena = this.arena;
        if (arena == null) {
            return;
        }
        for (UUID id : watcherModes.keySet()) {
            Player watcher = Bukkit.getPlayer(id);
            if (watcher != null) {
                watcher.setGameMode(GameMode.SPECTATOR);
                watcher.teleport(arena.camera());
            }
        }
    }

    private void releaseWatchers() {
        for (Map.Entry<UUID, GameMode> entry : watcherModes.entrySet()) {
            Player watcher = Bukkit.getPlayer(entry.getKey());
            if (watcher != null) {
                Location spot = watcherSpots.get(entry.getKey());
                if (spot != null) {
                    watcher.teleport(spot);
                }
                watcher.setGameMode(entry.getValue());
            }
        }
        watcherModes.clear();
        watcherSpots.clear();
    }

    /** @return null when started, otherwise why not */
    public @Nullable String start(Player player, String who, @Nullable String arenaChoice, int bestOf, @Nullable Boolean mockOverride) {
        if (phase != Phase.IDLE) {
            return "Zaten bir kapışma sürüyor (/yzmac durdur).";
        }
        if (matches.busy()) {
            return "Önce süren seriyi bitir (/yzmac durdur).";
        }
        String key = who.toLowerCase(Locale.ROOT);
        if (!key.equals("chatgpt") && !key.equals("gemini")) {
            return "Rakip chatgpt ya da gemini olmalı.";
        }
        settings = matches.reloadForDuel();
        YamlConfiguration config = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "llm.yml"));
        aiDamageMultiplier = Math.max(0.0, config.getDouble("kapis.rakip-hasar-carpani", 1.0));
        humanDamageMultiplier = Math.max(0.0, config.getDouble("kapis.insan-hasar-carpani", 1.0));
        boolean mock = mockOverride != null ? mockOverride : settings.mock();
        LlmSettings.Side side = key.equals("chatgpt") ? settings.chatgpt() : settings.gemini();
        if (!mock && (!side.hasKey() || side.model().isBlank())) {
            return side.name() + ": llm.yml'de api-anahtari ya da model yok.";
        }
        String sword = settings.sword().toLowerCase(Locale.ROOT);
        if (!sword.equals("diamond_sword") && !sword.equals("netherite_sword")) {
            return "kit.kilic diamond_sword ya da netherite_sword olmalı.";
        }
        List<LlmArena> built = new ArrayList<>();
        String error = matches.buildArenas(arenaChoice == null ? ArenaStore.BOX : arenaChoice.toLowerCase(Locale.ROOT), built);
        if (error != null) {
            return error;
        }

        LlmClient client = mock ? new LlmClient.Mock(settings)
                : key.equals("chatgpt") ? new LlmClient.OpenAi(side, settings) : new LlmClient.Gemini(side, settings);
        ai = new Fighter(key, side, client, key.equals("chatgpt") ? NamedTextColor.GREEN : NamedTextColor.AQUA);
        FightCard card = matches.cardsOn() ? CardTraining.cardOf(plugin, key) : null;
        executor = new MoveExecutor(actions.get(), settings);
        ai.card = card;
        rotation = built;
        winsNeeded = Math.max(1, bestOf) / 2 + 1;
        round = 0;
        humanWins = 0;
        aiWins = 0;

        humanId = player.getUniqueId();
        profile = new HumanProfile(new File(plugin.getDataFolder(), "ders-defteri"), player.getName().toLowerCase(Locale.ROOT));
        PlayerInventory inv = player.getInventory();
        savedContents = inv.getContents().clone();
        savedArmor = inv.getArmorContents().clone();
        savedOffhand = inv.getItemInOffHand().clone();
        savedMode = player.getGameMode();
        savedLocation = player.getLocation();

        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        startRound();
        return null;
    }

    /** @return false when nothing was running */
    public boolean stop(String reason) {
        if (phase == Phase.IDLE) {
            return false;
        }
        Player human = human();
        if (human != null) {
            human.sendMessage(Component.text("Kapışma bitti (" + reason + ").", NamedTextColor.GRAY));
        }
        cleanup();
        return true;
    }

    private @Nullable Player human() {
        return humanId == null ? null : Bukkit.getPlayer(humanId);
    }

    private static ServerPlayer handle(Player p) {
        return ((CraftPlayer) p).getHandle();
    }

    // ================================================================ rounds

    private void startRound() {
        Player human = human();
        Fighter ai = this.ai;
        if (human == null || ai == null) {
            cleanup();
            return;
        }
        LlmArena arena = rotation.get(round % rotation.size());
        this.arena = arena;
        round++;
        token++;
        fightTicks = 0;
        roundLog = new RoundLog();
        executor.customArena = !arena.name().equals(ArenaStore.BOX);
        executor.edgeBrake = executor.customArena && matches.edgeSenseOn();

        human.teleport(arena.spawn(0));
        human.setGameMode(GameMode.SURVIVAL);
        LlmMatchManager.giveKit(human, arena, settings);
        FightCard card = ai.card;
        ai.resetForMatch();
        ai.card = card;
        try {
            ai.npc = npcs.spawn(arena.spawn(1), 0, "orta", ai.name());
        } catch (RuntimeException e) {
            plugin.getLogger().severe("yz-mac kapis: NPC çıkarılamadı: " + e.getMessage());
            human.sendMessage(Component.text("NPC çıkarılamadı: " + e.getMessage(), NamedTextColor.RED));
            cleanup();
            return;
        }
        LlmMatchManager.giveKit(ai.npc.bukkit(), arena, settings);
        ai.npc.lockFocus(handle(human));
        phase = Phase.COUNTDOWN;
        phaseTicks = 0;
        updateBar();
        moveWatchers();
    }

    private void tick() {
        Player human = human();
        if (human == null || !human.isOnline()) {
            cleanup();
            return;
        }
        switch (phase) {
            case COUNTDOWN -> {
                if (ai.npc != null) {
                    ai.npc.setMovement(0, 0, false);
                }
                int left = settings.countdownTicks() - phaseTicks;
                if (left > 0 && left % 20 == 0) {
                    title(human, Component.text(left / 20, NamedTextColor.YELLOW),
                            Component.text("Raunt " + round + " · " + arenaLabel()), 0, 18, 2);
                }
                if (++phaseTicks >= settings.countdownTicks()) {
                    phase = Phase.FIGHT;
                    title(human, Component.text("DÖVÜŞ!", NamedTextColor.RED), Component.empty(), 0, 20, 10);
                }
            }
            case FIGHT -> tickFight(human);
            case PAUSE -> {
                if (++phaseTicks >= settings.pauseTicks()) {
                    removeNpc();
                    if (humanWins >= winsNeeded || aiWins >= winsNeeded) {
                        finish(human);
                    } else {
                        startRound();
                    }
                }
            }
            case IDLE -> { }
        }
    }

    private void tickFight(Player human) {
        Fighter ai = this.ai;
        if (ai.npc == null || npcs.get(ai.npc.handle().getUUID()) == null) {
            endRound(null, "npc_kayboldu");
            return;
        }
        poll(human);
        if (profile != null) {
            profile.tick(human, ai.npc.bukkit());
        }
        if (fightTicks % settings.decisionIntervalTicks() == 0) {
            ask(human);
        }
        executor.tick(ai, handle(human));
        sample(human);
        fightTicks++;
        if (fightTicks % 5 == 0) {
            updateBar();
        }
        if (fightTicks >= settings.matchTicks()) {
            double h = LlmMatchManager.effectiveHealth(human);
            double a = LlmMatchManager.effectiveHealth(ai.npc.bukkit());
            endRound(Math.abs(h - a) < 0.01 ? null : h > a, "sure_doldu");
        }
    }

    /** One tick of the round log: shield up, standing still (also at low health), eating, distance. */
    private void sample(Player human) {
        ServerPlayer self = ai.npc.handle();
        RoundLog r = roundLog;
        boolean blocking = self.isBlocking();
        boolean usingMain = self.isUsingItem() && self.getUsedItemHand() == InteractionHand.MAIN_HAND;
        if (blocking) {
            r.aiShieldTicks++;
        }
        if (usingMain && !r.eating) {
            r.aiApples++;
        }
        r.eating = usingMain;
        double health = LlmMatchManager.effectiveHealth(ai.npc.bukkit());
        r.minAiHealth = Math.min(r.minAiHealth, health);
        boolean low = health <= 8.0;
        if (low) {
            r.aiLowTicks++;
        }
        // Standing still: no sideways/forward progress and not busy shielding or chewing.
        if (r.hasLast && !blocking && !usingMain && Math.hypot(self.getX() - r.lastX, self.getZ() - r.lastZ) < 0.02) {
            r.aiIdleTicks++;
            if (low) {
                r.aiIdleLowTicks++;
            }
        }
        r.hasLast = true;
        r.lastX = self.getX();
        r.lastZ = self.getZ();
        double distance = self.distanceTo(handle(human));
        r.distSum += distance;
        if (distance <= 3.6) {
            r.closeTicks++;
        }
    }

    /** One line per round to yz-mac/kapis-log.jsonl. Called once, when the round ends. */
    private void logRound(@Nullable Boolean humanWon, String reason, Player human) {
        RoundLog r = roundLog;
        if (r.written || ai == null) {
            return;
        }
        r.written = true;
        JsonObject line = new JsonObject();
        line.addProperty("zaman", Instant.now().toString());
        line.addProperty("insan", human.getName());
        line.addProperty("model", ai.key);
        line.addProperty("arena", arenaLabel());
        line.addProperty("raunt", round);
        line.addProperty("kazanan", humanWon == null ? "berabere" : humanWon ? "insan" : ai.key);
        line.addProperty("sebep", reason);
        line.addProperty("sure_sn", Math.round(fightTicks / 2.0) / 10.0);
        line.addProperty("ai_vurus", ai.match.hits);
        line.addProperty("ai_kritik", ai.match.crits);
        line.addProperty("ai_hasar", Math.round(ai.match.damage * 10.0) / 10.0);
        line.addProperty("ai_vurus_kalkana_carpti", r.aiHitsBlocked);
        line.addProperty("insan_vurus", r.humanHits);
        line.addProperty("insan_kritik", r.humanCrits);
        line.addProperty("insan_hasar", Math.round(r.humanDamage * 10.0) / 10.0);
        line.addProperty("insan_vurus_kalkana_carpti", r.humanHitsBlocked);
        line.addProperty("ai_kalkan_sn", Math.round(r.aiShieldTicks / 2.0) / 10.0);
        line.addProperty("ai_durma_sn", Math.round(r.aiIdleTicks / 2.0) / 10.0);
        line.addProperty("ai_dusuk_can_sn", Math.round(r.aiLowTicks / 2.0) / 10.0);
        line.addProperty("ai_dusuk_canda_durma_sn", Math.round(r.aiIdleLowTicks / 2.0) / 10.0);
        line.addProperty("ai_elma", r.aiApples);
        line.addProperty("teknik_blokvurus", ai.techBlockHits);
        line.addProperty("teknik_kalkan_bekleme_sn", Math.round(ai.techWaitTicks / 2.0) / 10.0);
        line.addProperty("teknik_yemege_ceza_sn", Math.round(ai.techPunishTicks / 2.0) / 10.0);
        line.addProperty("teknik_bosluk_elma", ai.techGapEats);
        line.addProperty("ai_en_dusuk_can", Math.round(r.minAiHealth * 10.0) / 10.0);
        line.addProperty("insan_son_can", Math.round(LlmMatchManager.effectiveHealth(human) * 10.0) / 10.0);
        line.addProperty("ort_mesafe", fightTicks == 0 ? 0.0 : Math.round(r.distSum / fightTicks * 100.0) / 100.0);
        line.addProperty("yakin_sn", Math.round(r.closeTicks / 2.0) / 10.0);
        line.addProperty("ilk_ai_vurus_sn", r.firstAiHitTick < 0 ? -1.0 : r.firstAiHitTick / 20.0);
        line.addProperty("ilk_insan_vurus_sn", r.firstHumanHitTick < 0 ? -1.0 : r.firstHumanHitTick / 20.0);
        JsonObject moves = new JsonObject();
        ai.match.moves.forEach((m, n) -> moves.addProperty(m.name(), n));
        line.add("hamleler", moves);
        line.addProperty("karar_sayisi", ai.match.calls);
        line.addProperty("kart", ai.card == null ? "yok" : ai.card.summary());
        line.addProperty("kalkan_oynanan", ai.card == null ? 0.0 : ai.card.playedShield());
        try {
            File file = new File(plugin.getDataFolder(), "yz-mac/kapis-log.jsonl");
            file.getParentFile().mkdirs();
            Files.writeString(file.toPath(), line + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            plugin.getLogger().warning("yz-mac kapis: raunt logu yazılamadı: " + e.getMessage());
        }
    }

    private void ask(Player human) {
        Fighter ai = this.ai;
        if (ai.inFlight) {
            ai.match.skipped++;
            return;
        }
        int secondsLeft = (int) Math.ceil(Math.max(0, settings.matchTicks() - fightTicks) / 20.0);
        String state = matches.stateJson(ai.npc.bukkit(), human, ai.lastChosen, secondsLeft, false, arena);
        int t = token;
        long askedAt = fightTicks;
        ai.inFlight = true;
        ai.client.decide(matches.systemPrompt(ai.key, arena), state)
                .thenAccept(decision -> ai.answer.set(new Fighter.Answer(t, askedAt, state, decision)));
    }

    private void poll(Player human) {
        Fighter ai = this.ai;
        Fighter.Answer answer = ai.answer.getAndSet(null);
        if (answer == null || answer.matchToken() != token) {
            return;
        }
        ai.inFlight = false;
        LlmClient.Decision d = answer.decision();
        ai.match.addDecision(d, ai.side);
        if (d.valid()) {
            ai.move = d.move();
            ai.lastChosen = d.move();
            ai.ateThisDecision = false;
            if (!d.thought().isBlank()) {
                // The human sees what the model is thinking, right above the hotbar.
                human.sendActionBar(Component.text(ai.name() + ": ", ai.color).append(Component.text(d.thought(), NamedTextColor.WHITE)));
                if (settings.thoughtsInChat()) {
                    human.sendMessage(Component.text("[" + ai.name() + "] ", ai.color).append(Component.text(d.thought(), NamedTextColor.WHITE)));
                }
            }
        } else if (d.timedOut() && settings.onTimeout().equals("blokla")) {
            ai.move = Move.BLOKLA;
        }
    }

    /** @param humanWon null = draw, the round is replayed */
    private void endRound(@Nullable Boolean humanWon, String reason) {
        Player human = human();
        if (human != null && phase == Phase.FIGHT) {
            logRound(humanWon, reason, human);
        }
        phase = Phase.PAUSE;
        phaseTicks = 0;
        token++;
        if (ai.npc != null && ai.npc.handle().isAlive()) {
            ai.npc.setMovement(0, 0, false);
            ai.npc.handle().releaseUsingItem();
        }
        if (humanWon != null) {
            if (humanWon) {
                humanWins++;
            } else {
                aiWins++;
            }
        }
        if (human == null) {
            return;
        }
        // Back on solid ground, whatever killed the round (the void keeps hurting otherwise).
        human.setFireTicks(0);
        human.setFallDistance(0.0F);
        human.setHealth(20.0);
        human.teleport(arena.spawn(0));
        String how = switch (reason) {
            case "sure_doldu" -> "süre doldu";
            case "cevre" -> "arena öldürdü";
            case "npc_kayboldu" -> "hata, raunt tekrar";
            default -> "nakavt";
        };
        Component main = humanWon == null ? Component.text("BERABERE", NamedTextColor.GRAY)
                : humanWon ? Component.text("Raundu aldın!", NamedTextColor.GOLD)
                : Component.text(ai.name() + " raundu aldı", ai.color);
        title(human, main, Component.text(how + " · " + scoreText(human)), 5, 50, 10);
        human.sendMessage(Component.text("Raunt " + round + ": ", NamedTextColor.GOLD).append(main)
                .append(Component.text(" (" + how + ", " + scoreText(human) + ")", NamedTextColor.GRAY)));
        updateBar();
    }

    private void finish(Player human) {
        boolean won = humanWins > aiWins;
        Component main = won ? Component.text("KAZANDIN!", NamedTextColor.GOLD)
                : Component.text(ai.name() + " KAZANDI", ai.color);
        title(human, main, Component.text(scoreText(human)), 10, 100, 20);
        human.sendMessage(Component.text("Kapışma bitti: ", NamedTextColor.GOLD).append(main)
                .append(Component.text(" (" + scoreText(human) + ")", NamedTextColor.GRAY)));
        cleanup();
    }

    private void cleanup() {
        removeNpc();
        if (task != null) {
            task.cancel();
            task = null;
        }
        Player human = human();
        if (human != null) {
            human.hideBossBar(bar);
            PlayerInventory inv = human.getInventory();
            inv.clear();
            if (savedContents != null) {
                inv.setContents(savedContents);
                inv.setArmorContents(savedArmor);
                inv.setItemInOffHand(savedOffhand);
            }
            human.setHealth(20.0);
            human.setFireTicks(0);
            human.setFallDistance(0.0F);
            if (savedLocation != null) {
                human.teleport(savedLocation);
            }
            if (savedMode != null) {
                human.setGameMode(savedMode);
            }
        }
        savedContents = null;
        savedArmor = null;
        savedOffhand = null;
        savedMode = null;
        savedLocation = null;
        releaseWatchers();
        if (profile != null) {
            profile.save(plugin.getLogger());
            profile = null;
        }
        humanId = null;
        phase = Phase.IDLE;
    }

    private void removeNpc() {
        Fighter ai = this.ai;
        if (ai == null) {
            return;
        }
        CrystalNpc npc = ai.npc;
        if (npc != null && npcs.get(npc.handle().getUUID()) != null) {
            npcs.remove(npc);
        }
        ai.npc = null;
    }

    // ================================================================ HUD

    private String arenaLabel() {
        return arena == null || arena.name().equals(ArenaStore.BOX) ? "kutu" : arena.name();
    }

    private String scoreText(Player human) {
        return human.getName() + " " + humanWins + " - " + aiWins + " " + ai.name();
    }

    private void updateBar() {
        Player human = human();
        if (human == null || ai == null) {
            return;
        }
        int secondsLeft = (int) Math.ceil(Math.max(0, settings.matchTicks() - fightTicks) / 20.0);
        String clock = String.format(Locale.ROOT, "%d:%02d", secondsLeft / 60, secondsLeft % 60);
        double aiHealth = ai.npc == null ? 0.0 : LlmMatchManager.effectiveHealth(ai.npc.bukkit());
        bar.name(Component.text(scoreText(human) + "  ·  Raunt " + round + " · " + arenaLabel() + " · " + clock
                + "  ·  " + ai.name() + " can " + Math.round(aiHealth * 10.0) / 10.0));
        bar.progress(Math.max(0.0F, Math.min(1.0F, (float) (aiHealth / 20.0))));
        bar.color(ai.key.equals("chatgpt") ? BossBar.Color.GREEN : BossBar.Color.BLUE);
        human.showBossBar(bar);
    }

    private static void title(Player p, Component main, Component sub, int fadeIn, int stay, int fadeOut) {
        p.showTitle(Title.title(main, sub, Title.Times.times(
                Duration.ofMillis(fadeIn * 50L), Duration.ofMillis(stay * 50L), Duration.ofMillis(fadeOut * 50L))));
    }

    // ================================================================ events

    private boolean isHuman(Entity e) {
        return humanId != null && e.getUniqueId().equals(humanId);
    }

    private boolean isAi(Entity e) {
        return ai != null && ai.npc != null && ai.npc.handle().getUUID().equals(e.getUniqueId());
    }

    /** No damage outside the fight; a killing blow on the human is cancelled and loses the round. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (phase == Phase.IDLE) {
            return;
        }
        Entity entity = event.getEntity();
        boolean human = isHuman(entity);
        if (!human && !isAi(entity)) {
            return;
        }
        if (phase != Phase.FIGHT) {
            event.setCancelled(true);
            return;
        }
        // Video handicap (llm.yml kapis): invisible on camera, only the hearts move differently.
        if (event instanceof EntityDamageByEntityEvent byEntity) {
            if (human && isAi(byEntity.getDamager())) {
                event.setDamage(event.getDamage() * aiDamageMultiplier);
            } else if (!human && isHuman(byEntity.getDamager())) {
                event.setDamage(event.getDamage() * humanDamageMultiplier);
            }
        }
        if (human && event.getFinalDamage() >= LlmMatchManager.effectiveHealth((Player) entity)) {
            event.setCancelled(true);
            boolean byAi = event instanceof EntityDamageByEntityEvent byEntity && isAi(byEntity.getDamager());
            endRound(false, byAi ? "nakavt" : "cevre");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent event) {
        if (phase != Phase.FIGHT || ai == null) {
            return;
        }
        if (isAi(event.getDamager()) && isHuman(event.getEntity())) {
            ai.match.hits++;
            ai.match.damage += event.getFinalDamage();
            if (event.isCritical()) {
                ai.match.crits++;
            }
            if (event.getFinalDamage() <= 0.0) {
                roundLog.aiHitsBlocked++;
            }
            if (roundLog.firstAiHitTick < 0) {
                roundLog.firstAiHitTick = fightTicks;
            }
        } else if (isHuman(event.getDamager()) && isAi(event.getEntity())) {
            roundLog.humanHits++;
            roundLog.humanDamage += event.getFinalDamage();
            if (event.isCritical()) {
                roundLog.humanCrits++;
            }
            if (event.getFinalDamage() <= 0.0) {
                roundLog.humanHitsBlocked++;
            }
            if (roundLog.firstHumanHitTick < 0) {
                roundLog.firstHumanHitTick = fightTicks;
            }
            if (profile != null) {
                profile.onHit(event.isCritical());
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        Player dead = event.getEntity();
        boolean human = isHuman(dead);
        if (!human && !isAi(dead)) {
            return;
        }
        event.setKeepInventory(true);
        event.getDrops().clear();
        event.setKeepLevel(true);
        event.setDroppedExp(0);
        event.deathMessage(null);
        if (phase != Phase.FIGHT) {
            return;
        }
        if (human) {
            endRound(false, "cevre"); // /kill or something the damage check could not stop
        } else {
            Player killer = dead.getKiller();
            endRound(true, killer != null && isHuman(killer) ? "nakavt" : "cevre");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onRegain(EntityRegainHealthEvent event) {
        if (phase == Phase.IDLE || settings.naturalRegeneration()) {
            return;
        }
        Entity entity = event.getEntity();
        if (!isHuman(entity) && !isAi(entity)) {
            return;
        }
        EntityRegainHealthEvent.RegainReason reason = event.getRegainReason();
        if (reason == EntityRegainHealthEvent.RegainReason.SATIATED || reason == EntityRegainHealthEvent.RegainReason.REGEN) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (isHuman(event.getPlayer())) {
            cleanup();
        }
    }
}
