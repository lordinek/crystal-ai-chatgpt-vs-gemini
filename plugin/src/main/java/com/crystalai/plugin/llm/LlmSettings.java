package com.crystalai.plugin.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

/** Everything from llm.yml. Both sides share every number except their API settings. */
public record LlmSettings(
        boolean mock,
        Side chatgpt,
        Side gemini,
        String systemPrompt,
        int decisionIntervalTicks,
        int timeoutMs,
        String onTimeout,
        int maxTokens,
        int maxThoughtWords,
        int bestOf,
        int matchTicks,
        int suddenDeathTicks,
        int countdownTicks,
        int pauseTicks,
        boolean naturalRegeneration,
        String world,
        int floorY,
        int floorRadius,
        int wallHeight,
        int spawnDistance,
        String armorMaterial,
        String sword,
        boolean shield,
        int goldenApples,
        double retreatDistance,
        double approachStopDistance,
        int strafeSwitchTicks,
        boolean thoughtsInChat,
        int mockMinLatencyMs,
        int mockMaxLatencyMs,
        double mockInvalidChance) {

    /** API settings of one fighter. */
    public record Side(String name, String model, String apiKey, double temperature, int thinkingBudget,
                       double inputPricePerMillion, double outputPricePerMillion) {

        static Side from(ConfigurationSection section, String fallbackName) {
            return new Side(
                    section.getString("isim", fallbackName),
                    section.getString("model", ""),
                    section.getString("api-anahtari", ""),
                    section.getDouble("temperature", -1.0),
                    section.getInt("thinking-budget", -1),
                    section.getDouble("fiyat-girdi-1m", 0.0),
                    section.getDouble("fiyat-cikti-1m", 0.0));
        }

        public boolean hasKey() {
            return apiKey != null && !apiKey.isBlank() && !apiKey.startsWith("BURAYA");
        }
    }

    public static LlmSettings from(FileConfiguration c) {
        ConfigurationSection arena = section(c, "arena");
        ConfigurationSection kit = section(c, "kit");
        ConfigurationSection exec = section(c, "yurutucu");
        ConfigurationSection mock = section(c, "mock");
        return new LlmSettings(
                c.getString("mod", "mock").toLowerCase(Locale.ROOT).equals("mock"),
                Side.from(section(c, "chatgpt"), "ChatGPT"),
                Side.from(section(c, "gemini"), "Gemini"),
                c.getString("sistem-promptu", ""),
                Math.max(1, c.getInt("karar-araligi-tick", 50)),
                Math.max(100, c.getInt("zaman-asimi-ms", 2500)),
                c.getString("zaman-asiminda", "onceki").toLowerCase(Locale.ROOT),
                Math.max(16, c.getInt("max-tokens", 80)),
                Math.max(1, c.getInt("dusunce-max-kelime", 10)),
                Math.max(1, c.getInt("mac.en-iyi", 5)),
                Math.max(20, c.getInt("mac.sure-tick", 1800)),
                Math.max(20, c.getInt("mac.ani-olum-sure-tick", 600)),
                Math.max(0, c.getInt("mac.geri-sayim-tick", 60)),
                Math.max(0, c.getInt("mac.ara-tick", 100)),
                c.getBoolean("mac.dogal-iyilesme", false),
                arena.getString("dunya", "yz_arena"),
                arena.getInt("zemin-y", 64),
                arena.getInt("zemin-yaricap", 12),
                arena.getInt("duvar-yukseklik", 6),
                arena.getInt("baslangic-mesafe", 10),
                kit.getString("zirh", "iron"),
                kit.getString("kilic", "diamond_sword"),
                kit.getBoolean("kalkan", true),
                Math.max(0, kit.getInt("altin-elma", 2)),
                exec.getDouble("kac-mesafe", 8.0),
                exec.getDouble("yaklas-dur-mesafe", 3.5),
                Math.max(1, exec.getInt("yan-adim-degistir-tick", 12)),
                c.getBoolean("dusunceleri-sohbete-yaz", true),
                mock.getInt("min-gecikme-ms", 200),
                mock.getInt("max-gecikme-ms", 900),
                mock.getDouble("gecersiz-orani", 0.05));
    }

    private static ConfigurationSection section(ConfigurationSection c, String path) {
        ConfigurationSection section = c.getConfigurationSection(path);
        return section != null ? section : c.createSection(path);
    }

    public int winsNeeded() {
        return bestOf / 2 + 1;
    }

    /** Problems that stop a real (non-mock) series from starting. */
    public List<String> realModeProblems() {
        List<String> problems = new ArrayList<>();
        for (Side side : List.of(chatgpt, gemini)) {
            if (!side.hasKey()) {
                problems.add(side.name() + ": api-anahtari yok");
            }
            if (side.model().isBlank()) {
                problems.add(side.name() + ": model yok");
            }
        }
        return problems;
    }
}
