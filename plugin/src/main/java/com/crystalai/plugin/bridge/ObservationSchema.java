package com.crystalai.plugin.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Observation layout read from protocol/schema.json (embedded in the jar at build time).
 * Repeated groups are expanded, so index i always means the same thing on both sides.
 */
public final class ObservationSchema {

    private static final float DEFAULT_CLIP = 5.0F;

    private final int version;
    private final String[] groups;
    private final String[] fields;
    private final float[] scales;
    private final int[] branchSizes;

    private ObservationSchema(int version, List<String> groups, List<String> fields, List<Float> scales, int[] branchSizes) {
        this.version = version;
        this.groups = groups.toArray(String[]::new);
        this.fields = fields.toArray(String[]::new);
        this.scales = new float[scales.size()];
        for (int i = 0; i < scales.size(); i++) {
            this.scales[i] = scales.get(i);
        }
        this.branchSizes = branchSizes;
    }

    public static ObservationSchema load(InputStream stream) {
        JsonObject root;
        try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            root = JsonParser.parseReader(reader).getAsJsonObject();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot read protocol schema", e);
        }
        List<String> groups = new ArrayList<>();
        List<String> fields = new ArrayList<>();
        List<Float> scales = new ArrayList<>();
        for (JsonElement groupElement : root.getAsJsonArray("observation")) {
            JsonObject group = groupElement.getAsJsonObject();
            String name = group.get("group").getAsString();
            int repeat = group.has("repeat") ? group.get("repeat").getAsInt() : 1;
            JsonArray groupFields = group.getAsJsonArray("fields");
            for (int r = 0; r < repeat; r++) {
                for (JsonElement fieldElement : groupFields) {
                    JsonObject field = fieldElement.getAsJsonObject();
                    groups.add(name);
                    fields.add(field.get("name").getAsString());
                    scales.add(field.get("scale").getAsFloat());
                }
            }
        }
        JsonArray branches = root.getAsJsonObject("action").getAsJsonArray("branches");
        int[] sizes = new int[branches.size()];
        for (int i = 0; i < sizes.length; i++) {
            sizes[i] = branches.get(i).getAsJsonObject().get("size").getAsInt();
        }
        return new ObservationSchema(root.get("version").getAsInt(), groups, fields, scales, sizes);
    }

    public int version() {
        return version;
    }

    public int size() {
        return fields.length;
    }

    public int[] branchSizes() {
        return branchSizes.clone();
    }

    /** Collects one observation, checking each field name against the schema in order. */
    public Writer writer() {
        return new Writer();
    }

    public final class Writer {
        private final float[] data = new float[fields.length];
        private int index;

        public Writer put(String group, String field, double raw) {
            if (index >= fields.length) {
                throw new IllegalStateException("Observation longer than schema at " + group + "." + field);
            }
            if (!groups[index].equals(group) || !fields[index].equals(field)) {
                throw new IllegalStateException("Observation field " + index + " is " + group + "." + field
                        + " but the schema says " + groups[index] + "." + fields[index]);
            }
            float value = (float) (raw / scales[index]);
            if (Float.isNaN(value)) {
                value = 0.0F;
            }
            data[index++] = Math.max(-DEFAULT_CLIP, Math.min(DEFAULT_CLIP, value));
            return this;
        }

        public Writer put(String group, String field, boolean flag) {
            return put(group, field, flag ? 1.0 : 0.0);
        }

        public float[] finish() {
            if (index != fields.length) {
                throw new IllegalStateException("Observation has " + index + " fields, schema has " + fields.length
                        + " (next expected: " + groups[index] + "." + fields[index] + ")");
            }
            return data;
        }
    }
}
