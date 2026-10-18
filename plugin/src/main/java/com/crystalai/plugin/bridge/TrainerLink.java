package com.crystalai.plugin.bridge;

import com.crystalai.plugin.arena.Arena;
import com.crystalai.plugin.control.ActionSpace;
import com.crystalai.plugin.control.NpcAction;
import com.crystalai.plugin.control.NpcController;
import com.crystalai.plugin.npc.CrystalNpc;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;
import javax.annotation.Nullable;

/**
 * The plugin side of the vectorised environment: one "agent" per trainer-controlled NPC.
 * Each decision step sends every agent's observation, action mask and events in one message
 * and reads all actions back in one message.
 */
public final class TrainerLink {

    public static final int PROTOCOL = 2;

    /** One trainer-controlled NPC slot. Its id is its index in every step message. */
    public static final class Agent {
        final int id;
        final Arena arena;
        final int side;
        // tally values already reported this episode
        double dealt;
        double taken;
        double selfDamage;
        int totemsSelf;
        int totemsOpponent;
        // set when the episode ended, reported with the next step
        @Nullable float[] terminalObs;
        @Nullable JsonObject terminalEvents;

        Agent(int id, Arena arena, int side) {
            this.id = id;
            this.arena = arena;
            this.side = side;
        }

        public Arena arena() {
            return arena;
        }

        public int side() {
            return side;
        }

        void resetEpisodeCounters() {
            dealt = taken = selfDamage = 0;
            totemsSelf = totemsOpponent = 0;
        }
    }

    /** What the manager collected for one agent this step. */
    public record Decision(Agent agent, ActionSpace.Context context, boolean[][] mask) {
    }

    private final Logger logger;
    private final BridgeServer bridge;
    private final ObservationSchema schema;
    private final ObservationBuilder builder;
    private final int timeoutMillis;
    private final List<Agent> agents = new ArrayList<>();

    private long steps;
    private long exchangeNanos;
    private long invalidChoices;
    private long stepsAtLastReport;

    public TrainerLink(Logger logger, BridgeServer bridge, ObservationSchema schema, int timeoutMillis) {
        this.logger = logger;
        this.bridge = bridge;
        this.schema = schema;
        this.builder = new ObservationBuilder(schema);
        this.timeoutMillis = timeoutMillis;
    }

    /** Creates one agent per side whose controller is "python". */
    public void configure(List<Arena> arenas) {
        agents.clear();
        for (Arena arena : arenas) {
            for (int side = 0; side < 2; side++) {
                if (arena.controllerOf(side) instanceof NpcController.External) {
                    agents.add(new Agent(agents.size(), arena, side));
                }
            }
        }
        steps = exchangeNanos = invalidChoices = 0;
    }

    public boolean active() {
        return !agents.isEmpty();
    }

    public boolean connected() {
        return bridge.connected();
    }

    public @Nullable Agent agentFor(Arena arena, int side) {
        for (Agent agent : agents) {
            if (agent.arena == arena && agent.side == side) {
                return agent;
            }
        }
        return null;
    }

    /**
     * Adopts a newly connected trainer and performs the handshake. Returns true when a new
     * trainer is ready (the caller then restarts all episodes).
     */
    public boolean pollNewTrainer(int decisionInterval) throws IOException {
        if (!bridge.adoptNewClient(timeoutMillis)) {
            return false;
        }
        try {
            JsonObject hello = bridge.receive();
            if (!"hello".equals(type(hello))) {
                throw new IOException("expected hello, got " + type(hello));
            }
            int protocol = hello.has("protocol") ? hello.get("protocol").getAsInt() : -1;
            if (protocol != PROTOCOL || schema.version() != PROTOCOL) {
                sendError("protocol mismatch: trainer " + protocol + ", plugin " + PROTOCOL + ", schema " + schema.version());
                throw new IOException("protocol mismatch");
            }
            JsonObject spec = new JsonObject();
            spec.addProperty("type", "spec");
            spec.addProperty("protocol", PROTOCOL);
            spec.addProperty("obs_size", schema.size());
            JsonArray branches = new JsonArray();
            for (int size : schema.branchSizes()) {
                branches.add(size);
            }
            spec.add("branches", branches);
            spec.addProperty("decision_interval", decisionInterval);
            JsonArray list = new JsonArray();
            for (Agent agent : agents) {
                JsonObject entry = new JsonObject();
                entry.addProperty("id", agent.id);
                entry.addProperty("arena", agent.arena.index() + 1);
                entry.addProperty("side", agent.side == 0 ? "A" : "B");
                list.add(entry);
            }
            spec.add("agents", list);
            bridge.send(spec.toString());
        } catch (IOException | RuntimeException e) {
            bridge.disconnect("handshake failed: " + e.getMessage());
            throw e instanceof IOException io ? io : new IOException(e);
        }
        for (Agent agent : agents) {
            agent.resetEpisodeCounters();
            agent.terminalObs = null;
            agent.terminalEvents = null;
        }
        steps = exchangeNanos = invalidChoices = 0;
        stepsAtLastReport = 0;
        return true;
    }

    /**
     * Called before an arena is reset: stores the final observation and events of its agents so
     * the next step reports them with done=true.
     */
    public void recordEpisodeEnd(Arena arena, Arena.Outcome outcome, ActionSpace space) {
        for (Agent agent : agents) {
            if (agent.arena != arena) {
                continue;
            }
            CrystalNpc self = arena.npc(agent.side);
            CrystalNpc opponent = arena.npc(1 - agent.side);
            float[] terminal = null;
            if (self != null && opponent != null && self.handle().isAlive() && opponent.handle().isAlive()) {
                terminal = builder.build(self, opponent.handle(), space.observe(self, opponent.handle()), arena);
            }
            JsonObject events = deltaEvents(agent);
            String result = switch (outcome) {
                case WIN_A -> agent.side == 0 ? "win" : "loss";
                case WIN_B -> agent.side == 1 ? "win" : "loss";
                case TIMEOUT -> "timeout";
                case ABORTED -> "aborted";
            };
            events.addProperty("outcome", result);
            events.addProperty("self_kill", "loss".equals(result) && arena.endedBySelfKill());
            events.addProperty("length", arena.episodeTick());
            agent.terminalObs = terminal == null ? new float[schema.size()] : terminal;
            agent.terminalEvents = events;
            agent.resetEpisodeCounters();
        }
    }

    private JsonObject deltaEvents(Agent agent) {
        double[] dealt = agent.arena.tallyDealt();
        double[] taken = agent.arena.tallyTaken();
        double[] self = agent.arena.tallySelfDamage();
        int[] totems = agent.arena.tallyTotems();
        int s = agent.side;
        JsonObject events = new JsonObject();
        events.addProperty("dealt", round(dealt[s] - agent.dealt));
        events.addProperty("taken", round(taken[s] - agent.taken));
        events.addProperty("self_damage", round(self[s] - agent.selfDamage));
        events.addProperty("totems_self", totems[s] - agent.totemsSelf);
        events.addProperty("totems_opponent", totems[1 - s] - agent.totemsOpponent);
        agent.dealt = dealt[s];
        agent.taken = taken[s];
        agent.selfDamage = self[s];
        agent.totemsSelf = totems[s];
        agent.totemsOpponent = totems[1 - s];
        return events;
    }

    /** Sends one step for all agents (in id order) and returns their actions. */
    public List<NpcAction> exchange(long tick, List<Decision> decisions) throws IOException {
        long start = System.nanoTime();
        StringBuilder json = new StringBuilder(decisions.size() * 2600);
        json.append("{\"type\":\"step\",\"tick\":").append(tick).append(",\"agents\":[");
        for (int i = 0; i < decisions.size(); i++) {
            Decision decision = decisions.get(i);
            Agent agent = decision.agent();
            CrystalNpc self = agent.arena.npc(agent.side);
            CrystalNpc opponent = agent.arena.npc(1 - agent.side);
            float[] obs = builder.build(self, opponent.handle(), decision.context(), agent.arena);
            boolean done = agent.terminalEvents != null;
            JsonObject events = done ? agent.terminalEvents : deltaEvents(agent);
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"id\":").append(agent.id).append(",\"obs\":");
            appendFloats(json, obs);
            json.append(",\"mask\":[");
            boolean first = true;
            for (boolean[] branch : decision.mask()) {
                for (boolean valid : branch) {
                    if (!first) {
                        json.append(',');
                    }
                    json.append(valid ? '1' : '0');
                    first = false;
                }
            }
            json.append("],\"done\":").append(done).append(",\"terminal_obs\":");
            if (done) {
                appendFloats(json, agent.terminalObs);
            } else {
                json.append("null");
            }
            json.append(",\"events\":").append(events).append('}');
            agent.terminalObs = null;
            agent.terminalEvents = null;
        }
        json.append("]}");
        bridge.send(json.toString());

        JsonObject reply = bridge.receive();
        if ("bye".equals(type(reply))) {
            throw new IOException("trainer said bye");
        }
        if (!"act".equals(type(reply))) {
            throw new IOException("expected act, got " + type(reply));
        }
        JsonArray actions = reply.getAsJsonArray("actions");
        if (actions == null || actions.size() != decisions.size()) {
            throw new IOException("expected " + decisions.size() + " actions, got "
                    + (actions == null ? "none" : actions.size()));
        }
        List<NpcAction> result = new ArrayList<>(decisions.size());
        for (int i = 0; i < actions.size(); i++) {
            JsonArray values = actions.get(i).getAsJsonArray();
            boolean[][] mask = decisions.get(i).mask();
            int[] choice = new int[NpcAction.BRANCH_SIZES.length];
            for (int branch = 0; branch < choice.length; branch++) {
                int value = branch < values.size() ? values.get(branch).getAsInt() : 0;
                if (value < 0 || value >= mask[branch].length || !mask[branch][value]) {
                    invalidChoices++;
                    value = 0; // masked out: do nothing on this branch
                }
                choice[branch] = value;
            }
            result.add(NpcAction.of(choice));
        }
        steps++;
        exchangeNanos += System.nanoTime() - start;
        return result;
    }

    public void disconnect(String reason) {
        bridge.disconnect(reason);
    }

    private void sendError(String message) {
        JsonObject error = new JsonObject();
        error.addProperty("type", "error");
        error.addProperty("message", message);
        try {
            bridge.send(error.toString());
        } catch (IOException ignored) {
            // closing anyway
        }
    }

    private static String type(JsonObject message) {
        return message.has("type") ? message.get("type").getAsString() : "?";
    }

    private static double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    private static void appendFloats(StringBuilder json, float[] values) {
        json.append('[');
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                json.append(',');
            }
            float v = values[i];
            if (v == 0.0F) {
                json.append('0');
            } else if (v == 1.0F) {
                json.append('1');
            } else {
                json.append(Math.round(v * 10000.0) / 10000.0);
            }
        }
        json.append(']');
    }

    public List<String> status() {
        List<String> lines = new ArrayList<>();
        if (!active()) {
            return lines;
        }
        if (!connected()) {
            lines.add(String.format(Locale.ROOT, "§eEğitici bekleniyor §7(%d ajan, arenalar donduruldu)", agents.size()));
            return lines;
        }
        double avgMs = steps == 0 ? 0 : exchangeNanos / 1e6 / steps;
        lines.add(String.format(Locale.ROOT, "§aEğitici bağlı §7| %d ajan | %d adım | adım başı %.2f ms (gözlem+JSON+Python) | maskeli seçim %d",
                agents.size(), steps, avgMs, invalidChoices));
        return lines;
    }

    public long steps() {
        return steps;
    }

    public int agentCount() {
        return agents.size();
    }

    /** Logs throughput every so often. */
    public void maybeReport(long everySteps) {
        if (steps - stepsAtLastReport >= everySteps) {
            stepsAtLastReport = steps;
            logger.info(String.format(Locale.ROOT, "Bridge: %d steps, %.2f ms/step avg, %d masked-out choices",
                    steps, exchangeNanos / 1e6 / Math.max(1, steps), invalidChoices));
        }
    }
}
