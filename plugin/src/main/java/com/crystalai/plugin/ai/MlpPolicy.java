package com.crystalai.plugin.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

/**
 * A trained policy running inside the plugin, without Python: the MaskablePPO actor (MLP with
 * tanh, then the action head) exported by trainer/export_policy.py. The export carries one
 * reference observation with the PyTorch logits; loading fails if this forward pass disagrees.
 */
public final class MlpPolicy {

    private final String name;
    private final float[][] weights;   // per layer, row-major [out][in]
    private final float[][] biases;
    private final int[] in;
    private final int[] out;

    private MlpPolicy(String name, float[][] weights, float[][] biases, int[] in, int[] out) {
        this.name = name;
        this.weights = weights;
        this.biases = biases;
        this.in = in;
        this.out = out;
    }

    public static MlpPolicy load(Path file) throws IOException {
        JsonObject doc = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
        if (!"tanh".equals(doc.get("activation").getAsString())) {
            throw new IOException("unsupported activation " + doc.get("activation"));
        }
        JsonArray layers = doc.getAsJsonArray("layers");
        int n = layers.size();
        float[][] w = new float[n][];
        float[][] b = new float[n][];
        int[] in = new int[n];
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            JsonObject layer = layers.get(i).getAsJsonObject();
            in[i] = layer.get("in").getAsInt();
            out[i] = layer.get("out").getAsInt();
            w[i] = floats(layer.getAsJsonArray("w"));
            b[i] = floats(layer.getAsJsonArray("b"));
            if (w[i].length != in[i] * out[i] || b[i].length != out[i]) {
                throw new IOException("layer " + i + " has the wrong size");
            }
        }
        MlpPolicy policy = new MlpPolicy(doc.get("generation").getAsString(), w, b, in, out);
        JsonObject check = doc.getAsJsonObject("check");
        float[] logits = policy.logits(floats(check.getAsJsonArray("obs")));
        float[] expected = floats(check.getAsJsonArray("logits"));
        double worst = 0;
        for (int i = 0; i < expected.length; i++) {
            worst = Math.max(worst, Math.abs(logits[i] - expected[i]));
        }
        if (worst > 1e-3) {
            throw new IOException(String.format("forward pass differs from PyTorch by %.5f", worst));
        }
        return policy;
    }

    private static float[] floats(JsonArray array) {
        float[] values = new float[array.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = array.get(i).getAsFloat();
        }
        return values;
    }

    public String name() {
        return name;
    }

    public int observationSize() {
        return in[0];
    }

    /** All action-branch logits, concatenated. */
    public float[] logits(float[] observation) {
        float[] x = observation;
        for (int layer = 0; layer < weights.length; layer++) {
            float[] w = weights[layer];
            float[] y = new float[out[layer]];
            int inputs = in[layer];
            for (int o = 0; o < y.length; o++) {
                float sum = biases[layer][o];
                int row = o * inputs;
                for (int i = 0; i < inputs; i++) {
                    sum += w[row + i] * x[i];
                }
                y[o] = layer < weights.length - 1 ? (float) Math.tanh(sum) : sum;
            }
            x = y;
        }
        return x;
    }

    /**
     * One choice per branch: masked softmax sampling (as during training), or the most likely
     * valid choice when {@code greedy}.
     */
    public int[] act(float[] observation, boolean[][] mask, Random random, boolean greedy) {
        float[] logits = logits(observation);
        int[] choice = new int[mask.length];
        int offset = 0;
        for (int b = 0; b < mask.length; b++) {
            int size = mask[b].length;
            double max = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < size; i++) {
                if (mask[b][i]) {
                    max = Math.max(max, logits[offset + i]);
                }
            }
            double[] p = new double[size];
            double total = 0;
            int best = 0;
            for (int i = 0; i < size; i++) {
                if (mask[b][i]) {
                    p[i] = Math.exp(logits[offset + i] - max);
                    total += p[i];
                    if (!mask[b][best] || logits[offset + i] > logits[offset + best]) {
                        best = i;
                    }
                }
            }
            if (greedy || total <= 0) {
                choice[b] = best;
            } else {
                double r = random.nextDouble() * total;
                int pick = best;
                for (int i = 0; i < size; i++) {
                    if (p[i] > 0) {
                        r -= p[i];
                        if (r <= 0) {
                            pick = i;
                            break;
                        }
                    }
                }
                choice[b] = pick;
            }
            offset += size;
        }
        return choice;
    }
}
