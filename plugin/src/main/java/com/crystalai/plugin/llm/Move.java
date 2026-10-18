package com.crystalai.plugin.llm;

import java.util.Locale;
import javax.annotation.Nullable;

/** The only moves a language model may choose. The label is what goes over the wire. */
public enum Move {
    SALDIR("saldir"),
    KAC("kac"),
    BLOKLA("blokla"),
    YAKLAS("yaklas"),
    YAN_ADIM("yan_adim"),
    IYILES("iyiles"),
    KRITIK("kritik");

    private final String label;

    Move(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static @Nullable Move parse(@Nullable String label) {
        if (label == null) {
            return null;
        }
        String normalized = label.trim().toLowerCase(Locale.ROOT);
        for (Move move : values()) {
            if (move.label.equals(normalized)) {
                return move;
            }
        }
        return null;
    }

    /** "saldir|kac|..." for the system prompt. */
    public static String labels() {
        StringBuilder builder = new StringBuilder();
        for (Move move : values()) {
            if (!builder.isEmpty()) {
                builder.append('|');
            }
            builder.append(move.label);
        }
        return builder.toString();
    }
}
