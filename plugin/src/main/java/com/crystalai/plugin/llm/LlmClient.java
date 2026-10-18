package com.crystalai.plugin.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

/** One model behind an HTTP API. {@link #decide} never completes exceptionally. */
public interface LlmClient {

    /** What came back for one decision. {@code move} is null when the answer was unusable. */
    record Decision(@Nullable Move move, String thought, String raw, long latencyMs,
                    int inputTokens, int outputTokens, @Nullable String error, boolean timedOut) {

        public boolean valid() {
            return move != null;
        }
    }

    CompletableFuture<Decision> decide(String systemPrompt, String stateJson);

    /** A free-form JSON answer (the lesson notebook). Completes with "" on any failure, never exceptionally. */
    CompletableFuture<String> ask(String systemPrompt, String user, int maxTokens, int timeoutMs);

    // ------------------------------------------------------------------ shared

    HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    /** Sends the request and turns the model's text into a Decision. */
    static CompletableFuture<Decision> post(HttpRequest request, int timeoutMs, int maxThoughtWords,
                                            java.util.function.Function<JsonObject, Usage> parse) {
        long start = System.nanoTime();
        return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .thenApply(response -> {
                    long latency = elapsedMs(start);
                    if (response.statusCode() / 100 != 2) {
                        return failure(response.body(), latency, "http_" + response.statusCode(), false);
                    }
                    try {
                        Usage usage = parse.apply(JsonParser.parseString(response.body()).getAsJsonObject());
                        return fromText(usage.text(), latency, usage.inputTokens(), usage.outputTokens(), maxThoughtWords);
                    } catch (RuntimeException e) {
                        return failure(response.body(), latency, "bad_response: " + e.getMessage(), false);
                    }
                })
                .exceptionally(error -> {
                    Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
                    boolean timeout = cause instanceof java.util.concurrent.TimeoutException || cause instanceof HttpTimeoutException;
                    return failure("", elapsedMs(start), timeout ? "timeout" : cause.toString(), timeout);
                });
    }

    record Usage(String text, int inputTokens, int outputTokens) {
    }

    /** Sends the request and returns the model's text, "" on an HTTP error, timeout or unreadable body. */
    static CompletableFuture<String> askText(HttpRequest request, int timeoutMs,
                                             java.util.function.Function<JsonObject, Usage> parse) {
        return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .thenApply(response -> {
                    if (response.statusCode() / 100 != 2) {
                        return "";
                    }
                    try {
                        return parse.apply(JsonParser.parseString(response.body()).getAsJsonObject()).text();
                    } catch (RuntimeException e) {
                        return "";
                    }
                })
                .exceptionally(error -> "");
    }

    static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    static Decision failure(String raw, long latency, String error, boolean timedOut) {
        return new Decision(null, "", raw, latency, 0, 0, error, timedOut);
    }

    /** Parses {"hamle": "...", "dusunce": "..."}; tolerates code fences and text around the object. */
    static Decision fromText(String text, long latency, int inputTokens, int outputTokens, int maxThoughtWords) {
        String raw = text == null ? "" : text.trim();
        int open = raw.indexOf('{');
        int close = raw.lastIndexOf('}');
        if (open < 0 || close <= open) {
            return new Decision(null, "", raw, latency, inputTokens, outputTokens, "no_json", false);
        }
        try {
            JsonObject json = JsonParser.parseString(raw.substring(open, close + 1)).getAsJsonObject();
            Move move = Move.parse(json.has("hamle") ? json.get("hamle").getAsString() : null);
            String thought = json.has("dusunce") && !json.get("dusunce").isJsonNull() ? json.get("dusunce").getAsString() : "";
            thought = limitWords(thought.replace('\n', ' ').trim(), maxThoughtWords);
            return new Decision(move, thought, raw, latency, inputTokens, outputTokens, move == null ? "bad_move" : null, false);
        } catch (RuntimeException e) {
            return new Decision(null, "", raw, latency, inputTokens, outputTokens, "bad_json", false);
        }
    }

    static String limitWords(String text, int max) {
        String[] words = text.split("\\s+");
        if (words.length <= max) {
            return text;
        }
        return String.join(" ", java.util.Arrays.copyOf(words, max));
    }

    static int intOr0(@Nullable JsonElement element) {
        return element == null || element.isJsonNull() ? 0 : element.getAsInt();
    }

    // ------------------------------------------------------------------ OpenAI

    /** OpenAI Chat Completions with JSON mode. */
    final class OpenAi implements LlmClient {
        private final LlmSettings.Side side;
        private final LlmSettings settings;

        public OpenAi(LlmSettings.Side side, LlmSettings settings) {
            this.side = side;
            this.settings = settings;
        }

        @Override
        public CompletableFuture<Decision> decide(String systemPrompt, String stateJson) {
            return post(request(systemPrompt, stateJson, settings.maxTokens(), settings.timeoutMs()),
                    settings.timeoutMs(), settings.maxThoughtWords(), OpenAi::usage);
        }

        @Override
        public CompletableFuture<String> ask(String systemPrompt, String user, int maxTokens, int timeoutMs) {
            return askText(request(systemPrompt, user, maxTokens, timeoutMs), timeoutMs, OpenAi::usage);
        }

        private HttpRequest request(String systemPrompt, String user, int maxTokens, int timeoutMs) {
            JsonObject body = new JsonObject();
            body.addProperty("model", side.model());
            JsonArray messages = new JsonArray();
            messages.add(message("system", systemPrompt));
            messages.add(message("user", user));
            body.add("messages", messages);
            // max_completion_tokens: newer models reject the older max_tokens
            body.addProperty("max_completion_tokens", maxTokens);
            if (side.temperature() >= 0) {
                body.addProperty("temperature", side.temperature());
            }
            JsonObject format = new JsonObject();
            format.addProperty("type", "json_object");
            body.add("response_format", format);

            return HttpRequest.newBuilder(URI.create("https://api.openai.com/v1/chat/completions"))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("Authorization", "Bearer " + side.apiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                    .build();
        }

        private static Usage usage(JsonObject json) {
            String text = json.getAsJsonArray("choices").get(0).getAsJsonObject()
                    .getAsJsonObject("message").get("content").getAsString();
            JsonObject usage = json.getAsJsonObject("usage");
            return new Usage(text,
                    usage == null ? 0 : intOr0(usage.get("prompt_tokens")),
                    usage == null ? 0 : intOr0(usage.get("completion_tokens")));
        }

        private static JsonObject message(String role, String content) {
            JsonObject message = new JsonObject();
            message.addProperty("role", role);
            message.addProperty("content", content);
            return message;
        }
    }

    // ------------------------------------------------------------------ Gemini

    /** Gemini generateContent with a JSON response type. */
    final class Gemini implements LlmClient {
        private final LlmSettings.Side side;
        private final LlmSettings settings;

        public Gemini(LlmSettings.Side side, LlmSettings settings) {
            this.side = side;
            this.settings = settings;
        }

        @Override
        public CompletableFuture<Decision> decide(String systemPrompt, String stateJson) {
            return post(request(systemPrompt, stateJson, settings.maxTokens(), settings.timeoutMs()),
                    settings.timeoutMs(), settings.maxThoughtWords(), Gemini::usage);
        }

        @Override
        public CompletableFuture<String> ask(String systemPrompt, String user, int maxTokens, int timeoutMs) {
            return askText(request(systemPrompt, user, maxTokens, timeoutMs), timeoutMs, Gemini::usage);
        }

        private HttpRequest request(String systemPrompt, String user, int maxTokens, int timeoutMs) {
            JsonObject body = new JsonObject();
            body.add("systemInstruction", parts(null, systemPrompt));
            JsonArray contents = new JsonArray();
            contents.add(parts("user", user));
            body.add("contents", contents);
            JsonObject generation = new JsonObject();
            generation.addProperty("maxOutputTokens", maxTokens);
            generation.addProperty("responseMimeType", "application/json");
            if (side.temperature() >= 0) {
                generation.addProperty("temperature", side.temperature());
            }
            if (side.thinkingBudget() >= 0) {
                // Thinking tokens count against maxOutputTokens: 0 keeps the answer from being cut off
                JsonObject thinking = new JsonObject();
                thinking.addProperty("thinkingBudget", side.thinkingBudget());
                generation.add("thinkingConfig", thinking);
            }
            body.add("generationConfig", generation);

            String url = "https://generativelanguage.googleapis.com/v1beta/models/"
                    + URLEncoder.encode(side.model(), StandardCharsets.UTF_8) + ":generateContent";
            return HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("x-goog-api-key", side.apiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                    .build();
        }

        private static Usage usage(JsonObject json) {
            StringBuilder text = new StringBuilder();
            JsonObject candidate = json.getAsJsonArray("candidates").get(0).getAsJsonObject();
            for (JsonElement part : candidate.getAsJsonObject("content").getAsJsonArray("parts")) {
                JsonObject p = part.getAsJsonObject();
                if (p.has("text") && !(p.has("thought") && p.get("thought").getAsBoolean())) {
                    text.append(p.get("text").getAsString());
                }
            }
            JsonObject usage = json.getAsJsonObject("usageMetadata");
            return new Usage(text.toString(),
                    usage == null ? 0 : intOr0(usage.get("promptTokenCount")),
                    usage == null ? 0 : intOr0(usage.get("candidatesTokenCount")) + intOr0(usage.get("thoughtsTokenCount")));
        }

        private static JsonObject parts(@Nullable String role, String text) {
            JsonObject content = new JsonObject();
            if (role != null) {
                content.addProperty("role", role);
            }
            JsonArray parts = new JsonArray();
            JsonObject part = new JsonObject();
            part.addProperty("text", text);
            parts.add(part);
            content.add("parts", parts);
            return content;
        }
    }

    // ------------------------------------------------------------------ Mock

    /** Free test mode: random valid moves after a fake delay, sometimes an invalid answer. */
    final class Mock implements LlmClient {
        private static final List<String> THOUGHTS = List.of(
                "rakip yakın, bastırıyorum", "canım az, geri çekiliyorum", "kalkanı kaldırıyorum",
                "yan adımla şaşırtacağım", "elmayı şimdi yemeliyim", "zıplayıp kritik vuracağım",
                "mesafeyi kapatıyorum", "sabırlı ol, açık bekle");

        private final LlmSettings settings;
        private final Random random = new Random();

        public Mock(LlmSettings settings) {
            this.settings = settings;
        }

        @Override
        public CompletableFuture<Decision> decide(String systemPrompt, String stateJson) {
            int min = settings.mockMinLatencyMs();
            int latency = min + random.nextInt(Math.max(1, settings.mockMaxLatencyMs() - min + 1));
            boolean invalid = random.nextDouble() < settings.mockInvalidChance();
            Move move = Move.values()[random.nextInt(Move.values().length)];
            String thought = THOUGHTS.get(random.nextInt(THOUGHTS.size()));
            String raw = invalid ? "{\"hamle\": \"dans_et\"}"
                    : "{\"hamle\": \"" + move.label() + "\", \"dusunce\": \"" + thought + "\"}";
            return CompletableFuture.supplyAsync(() -> fromText(raw, latency, stateJson.length() / 4, raw.length() / 4,
                            settings.maxThoughtWords()),
                    CompletableFuture.delayedExecutor(latency, TimeUnit.MILLISECONDS));
        }

        @Override
        public CompletableFuture<String> ask(String systemPrompt, String user, int maxTokens, int timeoutMs) {
            if (systemPrompt.contains("\"kart\"")) {
                // Training: a random card, so the loop and the files can be tested for free.
                String card = String.format(java.util.Locale.ROOT,
                        "{\"kart\": {\"vurus_sarj\": %.2f, \"kritik\": %.2f, \"wtap\": %d, \"strafe\": %.2f, \"kalkan\": %.2f},"
                                + " \"not\": \"mock: rastgele kart\"}",
                        0.5 + random.nextDouble() * 0.5, random.nextDouble(), random.nextInt(7), random.nextDouble(),
                        random.nextDouble());
                return CompletableFuture.supplyAsync(() -> card, CompletableFuture.delayedExecutor(300, TimeUnit.MILLISECONDS));
            }
            String lesson = THOUGHTS.get(random.nextInt(THOUGHTS.size()));
            return CompletableFuture.supplyAsync(() -> "{\"dersler\": [\"mock ders: " + lesson + "\"]}",
                    CompletableFuture.delayedExecutor(300, TimeUnit.MILLISECONDS));
        }
    }
}
