package com.crystalai.plugin.llm;

import com.crystalai.plugin.npc.CrystalNpc;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;
import net.kyori.adventure.text.format.NamedTextColor;

/** One side of the series: its model, its NPC for the current match, its running stats. */
final class Fighter {

    /** A decision that came back, tagged with the match it was asked for. */
    record Answer(int matchToken, long askedAtFightTick, String stateJson, LlmClient.Decision decision) {
    }

    final String key;            // "chatgpt" / "gemini": file names, JSON keys
    final LlmSettings.Side side;
    final LlmClient client;
    final NamedTextColor color;

    // ---- per match
    @Nullable CrystalNpc npc;
    @Nullable Move move;
    @Nullable Move lastChosen;
    boolean inFlight;
    final AtomicReference<Answer> answer = new AtomicReference<>();
    boolean strafeLeft;
    int strafeTicks;
    boolean ateThisDecision;
    /** The trained fighting style the body plays in fight moves; null = the plain moves. */
    @Nullable FightCard card;
    // card body state (see MoveExecutor.cardFight)
    final java.util.Random rng = new java.util.Random();
    int wTapLeft;
    int backOffLeft;
    int cardStrafeTicks;
    int cardStrafeLimit;
    int eatCooldown;
    boolean cardStrafeRight;
    boolean circling;
    boolean critArmed;
    boolean critAirborne;
    int critTicks;
    int shieldRollTicks;
    double shieldRoll;
    // techniques (see MoveExecutor.cardFight): state and counters for the round log
    int blockHitLeft;
    int shieldWaitStreak;
    int techBlockHits;
    int techWaitTicks;
    int techPunishTicks;
    int techGapEats;

    /** This match's decisions in short ("can 20/14, mesafe 3.1 -> saldir"), for the lesson notebook. */
    final List<String> trail = new ArrayList<>();

    // ---- per series
    int wins;
    final Stats total = new Stats();
    Stats match = new Stats();

    Fighter(String key, LlmSettings.Side side, LlmClient client, NamedTextColor color) {
        this.key = key;
        this.side = side;
        this.client = client;
        this.color = color;
    }

    String name() {
        return side.name();
    }

    void resetForMatch() {
        npc = null;
        move = null;
        lastChosen = null;
        inFlight = false;
        answer.set(null);
        strafeLeft = false;
        strafeTicks = 0;
        ateThisDecision = false;
        wTapLeft = 0;
        backOffLeft = 0;
        cardStrafeTicks = 0;
        cardStrafeLimit = 0;
        eatCooldown = 0;
        circling = false;
        critArmed = false;
        critAirborne = false;
        critTicks = 0;
        shieldRollTicks = 0;
        blockHitLeft = 0;
        shieldWaitStreak = 0;
        techBlockHits = 0;
        techWaitTicks = 0;
        techPunishTicks = 0;
        techGapEats = 0;
        trail.clear();
        match = new Stats();
    }

    /** Counters for the log summary and the end-of-video stats. */
    static final class Stats {
        final Map<Move, Integer> moves = new EnumMap<>(Move.class);
        int calls;
        int invalid;
        int timeouts;
        int errors;
        int skipped;
        long latencySum;
        long latencyMax;
        long inputTokens;
        long outputTokens;
        double cost;
        int hits;
        int crits;
        int blocked;
        double damage;

        void addDecision(LlmClient.Decision d, LlmSettings.Side side) {
            calls++;
            latencySum += d.latencyMs();
            latencyMax = Math.max(latencyMax, d.latencyMs());
            inputTokens += d.inputTokens();
            outputTokens += d.outputTokens();
            cost += cost(d, side);
            if (d.timedOut()) {
                timeouts++;
            } else if (d.error() != null && !d.error().startsWith("bad_") && !d.error().equals("no_json")) {
                errors++; // HTTP status or network failure, not the model's fault
            } else if (!d.valid()) {
                invalid++;
            }
            if (d.valid()) {
                moves.merge(d.move(), 1, Integer::sum);
            }
        }

        static double cost(LlmClient.Decision d, LlmSettings.Side side) {
            return d.inputTokens() * side.inputPricePerMillion() / 1_000_000.0
                    + d.outputTokens() * side.outputPricePerMillion() / 1_000_000.0;
        }

        void add(Stats other) {
            other.moves.forEach((m, n) -> moves.merge(m, n, Integer::sum));
            calls += other.calls;
            invalid += other.invalid;
            timeouts += other.timeouts;
            errors += other.errors;
            skipped += other.skipped;
            latencySum += other.latencySum;
            latencyMax = Math.max(latencyMax, other.latencyMax);
            inputTokens += other.inputTokens;
            outputTokens += other.outputTokens;
            cost += other.cost;
            hits += other.hits;
            crits += other.crits;
            blocked += other.blocked;
            damage += other.damage;
        }

        long avgLatency() {
            return calls == 0 ? 0 : latencySum / calls;
        }
    }
}
