package com.crystalai.plugin.verify;

import com.crystalai.plugin.action.ActionResult;
import com.crystalai.plugin.action.NpcActions;
import com.crystalai.plugin.combat.CandidateFinder;
import com.crystalai.plugin.combat.CrystalCandidate;
import com.crystalai.plugin.combat.DamageCalculator;
import com.crystalai.plugin.npc.CrystalNpc;
import com.crystalai.plugin.npc.NpcManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Difficulty;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * In-game check that {@link DamageCalculator} and {@link CandidateFinder} agree with real vanilla
 * explosions. Builds scenes in an empty area, predicts, detonates real crystals and compares with
 * the damage the server actually applies (EntityDamageEvent final damage and health lost).
 * Everything runs synchronously inside one command.
 */
public final class DamageVerifier implements Listener {

    private static final double TOLERANCE = 0.02;
    private static final int AREA = 8;
    private static final int OFFSET_FROM_SPAWN = 64;

    private final JavaPlugin plugin;
    private final NpcManager npcs;

    private final Set<UUID> capturing = new HashSet<>();
    private final Map<UUID, Double> captured = new HashMap<>();
    private final Set<UUID> cancelled = new HashSet<>();

    public DamageVerifier(JavaPlugin plugin, NpcManager npcs) {
        this.plugin = plugin;
        this.npcs = npcs;
    }

    /** Records the damage vanilla computed; cancels lethal hits so the test NPCs survive. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageEvent event) {
        UUID id = event.getEntity().getUniqueId();
        if (!capturing.contains(id) || !(event.getEntity() instanceof Player player)) {
            return;
        }
        captured.merge(id, event.getFinalDamage(), Double::sum);
        if (event.getFinalDamage() >= player.getHealth() + player.getAbsorptionAmount()) {
            event.setCancelled(true);
            cancelled.add(id);
        }
    }

    // ================================================================ damage

    private record Scenario(String name, int dx, int dy, int dz, Wall wall, boolean armor,
                            Difficulty difficulty, int resistance) {
    }

    private enum Wall { NONE, LOW, FULL }

    /** Builds every scenario, then runs them over the next ticks; {@code done} gets the report. */
    public void verifyDamage(Consumer<Report> done) {
        List<Scenario> scenarios = new ArrayList<>();
        int[][] offsets = {
                {1, 0, 0}, {2, 0, 0}, {2, 0, 2}, {3, 0, 1}, {4, 0, 0}, {5, 0, 3}, {0, 0, 3},
                {2, -1, 1}, {2, 1, 0}, {3, 2, 1}, {6, 0, 0}, {8, 0, 4}, {11, 0, 0},
        };
        for (Difficulty difficulty : List.of(Difficulty.EASY, Difficulty.NORMAL, Difficulty.HARD)) {
            for (boolean armor : List.of(false, true)) {
                for (int[] o : offsets) {
                    scenarios.add(new Scenario(String.format("(%d,%d,%d)", o[0], o[1], o[2]),
                            o[0], o[1], o[2], Wall.NONE, armor, difficulty, -1));
                }
                scenarios.add(new Scenario("(4,0,0) tek sütun", 4, 0, 0, Wall.LOW, armor, difficulty, -1));
                scenarios.add(new Scenario("(4,0,0) tam duvar", 4, 0, 0, Wall.FULL, armor, difficulty, -1));
                scenarios.add(new Scenario("(3,0,3) tek sütun", 3, 0, 3, Wall.LOW, armor, difficulty, -1));
            }
        }
        scenarios.add(new Scenario("(2,0,0) direnç I", 2, 0, 0, Wall.NONE, true, Difficulty.HARD, 0));
        scenarios.add(new Scenario("(2,0,0) direnç II", 2, 0, 0, Wall.NONE, false, Difficulty.HARD, 1));

        Scene scene = new Scene();
        Report report = new Report("hasar");
        List<Step> steps = new ArrayList<>();
        for (Scenario scenario : scenarios) {
            steps.add(new Step(() -> {
                scene.reset(scenario.armor(), scenario.difficulty(), -3);
                if (scenario.resistance() >= 0) {
                    scene.target.bukkit().addPotionEffect(
                            new PotionEffect(PotionEffectType.RESISTANCE, 200, scenario.resistance()));
                }
                scene.buildWall(scenario);
            }, () -> {
                Hit hit = scene.detonate(scene.feet.offset(scenario.dx(), scenario.dy() - 1, scenario.dz()), scene.target);
                report.add(String.format(Locale.ROOT, "%-8s %-6s %-22s", scenario.difficulty(),
                        scenario.armor() ? "zırh" : "çıplak", scenario.name()), hit.predicted, hit.actual, hit.healthLoss);
            }));
        }
        // Hurt cooldown: a second crystal while invulnerableTime > 10 only deals the excess over the last hit.
        for (int[] second : new int[][] {{2, 0, 0}, {8, 0, 4}}) {
            steps.add(new Step(() -> scene.reset(true, Difficulty.HARD, -3), () -> {
                Hit first = scene.detonate(scene.feet.offset(6, -1, 0), scene.target);
                report.add("HARD zırh cooldown 1. (6,0,0)", first.predicted, first.actual, first.healthLoss);
                report.note(String.format(Locale.ROOT, "   cooldown: invulnerableTime=%d lastHurt=%.3f",
                        scene.target.handle().invulnerableTime, scene.target.handle().lastHurt));
                scene.clearDrops();
                Hit hit = scene.detonate(scene.feet.offset(second[0], -1, second[2]), scene.target);
                report.add(String.format("HARD zırh cooldown 2. (%d,0,%d)", second[0], second[2]),
                        hit.predicted, hit.actual, hit.healthLoss);
            }));
        }
        run(scene, steps, report, done);
    }

    // ================================================================ anchor

    /**
     * Respawn anchor explosions (v2): the attacker NPC really detonates a 4-charge anchor through
     * {@link NpcActions#detonateAnchor}, standing 3 blocks behind it; the target's damage is compared
     * with {@link DamageCalculator#anchorDamage}.
     */
    public void verifyAnchor(NpcActions actions, Consumer<Report> done) {
        List<Scenario> scenarios = new ArrayList<>();
        int[][] offsets = {
                {1, 0, 0}, {2, 0, 0}, {2, 0, 2}, {3, 0, 1}, {4, 0, 0}, {5, 0, 3}, {0, 0, 3},
                {2, -1, 1}, {2, 1, 0}, {3, 2, 1}, {6, 0, 0}, {8, 0, 4}, {-2, 0, -2}, {0, 0, -2},
        };
        for (Difficulty difficulty : List.of(Difficulty.NORMAL, Difficulty.HARD)) {
            for (boolean armor : List.of(false, true)) {
                for (int[] o : offsets) {
                    scenarios.add(new Scenario(String.format("(%d,%d,%d)", o[0], o[1], o[2]),
                            o[0], o[1], o[2], Wall.NONE, armor, difficulty, -1));
                }
                scenarios.add(new Scenario("(4,0,0) tek sütun", 4, 0, 0, Wall.LOW, armor, difficulty, -1));
                scenarios.add(new Scenario("(4,0,0) tam duvar", 4, 0, 0, Wall.FULL, armor, difficulty, -1));
            }
        }
        Scene scene = new Scene();
        Report report = new Report("anchor");
        List<Step> steps = new ArrayList<>();
        for (Scenario scenario : scenarios) {
            BlockPos anchor = scene.feet.offset(scenario.dx(), scenario.dy(), scenario.dz());
            steps.add(new Step(() -> {
                scene.reset(scenario.armor(), scenario.difficulty(), -3);
                scene.buildWall(scenario);
                scene.level.setBlock(anchor, net.minecraft.world.level.block.Blocks.RESPAWN_ANCHOR.defaultBlockState()
                        .setValue(net.minecraft.world.level.block.RespawnAnchorBlock.CHARGE, 4), 3);
                // Attacker 3 blocks south of the anchor, on the floor, facing it.
                scene.attacker.bukkit().teleport(scene.center(new BlockPos(anchor.getX(), scene.feet.getY(), anchor.getZ() + 3)));
            }, () -> {
                CrystalNpc target = scene.target;
                float predicted = DamageCalculator.anchorDamage(target.handle(), anchor, true);
                double before = scene.hp(target);
                capturing.add(target.handle().getUUID());
                capturing.add(scene.attacker.handle().getUUID());
                captured.clear();
                cancelled.clear();
                ActionResult result;
                try {
                    result = actions.detonateAnchor(scene.attacker.handle(), anchor);
                } finally {
                    capturing.clear();
                }
                String label = String.format(Locale.ROOT, "%-8s %-6s %-18s", scenario.difficulty(),
                        scenario.armor() ? "zırh" : "çıplak", scenario.name());
                if (!result.success()) {
                    report.fail(label + ": patlatılamadı (" + result.reason() + ")");
                    return;
                }
                UUID id = target.handle().getUUID();
                double loss = cancelled.contains(id) ? Double.NaN : before - scene.hp(target);
                report.add(label, predicted, captured.getOrDefault(id, 0.0), loss);
            }));
        }
        run(scene, steps, report, done);
    }

    // ================================================================ candidates

    public void verifyCandidates(CandidateFinder finder, NpcActions actions, int layouts, Consumer<Report> done) {
        Scene scene = new Scene();
        Report report = new Report("aday");
        List<Step> steps = new ArrayList<>();
        for (int layout = 0; layout < layouts; layout++) {
            int layoutIndex = layout;
            long seed = 1000L + layout;
            // Attacker 3, 5 or 7 blocks away: the farther layouts produce spots out of reach.
            int attackerX = -(3 + 2 * (layout % 3));
            List<List<CrystalCandidate>> survey = new ArrayList<>();
            Runnable setup = () -> {
                scene.reset(true, Difficulty.HARD, attackerX);
                scene.buildLayout(seed);
            };
            steps.add(new Step(setup, () -> {
                List<CrystalCandidate> candidates = finder.find(scene.attacker.handle(), scene.target.handle());
                survey.add(candidates);
                report.note(String.format(Locale.ROOT,
                        "düzen %d (saldıran %d blok uzakta): %d aday, %d tanesi şu an koyulabilir",
                        layoutIndex, -attackerX, candidates.size(),
                        candidates.stream().filter(CrystalCandidate::placeable).count()));
            }));
            for (int i = 0; i < 8; i++) {
                int index = i;
                steps.add(new Step(setup, () -> {
                    List<CrystalCandidate> first = survey.get(0);
                    if (index >= first.size()) {
                        return;
                    }
                    List<CrystalCandidate> again = finder.find(scene.attacker.handle(), scene.target.handle());
                    if (index >= again.size() || !again.get(index).base().equals(first.get(index).base())) {
                        report.fail("düzen " + layoutIndex + " aday " + index
                                + ": aynı sahnede farklı sonuç (deterministik değil)");
                        return;
                    }
                    CrystalCandidate candidate = again.get(index);
                    String label = String.format(Locale.ROOT, "düzen %d #%d %s%s", layoutIndex, index,
                            shortPos(candidate.base(), scene.feet), candidate.hasBase() ? "" : " +obs");
                    ActionResult placed = actions.placeCrystal(scene.attacker.handle(), candidate.base(), true);
                    if (placed.success() != candidate.placeable()) {
                        report.fail(label + ": koyulabilir=" + candidate.placeable() + " dendi ama aksiyon " + placed.reason());
                        return;
                    }
                    if (!placed.success()) {
                        report.pass(label + ": koyulamaz (" + candidate.reason() + "), aksiyon da reddetti");
                        return;
                    }
                    EndCrystal crystal = scene.crystalAt(candidate.crystalPos());
                    if (crystal == null) {
                        report.fail(label + ": aksiyon ok ama kristal yok");
                        return;
                    }
                    Hit[] hits = scene.explode(crystal, scene.target, scene.attacker);
                    report.add(label + " rakip ", hits[0].predicted, hits[0].actual, hits[0].healthLoss);
                    report.add(label + " kendim", hits[1].predicted, hits[1].actual, hits[1].healthLoss);
                    report.note(String.format(Locale.ROOT, "   %s: aday ön hesabı rakip %.2f / kendim %.2f",
                            label, candidate.targetDamage(), candidate.selfDamage()));
                }));
            }
        }
        run(scene, steps, report, done);
    }

    /** One test step: setup runs in one tick, check in the next (equipment attributes apply on tick). */
    private record Step(Runnable setup, Runnable check) {
    }

    private void run(Scene scene, List<Step> steps, Report report, Consumer<Report> done) {
        new BukkitRunnable() {
            private int index;
            private boolean setUp;

            @Override
            public void run() {
                try {
                    if (index >= steps.size()) {
                        finish();
                        return;
                    }
                    Step step = steps.get(index);
                    if (!setUp) {
                        step.setup().run();
                        setUp = true;
                    } else {
                        step.check().run();
                        setUp = false;
                        index++;
                    }
                } catch (RuntimeException e) {
                    plugin.getLogger().log(Level.SEVERE, "Verification step " + index + " crashed", e);
                    report.fail("adım " + index + " çöktü: " + e);
                    finish();
                }
            }

            private void finish() {
                cancel();
                try {
                    scene.close();
                } finally {
                    done.accept(report);
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    private static String shortPos(BlockPos base, BlockPos feet) {
        return String.format("(%d,%d,%d)", base.getX() - feet.getX(), base.getY() + 1 - feet.getY(), base.getZ() - feet.getZ());
    }

    // ================================================================ scene

    private record Hit(float predicted, double actual, double healthLoss) {
    }

    /** Test area next to spawn with a target NPC and an attacker NPC (the explosion's owner). */
    private final class Scene {
        final World world = org.bukkit.Bukkit.getWorlds().get(0);
        final ServerLevel level = NpcManager.levelOf(world);
        final Difficulty originalDifficulty = world.getDifficulty();
        final BlockPos feet;
        final CrystalNpc target;
        final CrystalNpc attacker;
        int attackerOffsetX = -3;

        Scene() {
            Location spawn = world.getSpawnLocation();
            int x = spawn.getBlockX() + OFFSET_FROM_SPAWN;
            int z = spawn.getBlockZ();
            int y = world.getHighestBlockYAt(x, z) + 1;
            feet = new BlockPos(x, y, z);
            for (int cx = (x - AREA - 16) >> 4; cx <= (x + AREA + 16) >> 4; cx++) {
                for (int cz = (z - AREA - 16) >> 4; cz <= (z + AREA + 16) >> 4; cz++) {
                    world.getChunkAt(cx, cz).setForceLoaded(true);
                }
            }
            target = npcs.spawn(center(feet), 0, "orta");
            attacker = npcs.spawn(center(feet.offset(-3, 0, 0)), 0, "orta");
        }

        Location center(BlockPos pos) {
            return new Location(world, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        }

        void reset(boolean armor, Difficulty difficulty, int attackerX) {
            world.setDifficulty(difficulty);
            clearArea();
            attackerOffsetX = attackerX;
            resetNpc(target, feet, armor);
            resetNpc(attacker, feet.offset(attackerX, 0, 0), true);
        }

        private void resetNpc(CrystalNpc npc, BlockPos at, boolean armor) {
            Player player = npc.bukkit();
            ServerPlayer handle = npc.handle();
            player.teleport(center(at));
            player.setVelocity(new org.bukkit.util.Vector());
            player.setFallDistance(0);
            player.getActivePotionEffects().forEach(effect -> player.removePotionEffect(effect.getType()));
            if (armor) {
                npcs.combatKit().apply(player.getInventory());
            } else {
                player.getInventory().clear();
            }
            player.setHealth(20.0);
            player.setAbsorptionAmount(0.0);
            player.setFoodLevel(20);
            player.setFireTicks(0); // anchors leave fire
            player.getInventory().setHeldItemSlot(0);
            handle.invulnerableTime = 0;
            handle.lastHurt = 0.0F;
            handle.hurtTime = 0;
        }

        void clearArea() {
            int y0 = feet.getY();
            for (int x = -AREA; x <= AREA; x++) {
                for (int z = -AREA; z <= AREA; z++) {
                    for (int y = -3; y <= 6; y++) {
                        Material material = y >= 0 ? Material.AIR : y == -1 ? Material.GRASS_BLOCK : Material.DIRT;
                        world.getBlockAt(feet.getX() + x, y0 + y, feet.getZ() + z).setType(material, false);
                    }
                }
            }
            clearDrops();
            for (EndCrystal crystal : level.getEntitiesOfClass(EndCrystal.class, areaBox())) {
                crystal.discard();
            }
        }

        void clearDrops() {
            world.getNearbyEntities(center(feet), AREA + 4, 12, AREA + 4, entity -> entity instanceof Item)
                    .forEach(org.bukkit.entity.Entity::remove);
        }

        AABB areaBox() {
            return new AABB(feet).inflate(AREA + 4);
        }

        void buildWall(Scenario scenario) {
            if (scenario.wall() == Wall.NONE) {
                return;
            }
            // Full wall halfway; the low column a quarter of the way, close to the target, so rays
            // from the upper body pass over it (partial exposure).
            float fraction = scenario.wall() == Wall.LOW ? 4.0f : 2.0f;
            int wx = feet.getX() + Math.round(scenario.dx() / fraction);
            int wz = feet.getZ() + Math.round(scenario.dz() / fraction);
            int top = scenario.wall() == Wall.LOW ? 0 : 2;
            for (int y = 0; y <= top; y++) {
                int width = scenario.wall() == Wall.LOW ? 0 : 1; // single column lets part of the rays pass
                for (int side = -width; side <= width; side++) {
                    boolean alongX = Math.abs(scenario.dx()) >= Math.abs(scenario.dz());
                    int bx = alongX ? wx : wx + side;
                    int bz = alongX ? wz + side : wz;
                    world.getBlockAt(bx, feet.getY() + y, bz).setType(Material.OBSIDIAN, false);
                }
            }
        }

        /** Random obsidian/bedrock bits and a couple of walls around the target. */
        void buildLayout(long seed) {
            Random random = new Random(seed);
            for (int i = 0; i < 10; i++) {
                int x = random.nextInt(11) - 5;
                int z = random.nextInt(11) - 5;
                if (Math.abs(x) <= 1 && Math.abs(z) <= 1 || (x == attackerOffsetX && z == 0)) {
                    continue; // keep the NPCs' own blocks free
                }
                Material material = random.nextInt(5) == 0 ? Material.BEDROCK : Material.OBSIDIAN;
                world.getBlockAt(feet.getX() + x, feet.getY() - 1, feet.getZ() + z).setType(material, false);
            }
            for (int i = 0; i < 2; i++) {
                int x = random.nextInt(9) - 4;
                int z = random.nextInt(9) - 4;
                if (Math.abs(x) <= 1 && Math.abs(z) <= 1
                        || (x >= attackerOffsetX - 1 && x <= attackerOffsetX + 1 && Math.abs(z) <= 1)) {
                    continue;
                }
                int height = 1 + random.nextInt(2);
                for (int y = 0; y < height; y++) {
                    world.getBlockAt(feet.getX() + x, feet.getY() + y, feet.getZ() + z).setType(Material.OBSIDIAN, false);
                }
            }
        }

        /** Places a crystal on {@code base} (obsidian set directly) and detonates it; measures {@code victim}. */
        Hit detonate(BlockPos base, CrystalNpc victim) {
            world.getBlockAt(base.getX(), base.getY(), base.getZ()).setType(Material.OBSIDIAN, false);
            world.getBlockAt(base.getX(), base.getY() + 1, base.getZ()).setType(Material.AIR, false);
            world.getBlockAt(base.getX(), base.getY() + 2, base.getZ()).setType(Material.AIR, false);
            Vec3 pos = DamageCalculator.crystalPosition(base);
            EndCrystal crystal = new EndCrystal(level, pos.x, pos.y, pos.z);
            crystal.setShowBottom(false);
            level.addFreshEntity(crystal);
            return explode(crystal, victim)[0];
        }

        Hit[] explode(EndCrystal crystal, CrystalNpc... victims) {
            Vec3 center = crystal.position();
            float[] predicted = new float[victims.length];
            double[] before = new double[victims.length];
            for (int i = 0; i < victims.length; i++) {
                predicted[i] = DamageCalculator.crystalDamage(victims[i].handle(), center, true);
                before[i] = hp(victims[i]);
                capturing.add(victims[i].handle().getUUID());
            }
            capturing.add(attacker.handle().getUUID()); // never let the owner NPC die mid-run
            captured.clear();
            cancelled.clear();
            try {
                crystal.hurt(level.damageSources().playerAttack(attacker.handle()), 1.0F);
            } finally {
                capturing.clear();
            }
            Hit[] hits = new Hit[victims.length];
            for (int i = 0; i < victims.length; i++) {
                UUID id = victims[i].handle().getUUID();
                double actual = captured.getOrDefault(id, 0.0);
                double loss = cancelled.contains(id) ? Double.NaN : before[i] - hp(victims[i]);
                hits[i] = new Hit(predicted[i], actual, loss);
            }
            return hits;
        }

        EndCrystal crystalAt(Vec3 pos) {
            return level.getEntitiesOfClass(EndCrystal.class, new AABB(pos, pos).inflate(0.3)).stream()
                    .findFirst().orElse(null);
        }

        double hp(CrystalNpc npc) {
            return npc.bukkit().getHealth() + npc.bukkit().getAbsorptionAmount();
        }

        void close() {
            world.setDifficulty(originalDifficulty);
            clearArea();
            npcs.remove(target);
            npcs.remove(attacker);
        }
    }

    // ================================================================ report

    public static final class Report {
        private final String kind;
        private final List<String> lines = new ArrayList<>();
        private int passed;
        private int failed;
        private double maxError;

        Report(String kind) {
            this.kind = kind;
        }

        void add(String label, float predicted, double actual, double healthLoss) {
            double error = Math.abs(predicted - actual);
            boolean lossOk = Double.isNaN(healthLoss) || Math.abs(predicted - healthLoss) <= TOLERANCE;
            boolean ok = error <= TOLERANCE && lossOk;
            maxError = Math.max(maxError, error);
            String loss = Double.isNaN(healthLoss) ? "öldürücü" : String.format(Locale.ROOT, "%.3f", healthLoss);
            String line = String.format(Locale.ROOT, "%s %s tahmin %.3f | vanilla %.3f | can kaybı %s",
                    ok ? "OK  " : "FAIL", label, predicted, actual, loss);
            if (ok) {
                passed++;
            } else {
                failed++;
            }
            lines.add(line);
        }

        void pass(String line) {
            passed++;
            lines.add("OK   " + line);
        }

        void fail(String line) {
            failed++;
            lines.add("FAIL " + line);
        }

        void note(String line) {
            lines.add(line);
        }

        public String summary() {
            return String.format(Locale.ROOT, "Doğrulama (%s): %d geçti, %d kaldı, en büyük sapma %.4f",
                    kind, passed, failed, maxError);
        }

        public boolean allPassed() {
            return failed == 0;
        }

        public void forEachLine(Consumer<String> sink) {
            lines.forEach(sink);
        }
    }
}
