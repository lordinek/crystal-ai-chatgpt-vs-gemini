package com.crystalai.plugin.command;

import com.crystalai.plugin.action.ActionResult;
import com.crystalai.plugin.action.ActionSettings;
import com.crystalai.plugin.action.NpcActions;
import com.crystalai.plugin.arena.ArenaManager;
import com.crystalai.plugin.combat.CandidateFinder;
import com.crystalai.plugin.combat.CrystalCandidate;
import com.crystalai.plugin.verify.DamageVerifier;
import com.crystalai.plugin.npc.CrystalNpc;
import com.crystalai.plugin.npc.NpcManager;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.StringUtil;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

/**
 * /crystalai spawn [generation|latest] [difficulty]
 * /crystalai list
 * /crystalai remove <name|all>
 * /crystalai kit <name|all>
 * /crystalai do <name> <action> [args]   (test harness for the action API)
 * /crystalai candidates <name>            (show the candidate crystal spots vs. its focus)
 * /crystalai verify damage|candidates     (compare predictions with real explosions)
 */
public final class CrystalAICommand implements TabExecutor {

    private static final List<String> DIFFICULTIES = List.of("kolay", "orta", "pro", "insanustu");
    private static final List<String> ACTIONS =
            List.of("obsidian", "crystal", "explode", "totem", "eat", "move", "jump", "stop",
                    "sword", "anchor", "charge", "detonate", "glowstone", "pearl", "xp", "totem2", "mine");
    private static final int TARGET_RANGE = 8;

    private final JavaPlugin plugin;
    private final NpcManager npcs;
    private final DamageVerifier verifier;
    private final ArenaManager arenas;

    public CrystalAICommand(JavaPlugin plugin, NpcManager npcs, DamageVerifier verifier, ArenaManager arenas) {
        this.plugin = plugin;
        this.npcs = npcs;
        this.verifier = verifier;
        this.arenas = arenas;
    }

    private NpcActions actions() {
        return new NpcActions(ActionSettings.from(
                Objects.requireNonNull(plugin.getConfig().getConfigurationSection("actions"), "actions section")));
    }

    private CandidateFinder candidateFinder() {
        return new CandidateFinder(CandidateFinder.Settings.from(
                Objects.requireNonNull(plugin.getConfig().getConfigurationSection("candidates"), "candidates section")),
                actions());
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        if (args.length == 0) {
            return usage(sender, label);
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "spawn" -> spawn(sender, args);
            case "list" -> list(sender);
            case "remove" -> remove(sender, label, args);
            case "kit" -> kit(sender, label, args);
            case "do" -> doAction(sender, label, args);
            case "candidates" -> candidates(sender, label, args);
            case "context" -> context(sender, label, args);
            case "duel" -> duel(sender, label, args);
            case "pvp" -> pvp(sender, label, args);
            case "verify" -> verify(sender, label, args);
            case "arena" -> arena(sender, label, args);
            default -> usage(sender, label);
        }
        return true;
    }

    private boolean usage(CommandSender sender, String label) {
        sender.sendMessage("§b/" + label + " spawn [nesil|latest] [kolay|orta|pro|insanustu]");
        sender.sendMessage("§b/" + label + " list");
        sender.sendMessage("§b/" + label + " remove <isim|all>");
        sender.sendMessage("§b/" + label + " kit <isim|all>");
        sender.sendMessage("§b/" + label + " do <isim> obsidian|crystal [x y z]");
        sender.sendMessage("§b/" + label + " do <isim> explode|totem|totem2|eat|jump|stop|sword|xp");
        sender.sendMessage("§b/" + label + " do <isim> anchor|glowstone [x y z]  |  charge|detonate [x y z]");
        sender.sendMessage("§b/" + label + " do <isim> pearl [yaw] [pitch]  |  mine [x y z]");
        sender.sendMessage("§b/" + label + " do <isim> move <0-8> [tick] [sprint]");
        sender.sendMessage("§b/" + label + " candidates <isim>");
        sender.sendMessage("§b/" + label + " verify damage|candidates|anchor [düzen sayısı]");
        sender.sendMessage("§b/" + label + " arena start [sayı] | stop | status | tp <no>");
        sender.sendMessage("§b/" + label + " duel [latest|nesil|stop] [oyuncu]  |  pvp <oyuncu1> <oyuncu2>");
        return true;
    }

    // ---------------------------------------------------------------- spawn / list / remove / kit

    private void spawn(CommandSender sender, String[] args) {
        int generation = 0; // No trained models yet; "latest" resolves to gen 0 until stage 6.
        if (args.length >= 2 && !args[1].equalsIgnoreCase("latest")) {
            try {
                generation = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                sender.sendMessage("§cNesil bir sayı ya da 'latest' olmalı: " + args[1]);
                return;
            }
            if (generation < 0) {
                sender.sendMessage("§cNesil negatif olamaz.");
                return;
            }
        }
        String difficulty = plugin.getConfig().getString("npc.default-difficulty", "orta");
        if (args.length >= 3) {
            difficulty = args[2].toLowerCase(Locale.ROOT);
            if (!DIFFICULTIES.contains(difficulty)) {
                sender.sendMessage("§cZorluk şunlardan biri olmalı: " + String.join(", ", DIFFICULTIES));
                return;
            }
        }

        CrystalNpc npc;
        try {
            npc = npcs.spawn(spawnLocation(sender), generation, difficulty);
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.SEVERE, "NPC spawn failed", e);
            sender.sendMessage("§cNPC oluşturulamadı: " + e.getMessage() + " (ayrıntı konsolda)");
            return;
        }
        sender.sendMessage("§aNPC oluşturuldu: §f" + npc.name() + " §7(nesil " + generation + ", " + difficulty + ")");
    }

    /** In front of the player, facing them; world spawn when run from the console. */
    private Location spawnLocation(CommandSender sender) {
        if (sender instanceof Player player) {
            double distance = plugin.getConfig().getDouble("npc.spawn-distance", 2.0);
            Location eye = player.getLocation();
            Vector forward = eye.getDirection().setY(0);
            if (forward.lengthSquared() < 1.0E-6) {
                forward = new Vector(0, 0, 1);
            }
            Location spot = eye.clone().add(forward.normalize().multiply(distance));
            spot.setDirection(eye.toVector().subtract(spot.toVector()));
            spot.setPitch(0);
            return spot;
        }
        return Bukkit.getWorlds().get(0).getSpawnLocation().add(0.5, 0, 0.5);
    }

    private void list(CommandSender sender) {
        if (npcs.all().isEmpty()) {
            sender.sendMessage("§7Aktif NPC yok.");
            return;
        }
        for (CrystalNpc npc : npcs.all()) {
            ServerPlayer handle = npc.handle();
            Location l = npc.bukkit().getLocation();
            sender.sendMessage(String.format(Locale.ROOT,
                    "§f%s §7nesil %d, %s | can %.1f +%.1f | açlık %d | %s %.2f %.2f %.2f | el %s / %s",
                    npc.name(), npc.generation(), npc.difficulty(), handle.getHealth(), handle.getAbsorptionAmount(),
                    handle.getFoodData().getFoodLevel(), l.getWorld().getName(), l.getX(), l.getY(), l.getZ(),
                    handle.getMainHandItem().getItem(), handle.getOffhandItem().getItem()));
        }
    }

    private void remove(CommandSender sender, String label, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§c/" + label + " remove <isim|all>");
            return;
        }
        if (args[1].equalsIgnoreCase("all")) {
            sender.sendMessage("§a" + npcs.removeAll() + " NPC silindi.");
            return;
        }
        CrystalNpc npc = findNpc(sender, args[1]);
        if (npc == null) {
            return;
        }
        npcs.remove(npc);
        sender.sendMessage("§a" + npc.name() + " silindi.");
    }

    private void kit(CommandSender sender, String label, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§c/" + label + " kit <isim|all>");
            return;
        }
        List<CrystalNpc> targets;
        if (args[1].equalsIgnoreCase("all")) {
            targets = List.copyOf(npcs.all());
        } else {
            CrystalNpc npc = findNpc(sender, args[1]);
            if (npc == null) {
                return;
            }
            targets = List.of(npc);
        }
        try {
            var kit = npcs.combatKit();
            targets.forEach(npc -> kit.apply(npc.bukkit().getInventory()));
        } catch (RuntimeException e) {
            sender.sendMessage("§cKit uygulanamadı: " + e.getMessage());
            return;
        }
        sender.sendMessage("§aKit verildi: " + targets.size() + " NPC");
    }

    // ---------------------------------------------------------------- do

    private void doAction(CommandSender sender, String label, String[] args) {
        if (args.length < 3) {
            sender.sendMessage("§c/" + label + " do <isim> <" + String.join("|", ACTIONS) + "> ...");
            return;
        }
        CrystalNpc npc = findNpc(sender, args[1]);
        if (npc == null) {
            return;
        }
        NpcActions actions = actions();
        ServerPlayer player = npc.handle();
        String[] rest = Arrays.copyOfRange(args, 3, args.length);
        ActionResult result;
        BlockPos aimed = null;

        switch (args[2].toLowerCase(Locale.ROOT)) {
            case "obsidian" -> {
                BlockPos pos = targetPos(sender, rest, true);
                if (pos == null) {
                    return;
                }
                aimed = pos;
                result = actions.placeObsidian(player, pos);
            }
            case "crystal" -> {
                BlockPos base = targetPos(sender, rest, false);
                if (base == null) {
                    return;
                }
                boolean autoObsidian = plugin.getConfig().getBoolean("actions.crystal-places-obsidian", true);
                aimed = base;
                result = actions.placeCrystal(player, base, autoObsidian);
            }
            case "explode" -> result = actions.attackNearestCrystal(player);
            case "sword" -> {
                if (!(npc.focus() instanceof net.minecraft.world.entity.LivingEntity target)) {
                    sender.sendMessage("§cNPC'nin odaklandığı bir rakip yok.");
                    return;
                }
                result = actions.swordHit(player, target);
            }
            case "anchor", "glowstone" -> {
                BlockPos pos = targetPos(sender, rest, true);
                if (pos == null) {
                    return;
                }
                aimed = pos;
                result = args[2].equalsIgnoreCase("anchor") ? actions.placeAnchor(player, pos)
                        : actions.placeBlock(player, pos, net.minecraft.world.item.Items.GLOWSTONE,
                                net.minecraft.world.level.block.Blocks.GLOWSTONE.defaultBlockState());
            }
            case "charge", "detonate" -> {
                // x y z, or the nearest anchor in reach
                BlockPos pos = rest.length >= 3 ? targetPos(sender, rest, false)
                        : actions.anchorsInReach(player).stream().findFirst().orElse(null);
                if (pos == null) {
                    result = ActionResult.fail("no_anchor");
                } else {
                    aimed = pos;
                    result = args[2].equalsIgnoreCase("charge") ? actions.chargeAnchor(player, pos)
                            : actions.detonateAnchor(player, pos);
                }
            }
            case "pearl" -> {
                try {
                    float yaw = rest.length >= 1 ? Float.parseFloat(rest[0]) : player.getYRot();
                    float pitch = rest.length >= 2 ? Float.parseFloat(rest[1]) : -20.0F;
                    result = actions.throwPearl(player, yaw, pitch);
                } catch (NumberFormatException e) {
                    sender.sendMessage("§cpearl [yaw] [pitch]  (derece; pitch -90 yukarı)");
                    return;
                }
            }
            case "mine" -> {
                BlockPos pos = targetPos(sender, rest, false);
                if (pos == null) {
                    return;
                }
                aimed = pos;
                NpcActions.MinePlan plan = actions.planMining(player, pos);
                result = plan.result();
                if (result.success()) {
                    npc.startMining(pos, plan.face());
                }
            }
            case "xp" -> result = actions.throwXp(player);
            case "totem2" -> result = actions.totemToMainHand(player);
            case "totem" -> result = actions.totemToOffhand(player);
            case "eat" -> result = actions.eatGoldenApple(player);
            case "jump" -> {
                npc.requestJump();
                result = ActionResult.OK;
            }
            case "stop" -> {
                npc.setMovement(0, 0, false);
                result = ActionResult.OK;
            }
            case "move" -> {
                if (rest.length < 1) {
                    sender.sendMessage("§cmove <0-8> [tick] [sprint]  (1 = rakibe doğru, 5 = rakipten uzağa)");
                    return;
                }
                try {
                    int direction = Integer.parseInt(rest[0]);
                    int ticks = rest.length >= 2 ? Integer.parseInt(rest[1])
                            : plugin.getConfig().getInt("actions.default-move-ticks", 20);
                    boolean sprint = rest.length >= 3 && rest[2].equalsIgnoreCase("sprint");
                    npc.setMovement(direction, ticks, sprint);
                } catch (IllegalArgumentException e) {
                    sender.sendMessage("§cGeçersiz hareket: " + e.getMessage());
                    return;
                }
                result = ActionResult.OK;
            }
            default -> {
                sender.sendMessage("§cBilinmeyen aksiyon. Seçenekler: " + String.join(", ", ACTIONS));
                return;
            }
        }

        String message = npc.name() + " " + args[2] + ": " + result.reason();
        plugin.getLogger().info("[action] " + message);
        if (sender instanceof Player) {
            sender.sendMessage(result.success() ? "§a" + message
                    : "§c" + message + " §7- " + explain(result.reason(), player, aimed, actions));
        }
    }

    /** Turkish hint for a failed action, for people testing by hand. */
    private static String explain(String reason, ServerPlayer npc, @Nullable BlockPos aimed, NpcActions actions) {
        String core = reason.startsWith("obsidian_") ? reason.substring("obsidian_".length()) : reason;
        return switch (core) {
            case "out_of_reach" -> aimed == null ? "NPC'nin menzili dışında."
                    : String.format(Locale.ROOT, "Baktığın yer NPC'nin gözüne %.1f blok uzak, menzil %.1f. NPC'nin yanındaki bir yere bak.",
                            Math.sqrt(npc.getEyePosition().distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(aimed))),
                            actions.settings().blockReach());
            case "no_line_of_sight" -> "NPC oraya göremiyor, arada blok var.";
            case "occupied" -> "O blok zaten dolu.";
            case "entity_in_way" -> "Orada biri duruyor (sen ya da NPC).";
            case "no_support" -> "Havada, yanında koyulacak katı blok yok.";
            case "blocked" -> "Kristalin konacağı yer (bloğun üstü) boş değil ya da orada biri duruyor.";
            case "no_base" -> "Kristal sadece obsidyen/bedrock üstüne konur.";
            case "no_item", "no_crystal_item", "no_obsidian_item" -> "NPC'de o eşya yok. Önce /cai kit <isim>.";
            case "no_crystal" -> "NPC'nin 3 blok menzilinde kristal yok.";
            case "already_holding" -> "Offhand'de zaten totem var.";
            case "busy" -> "NPC zaten bir şey yiyor.";
            case "cooldown" -> "Bekleme süresi dolmadı (kılıç savuruşu ya da pearl).";
            case "no_anchor" -> "Orada (ya da menzilde) anchor yok.";
            case "not_charged" -> "Anchor boş, önce glowstone ile şarj et (charge).";
            case "full" -> "Anchor zaten dolu (4 şarj).";
            case "gone" -> "Hedef ölü/yok.";
            case "unbreakable" -> "Bu blok kırılamaz (bedrock/bariyer).";
            case "nothing_to_mine" -> "Orada kazılacak blok yok.";
            default -> "Vanilla reddetti (" + reason + ").";
        };
    }

    // ---------------------------------------------------------------- candidates / verify

    private void candidates(CommandSender sender, String label, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§c/" + label + " candidates <isim>");
            return;
        }
        CrystalNpc npc = findNpc(sender, args[1]);
        if (npc == null) {
            return;
        }
        if (!(npc.focus() instanceof net.minecraft.world.entity.LivingEntity target)) {
            sender.sendMessage("§cNPC'nin odaklandığı bir rakip yok (yakında başka oyuncu olmalı).");
            return;
        }
        long start = System.nanoTime();
        List<CrystalCandidate> list = candidateFinder().find(npc.handle(), target);
        double ms = (System.nanoTime() - start) / 1_000_000.0;
        sender.sendMessage(String.format(Locale.ROOT, "§b%d aday §7(rakip %s, %.2f ms)", list.size(),
                target.getName().getString(), ms));
        for (int i = 0; i < list.size(); i++) {
            CrystalCandidate c = list.get(i);
            sender.sendMessage(String.format(Locale.ROOT, "%s#%d §f%d %d %d §7%s| rakibe §c%.1f §7| bana §6%.1f §7| %s",
                    c.placeable() ? "§a" : "§8", i, c.base().getX(), c.base().getY(), c.base().getZ(),
                    c.hasBase() ? "" : "+obsidyen ", c.targetDamage(), c.selfDamage(),
                    c.placeable() ? "koyulabilir" : c.reason()));
        }
        showCandidateParticles(npc.bukkit().getWorld(), list);
    }

    private void showCandidateParticles(org.bukkit.World world, List<CrystalCandidate> list) {
        int ticks = plugin.getConfig().getInt("candidates.show-ticks", 100);
        new org.bukkit.scheduler.BukkitRunnable() {
            private int elapsed;

            @Override
            public void run() {
                if (elapsed >= ticks) {
                    cancel();
                    return;
                }
                for (CrystalCandidate c : list) {
                    org.bukkit.Particle particle = c.placeable()
                            ? org.bukkit.Particle.HAPPY_VILLAGER : org.bukkit.Particle.SMOKE;
                    world.spawnParticle(particle, c.crystalPos().x, c.crystalPos().y + 0.3, c.crystalPos().z,
                            4, 0.2, 0.2, 0.2, 0);
                }
                elapsed += 5;
            }
        }.runTaskTimer(plugin, 0L, 5L);
    }

    private void verify(CommandSender sender, String label, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§c/" + label + " verify damage|candidates|anchor [düzen sayısı]");
            return;
        }
        Consumer<DamageVerifier.Report> done = report -> {
            report.forEachLine(line -> plugin.getLogger().info("[verify] " + line));
            plugin.getLogger().info("[verify] " + report.summary());
            if (sender instanceof Player player && player.isOnline()) {
                player.sendMessage((report.allPassed() ? "§a" : "§c") + report.summary() + " §7(ayrıntı konsolda)");
            }
        };
        try {
            switch (args[1].toLowerCase(Locale.ROOT)) {
                case "damage" -> verifier.verifyDamage(done);
                case "anchor" -> verifier.verifyAnchor(actions(), done);
                case "candidates" -> verifier.verifyCandidates(candidateFinder(), actions(),
                        args.length >= 3 ? Integer.parseInt(args[2]) : 6, done);
                default -> {
                    sender.sendMessage("§cdamage ya da candidates yaz.");
                    return;
                }
            }
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.SEVERE, "Verification failed to start", e);
            sender.sendMessage("§cDoğrulama başlamadı: " + e + " (ayrıntı konsolda)");
            return;
        }
        sender.sendMessage("§7Doğrulama başladı, birkaç saniye sürer...");
    }

    // ---------------------------------------------------------------- arenas

    private void arena(CommandSender sender, String label, String[] args) {
        String sub = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "status";
        switch (sub) {
            case "start" -> {
                Integer count = null;
                if (args.length >= 3) {
                    try {
                        count = Integer.parseInt(args[2]);
                    } catch (NumberFormatException e) {
                        sender.sendMessage("§cSayı bekleniyordu: " + args[2]);
                        return;
                    }
                }
                try {
                    arenas.start(count);
                } catch (RuntimeException e) {
                    plugin.getLogger().log(Level.SEVERE, "Arena start failed", e);
                    sender.sendMessage("§cArenalar başlatılamadı: " + e.getMessage() + " (ayrıntı konsolda)");
                    return;
                }
                sender.sendMessage("§a" + arenas.arenas().size() + " arena başladı. /" + label + " arena tp 1 ile izle.");
            }
            case "stop" -> {
                arenas.stop();
                sender.sendMessage("§aArenalar durduruldu.");
            }
            case "status" -> arenas.status().forEach(sender::sendMessage);
            case "tp" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("§cSadece oyun içinden.");
                    return;
                }
                int number;
                try {
                    number = args.length >= 3 ? Integer.parseInt(args[2]) : 1;
                } catch (NumberFormatException e) {
                    sender.sendMessage("§cArena numarası bekleniyordu.");
                    return;
                }
                if (number < 1 || number > arenas.arenas().size()) {
                    sender.sendMessage("§cArena 1-" + arenas.arenas().size() + " arası olmalı (çalışıyor mu?).");
                    return;
                }
                org.bukkit.Location spot = arenas.arenas().get(number - 1).center().add(0, 6, -9);
                spot.setPitch(30);
                player.teleport(spot);
                player.setGameMode(org.bukkit.GameMode.SPECTATOR);
                sender.sendMessage("§7Seyirci moduna geçtin. Çıkınca /gamemode creative.");
            }
            default -> sender.sendMessage("§c/" + label + " arena start [sayı] | stop | status | tp <no>");
        }
    }

    /**
     * Target block from "x y z", or from what the sending player looks at.
     * For obsidian ({@code adjacent}) the looked-at face's neighbour is used; for crystals the block itself.
     */
    private @Nullable BlockPos targetPos(CommandSender sender, String[] rest, boolean adjacent) {
        if (rest.length >= 3) {
            try {
                return new BlockPos(Integer.parseInt(rest[0]), Integer.parseInt(rest[1]), Integer.parseInt(rest[2]));
            } catch (NumberFormatException e) {
                sender.sendMessage("§cKoordinatlar tam sayı olmalı.");
                return null;
            }
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cKonsoldan koordinat ver: x y z");
            return null;
        }
        Block block = player.getTargetBlockExact(TARGET_RANGE);
        BlockFace face = player.getTargetBlockFace(TARGET_RANGE);
        if (block == null || face == null) {
            sender.sendMessage("§c" + TARGET_RANGE + " blok içinde bir bloğa bak ya da x y z ver.");
            return null;
        }
        Block target = adjacent ? block.getRelative(face) : block;
        return new BlockPos(target.getX(), target.getY(), target.getZ());
    }

    /** Player vs. AI: /crystalai duel [latest|&lt;generation&gt;|stop] */
    private void duel(CommandSender sender, String label, String[] args) {
        var duels = ((com.crystalai.plugin.CrystalAIPlugin) plugin).duels();
        String what = args.length >= 2 ? args[1] : "latest";
        if (what.equalsIgnoreCase("stop")) {
            if (duels.running()) {
                duels.stop("Düello durduruldu");
            } else {
                sender.sendMessage("§7Süren düello yok.");
            }
            return;
        }
        // Optional 3rd argument: who fights (console tests use an NPC as the "human" side).
        Player player = args.length >= 3 ? org.bukkit.Bukkit.getPlayerExact(args[2])
                : sender instanceof Player self ? self : null;
        if (player == null) {
            sender.sendMessage("§cDüelloya bir oyuncu girmeli: oyunda /" + label + " duel [latest|nesil] ya da konsoldan "
                    + label + " duel <model> <oyuncu>");
            return;
        }
        duels.start(player, what);
    }

    /** Player vs. player on a duel site, both sides recorded: /crystalai pvp &lt;player1&gt; &lt;player2&gt; */
    private void pvp(CommandSender sender, String label, String[] args) {
        if (args.length < 3) {
            sender.sendMessage("§c/" + label + " pvp <oyuncu1> <oyuncu2>   (bitirmek: /" + label + " duel stop)");
            return;
        }
        Player first = org.bukkit.Bukkit.getPlayerExact(args[1]);
        Player second = org.bukkit.Bukkit.getPlayerExact(args[2]);
        if (first == null || second == null) {
            sender.sendMessage("§cOyuncu çevrimiçi değil: " + (first == null ? args[1] : args[2]));
            return;
        }
        ((com.crystalai.plugin.CrystalAIPlugin) plugin).duels().startPvp(sender, first, second);
    }

    /** Debug: what the model would see and may choose for {@code npc} against {@code opponent}. */
    private void context(CommandSender sender, String label, String[] args) {
        if (args.length < 3) {
            sender.sendMessage("§c/" + label + " context <npc> <rakip npc>");
            return;
        }
        CrystalNpc npc = findNpc(sender, args[1]);
        CrystalNpc opponent = findNpc(sender, args[2]);
        if (npc == null || opponent == null) {
            return;
        }
        var space = ((com.crystalai.plugin.CrystalAIPlugin) plugin).actionSpace();
        var context = space.observe(npc, opponent.handle());
        boolean[][] mask = space.mask(npc, context);
        var player = npc.handle();
        sender.sendMessage(String.format(Locale.ROOT, "§bmesafe %.2f | kristal aday %d (koyulabilir %d) | anchor aday %d (koyulabilir %d) | sahne anchor %d | kalkan %s | topblock %s | kazma %s",
                player.distanceTo(opponent.handle()), context.candidates().size(),
                context.candidates().stream().filter(c -> c.placeable()).count(),
                context.anchorCandidates().size(), context.anchorCandidates().stream().filter(c -> c.placeable()).count(),
                context.sceneAnchors().size(), context.shield(), context.topblock(),
                java.util.Arrays.toString(context.mineTargets())));
        if (!context.anchorCandidates().isEmpty()) {
            var first = context.anchorCandidates().get(0);
            var plan = actions().planBlock(player, first.pos(),
                    net.minecraft.world.level.block.Blocks.RESPAWN_ANCHOR.defaultBlockState());
            sender.sendMessage(String.format(Locale.ROOT, "§7ilk anchor adayı %s rakibe %.1f kendime %.1f | plan: %s | elde anchor: %s",
                    first.pos().toShortString(), first.targetDamage(), first.selfDamage(), plan.result().reason(),
                    NpcActions.hasItem(player, net.minecraft.world.item.Items.RESPAWN_ANCHOR)));
        }
        String[] names = {"move", "jump", "sprint", "attack", "crystal", "explode", "anchor", "anchor_use", "block",
                "pearl", "util", "mine"};
        StringBuilder open = new StringBuilder("§7açık seçenekler:");
        for (int b = 0; b < mask.length; b++) {
            int count = 0;
            for (int i = 1; i < mask[b].length; i++) {
                count += mask[b][i] ? 1 : 0;
            }
            open.append(' ').append(names[b]).append('=').append(count);
        }
        sender.sendMessage(open.toString());
        sender.sendMessage("§7kılıç: " + actions().checkSwordHit(player, opponent.handle()).reason());
    }

    private @Nullable CrystalNpc findNpc(CommandSender sender, String name) {
        CrystalNpc npc = npcs.byName(name);
        if (npc == null) {
            sender.sendMessage("§cBöyle bir NPC yok: " + name);
        }
        return npc;
    }

    // ---------------------------------------------------------------- tab completion

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String[] args) {
        String sub = args[0].toLowerCase(Locale.ROOT);
        List<String> options = switch (args.length) {
            case 1 -> List.of("spawn", "list", "remove", "kit", "do", "candidates", "verify", "arena", "duel", "pvp");
            case 2 -> switch (sub) {
                case "spawn" -> List.of("latest", "0");
                case "remove", "kit" -> Stream.concat(Stream.of("all"), npcNames()).toList();
                case "do", "candidates" -> npcNames().toList();
                case "verify" -> List.of("damage", "candidates", "anchor");
                case "arena" -> List.of("start", "stop", "status", "tp");
                default -> List.of();
            };
            case 3 -> switch (sub) {
                case "spawn" -> DIFFICULTIES;
                case "do" -> ACTIONS;
                default -> List.of();
            };
            case 4 -> sub.equals("do") && args[2].equalsIgnoreCase("move")
                    ? List.of("0", "1", "2", "3", "4", "5", "6", "7", "8") : List.of();
            case 6 -> sub.equals("do") && args[2].equalsIgnoreCase("move") ? List.of("sprint") : List.of();
            default -> List.of();
        };
        return StringUtil.copyPartialMatches(args[args.length - 1], options, new ArrayList<>());
    }

    private Stream<String> npcNames() {
        return npcs.all().stream().map(CrystalNpc::name);
    }
}
