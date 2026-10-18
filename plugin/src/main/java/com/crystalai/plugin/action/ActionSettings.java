package com.crystalai.plugin.action;

import org.bukkit.configuration.ConfigurationSection;

/** Tunables for NPC actions, read from the {@code actions} section of config.yml. */
public record ActionSettings(
        double blockReach,
        double entityReach,
        boolean requireLineOfSight,
        int fallbackHotbarSlot,
        double swordMinCharge) {

    public static ActionSettings from(ConfigurationSection section) {
        return new ActionSettings(
                section.getDouble("block-reach", 4.5),
                section.getDouble("entity-reach", 3.0),
                section.getBoolean("require-line-of-sight", true),
                Math.max(0, Math.min(8, section.getInt("fallback-hotbar-slot", 8))),
                section.getDouble("sword-min-charge", 0.9));
    }
}
