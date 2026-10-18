package com.crystalai.plugin.llm;

import com.crystalai.plugin.action.NpcActions;
import com.crystalai.plugin.npc.CrystalNpc;
import com.crystalai.plugin.npc.NpcManager;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

/**
 * ChatGPT vs Gemini sword duels: a best-of-N series of timed matches between two NPCs whose
 * moves come from two language models. Settings: plugins/CrystalAI/llm.yml.
 */
public final class LlmMatchManager implements Listener {

    private enum Phase { IDLE, COUNTDOWN, FIGHT, PAUSE }

    private static final double EQUAL_HEALTH = 0.01;
    /** How far the safe-step fields look, in blocks. */
    private static final int SAFE_LOOK = 6;

    private final JavaPlugin plugin;
    private final NpcManager npcs;
    private final Supplier<NpcActions> actions;

    private LlmSettings settings;
    /** llm.yml kenar-zekasi: safe-step fields, their prompt lines and the edge brake on hand-built arenas. */
    private boolean edgeSense = true;
    /** llm.yml ders-defteri: lessons in the prompt during normal series, and the notebook limits. */
    private boolean notebookUse = true;
    /** llm.yml dovus-karti.kullan: a trained fighting card (if any) drives the body in fight moves. */
    private boolean cardUse = true;
    private int maxLessons = 5;
    private int maxLessonWords = 15;
    private final Map<String, Notebook> notebooks = new HashMap<>();
    /** > 0: a training run of exactly this many matches; every model rewrites its notebook after each. */
    private int trainingMatches;
    /** Lesson calls still out; the pause after a training match waits for them. */
    private int pendingLessons;
    private final ArenaStore arenas;
    /** Arenas of the running series, one per match in turn. */
    private List<LlmArena> rotation = List.of();
    private @Nullable LlmArena arena;
    private @Nullable MatchLog log;
    private @Nullable MoveExecutor executor;
    private final List<Fighter> fighters = new ArrayList<>();
    private @Nullable BukkitTask task;
    private final BossBar bar = BossBar.bossBar(Component.empty(), 1.0F, BossBar.Color.WHITE, BossBar.Overlay.PROGRESS);

    private Phase phase = Phase.IDLE;
    private String seriesId = "";
    private int matchNo;
    /** Bumped whenever a match ends, so late API answers for it are dropped. */
    private int matchToken;
    private int phaseTicks;
    private int fightTicks;
    private int fightLimit;
    private boolean suddenDeath;
    private int draws;

    public LlmMatchManager(JavaPlugin plugin, NpcManager npcs, Supplier<NpcActions> actions) {
        this.plugin = plugin;
        this.npcs = npcs;
        this.actions = actions;
        this.settings = loadSettings();
        this.arenas = new ArenaStore(new File(plugin.getDataFolder(), "arenalar.yml"));
    }

    // ================================================================ settings

    private LlmSettings loadSettings() {
        File file = new File(plugin.getDataFolder(), "llm.yml");
        if (!file.exists()) {
            plugin.saveResource("llm.yml", false);
        }
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        edgeSense = config.getBoolean("kenar-zekasi", true);
        notebookUse = config.getBoolean("ders-defteri.kullan", true);
        cardUse = config.getBoolean("dovus-karti.kullan", true);
        maxLessons = Math.max(1, config.getInt("ders-defteri.max-ders", 5));
        maxLessonWords = Math.max(3, config.getInt("ders-defteri.max-kelime", 15));
        return LlmSettings.from(config);
    }

    /** Re-reads llm.yml. Only between series. */
    public String reload() {
        if (phase != Phase.IDLE) {
            return "Seri sürerken yenilenemez, önce /yzmac durdur.";
        }
        settings = loadSettings();
        arenas.load();
        return "llm.yml + arenalar.yml yenilendi (mod: " + (settings.mock() ? "mock" : "gercek") + ", arena: "
                + arenas.names().size() + ").";
    }

    // ================================================================ series control

    /** @return null when started, otherwise why not */
    public @Nullable String start(@Nullable Boolean mockOverride, @Nullable Integer bestOfOverride,
                                  @Nullable String arenaChoice) {
        return start(mockOverride, bestOfOverride, arenaChoice, 0);
    }

    /** A training run: exactly {@code matches} matches, both notebooks rewritten after each one. */
    public @Nullable String startTraining(@Nullable Boolean mockOverride, int matches, @Nullable String arenaChoice) {
        return start(mockOverride, null, arenaChoice, Math.max(1, matches));
    }

    private @Nullable String start(@Nullable Boolean mockOverride, @Nullable Integer bestOfOverride,
                                   @Nullable String arenaChoice, int training) {
        if (phase != Phase.IDLE) {
            return "Zaten bir seri sürüyor (/yzmac durdur).";
        }
        settings = loadSettings();
        arenas.load();
        boolean mock = mockOverride != null ? mockOverride : settings.mock();
        if (!mock) {
            List<String> problems = settings.realModeProblems();
            if (!problems.isEmpty()) {
                return "llm.yml eksik: " + String.join(", ", problems);
            }
        }
        if (bestOfOverride != null) {
            settings = withBestOf(settings, bestOfOverride);
        }
        if (!isSupportedSword(settings.sword())) {
            return "kit.kilic diamond_sword ya da netherite_sword olmalı (NPC vuruşu bu ikisini tanıyor).";
        }
        String arenaError = pickRotation(arenaChoice == null ? ArenaStore.BOX : arenaChoice.toLowerCase(Locale.ROOT));
        if (arenaError != null) {
            return arenaError;
        }

        fighters.clear();
        LlmSettings s = settings;
        fighters.add(new Fighter("chatgpt", s.chatgpt(), mock ? new LlmClient.Mock(s) : new LlmClient.OpenAi(s.chatgpt(), s),
                NamedTextColor.GREEN));
        fighters.add(new Fighter("gemini", s.gemini(), mock ? new LlmClient.Mock(s) : new LlmClient.Gemini(s.gemini(), s),
                NamedTextColor.AQUA));

        executor = new MoveExecutor(actions.get(), s);
        for (Fighter f : fighters) {
            f.card = cardUse ? CardTraining.cardOf(plugin, f.key) : null;
        }
        trainingMatches = training;
        pendingLessons = 0;
        for (Fighter f : fighters) {
            notebook(f.key);
        }
        seriesId = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + (mock ? "-mock" : "")
                + (training > 0 ? "-antrenman" : "");
        try {
            log = new MatchLog(new File(plugin.getDataFolder(), "yz-mac").toPath(), seriesId, plugin.getLogger(),
                    s.decisionIntervalTicks() / 20.0);
        } catch (IOException e) {
            return "Log klasörü açılamadı: " + e.getMessage();
        }
        clearBoard();
        JsonObject start = new JsonObject();
        start.addProperty("mod", mock ? "mock" : "gercek");
        start.addProperty("en_iyi", s.bestOf());
        start.addProperty("chatgpt_model", s.chatgpt().model());
        start.addProperty("gemini_model", s.gemini().model());
        start.addProperty("sistem_promptu", s.systemPrompt());
        start.addProperty("arenalar", String.join(",", rotation.stream().map(LlmArena::name).toList()));
        start.addProperty("antrenman_maci", training);
        start.addProperty("ders_defteri", lessonsInPrompt());
        for (Fighter f : fighters) {
            start.addProperty(f.key + "_ders_sayisi", notebook(f.key).lessons().size());
        }
        log.event("seri_basi", start);

        matchNo = 0;
        draws = 0;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        startMatch();
        return null;
    }

    /**
     * kutu | an arena name | sirayla (every saved arena in order) | rastgele (every saved arena, shuffled).
     * Every arena is checked now, so a broken one fails the start, not match 4.
     * @return null when the rotation is set, otherwise why not
     */
    private @Nullable String pickRotation(String choice) {
        List<LlmArena> built = new ArrayList<>();
        String error = buildArenas(choice, built);
        if (error == null) {
            rotation = built;
        }
        return error;
    }

    /** Builds the arenas {@code choice} names into {@code out}. @return null on success, otherwise why not */
    @Nullable String buildArenas(String choice, List<LlmArena> out) {
        List<String> names;
        if (choice.equals("sirayla") || choice.equals("rastgele")) {
            names = new ArrayList<>(arenas.names());
            if (names.isEmpty()) {
                return "Kayıtlı arena yok (/yzmac arena nokta <isim> 1).";
            }
            if (choice.equals("rastgele")) {
                Collections.shuffle(names);
            }
        } else {
            names = List.of(choice);
        }
        for (String name : names) {
            if (name.equals(ArenaStore.BOX)) {
                out.add(LlmArena.box(settings));
                continue;
            }
            ArenaStore.Def def = arenas.get(name);
            if (def == null) {
                return "Arena yok: " + name + " (/yzmac arena liste)";
            }
            try {
                out.add(LlmArena.custom(def, settings));
            } catch (IllegalStateException e) {
                return e.getMessage();
            }
        }
        return null;
    }

    // ================================================================ arenas (/yzmac arena)

    /** Saves {@code location} as point 1, 2 or the camera of arena {@code name}, creating it if needed. */
    public String arenaPoint(String name, String which, Location location) {
        ArenaStore.Def def = arenas.getOrCreate(name);
        ArenaStore.Point point = ArenaStore.Point.of(location);
        switch (which) {
            case "1" -> def.spawn1 = point;
            case "2" -> def.spawn2 = point;
            case "kamera" -> def.camera = point;
            default -> {
                return "Nokta 1, 2 ya da kamera olmalı.";
            }
        }
        String saved = saveArenas();
        if (saved != null) {
            return saved;
        }
        String problem = def.problem();
        return "§a" + def.name + ": " + which + " kaydedildi." + (problem == null ? " Hazır." : " §7Eksik: " + problem);
    }

    public String arenaNote(String name, String note) {
        ArenaStore.Def def = arenas.get(name);
        if (def == null) {
            return "Arena yok: " + name;
        }
        def.note = note;
        String saved = saveArenas();
        return saved != null ? saved : "§a" + def.name + " notu: §f" + (note.isEmpty() ? "(boş)" : note);
    }

    public String arenaRemove(String name) {
        if (!arenas.remove(name)) {
            return "Arena yok: " + name;
        }
        String saved = saveArenas();
        return saved != null ? saved : "§e" + name + " silindi.";
    }

    public List<String> arenaList() {
        List<String> lines = new ArrayList<>();
        lines.add(ArenaStore.BOX + " §8(hazır bedrock kutu)");
        for (String name : arenas.names()) {
            ArenaStore.Def def = arenas.get(name);
            def.loadWorlds(world -> LlmArena.loadWorld(world, settings));
            String problem = def.problem();
            lines.add(name + (problem == null ? " §a✔" : " §c" + problem)
                    + (def.note.isEmpty() ? "" : " §8· " + def.note));
        }
        return lines;
    }

    public List<String> arenaNames() {
        return arenas.names();
    }

    /** Point 1 of a saved arena, for /yzmac arena git. */
    public @Nullable Location arenaLocation(String name) {
        ArenaStore.Def def = arenas.get(name);
        if (def == null) {
            return null;
        }
        ArenaStore.Point p = def.spawn1 != null ? def.spawn1 : def.camera;
        if (p == null) {
            return null;
        }
        LlmArena.loadWorld(p.world(), settings);
        return p.resolve();
    }

    private @Nullable String saveArenas() {
        try {
            arenas.save();
            return null;
        } catch (IOException e) {
            return "arenalar.yml yazılamadı: " + e.getMessage();
        }
    }

    private static boolean isSupportedSword(String sword) {
        String s = sword.toLowerCase(Locale.ROOT);
        return s.equals("diamond_sword") || s.equals("netherite_sword");
    }

    private static LlmSettings withBestOf(LlmSettings s, int bestOf) {
        return new LlmSettings(s.mock(), s.chatgpt(), s.gemini(), s.systemPrompt(), s.decisionIntervalTicks(),
                s.timeoutMs(), s.onTimeout(), s.maxTokens(), s.maxThoughtWords(), Math.max(1, bestOf), s.matchTicks(),
                s.suddenDeathTicks(), s.countdownTicks(), s.pauseTicks(), s.naturalRegeneration(), s.world(), s.floorY(),
                s.floorRadius(), s.wallHeight(), s.spawnDistance(), s.armorMaterial(), s.sword(), s.shield(),
                s.goldenApples(), s.retreatDistance(), s.approachStopDistance(), s.strafeSwitchTicks(),
                s.thoughtsInChat(), s.mockMinLatencyMs(), s.mockMaxLatencyMs(), s.mockInvalidChance());
    }

    /** @return false when nothing was running */
    public boolean stop(String reason) {
        if (phase == Phase.IDLE) {
            return false;
        }
        matchToken++;
        removeNpcs();
        for (Fighter f : fighters) {
            f.total.add(f.match);
            f.match = new Fighter.Stats();
        }
        if (log != null) {
            JsonObject data = new JsonObject();
            data.addProperty("sebep", reason);
            log.event("seri_durduruldu", data);
        }
        clearBoard();
        endSeries();
        return true;
    }

    private void endSeries() {
        writeSummary();
        if (log != null) {
            log.close();
            log = null;
        }
        if (task != null) {
            task.cancel();
            task = null;
        }
        viewers().forEach(p -> p.hideBossBar(bar));
        phase = Phase.IDLE;
    }

    public List<String> status() {
        List<String> lines = new ArrayList<>();
        if (phase == Phase.IDLE) {
            lines.add("Seri yok. Mod: " + (settings.mock() ? "mock" : "gercek") + ", en iyi " + settings.bestOf());
            return lines;
        }
        lines.add("Seri " + seriesId + " · Maç " + matchNo + " · " + phase + " · " + scoreText());
        for (Fighter f : fighters) {
            Fighter.Stats st = new Fighter.Stats();
            st.add(f.total);
            st.add(f.match);
            lines.add(String.format(Locale.ROOT, "%s (%s): hamle %s, çağrı %d, geçersiz %d, zaman aşımı %d, hata %d, ort %d ms, $%.5f",
                    f.name(), f.side.model(), f.move == null ? "-" : f.move.label(), st.calls, st.invalid,
                    st.timeouts, st.errors, st.avgLatency(), st.cost));
        }
        if (log != null) {
            lines.add("Log: " + log.seriesDir());
        }
        return lines;
    }

    /**
     * Camera of {@code name}, or of the current arena when null (the box when nothing has run yet).
     * @throws IllegalStateException when the named arena cannot be used
     */
    public Location camera(@Nullable String name) {
        if (name != null) {
            ArenaStore.Def def = arenas.get(name);
            if (name.equalsIgnoreCase(ArenaStore.BOX)) {
                return LlmArena.box(settings).camera();
            }
            if (def == null) {
                throw new IllegalStateException("Arena yok: " + name);
            }
            return LlmArena.custom(def, settings).camera();
        }
        if (arena == null) {
            arena = LlmArena.box(settings);
        }
        return arena.camera();
    }

    // ================================================================ match flow

    private void startMatch() {
        if (rotation.isEmpty()) {
            return;
        }
        // Draws are replayed under a new match number, so they move on to the next arena too.
        LlmArena arena = rotation.get(matchNo % rotation.size());
        this.arena = arena;
        executor.customArena = !arena.name().equals(ArenaStore.BOX);
        executor.edgeBrake = executor.customArena && edgeSense;
        matchNo++;
        matchToken++;
        suddenDeath = false;
        fightTicks = 0;
        fightLimit = settings.matchTicks();
        // Sides swap every match: no left/right advantage.
        boolean swap = matchNo % 2 == 0;
        try {
            for (int i = 0; i < fighters.size(); i++) {
                Fighter f = fighters.get(i);
                f.resetForMatch();
                int slot = swap ? 1 - i : i;
                f.npc = npcs.spawn(arena.spawn(slot), 0, "orta", f.name());
                prepare(f);
            }
        } catch (RuntimeException e) {
            plugin.getLogger().severe("yz-mac: NPC çıkarılamadı: " + e.getMessage());
            stop("npc_spawn_hatasi: " + e.getMessage());
            return;
        }
        fighters.get(0).npc.lockFocus(fighters.get(1).npc.handle());
        fighters.get(1).npc.lockFocus(fighters.get(0).npc.handle());

        JsonObject data = new JsonObject();
        data.addProperty("mac", matchNo);
        data.addProperty("chatgpt_bati", !swap);
        data.addProperty("arena", arena.name());
        log.event("mac_basi", data);
        log.clearThoughts(fighters);
        if (rotation.size() > 1) {
            // Watchers (/yzmac izle) follow the series to the next arena.
            viewers().stream().filter(p -> p.getGameMode() == GameMode.SPECTATOR).forEach(p -> p.teleport(arena.camera()));
        }
        phase = Phase.COUNTDOWN;
        phaseTicks = 0;
        updateHud();
    }

    private void prepare(Fighter f) {
        giveKit(f.npc.bukkit(), arena, settings);
        Team team = Bukkit.getScoreboardManager().getMainScoreboard().getEntryTeam(f.name());
        if (team != null) {
            team.prefix(Component.empty());
            team.color(f.color);
        }
    }

    /** Empty inventory, the arena's kit, full health, no effects. */
    static void giveKit(Player p, LlmArena arena, LlmSettings settings) {
        PlayerInventory inv = p.getInventory();
        inv.clear();
        String armor = arena.armor().toUpperCase(Locale.ROOT);
        if (!armor.equals("NONE")) {
            inv.setHelmet(item(armor + "_HELMET", 1));
            inv.setChestplate(item(armor + "_CHESTPLATE", 1));
            inv.setLeggings(item(armor + "_LEGGINGS", 1));
            inv.setBoots(item(armor + "_BOOTS", 1));
        }
        inv.setItem(0, item(settings.sword(), 1));
        if (arena.goldenApples() > 0) {
            inv.setItem(1, item("GOLDEN_APPLE", arena.goldenApples()));
        }
        if (arena.shield()) {
            inv.setItemInOffHand(new ItemStack(Material.SHIELD));
        }
        inv.setHeldItemSlot(0);
        for (PotionEffect effect : p.getActivePotionEffects()) {
            p.removePotionEffect(effect.getType());
        }
        p.setHealth(20.0);
        p.setAbsorptionAmount(0.0);
        p.setFoodLevel(20);
        p.setSaturation(5.0F);
        p.setFireTicks(0);
        p.setFallDistance(0.0F);
    }

    private static ItemStack item(String material, int amount) {
        Material m = Material.matchMaterial(material);
        if (m == null) {
            throw new IllegalArgumentException("llm.yml: bilinmeyen eşya " + material);
        }
        return new ItemStack(m, amount);
    }

    private void tick() {
        switch (phase) {
            case COUNTDOWN -> tickCountdown();
            case FIGHT -> tickFight();
            case PAUSE -> {
                ++phaseTicks;
                // Up to 20 s more while a model is still writing its lessons.
                boolean lessonsDone = pendingLessons == 0 || phaseTicks >= settings.pauseTicks() + 400;
                if (phaseTicks >= settings.pauseTicks() && lessonsDone) {
                    removeNpcs();
                    if (trainingMatches > 0) {
                        if (matchNo >= trainingMatches) {
                            finishTraining();
                        } else {
                            startMatch();
                        }
                        return;
                    }
                    Fighter champion = champion();
                    if (champion != null) {
                        finishSeries(champion);
                    } else {
                        startMatch();
                    }
                }
            }
            case IDLE -> { }
        }
    }

    private void tickCountdown() {
        for (Fighter f : fighters) {
            if (f.npc != null) {
                f.npc.setMovement(0, 0, false);
            }
        }
        int left = settings.countdownTicks() - phaseTicks;
        if (left > 0 && left % 20 == 0) {
            title(Component.text(left / 20, NamedTextColor.YELLOW), Component.text("Maç " + matchNo), 0, 18, 2);
        }
        if (++phaseTicks >= settings.countdownTicks()) {
            phase = Phase.FIGHT;
            title(Component.text("DÖVÜŞ!", NamedTextColor.RED), Component.empty(), 0, 20, 10);
        }
    }

    private void tickFight() {
        Fighter a = fighters.get(0);
        Fighter b = fighters.get(1);
        if (a.npc == null || b.npc == null || npcs.get(a.npc.handle().getUUID()) == null
                || npcs.get(b.npc.handle().getUUID()) == null) {
            plugin.getLogger().warning("yz-mac: bir NPC kayboldu, maç tekrarlanacak");
            endMatch(null, "npc_kayboldu");
            return;
        }
        poll(a);
        poll(b);
        if (fightTicks % settings.decisionIntervalTicks() == 0) {
            // Same tick for both: equal decision frequency.
            ask(a, b);
            ask(b, a);
        }
        executor.tick(a, b);
        executor.tick(b, a);
        fightTicks++;
        if (fightTicks % 5 == 0) {
            updateHud();
        }
        if (phase == Phase.FIGHT && fightTicks >= fightLimit) {
            onTimeUp(a, b);
        }
    }

    private void ask(Fighter self, Fighter opponent) {
        if (self.inFlight) {
            self.match.skipped++;
            return;
        }
        String state = stateJson(self, opponent);
        int token = matchToken;
        long askedAt = fightTicks;
        self.inFlight = true;
        self.client.decide(systemPrompt(self), state)
                .thenAccept(decision -> self.answer.set(new Fighter.Answer(token, askedAt, state, decision)));
    }

    /**
     * llm.yml's prompt, the same for both models, plus each model's own notebook, plus on a hand-built arena what its note says and how to
     * read the safe-step fields. OpenAI's JSON mode refuses a request whose messages never say "json"
     * (http 400), so the word is added when the prompt lacks it.
     */
    private String systemPrompt(Fighter self) {
        return systemPrompt(self.key, arena);
    }

    String systemPrompt(String key, @Nullable LlmArena arena) {
        StringBuilder prompt = new StringBuilder(settings.systemPrompt().stripTrailing());
        if (!prompt.toString().toLowerCase(Locale.ROOT).contains("json")) {
            prompt.append("\nCevabın tek bir JSON nesnesi olsun.");
        }
        if (arena != null && !arena.name().equals(ArenaStore.BOX)) {
            if (!arena.note().isBlank()) {
                prompt.append("\nArena: ").append(arena.note().strip());
            }
            if (edgeSense) {
                prompt.append("\ngeri_guvenli_blok, sag_guvenli_blok, sol_guvenli_blok: o yönde düşmeden ve yanmadan kaç blok")
                        .append(" gidebileceğin. 0-1 ise o yön uçurum ya da lav: kac ve yan_adim seni oraya götürür, seçme.")
                        .append(" Rakibinin arkası boşsa ona vurup onu kenardan at.")
                        .append(" rakip_geri_guvenli_blok rakibinin arkasında kaç güvenli blok kaldığı.");
            }
        }
        if (lessonsInPrompt()) {
            prompt.append(notebook(key).promptBlock());
        }
        return prompt.toString();
    }

    private void poll(Fighter f) {
        Fighter.Answer answer = f.answer.getAndSet(null);
        if (answer == null || answer.matchToken() != matchToken) {
            return;
        }
        f.inFlight = false;
        LlmClient.Decision d = answer.decision();
        f.match.addDecision(d, f.side);
        String applied;
        if (d.valid()) {
            f.trail.add(trailLine(answer.stateJson(), d));
            f.move = d.move();
            f.lastChosen = d.move();
            f.ateThisDecision = false;
            applied = d.move().label();
        } else if (d.timedOut() && settings.onTimeout().equals("blokla")) {
            f.move = Move.BLOKLA;
            applied = "blokla (zaman_asimi)";
        } else {
            applied = (f.move == null ? "yok" : f.move.label()) + " (onceki)";
        }
        log.decision(matchNo, f, answer, applied, Fighter.Stats.cost(d, f.side));
        if (d.valid() && !d.thought().isBlank()) {
            log.thought(matchNo, f, d.thought());
            if (settings.thoughtsInChat()) {
                Component line = Component.text("[" + f.name() + "] ", f.color)
                        .append(Component.text(d.thought(), NamedTextColor.WHITE));
                viewers().forEach(p -> p.sendMessage(line));
            }
        }
    }

    /** The short state the plan specifies (section 4.3), from {@code self}'s point of view. */
    private String stateJson(Fighter self, Fighter opponent) {
        return stateJson(self.npc.bukkit(), opponent.npc.bukkit(), self.lastChosen,
                (int) Math.ceil(Math.max(0, fightLimit - fightTicks) / 20.0), suddenDeath, arena);
    }

    /** {@code me} and {@code them} are both server players: an NPC, or a human in a duel. */
    String stateJson(Player me, Player them, @Nullable Move lastChosen, int secondsLeft, boolean sudden,
                     @Nullable LlmArena arena) {
        JsonObject json = new JsonObject();
        json.addProperty("benim_can", round1(effectiveHealth(me)));
        json.addProperty("rakip_can", round1(effectiveHealth(them)));
        json.addProperty("mesafe", round1(me.getLocation().distance(them.getLocation())));
        json.addProperty("rakip_blokluyor", them.isBlocking());
        json.addProperty("benim_yemek_altin_elma", count(me.getInventory(), Material.GOLDEN_APPLE));
        json.addProperty("son_hamle", lastChosen == null ? "yok" : lastChosen.label());
        json.addProperty("kalan_sure", secondsLeft);
        if (edgeSense && arena != null && !arena.name().equals(ArenaStore.BOX)) {
            ServerPlayer a = ((CraftPlayer) me).getHandle();
            ServerPlayer b = ((CraftPlayer) them).getHandle();
            json.addProperty("geri_guvenli_blok", MoveExecutor.safeSteps(a, b, MoveExecutor.AWAY, SAFE_LOOK));
            json.addProperty("sag_guvenli_blok", MoveExecutor.safeSteps(a, b, MoveExecutor.RIGHT, SAFE_LOOK));
            json.addProperty("sol_guvenli_blok", MoveExecutor.safeSteps(a, b, MoveExecutor.LEFT, SAFE_LOOK));
            json.addProperty("rakip_geri_guvenli_blok", MoveExecutor.safeSteps(b, a, MoveExecutor.AWAY, SAFE_LOOK));
        }
        if (sudden) {
            json.addProperty("ani_olum", true);
        }
        return json.toString();
    }

    private static int count(PlayerInventory inv, Material material) {
        int n = 0;
        for (ItemStack stack : inv.getContents()) {
            if (stack != null && stack.getType() == material) {
                n += stack.getAmount();
            }
        }
        return n;
    }

    /** Hearts the viewer sees: health plus golden-apple absorption. */
    static double effectiveHealth(Player p) {
        return p.isDead() ? 0.0 : p.getHealth() + p.getAbsorptionAmount();
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private void onTimeUp(Fighter a, Fighter b) {
        double ha = effectiveHealth(a.npc.bukkit());
        double hb = effectiveHealth(b.npc.bukkit());
        if (Math.abs(ha - hb) >= EQUAL_HEALTH) {
            endMatch(ha > hb ? a : b, "sure_doldu");
            return;
        }
        if (suddenDeath) {
            endMatch(null, "berabere");
            return;
        }
        // Sudden death: one-hit health, a fresh short clock.
        suddenDeath = true;
        fightTicks = 0;
        fightLimit = settings.suddenDeathTicks();
        for (Fighter f : fighters) {
            Player p = f.npc.bukkit();
            for (PotionEffect effect : p.getActivePotionEffects()) {
                p.removePotionEffect(effect.getType());
            }
            p.setAbsorptionAmount(0.0);
            p.setHealth(1.0);
        }
        JsonObject data = new JsonObject();
        data.addProperty("mac", matchNo);
        log.event("ani_olum", data);
        title(Component.text("ANİ ÖLÜM", NamedTextColor.DARK_RED), Component.text("tek vuruş kazanır"), 0, 40, 10);
    }

    /** @param winner null = draw, the match is replayed and not scored */
    private void endMatch(@Nullable Fighter winner, String reason) {
        phase = Phase.PAUSE;
        phaseTicks = 0;
        matchToken++;
        for (Fighter f : fighters) {
            if (f.npc != null && f.npc.handle().isAlive()) {
                f.npc.setMovement(0, 0, false);
                f.npc.handle().releaseUsingItem();
            }
        }
        double left = winner == null || winner.npc == null ? 0.0 : effectiveHealth(winner.npc.bukkit());
        if (winner != null) {
            winner.wins++;
        } else {
            draws++;
        }
        if (trainingMatches > 0) {
            requestLessons(winner, reason);
        }
        JsonObject data = new JsonObject();
        data.addProperty("mac", matchNo);
        data.addProperty("kazanan", winner == null ? null : winner.key);
        data.addProperty("sebep", reason);
        data.addProperty("kalan_can", round1(left));
        data.addProperty("sure_sn", round1(fightTicks / 20.0));
        data.addProperty("ani_olum", suddenDeath);
        for (Fighter f : fighters) {
            data.addProperty(f.key + "_vurus", f.match.hits);
            data.addProperty(f.key + "_hasar", round1(f.match.damage));
            f.total.add(f.match);
            f.match = new Fighter.Stats();
        }
        log.event("mac_sonu", data);
        writeSummary();
        updateHud();

        String how = switch (reason) {
            case "sure_doldu" -> "süre doldu";
            case "berabere" -> "berabere, maç tekrar";
            case "npc_kayboldu" -> "hata, maç tekrar";
            case "cevre" -> "arena öldürdü";
            default -> "nakavt";
        };
        Component main = winner == null ? Component.text("BERABERE", NamedTextColor.GRAY)
                : Component.text(winner.name() + " kazandı", winner.color);
        String sub = winner == null ? how : how + " · kalan can " + round1(left) + " · " + scoreText();
        title(main, Component.text(sub), 5, 60, 15);
        viewers().forEach(p -> p.sendMessage(Component.text("Maç " + matchNo + ": ", NamedTextColor.GOLD).append(main)
                .append(Component.text(" (" + sub + ")", NamedTextColor.GRAY))));
    }

    // ================================================================ for HumanDuel

    boolean busy() {
        return phase != Phase.IDLE;
    }

    /** Fresh llm.yml and arenalar.yml, as a series start reads them. */
    LlmSettings reloadForDuel() {
        settings = loadSettings();
        arenas.load();
        return settings;
    }

    boolean edgeSenseOn() {
        return edgeSense;
    }

    boolean cardsOn() {
        return cardUse;
    }

    // ================================================================ lesson notebook

    private boolean lessonsInPrompt() {
        return trainingMatches > 0 || notebookUse;
    }

    private Notebook notebook(String key) {
        return notebooks.computeIfAbsent(key,
                k -> new Notebook(new File(plugin.getDataFolder(), "ders-defteri").toPath(), k));
    }

    private static String trailLine(String stateJson, LlmClient.Decision d) {
        try {
            JsonObject st = JsonParser.parseString(stateJson).getAsJsonObject();
            String edge = st.has("geri_guvenli_blok") ? ", arkam " + st.get("geri_guvenli_blok").getAsInt() + " blok" : "";
            return "can " + st.get("benim_can").getAsDouble() + "/" + st.get("rakip_can").getAsDouble()
                    + ", mesafe " + st.get("mesafe").getAsDouble() + edge + " -> " + d.move().label();
        } catch (RuntimeException e) {
            return "-> " + d.move().label();
        }
    }

    /** Each model gets its own record of the match just played and rewrites its notebook. Same prompt for both. */
    private void requestLessons(@Nullable Fighter winner, String reason) {
        String coach = Notebook.coachPrompt(maxLessons, maxLessonWords);
        LlmArena arena = this.arena;
        int no = matchNo;
        for (Fighter f : fighters) {
            Fighter other = fighters.get(0) == f ? fighters.get(1) : fighters.get(0);
            Notebook book = notebook(f.key);
            JsonObject record = new JsonObject();
            record.addProperty("arena", arena == null ? "" : arena.name());
            record.addProperty("sonuc", winner == null ? "berabere" : winner == f ? "kazandin" : "kaybettin");
            record.addProperty("nasil", reason.equals("cevre") ? (winner == f ? "rakip arenada öldü (lav/düşme)"
                    : "arenada öldün (lav/düşme)") : reason);
            record.addProperty("sure_sn", round1(fightTicks / 20.0));
            record.addProperty("senin_vurus", f.match.hits);
            record.addProperty("senin_kritik", f.match.crits);
            record.addProperty("verdigin_hasar", round1(f.match.damage));
            record.addProperty("aldigin_hasar", round1(other.match.damage));
            record.addProperty("rakip_vurus", other.match.hits);
            record.addProperty("rakip_kritik", other.match.crits);
            record.add("senin_hamle_sayilari", moveCounts(f.match));
            record.add("rakip_hamle_sayilari", moveCounts(other.match));
            JsonArray trail = new JsonArray();
            f.trail.subList(Math.max(0, f.trail.size() - 25), f.trail.size()).forEach(trail::add);
            record.add("senin_kararlarin", trail);
            JsonArray current = new JsonArray();
            book.lessons().forEach(current::add);
            record.add("mevcut_derslerin", current);

            pendingLessons++;
            f.client.ask(coach, record.toString(), 400, 20000).thenAccept(raw -> Bukkit.getScheduler().runTask(plugin, () -> {
                pendingLessons = Math.max(0, pendingLessons - 1);
                boolean ok = book.apply(raw, maxLessons, maxLessonWords);
                if (!ok) {
                    book.countFailedMatch();
                }
                try {
                    book.save();
                } catch (IOException e) {
                    plugin.getLogger().warning("yz-mac: ders defteri yazılamadı: " + e.getMessage());
                }
                if (log != null) {
                    JsonObject data = new JsonObject();
                    data.addProperty("mac", no);
                    data.addProperty("taraf", f.key);
                    data.addProperty("ham_cevap", raw);
                    data.addProperty("guncellendi", ok);
                    log.event("ders", data);
                }
                announceNotebook(f, book, ok);
            }));
        }
    }

    private void announceNotebook(Fighter f, Notebook book, boolean updated) {
        Component head = Component.text("[" + f.name() + " ders defteri · " + book.trainingMatches() + ". antrenman] ", f.color);
        if (!updated) {
            viewers().forEach(p -> p.sendMessage(head.append(Component.text("ders yazamadı, eski defter duruyor",
                    NamedTextColor.GRAY))));
            return;
        }
        viewers().forEach(p -> p.sendMessage(head));
        for (String lesson : book.lessons()) {
            Component line = Component.text("  • " + lesson, NamedTextColor.WHITE);
            viewers().forEach(p -> p.sendMessage(line));
        }
    }

    private static JsonObject moveCounts(Fighter.Stats stats) {
        JsonObject moves = new JsonObject();
        for (Move m : Move.values()) {
            moves.addProperty(m.label(), stats.moves.getOrDefault(m, 0));
        }
        return moves;
    }

    private void finishTraining() {
        JsonObject data = new JsonObject();
        data.addProperty("skor", scoreText());
        log.event("antrenman_sonu", data);
        title(Component.text("ANTRENMAN BİTTİ", NamedTextColor.GOLD), Component.text(scoreText()), 10, 100, 20);
        endSeries();
    }

    /** /yzmac defter: both notebooks. */
    public List<String> notebookLines() {
        List<String> lines = new ArrayList<>();
        for (String key : List.of("chatgpt", "gemini")) {
            Notebook book = notebook(key);
            String name = key.equals("chatgpt") ? settings.chatgpt().name() : settings.gemini().name();
            lines.add("§6" + name + " §7(" + book.trainingMatches() + " antrenman maçı, " + book.lessons().size() + " ders)");
            book.lessons().forEach(l -> lines.add("§f  • " + l));
        }
        lines.add("§8Serilerde kullanım: " + (notebookUse ? "açık" : "kapalı") + " (llm.yml ders-defteri.kullan)");
        return lines;
    }

    /** /yzmac defter sifirla: both notebooks back to empty, so both start equal. */
    public String notebookReset() {
        if (phase != Phase.IDLE) {
            return "Seri sürerken sıfırlanamaz.";
        }
        try {
            notebook("chatgpt").reset();
            notebook("gemini").reset();
            return "İki ders defteri de sıfırlandı.";
        } catch (IOException e) {
            return "Defter yazılamadı: " + e.getMessage();
        }
    }

    private @Nullable Fighter champion() {
        for (Fighter f : fighters) {
            if (f.wins >= settings.winsNeeded()) {
                return f;
            }
        }
        return null;
    }

    private void finishSeries(Fighter champion) {
        JsonObject data = new JsonObject();
        data.addProperty("sampiyon", champion.key);
        data.addProperty("skor", scoreText());
        log.event("seri_sonu", data);
        title(Component.text("ŞAMPİYON: " + champion.name(), champion.color), Component.text(scoreText()), 10, 100, 20);
        endSeries();
    }

    private void removeNpcs() {
        for (Fighter f : fighters) {
            CrystalNpc npc = f.npc;
            if (npc != null && npcs.get(npc.handle().getUUID()) != null) {
                npcs.remove(npc);
            }
            f.npc = null;
        }
    }

    // ================================================================ events

    private @Nullable Fighter fighterOf(UUID uuid) {
        for (Fighter f : fighters) {
            if (f.npc != null && f.npc.handle().getUUID().equals(uuid)) {
                return f;
            }
        }
        return null;
    }

    /** No damage outside the fight (countdown, pause). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (phase != Phase.IDLE && phase != Phase.FIGHT && fighterOf(event.getEntity().getUniqueId()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent event) {
        if (phase != Phase.FIGHT) {
            return;
        }
        Fighter attacker = fighterOf(event.getDamager().getUniqueId());
        Fighter victim = fighterOf(event.getEntity().getUniqueId());
        if (attacker == null || victim == null || attacker == victim) {
            return;
        }
        if (event.getFinalDamage() <= EQUAL_HEALTH && victim.npc.bukkit().isBlocking()) {
            victim.match.blocked++;
            return;
        }
        attacker.match.hits++;
        attacker.match.damage += event.getFinalDamage();
        if (event.isCritical()) {
            attacker.match.crits++;
        }
    }

    /** Hand-built arenas keep their world's game rules, so natural regeneration is stopped here per fighter. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onRegain(EntityRegainHealthEvent event) {
        if (settings.naturalRegeneration() || fighterOf(event.getEntity().getUniqueId()) == null) {
            return;
        }
        EntityRegainHealthEvent.RegainReason reason = event.getRegainReason();
        if (reason == EntityRegainHealthEvent.RegainReason.SATIATED || reason == EntityRegainHealthEvent.RegainReason.REGEN) {
            event.setCancelled(true);
        }
    }

    /** No kit spilled on a hand-built arena, no death message; the match decides what a death means. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onFighterDeath(PlayerDeathEvent event) {
        if (fighterOf(event.getEntity().getUniqueId()) == null) {
            return;
        }
        event.setKeepInventory(true);
        event.getDrops().clear();
        event.setKeepLevel(true);
        event.setDroppedExp(0);
        event.deathMessage(null);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        if (phase != Phase.FIGHT) {
            return;
        }
        Fighter loser = fighterOf(event.getEntity().getUniqueId());
        if (loser == null) {
            return;
        }
        // No killer: lava, a fall, the void... the arena did it.
        String reason = event.getEntity().getKiller() == null ? "cevre" : "nakavt";
        endMatch(fighters.get(0) == loser ? fighters.get(1) : fighters.get(0), reason);
    }

    // ================================================================ HUD and files

    private String scoreText() {
        if (fighters.size() < 2) {
            return "";
        }
        Fighter a = fighters.get(0);
        Fighter b = fighters.get(1);
        return a.name() + " " + a.wins + " - " + b.wins + " " + b.name();
    }

    private void updateHud() {
        int secondsLeft = (int) Math.ceil(Math.max(0, fightLimit - fightTicks) / 20.0);
        String clock = String.format(Locale.ROOT, "%d:%02d", secondsLeft / 60, secondsLeft % 60);
        String arenaName = arena == null || arena.name().equals(ArenaStore.BOX) ? "" : " · " + arena.name();
        String status = "Maç " + matchNo + arenaName + (suddenDeath ? " · ANİ ÖLÜM" : "") + " · " + clock;
        bar.name(Component.text(scoreText() + "  ·  " + status));
        bar.progress(Math.max(0.0F, Math.min(1.0F, (fightLimit - fightTicks) / (float) fightLimit)));
        bar.color(suddenDeath ? BossBar.Color.RED : BossBar.Color.WHITE);
        viewers().forEach(p -> p.showBossBar(bar));
        updateBoard();
        if (log != null) {
            log.overlay(scoreText(), status, fighters);
        }
    }

    private static final String BOARD = "yzmac_skor";

    /** Sidebar with each fighter's series wins. Lives on the main scoreboard, so name-tag teams keep working. */
    private void updateBoard() {
        if (fighters.size() < 2) {
            return;
        }
        Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
        Objective obj = sb.getObjective(BOARD);
        if (obj == null) {
            obj = sb.registerNewObjective(BOARD, Criteria.DUMMY, Component.empty());
        }
        obj.displayName(Component.text("SKOR · ilk " + settings.winsNeeded() + " galibiyet", NamedTextColor.GOLD));
        obj.setDisplaySlot(DisplaySlot.SIDEBAR);
        for (Fighter f : fighters) {
            obj.getScore(f.name()).setScore(f.wins);
        }
    }

    private void clearBoard() {
        Objective obj = Bukkit.getScoreboardManager().getMainScoreboard().getObjective(BOARD);
        if (obj != null) {
            obj.unregister();
        }
    }

    private void writeSummary() {
        if (log == null) {
            return;
        }
        JsonObject summary = new JsonObject();
        summary.addProperty("seri", seriesId);
        summary.addProperty("en_iyi", settings.bestOf());
        summary.addProperty("skor", scoreText());
        summary.addProperty("oynanan_mac", matchNo);
        summary.addProperty("beraberlik", draws);
        JsonObject sides = new JsonObject();
        for (Fighter f : fighters) {
            Fighter.Stats s = f.total;
            JsonObject side = new JsonObject();
            side.addProperty("isim", f.name());
            side.addProperty("model", f.side.model());
            side.addProperty("galibiyet", f.wins);
            side.addProperty("api_cagrisi", s.calls);
            side.addProperty("gecersiz_cevap", s.invalid);
            side.addProperty("zaman_asimi", s.timeouts);
            side.addProperty("api_hatasi", s.errors);
            side.addProperty("atlanan_karar", s.skipped);
            side.addProperty("ort_gecikme_ms", s.avgLatency());
            side.addProperty("max_gecikme_ms", s.latencyMax);
            side.addProperty("girdi_token", s.inputTokens);
            side.addProperty("cikti_token", s.outputTokens);
            side.addProperty("maliyet_usd", Math.round(s.cost * 1e6) / 1e6);
            side.addProperty("vurus", s.hits);
            side.addProperty("kritik", s.crits);
            side.addProperty("bloklanan", s.blocked);
            side.addProperty("verilen_hasar", round1(s.damage));
            JsonObject moves = new JsonObject();
            for (Move m : Move.values()) {
                moves.addProperty(m.label(), s.moves.getOrDefault(m, 0));
            }
            side.add("hamleler", moves);
            sides.add(f.key, side);
        }
        summary.add("taraflar", sides);
        log.summary(summary);
    }

    private List<Player> viewers() {
        List<Player> list = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!npcs.isNpc(p.getUniqueId())) {
                list.add(p);
            }
        }
        return list;
    }

    private void title(Component main, Component sub, int fadeIn, int stay, int fadeOut) {
        Title title = Title.title(main, sub, Title.Times.times(
                Duration.ofMillis(fadeIn * 50L), Duration.ofMillis(stay * 50L), Duration.ofMillis(fadeOut * 50L)));
        viewers().forEach(p -> p.showTitle(title));
    }
}
