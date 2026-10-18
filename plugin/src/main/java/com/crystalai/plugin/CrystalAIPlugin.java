package com.crystalai.plugin;

import com.crystalai.plugin.action.ActionSettings;
import com.crystalai.plugin.action.NpcActions;
import com.crystalai.plugin.arena.ArenaManager;
import com.crystalai.plugin.bridge.BridgeServer;
import com.crystalai.plugin.bridge.ObservationSchema;
import com.crystalai.plugin.bridge.TrainerLink;
import com.crystalai.plugin.combat.AnchorFinder;
import com.crystalai.plugin.combat.CandidateFinder;
import com.crystalai.plugin.command.CrystalAICommand;
import com.crystalai.plugin.control.ActionSpace;
import com.crystalai.plugin.npc.NpcListener;
import com.crystalai.plugin.npc.NpcManager;
import com.crystalai.plugin.verify.DamageVerifier;
import java.util.Objects;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

public final class CrystalAIPlugin extends JavaPlugin {

    private NpcManager npcs;
    private ArenaManager arenas;
    private BridgeServer bridge;
    private com.crystalai.plugin.arena.DuelManager duels;
    private com.crystalai.plugin.llm.LlmMatchManager llmMatches;
    private com.crystalai.plugin.llm.HumanDuel llmDuel;
    private com.crystalai.plugin.llm.CardTraining llmTraining;

    public com.crystalai.plugin.arena.DuelManager duels() {
        return duels;
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        // Add keys introduced by newer plugin versions to an existing config.yml, keeping user edits.
        getConfig().options().copyDefaults(true);
        saveConfig();
        npcs = new NpcManager(this);
        npcs.start();
        getServer().getPluginManager().registerEvents(new NpcListener(this, npcs), this);
        DamageVerifier verifier = new DamageVerifier(this, npcs);
        getServer().getPluginManager().registerEvents(verifier, this);
        bridge = new BridgeServer(getLogger());
        ObservationSchema schema;
        try (java.io.InputStream stream = Objects.requireNonNull(getResource("protocol/schema.json"),
                "protocol/schema.json missing from the jar")) {
            schema = ObservationSchema.load(stream);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        TrainerLink link = new TrainerLink(getLogger(), bridge, schema, getConfig().getInt("bridge.timeout-ms", 30000));
        int port = getConfig().getInt("bridge.port", 25590);
        try {
            bridge.start(port);
        } catch (java.io.IOException e) {
            getLogger().severe("Bridge could not listen on port " + port + ": " + e.getMessage());
        }
        arenas = new ArenaManager(this, npcs, this::actionSpace, link);
        getServer().getPluginManager().registerEvents(arenas, this);
        duels = new com.crystalai.plugin.arena.DuelManager(this, npcs, arenas, this::actionSpace,
                new com.crystalai.plugin.bridge.ObservationBuilder(schema));
        getServer().getPluginManager().registerEvents(duels, this);
        CrystalAICommand command = new CrystalAICommand(this, npcs, verifier, arenas);
        Objects.requireNonNull(getCommand("crystalai")).setExecutor(command);
        Objects.requireNonNull(getCommand("crystalai")).setTabCompleter(command);
        llmMatches = new com.crystalai.plugin.llm.LlmMatchManager(this, npcs, this::actions);
        getServer().getPluginManager().registerEvents(llmMatches, this);
        llmDuel = new com.crystalai.plugin.llm.HumanDuel(this, npcs, this::actions, llmMatches);
        getServer().getPluginManager().registerEvents(llmDuel, this);
        llmTraining = new com.crystalai.plugin.llm.CardTraining(this, npcs, this::actions, llmMatches);
        getServer().getPluginManager().registerEvents(llmTraining, this);
        com.crystalai.plugin.llm.LlmCommand llmCommand = new com.crystalai.plugin.llm.LlmCommand(llmMatches, llmDuel, llmTraining);
        Objects.requireNonNull(getCommand("yzmac")).setExecutor(llmCommand);
        Objects.requireNonNull(getCommand("yzmac")).setTabCompleter(llmCommand);
        Objects.requireNonNull(getCommand("ready")).setExecutor((sender, cmd, label, args) -> {
            // /ready <player> (ops, console): mark someone else ready, e.g. a guest who missed the message.
            org.bukkit.entity.Player target = args.length >= 1 && sender.hasPermission("crystalai.admin")
                    ? getServer().getPlayerExact(args[0])
                    : sender instanceof org.bukkit.entity.Player self ? self : null;
            if (target == null || !duels.ready(target)) {
                sender.sendMessage("§7Hazır olması beklenen bir düello yok.");
            }
            return true;
        });
    }

    public NpcActions actions() {
        return new NpcActions(ActionSettings.from(
                Objects.requireNonNull(getConfig().getConfigurationSection("actions"), "config: actions")));
    }

    public CandidateFinder candidateFinder() {
        return new CandidateFinder(CandidateFinder.Settings.from(
                Objects.requireNonNull(getConfig().getConfigurationSection("candidates"), "config: candidates")),
                actions());
    }

    public ActionSpace actionSpace() {
        ConfigurationSection anchors = getConfig().getConfigurationSection("anchor-candidates");
        if (anchors == null) {
            anchors = getConfig().createSection("anchor-candidates");
        }
        return new ActionSpace(actions(), candidateFinder(), new AnchorFinder(AnchorFinder.Settings.from(anchors), actions()),
                (float) getConfig().getDouble("actions.pearl-pitch", -10.0));
    }

    @Override
    public void onDisable() {
        if (llmTraining != null) {
            llmTraining.stop("Sunucu kapanıyor");
        }
        if (llmDuel != null) {
            llmDuel.stop("Sunucu kapanıyor");
        }
        if (llmMatches != null) {
            llmMatches.stop("Sunucu kapanıyor");
        }
        if (duels != null) {
            duels.stop("Sunucu kapanıyor");
        }
        if (arenas != null) {
            arenas.stop();
        }
        if (bridge != null) {
            bridge.stop();
        }
        if (npcs != null) {
            npcs.shutdown();
        }
    }
}
