package com.crystalai.plugin.llm;

import com.crystalai.plugin.action.NpcActions;
import com.crystalai.plugin.npc.CrystalNpc;
import com.crystalai.plugin.npc.NpcManager;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * /yzmac egitim [mock|gercek] <nesil> [mac] [hizli]: each model trains its own fighting card.
 * Two lanes side by side, one per model, each against the same sparring dummy (llm.yml egitim.kukla).
 * Fights make no API calls: the body plays the card. After every generation of matches the model reads
 * its results (and the earlier generations') and rewrites its card: one call per generation, the same
 * coach prompt, the same card ranges for both. Files: ders-defteri/<key>_kart.json, ders-defteri/egitim.jsonl.
 */
public final class CardTraining implements Listener {

    private enum Phase { COUNTDOWN, FIGHT, PAUSE, WAIT_CARD, DONE }

    /** One model's training: its NPC, the dummy, its box. */
    private final class Lane {
        final Fighter trainee;
        final Fighter dummy;
        final LlmArena box;
        final CardFile file;
        Phase phase = Phase.COUNTDOWN;
        int phaseTicks;
        int fightTicks;
        int matchInGen;
        int generation;          // generations finished in this run
        // this generation
        int wins, losses, draws, crits, hits, blocked;
        double dealt, taken, seconds;
        String lastNote = "";
        /** The dummy gets harder each time this lane wins two thirds of a generation. Same rule for both lanes. */
        int dummyLevel = 1;

        Lane(Fighter trainee, Fighter dummy, LlmArena box, CardFile file) {
            this.trainee = trainee;
            this.dummy = dummy;
            this.box = box;
            this.file = file;
        }

        void resetGeneration() {
            wins = losses = draws = crits = hits = blocked = 0;
            dealt = taken = seconds = 0;
            matchInGen = 0;
        }
    }

    /** A model's card and its training history on disk. */
    static final class CardFile {
        final Path path;
        FightCard card = FightCard.UNTRAINED;
        int generations;
        final JsonArray history = new JsonArray();

        CardFile(Path dir, String key) {
            this.path = dir.resolve(key + "_kart.json");
            if (Files.isRegularFile(path)) {
                try {
                    JsonObject root = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
                    card = FightCard.fromJson(root.getAsJsonObject("kart"), FightCard.UNTRAINED);
                    generations = root.has("nesil") ? root.get("nesil").getAsInt() : 0;
                    if (root.has("gecmis")) {
                        root.getAsJsonArray("gecmis").forEach(history::add);
                    }
                } catch (IOException | RuntimeException e) {
                    card = FightCard.UNTRAINED;
                }
            }
        }

        boolean exists() {
            return Files.isRegularFile(path);
        }

        void save() throws IOException {
            Files.createDirectories(path.getParent());
            JsonObject root = new JsonObject();
            root.addProperty("nesil", generations);
            root.add("kart", card.toJson());
            root.add("gecmis", history);
            Files.writeString(path, root.toString(), StandardCharsets.UTF_8);
        }
    }

    private final JavaPlugin plugin;
    private final NpcManager npcs;
    private final Supplier<NpcActions> actions;
    private final LlmMatchManager matches;
    private final BossBar bar = BossBar.bossBar(Component.empty(), 0.0F, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);

    private final List<Lane> lanes = new ArrayList<>();
    private @Nullable BukkitTask task;
    private @Nullable MoveExecutor executor;
    private LlmSettings settings;
    private int generationsWanted;
    private int matchesPerGen;
    private boolean fast;
    private float tickRateBefore = 20.0F;
    private FightCard dummyBase = FightCard.UNTRAINED;
    private boolean dummyLadder = true;

    public CardTraining(JavaPlugin plugin, NpcManager npcs, Supplier<NpcActions> actions, LlmMatchManager matches) {
        this.plugin = plugin;
        this.npcs = npcs;
        this.actions = actions;
        this.matches = matches;
        this.settings = matches.reloadForDuel();
    }

    public boolean busy() {
        return !lanes.isEmpty();
    }

    private Path dir() {
        return new File(plugin.getDataFolder(), "ders-defteri").toPath();
    }

    /** The card a model fights with outside training; null when it has never trained (plain moves). */
    static @Nullable FightCard cardOf(JavaPlugin plugin, String key) {
        CardFile file = new CardFile(new File(plugin.getDataFolder(), "ders-defteri").toPath(), key);
        return file.exists() ? file.card : null;
    }

    // ================================================================ control

    public @Nullable String start(@Nullable Boolean mockOverride, int generations, int perGen, boolean fast) {
        if (busy()) {
            return "Eğitim zaten sürüyor (/yzmac durdur).";
        }
        if (matches.busy()) {
            return "Önce süren seriyi bitir (/yzmac durdur).";
        }
        settings = matches.reloadForDuel();
        boolean mock = mockOverride != null ? mockOverride : settings.mock();
        if (!mock && !settings.realModeProblems().isEmpty()) {
            return "llm.yml eksik: " + String.join(", ", settings.realModeProblems());
        }
        YamlConfiguration config = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "llm.yml"));
        FightCard dummyCard = FightCard.fromConfig(config.getConfigurationSection("egitim.kukla"),
                new FightCard(0.95, 0.35, 2, 0.4, 14, 0, 0.3, 7.0, 2.9, true));
        dummyBase = dummyCard;
        dummyLadder = config.getBoolean("egitim.kukla-zorlasir", true);
        generationsWanted = Math.max(1, generations);
        matchesPerGen = Math.max(1, perGen);
        executor = new MoveExecutor(actions.get(), settings);
        executor.customArena = false;
        executor.edgeBrake = false;

        String[] keys = {"chatgpt", "gemini"};
        for (int i = 0; i < keys.length; i++) {
            String key = keys[i];
            LlmSettings.Side side = key.equals("chatgpt") ? settings.chatgpt() : settings.gemini();
            LlmClient client = mock ? new LlmClient.Mock(settings)
                    : key.equals("chatgpt") ? new LlmClient.OpenAi(side, settings) : new LlmClient.Gemini(side, settings);
            Fighter trainee = new Fighter(key, side, client, key.equals("chatgpt") ? NamedTextColor.GREEN : NamedTextColor.AQUA);
            LlmSettings.Side dummySide = new LlmSettings.Side("Kukla-" + (i + 1), "", "", -1, -1, 0, 0);
            Fighter dummy = new Fighter("kukla" + (i + 1), dummySide, new LlmClient.Mock(settings), NamedTextColor.GRAY);
            // Lanes side by side, 30 blocks apart, far from the duel box at the origin.
            LlmArena box = LlmArena.boxAt(settings, "egitim-" + key, 1000 + i * 30, 0);
            CardFile file = new CardFile(dir(), key);
            trainee.card = file.card;
            dummy.card = dummyCard;
            lanes.add(new Lane(trainee, dummy, box, file));
        }
        this.fast = fast;
        if (fast) {
            tickRateBefore = Bukkit.getServerTickManager().getTickRate();
            Bukkit.getServerTickManager().setTickRate(60.0F);
        }
        for (Lane lane : lanes) {
            startMatch(lane);
        }
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        broadcast(Component.text("Eğitim başladı: " + generationsWanted + " nesil × " + matchesPerGen + " maç, kukla: "
                + dummyCard.summary(), NamedTextColor.GOLD));
        return null;
    }

    public boolean stop(String reason) {
        if (!busy()) {
            return false;
        }
        for (Lane lane : lanes) {
            removeNpcs(lane);
        }
        lanes.clear();
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (fast) {
            Bukkit.getServerTickManager().setTickRate(tickRateBefore);
            fast = false;
        }
        viewers().forEach(p -> p.hideBossBar(bar));
        broadcast(Component.text("Eğitim bitti (" + reason + ").", NamedTextColor.GOLD));
        return true;
    }

    /** Both cards back to untrained, history gone: both start equal. */
    public String reset() {
        if (busy()) {
            return "Eğitim sürerken sıfırlanamaz.";
        }
        try {
            for (String key : List.of("chatgpt", "gemini")) {
                Files.deleteIfExists(new CardFile(dir(), key).path);
            }
            return "İki dövüş kartı da sıfırlandı (eğitimsiz).";
        } catch (IOException e) {
            return "Kart silinemedi: " + e.getMessage();
        }
    }

    public List<String> describe() {
        List<String> lines = new ArrayList<>();
        for (String key : List.of("chatgpt", "gemini")) {
            CardFile file = new CardFile(dir(), key);
            String name = key.equals("chatgpt") ? settings.chatgpt().name() : settings.gemini().name();
            lines.add("§6" + name + " §7(" + (file.exists() ? file.generations + " nesil" : "eğitimsiz") + ")");
            lines.add("§f  " + file.card.summary());
            int n = file.history.size();
            for (int i = Math.max(0, n - 5); i < n; i++) {
                JsonObject g = file.history.get(i).getAsJsonObject();
                lines.add("§8  nesil " + g.get("nesil").getAsInt() + ": " + g.get("galibiyet").getAsInt() + "/"
                        + g.get("mac").getAsInt() + " galibiyet"
                        + (g.has("not") ? " · " + g.get("not").getAsString() : ""));
            }
        }
        return lines;
    }

    public @Nullable Location camera() {
        if (lanes.size() < 2) {
            return null;
        }
        Location a = lanes.get(0).box.camera();
        Location b = lanes.get(1).box.camera();
        Location mid = a.clone().add(b).multiply(0.5);
        mid.add(0, 8, 8);
        mid.setPitch(38.0F);
        mid.setYaw(180.0F);
        return mid;
    }

    // ================================================================ matches

    private void startMatch(Lane lane) {
        lane.trainee.resetForMatch();
        lane.dummy.resetForMatch();
        lane.trainee.move = Move.SALDIR;
        lane.dummy.move = Move.SALDIR;
        // Sides swap every match.
        boolean swap = lane.matchInGen % 2 == 1;
        try {
            lane.trainee.npc = npcs.spawn(lane.box.spawn(swap ? 1 : 0), 0, "orta", lane.trainee.name());
            lane.dummy.npc = npcs.spawn(lane.box.spawn(swap ? 0 : 1), 0, "orta", lane.dummy.name());
        } catch (RuntimeException e) {
            plugin.getLogger().severe("yz-mac egitim: NPC çıkarılamadı: " + e.getMessage());
            stop("npc hatası");
            return;
        }
        LlmMatchManager.giveKit(lane.trainee.npc.bukkit(), lane.box, settings);
        LlmMatchManager.giveKit(lane.dummy.npc.bukkit(), lane.box, settings);
        lane.trainee.npc.lockFocus(lane.dummy.npc.handle());
        lane.dummy.npc.lockFocus(lane.trainee.npc.handle());
        lane.phase = Phase.COUNTDOWN;
        lane.phaseTicks = 0;
        lane.fightTicks = 0;
    }

    private void tick() {
        boolean allDone = true;
        for (Lane lane : new ArrayList<>(lanes)) {
            tickLane(lane);
            allDone &= lane.phase == Phase.DONE;
        }
        updateBar();
        if (allDone && !lanes.isEmpty()) {
            stop("tamamlandı");
        }
    }

    private void tickLane(Lane lane) {
        switch (lane.phase) {
            case COUNTDOWN -> {
                if (++lane.phaseTicks >= 20) {
                    lane.phase = Phase.FIGHT;
                }
            }
            case FIGHT -> {
                Fighter a = lane.trainee;
                Fighter b = lane.dummy;
                if (a.npc == null || b.npc == null || npcs.get(a.npc.handle().getUUID()) == null
                        || npcs.get(b.npc.handle().getUUID()) == null) {
                    endMatch(lane, null);
                    return;
                }
                executor.tick(a, b);
                executor.tick(b, a);
                lane.fightTicks++;
                if (lane.fightTicks >= settings.matchTicks()) {
                    double ha = LlmMatchManager.effectiveHealth(a.npc.bukkit());
                    double hb = LlmMatchManager.effectiveHealth(b.npc.bukkit());
                    endMatch(lane, Math.abs(ha - hb) < 0.01 ? null : ha > hb);
                }
            }
            case PAUSE -> {
                if (++lane.phaseTicks >= 30) {
                    removeNpcs(lane);
                    if (lane.matchInGen >= matchesPerGen) {
                        requestCard(lane);
                    } else {
                        startMatch(lane);
                    }
                }
            }
            case WAIT_CARD, DONE -> { }
        }
    }

    /** @param traineeWon null = draw */
    private void endMatch(Lane lane, @Nullable Boolean traineeWon) {
        lane.phase = Phase.PAUSE;
        lane.phaseTicks = 0;
        lane.matchInGen++;
        if (traineeWon == null) {
            lane.draws++;
        } else if (traineeWon) {
            lane.wins++;
        } else {
            lane.losses++;
        }
        lane.seconds += lane.fightTicks / 20.0;
        lane.hits += lane.trainee.match.hits;
        lane.crits += lane.trainee.match.crits;
        lane.dealt += lane.trainee.match.damage;
        lane.taken += lane.dummy.match.damage;
        lane.blocked += lane.trainee.match.blocked;
        for (Fighter f : List.of(lane.trainee, lane.dummy)) {
            if (f.npc != null && f.npc.handle().isAlive()) {
                f.npc.setMovement(0, 0, false);
            }
        }
    }

    // ================================================================ the card rewrite

    private static String coachPrompt(int perGen) {
        return "Sen Minecraft'ta kılıç PvP'si yapan bir yapay zekasın. Vücudun saniyede 20 kez çalışan bir dövüş kartıyla "
                + "hareket ediyor ve kartın ayarlarını sen seçiyorsun. Her nesilde kartınla aynı antrenman kuklasına karşı "
                + perGen + " maç oynuyorsun; sonra sonuçlara bakıp kartını yeniden yazıyorsun. Amacın kuklayı olabildiğince "
                + "çok ve hızlı yenmek. İki taraf da aynı zırh ve kılıçla (elmas), aynı kuklaya karşı dövüşüyor. "
                + "Bir nesilde maçların üçte ikisini kazanırsan kukla bir seviye zorlaşır (kukla_seviye): o zaman galibiyet "
                + "oranı düşebilir, bu kötüleştiğin anlamına gelmez.\n"
                + "Kart ayarları:" + FightCard.explain() + "\n"
                + "Sana bütün nesillerin sonuçları verilecek. Hangi kartın ne getirdiğine bak: işe yarayanı koru, tek seferde "
                + "çok şeyi birden değiştirme ki neyin işe yaradığını anlayabilesin.\n"
                + "Sadece şu JSON'u ver: {\"kart\": {\"vurus_sarj\": .., \"kritik\": .., \"wtap\": .., \"strafe\": .., "
                + "\"strafe_degistir\": .., \"geri_cekil\": .., \"kalkan\": .., \"iyiles_can\": .., \"menzil\": .., "
                + "\"sprint\": true}, \"not\": \"<en fazla 15 kelime: neyi neden değiştirdin>\"}";
    }

    private void requestCard(Lane lane) {
        lane.phase = Phase.WAIT_CARD;
        CardFile file = lane.file;
        file.generations++;
        JsonObject result = new JsonObject();
        result.addProperty("nesil", file.generations);
        result.add("kart", file.card.toJson());
        result.addProperty("mac", lane.matchInGen);
        result.addProperty("galibiyet", lane.wins);
        result.addProperty("maglubiyet", lane.losses);
        result.addProperty("berabere", lane.draws);
        result.addProperty("vurus", lane.hits);
        result.addProperty("kritik_vurus", lane.crits);
        result.addProperty("verilen_hasar", round1(lane.dealt));
        result.addProperty("alinan_hasar", round1(lane.taken));
        result.addProperty("kalkanla_engellenen", lane.blocked);
        result.addProperty("ort_mac_sn", round1(lane.seconds / Math.max(1, lane.matchInGen)));
        result.addProperty("kukla_seviye", lane.dummyLevel);
        if (!lane.lastNote.isEmpty()) {
            result.addProperty("not", lane.lastNote);
        }
        file.history.add(result);
        appendLog(lane, result);
        announceGeneration(lane, result);
        if (dummyLadder && lane.wins * 3 >= lane.matchInGen * 2) {
            lane.dummyLevel++;
            lane.dummy.card = dummyAt(lane.dummyLevel);
            broadcast(Component.text("[" + lane.trainee.name() + "] kuklayı geçti: kukla seviye " + lane.dummyLevel + "!",
                    NamedTextColor.GOLD));
        }

        if (lane.generation + 1 >= generationsWanted) {
            // The last generation is only measured: no rewrite after it.
            lane.generation++;
            saveQuietly(file);
            lane.phase = Phase.DONE;
            return;
        }
        JsonObject user = new JsonObject();
        user.add("simdiki_kart", file.card.toJson());
        JsonArray history = new JsonArray();
        int n = file.history.size();
        for (int i = Math.max(0, n - 8); i < n; i++) {
            history.add(file.history.get(i));
        }
        user.add("nesiller", history);
        lane.trainee.client.ask(coachPrompt(matchesPerGen), user.toString(), 400, 30000)
                .thenAccept(raw -> Bukkit.getScheduler().runTask(plugin, () -> applyCard(lane, raw)));
    }

    /** Level 1 is llm.yml's dummy; every level above sharpens it a step. */
    private FightCard dummyAt(int level) {
        FightCard b = dummyBase;
        int up = level - 1;
        return new FightCard(b.charge() + 0.02 * up, b.crit() + 0.12 * up, b.wTap() + up, b.strafe() + 0.12 * up,
                b.strafeSwitch(), b.backOff(), b.shield() + 0.1 * up, b.eatAt() + up, b.reach() + 0.04 * up, true);
    }

    private void applyCard(Lane lane, String raw) {
        if (!lanes.contains(lane)) {
            return; // stopped meanwhile
        }
        CardFile file = lane.file;
        FightCard next = null;
        String note = "";
        try {
            int open = raw.indexOf('{');
            int close = raw.lastIndexOf('}');
            JsonObject root = JsonParser.parseString(raw.substring(open, close + 1)).getAsJsonObject();
            JsonElement card = root.get("kart");
            if (card != null && card.isJsonObject()) {
                next = FightCard.fromJson(card.getAsJsonObject(), file.card);
            }
            if (root.has("not")) {
                note = LlmClient.limitWords(root.get("not").getAsString().replace('\n', ' ').trim(), 15);
            }
        } catch (RuntimeException e) {
            next = null;
        }
        if (next == null) {
            broadcast(Component.text("[" + lane.trainee.name() + "] kart yazamadı, eskisiyle devam", NamedTextColor.GRAY));
        } else {
            file.card = next;
            lane.lastNote = note;
            lane.trainee.card = next;
            broadcast(Component.text("[" + lane.trainee.name() + " · yeni kart] ", lane.trainee.color)
                    .append(Component.text(note, NamedTextColor.WHITE)));
            broadcast(Component.text("  " + next.summary(), NamedTextColor.GRAY));
        }
        saveQuietly(file);
        lane.generation++;
        lane.resetGeneration();
        startMatch(lane);
    }

    private void announceGeneration(Lane lane, JsonObject r) {
        broadcast(Component.text("[" + lane.trainee.name() + " · nesil " + r.get("nesil").getAsInt() + "] ", lane.trainee.color)
                .append(Component.text(lane.wins + "/" + lane.matchInGen + " galibiyet · vuruş " + lane.hits + " · kritik "
                        + lane.crits + " · hasar " + round1(lane.dealt) + "/" + round1(lane.taken), NamedTextColor.WHITE)));
    }

    private void appendLog(Lane lane, JsonObject result) {
        JsonObject line = result.deepCopy();
        line.addProperty("taraf", lane.trainee.key);
        line.addProperty("zaman", LocalDateTime.now().toString());
        try {
            Files.createDirectories(dir());
            Files.writeString(dir().resolve("egitim.jsonl"), line + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            plugin.getLogger().warning("yz-mac egitim: log yazılamadı: " + e.getMessage());
        }
    }

    private void saveQuietly(CardFile file) {
        try {
            file.save();
        } catch (IOException e) {
            plugin.getLogger().warning("yz-mac egitim: kart yazılamadı: " + e.getMessage());
        }
    }

    // ================================================================ HUD

    private void updateBar() {
        if (lanes.isEmpty()) {
            return;
        }
        StringBuilder text = new StringBuilder("EĞİTİM");
        float progress = 0;
        for (Lane lane : lanes) {
            int gen = Math.min(generationsWanted, lane.generation + (lane.phase == Phase.DONE ? 0 : 1));
            text.append("  ·  ").append(lane.trainee.name()).append(" nesil ").append(gen).append('/').append(generationsWanted)
                    .append(" (").append(lane.wins).append('/').append(lane.matchInGen).append(')');
            progress += (lane.generation * matchesPerGen + lane.matchInGen) / (float) (generationsWanted * matchesPerGen);
        }
        bar.name(Component.text(text.toString()));
        bar.progress(Math.max(0.0F, Math.min(1.0F, progress / lanes.size())));
        viewers().forEach(p -> p.showBossBar(bar));
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

    private void broadcast(Component line) {
        viewers().forEach(p -> p.sendMessage(line));
        plugin.getLogger().info("yz-mac egitim: " + net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                .plainText().serialize(line));
    }

    private void removeNpcs(Lane lane) {
        for (Fighter f : List.of(lane.trainee, lane.dummy)) {
            CrystalNpc npc = f.npc;
            if (npc != null && npcs.get(npc.handle().getUUID()) != null) {
                npcs.remove(npc);
            }
            f.npc = null;
        }
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    // ================================================================ events

    private @Nullable Lane laneOf(Entity e) {
        for (Lane lane : lanes) {
            for (Fighter f : List.of(lane.trainee, lane.dummy)) {
                if (f.npc != null && f.npc.handle().getUUID().equals(e.getUniqueId())) {
                    return lane;
                }
            }
        }
        return null;
    }

    private static @Nullable Fighter fighterIn(Lane lane, Entity e) {
        for (Fighter f : List.of(lane.trainee, lane.dummy)) {
            if (f.npc != null && f.npc.handle().getUUID().equals(e.getUniqueId())) {
                return f;
            }
        }
        return null;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        Lane lane = laneOf(event.getEntity());
        if (lane != null && lane.phase != Phase.FIGHT) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent event) {
        Lane lane = laneOf(event.getEntity());
        if (lane == null || lane.phase != Phase.FIGHT) {
            return;
        }
        Fighter attacker = fighterIn(lane, event.getDamager());
        Fighter victim = fighterIn(lane, event.getEntity());
        if (attacker == null || victim == null || attacker == victim) {
            return;
        }
        if (event.getFinalDamage() <= 0.01 && victim.npc.bukkit().isBlocking()) {
            victim.match.blocked++;
            return;
        }
        attacker.match.hits++;
        attacker.match.damage += event.getFinalDamage();
        if (event.isCritical()) {
            attacker.match.crits++;
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        Lane lane = laneOf(event.getEntity());
        if (lane == null) {
            return;
        }
        event.setKeepInventory(true);
        event.getDrops().clear();
        event.setKeepLevel(true);
        event.setDroppedExp(0);
        event.deathMessage(null);
        if (lane.phase == Phase.FIGHT) {
            Fighter dead = fighterIn(lane, event.getEntity());
            endMatch(lane, dead != lane.trainee);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onRegain(EntityRegainHealthEvent event) {
        if (settings.naturalRegeneration() || laneOf(event.getEntity()) == null) {
            return;
        }
        EntityRegainHealthEvent.RegainReason reason = event.getRegainReason();
        if (reason == EntityRegainHealthEvent.RegainReason.SATIATED || reason == EntityRegainHealthEvent.RegainReason.REGEN) {
            event.setCancelled(true);
        }
    }
}
