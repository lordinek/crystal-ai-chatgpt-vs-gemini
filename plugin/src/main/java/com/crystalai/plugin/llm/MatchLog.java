package com.crystalai.plugin.llm;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

/**
 * Files for the video, under plugins/CrystalAI/yz-mac/:
 * <pre>
 *  overlay/skor.txt                 "ChatGPT 1 - 0 Gemini"      (OBS text source, read from file)
 *  overlay/durum.txt                "Maç 2 · 0:62"
 *  overlay/&lt;side&gt;_dusunce.txt   latest thought of that side (one OBS source per colour)
 *  overlay/durum.json               all of the above as JSON
 *  &lt;series&gt;/kararlar.jsonl       every API call: state sent, raw answer, move, latency, tokens, cost
 *  &lt;series&gt;/altyazi.jsonl        thoughts only, with time and side (colour)
 *  &lt;series&gt;/altyazi.srt          the same as subtitles, "[ChatGPT] ..." lines
 *  &lt;series&gt;/olaylar.jsonl        match start/end, sudden death, KO
 *  &lt;series&gt;/ozet.json            score and per-side stats, rewritten after every match
 * </pre>
 */
final class MatchLog implements AutoCloseable {

    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final Logger logger;
    private final Path overlay;
    private final Path seriesDir;
    private final long startNanos = System.nanoTime();
    private final BufferedWriter decisions;
    private final BufferedWriter subtitles;
    private final BufferedWriter subtitleSrt;
    private final BufferedWriter events;
    private final double subtitleSeconds;
    private int srtIndex;

    MatchLog(Path root, String seriesId, Logger logger, double subtitleSeconds) throws IOException {
        this.logger = logger;
        this.subtitleSeconds = subtitleSeconds;
        this.overlay = Files.createDirectories(root.resolve("overlay"));
        this.seriesDir = Files.createDirectories(root.resolve(seriesId));
        decisions = open("kararlar.jsonl");
        subtitles = open("altyazi.jsonl");
        subtitleSrt = open("altyazi.srt");
        events = open("olaylar.jsonl");
    }

    private BufferedWriter open(String name) throws IOException {
        return Files.newBufferedWriter(seriesDir.resolve(name), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    Path seriesDir() {
        return seriesDir;
    }

    /** Seconds since the series started: line this up with the start of the OBS recording. */
    double now() {
        return (System.nanoTime() - startNanos) / 1e9;
    }

    void decision(int match, Fighter fighter, Fighter.Answer answer, String applied, double cost) {
        LlmClient.Decision d = answer.decision();
        JsonObject line = new JsonObject();
        line.addProperty("t", round(now()));
        line.addProperty("mac", match);
        line.addProperty("taraf", fighter.key);
        line.addProperty("model", fighter.side.model());
        line.addProperty("sorulan_tick", answer.askedAtFightTick());
        line.add("durum", JsonParser.parseString(answer.stateJson()));
        line.addProperty("ham_cevap", d.raw());
        line.addProperty("hamle", d.move() == null ? null : d.move().label());
        line.addProperty("dusunce", d.thought());
        line.addProperty("uygulanan", applied);
        line.addProperty("gecikme_ms", d.latencyMs());
        line.addProperty("girdi_token", d.inputTokens());
        line.addProperty("cikti_token", d.outputTokens());
        line.addProperty("maliyet_usd", cost);
        line.addProperty("hata", d.error());
        write(decisions, line.toString());
    }

    void thought(int match, Fighter fighter, String text) {
        if (text.isBlank()) {
            return;
        }
        double t = now();
        JsonObject line = new JsonObject();
        line.addProperty("t", round(t));
        line.addProperty("mac", match);
        line.addProperty("taraf", fighter.key);
        line.addProperty("isim", fighter.name());
        line.addProperty("renk", fighter.color.asHexString());
        line.addProperty("metin", text);
        write(subtitles, line.toString());
        write(subtitleSrt, ++srtIndex + "\n" + srtTime(t) + " --> " + srtTime(t + subtitleSeconds) + "\n["
                + fighter.name() + "] " + text + "\n");
        writeText(overlay.resolve(fighter.key + "_dusunce.txt"), text);
    }

    void event(String type, JsonObject data) {
        data.addProperty("t", round(now()));
        data.addProperty("olay", type);
        write(events, data.toString());
    }

    void overlay(String score, String status, List<Fighter> fighters) {
        writeText(overlay.resolve("skor.txt"), score);
        writeText(overlay.resolve("durum.txt"), status);
        JsonObject json = new JsonObject();
        json.addProperty("skor", score);
        json.addProperty("durum", status);
        for (Fighter f : fighters) {
            json.addProperty(f.key + "_galibiyet", f.wins);
        }
        writeText(overlay.resolve("durum.json"), json.toString());
    }

    void clearThoughts(List<Fighter> fighters) {
        for (Fighter f : fighters) {
            writeText(overlay.resolve(f.key + "_dusunce.txt"), "");
        }
    }

    void summary(JsonObject summary) {
        writeText(seriesDir.resolve("ozet.json"), PRETTY.toJson(summary));
    }

    private void write(BufferedWriter writer, String line) {
        try {
            writer.write(line);
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            logger.warning("yz-mac log: " + e.getMessage());
        }
    }

    private void writeText(Path file, String text) {
        try {
            Files.writeString(file, text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            logger.warning("yz-mac overlay: " + e.getMessage());
        }
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static String srtTime(double seconds) {
        long ms = Math.round(seconds * 1000);
        return String.format(Locale.ROOT, "%02d:%02d:%02d,%03d", ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000);
    }

    @Override
    public void close() {
        for (BufferedWriter writer : List.of(decisions, subtitles, subtitleSrt, events)) {
            try {
                writer.close();
            } catch (IOException ignored) {
                // closing anyway
            }
        }
    }
}
