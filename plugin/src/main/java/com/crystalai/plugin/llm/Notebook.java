package com.crystalai.plugin.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;

/**
 * One model's lesson notebook: after every training match the model reads its own match record and
 * rewrites its lessons, which then go into its own system prompt. Both sides get the same coach prompt,
 * the same record format and the same limits. Files: plugins/CrystalAI/ders-defteri/<key>.json (+ .txt for OBS).
 */
final class Notebook {

    private final Path json;
    private final Path text;
    private final List<String> lessons = new ArrayList<>();
    private int trainingMatches;

    Notebook(Path dir, String key) {
        this.json = dir.resolve(key + ".json");
        this.text = dir.resolve(key + ".txt");
        load();
    }

    private void load() {
        lessons.clear();
        trainingMatches = 0;
        if (!Files.isRegularFile(json)) {
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(json, StandardCharsets.UTF_8)).getAsJsonObject();
            trainingMatches = root.has("antrenman_maci") ? root.get("antrenman_maci").getAsInt() : 0;
            if (root.has("dersler")) {
                for (JsonElement e : root.getAsJsonArray("dersler")) {
                    lessons.add(e.getAsString());
                }
            }
        } catch (IOException | RuntimeException e) {
            lessons.clear();
        }
    }

    void save() throws IOException {
        Files.createDirectories(json.getParent());
        JsonObject root = new JsonObject();
        root.addProperty("antrenman_maci", trainingMatches);
        JsonArray array = new JsonArray();
        lessons.forEach(array::add);
        root.add("dersler", array);
        Files.writeString(json, root.toString(), StandardCharsets.UTF_8);
        StringBuilder lines = new StringBuilder();
        for (String lesson : lessons) {
            lines.append("- ").append(lesson).append('\n');
        }
        Files.writeString(text, lines.toString(), StandardCharsets.UTF_8);
    }

    void reset() throws IOException {
        lessons.clear();
        trainingMatches = 0;
        save();
    }

    List<String> lessons() {
        return List.copyOf(lessons);
    }

    int trainingMatches() {
        return trainingMatches;
    }

    /** What goes into this model's own system prompt; empty when it has no lessons yet. */
    String promptBlock() {
        if (lessons.isEmpty()) {
            return "";
        }
        StringBuilder block = new StringBuilder("\nÖnceki antrenman maçlarından kendi çıkardığın dersler (sen yazdın, uygula):");
        for (String lesson : lessons) {
            block.append("\n- ").append(lesson);
        }
        return block.toString();
    }

    /** The coach prompt, word for word the same for both models. */
    static String coachPrompt(int maxLessons, int maxWords) {
        return "Sen Minecraft'ta kılıç dövüşü yapan bir yapay zekasın ve az önce bir antrenman maçın bitti. "
                + "Sana bu maçın kaydı ve şimdiye kadarki ders defterin verilecek. Kayıttan neyin işe yarayıp neyin "
                + "yaramadığını çıkar ve defterini güncelle: doğru çıkan dersleri tut, yanlış çıkanları sil, yenilerini ekle. "
                + "En fazla " + maxLessons + " ders, her biri en fazla " + maxWords + " kelime. Dersler somut olsun: "
                + "hangi durumda (can, mesafe, kenar, rakibin hamlesi) hangi hamleyi seçeceğin. "
                + "Sadece şu JSON'u ver: {\"dersler\": [\"...\", \"...\"]}";
    }

    /**
     * Replaces the lessons with the model's rewrite.
     * @return false when the answer held no usable lesson list (the notebook is then left as it was)
     */
    boolean apply(@Nullable String raw, int maxLessons, int maxWords) {
        if (raw == null) {
            return false;
        }
        int open = raw.indexOf('{');
        int close = raw.lastIndexOf('}');
        if (open < 0 || close <= open) {
            return false;
        }
        List<String> fresh = new ArrayList<>();
        try {
            JsonObject root = JsonParser.parseString(raw.substring(open, close + 1)).getAsJsonObject();
            for (JsonElement e : root.getAsJsonArray("dersler")) {
                String lesson = LlmClient.limitWords(e.getAsString().replace('\n', ' ').trim(), maxWords);
                if (!lesson.isEmpty() && fresh.size() < maxLessons) {
                    fresh.add(lesson);
                }
            }
        } catch (RuntimeException e) {
            return false;
        }
        if (fresh.isEmpty()) {
            return false;
        }
        lessons.clear();
        lessons.addAll(fresh);
        trainingMatches++;
        return true;
    }

    /** Counts a training match whose lesson call failed, so both notebooks stay at the same match count. */
    void countFailedMatch() {
        trainingMatches++;
    }
}
