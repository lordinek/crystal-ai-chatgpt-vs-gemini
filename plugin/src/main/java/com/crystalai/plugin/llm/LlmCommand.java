package com.crystalai.plugin.llm;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

/**
 * /yzmac baslat [mock|gercek] [1|3|5|7] [arena|sirayla|rastgele]  start a series (defaults from llm.yml, box arena)
 * /yzmac durdur                           stop it, NPCs removed, summary written
 * /yzmac durum                            score, models, call counts, latency, cost
 * /yzmac izle [arena]                     spectator mode at the arena's camera
 * /yzmac yenile                           re-read llm.yml and arenalar.yml (between series)
 * /yzmac arena nokta <isim> <1|2|kamera>  save where you stand (creates the arena)
 * /yzmac arena not <isim> <metin...>      what both models are told about the arena
 * /yzmac arena liste | git <isim> | sil <isim>
 * /yzmac antrenman [mock|gercek] <maç sayısı> [arena|sirayla|rastgele]  exactly N matches, notebooks rewritten after each
 * /yzmac defter [sifirla]                 show both lesson notebooks / empty both
 * /yzmac kapis [oyuncu] <chatgpt|gemini> [arena|sirayla|rastgele] [1|3|5] [mock]  a player against one model
 * /yzmac egitim [mock|gercek] <nesil> [maç/nesil] [hizli]  both models train their fighting cards
 * /yzmac kart [sifirla]                   show both cards and their last generations / back to untrained
 */
public final class LlmCommand implements TabExecutor {

    /** Arena names become YAML keys and command words. */
    private static final String NAME = "[a-z0-9_-]{1,24}";
    private static final List<String> RESERVED = List.of(ArenaStore.BOX, "sirayla", "rastgele", "mock", "gercek");

    private final LlmMatchManager matches;
    private final HumanDuel duel;
    private final CardTraining training;

    public LlmCommand(LlmMatchManager matches, HumanDuel duel, CardTraining training) {
        this.matches = matches;
        this.duel = duel;
        this.training = training;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "durum" : args[0].toLowerCase(Locale.ROOT);
        if ((sub.equals("baslat") || sub.equals("antrenman") || sub.equals("kapis") || sub.equals("egitim"))
                && (duel.busy() || training.busy())) {
            sender.sendMessage("§cÖnce süren kapışmayı/eğitimi bitir (/yzmac durdur).");
            return true;
        }
        switch (sub) {
            case "baslat" -> {
                Boolean mock = null;
                Integer bestOf = null;
                String arena = null;
                for (int i = 1; i < args.length; i++) {
                    String a = args[i].toLowerCase(Locale.ROOT);
                    if (a.equals("mock")) {
                        mock = true;
                    } else if (a.equals("gercek")) {
                        mock = false;
                    } else if (a.matches("\\d+")) {
                        bestOf = Integer.parseInt(a);
                    } else if (a.matches(NAME)) {
                        arena = a;
                    } else {
                        sender.sendMessage("§cBilinmeyen: " + args[i] + " (mock | gercek | 3 | 5 | arena adı | sirayla | rastgele)");
                        return true;
                    }
                }
                String error = matches.start(mock, bestOf, arena);
                sender.sendMessage(error == null ? "§aSeri başladı." : "§c" + error);
            }
            case "durdur" -> {
                if (training.stop("komut")) {
                    sender.sendMessage("§eEğitim durduruldu (biten nesiller kaydedildi).");
                } else if (duel.stop("komut")) {
                    sender.sendMessage("§eKapışma durduruldu.");
                } else {
                    sender.sendMessage(matches.stop("komut") ? "§eSeri durduruldu." : "§7Süren seri yok.");
                }
            }
            case "egitim" -> {
                Boolean mock = null;
                List<Integer> numbers = new ArrayList<>();
                boolean fast = false;
                for (int i = 1; i < args.length; i++) {
                    String a = args[i].toLowerCase(Locale.ROOT);
                    if (a.equals("mock")) {
                        mock = true;
                    } else if (a.equals("gercek")) {
                        mock = false;
                    } else if (a.equals("hizli")) {
                        fast = true;
                    } else if (a.matches("\\d+")) {
                        numbers.add(Integer.parseInt(a));
                    } else {
                        sender.sendMessage("§cBilinmeyen: " + args[i]);
                        return true;
                    }
                }
                if (numbers.isEmpty()) {
                    sender.sendMessage("§7/yzmac egitim [mock|gercek] <nesil> [maç/nesil, varsayılan 3] [hizli]");
                    return true;
                }
                int perGen = numbers.size() > 1 ? numbers.get(1) : 3;
                String error = training.start(mock, numbers.get(0), perGen, fast);
                sender.sendMessage(error == null ? "§aEğitim başladı. İzlemek için: /yzmac izle egitim" : "§c" + error);
            }
            case "kart" -> {
                if (args.length > 1 && args[1].equalsIgnoreCase("sifirla")) {
                    sender.sendMessage("§e" + training.reset());
                } else {
                    training.describe().forEach(sender::sendMessage);
                }
            }
            case "kapis" -> {
                // An admin can name another online player: /yzmac kapis <oyuncu> <chatgpt|gemini> ...
                Player player;
                int m = 1;
                if (args.length >= 3 && !args[1].equalsIgnoreCase("chatgpt") && !args[1].equalsIgnoreCase("gemini")) {
                    player = Bukkit.getPlayer(args[1]);
                    if (player == null) {
                        sender.sendMessage("§cOyuncu çevrimiçi değil: " + args[1]);
                        return true;
                    }
                    m = 2;
                } else if (sender instanceof Player self) {
                    player = self;
                } else {
                    sender.sendMessage("Sadece oyuncu.");
                    return true;
                }
                if (args.length <= m) {
                    sender.sendMessage("§7/yzmac kapis [oyuncu] <chatgpt|gemini> [arena|sirayla|rastgele] [1|3|5] [mock]");
                    return true;
                }
                String who = args[m];
                String arena = null;
                int bestOf = 3;
                Boolean mock = null;
                for (int i = m + 1; i < args.length; i++) {
                    String a = args[i].toLowerCase(Locale.ROOT);
                    if (a.equals("mock")) {
                        mock = true;
                    } else if (a.equals("gercek")) {
                        mock = false;
                    } else if (a.matches("\\d+")) {
                        bestOf = Integer.parseInt(a);
                    } else if (a.matches(NAME)) {
                        arena = a;
                    } else {
                        sender.sendMessage("§cBilinmeyen: " + args[i]);
                        return true;
                    }
                }
                String error = duel.start(player, who, arena, bestOf, mock);
                sender.sendMessage(error == null ? "§aKapışma başladı: " + player.getName() : "§c" + error);
                if (error == null && sender instanceof Player admin && !admin.equals(player)) {
                    duel.watch(admin);
                }
            }
            case "durum" -> matches.status().forEach(line -> sender.sendMessage("§7" + line));
            case "izle" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Sadece oyuncu.");
                    return true;
                }
                Location camera;
                if (args.length > 1 && args[1].equalsIgnoreCase("egitim")) {
                    camera = training.camera();
                    if (camera == null) {
                        sender.sendMessage("§cSüren eğitim yok.");
                        return true;
                    }
                    player.setGameMode(GameMode.SPECTATOR);
                    player.teleport(camera);
                    return true;
                }
                try {
                    camera = matches.camera(args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : null);
                } catch (IllegalStateException e) {
                    sender.sendMessage("§c" + e.getMessage());
                    return true;
                }
                player.setGameMode(GameMode.SPECTATOR);
                player.teleport(camera);
            }
            case "yenile" -> sender.sendMessage("§7" + matches.reload());
            case "antrenman" -> {
                Boolean mock = null;
                Integer count = null;
                String arena = null;
                for (int i = 1; i < args.length; i++) {
                    String a = args[i].toLowerCase(Locale.ROOT);
                    if (a.equals("mock")) {
                        mock = true;
                    } else if (a.equals("gercek")) {
                        mock = false;
                    } else if (a.matches("\\d+")) {
                        count = Integer.parseInt(a);
                    } else if (a.matches(NAME)) {
                        arena = a;
                    } else {
                        sender.sendMessage("§cBilinmeyen: " + args[i]);
                        return true;
                    }
                }
                if (count == null) {
                    sender.sendMessage("§7/yzmac antrenman [mock|gercek] <maç sayısı> [arena|sirayla|rastgele]");
                    return true;
                }
                String error = matches.startTraining(mock, count, arena);
                sender.sendMessage(error == null ? "§aAntrenman başladı: " + count + " maç." : "§c" + error);
            }
            case "defter" -> {
                if (args.length > 1 && args[1].equalsIgnoreCase("sifirla")) {
                    sender.sendMessage("§e" + matches.notebookReset());
                } else {
                    matches.notebookLines().forEach(sender::sendMessage);
                }
            }
            case "arena" -> arena(sender, args);
            default -> sender.sendMessage("§7/yzmac <baslat [mock|gercek] [3|5] [arena|sirayla|rastgele]|durdur|durum|izle [arena]|yenile|arena>");
        }
        return true;
    }

    private void arena(CommandSender sender, String[] args) {
        String action = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "liste";
        if (action.equals("liste")) {
            sender.sendMessage("§6Arenalar §7(/yzmac baslat gercek 5 <isim|sirayla|rastgele>):");
            matches.arenaList().forEach(line -> sender.sendMessage("§7- " + line));
            return;
        }
        if (args.length < 3) {
            sender.sendMessage("§7/yzmac arena <nokta <isim> <1|2|kamera>|not <isim> <metin>|git <isim>|sil <isim>|liste>");
            return;
        }
        String name = args[2].toLowerCase(Locale.ROOT);
        if (!name.matches(NAME) || RESERVED.contains(name)) {
            sender.sendMessage("§cArena adı: küçük harf, rakam, _ ya da - (en fazla 24); "
                    + String.join(", ", RESERVED) + " olamaz.");
            return;
        }
        switch (action) {
            case "nokta" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Sadece oyuncu (durduğun yer kaydedilir).");
                    return;
                }
                if (args.length < 4) {
                    sender.sendMessage("§7/yzmac arena nokta " + name + " <1|2|kamera>");
                    return;
                }
                sender.sendMessage(matches.arenaPoint(name, args[3].toLowerCase(Locale.ROOT), player.getLocation()));
            }
            case "not" -> sender.sendMessage(matches.arenaNote(name,
                    String.join(" ", Arrays.copyOfRange(args, 3, args.length)).trim()));
            case "git" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Sadece oyuncu.");
                    return;
                }
                Location location = matches.arenaLocation(name);
                if (location == null) {
                    sender.sendMessage("§cArena yok ya da noktası/dünyası yok: " + name);
                    return;
                }
                player.teleport(location);
            }
            case "sil" -> sender.sendMessage(matches.arenaRemove(name));
            default -> sender.sendMessage("§cBilinmeyen: " + action + " (nokta | not | git | sil | liste)");
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) {
            return filter(List.of("baslat", "egitim", "kart", "kapis", "antrenman", "defter", "durdur", "durum", "izle", "yenile",
                    "arena"), args[0]);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("kapis")) {
            if (args.length == 2) {
                return filter(List.of("chatgpt", "gemini"), args[1]);
            }
            List<String> options = new ArrayList<>(List.of("1", "3", "5", "mock", ArenaStore.BOX, "sirayla", "rastgele"));
            options.addAll(matches.arenaNames());
            return filter(options, args[args.length - 1]);
        }
        if (sub.equals("defter") && args.length == 2) {
            return filter(List.of("sifirla"), args[1]);
        }
        if (sub.equals("baslat") || sub.equals("antrenman")) {
            List<String> options = new ArrayList<>(List.of("mock", "gercek", "3", "5", ArenaStore.BOX, "sirayla", "rastgele"));
            options.addAll(matches.arenaNames());
            return filter(options, args[args.length - 1]);
        }
        if (sub.equals("kart") && args.length == 2) {
            return filter(List.of("sifirla"), args[1]);
        }
        if (sub.equals("egitim")) {
            return filter(List.of("gercek", "mock", "10", "3", "hizli"), args[args.length - 1]);
        }
        if (sub.equals("izle") && args.length == 2) {
            List<String> options = new ArrayList<>(List.of(ArenaStore.BOX, "egitim"));
            options.addAll(matches.arenaNames());
            return filter(options, args[1]);
        }
        if (sub.equals("arena")) {
            if (args.length == 2) {
                return filter(List.of("nokta", "not", "git", "sil", "liste"), args[1]);
            }
            if (args.length == 3) {
                return filter(matches.arenaNames(), args[2]);
            }
            if (args.length == 4 && args[1].equalsIgnoreCase("nokta")) {
                return filter(List.of("1", "2", "kamera"), args[3]);
            }
        }
        return List.of();
    }

    private static List<String> filter(List<String> options, String prefix) {
        return options.stream().filter(o -> o.startsWith(prefix.toLowerCase(Locale.ROOT))).toList();
    }
}
