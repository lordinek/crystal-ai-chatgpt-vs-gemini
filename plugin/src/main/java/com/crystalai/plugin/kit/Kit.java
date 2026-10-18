package com.crystalai.plugin.kit;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.annotation.Nullable;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * The standard combat kit from config.yml ({@code kit} section). Items are written as
 * "material:amount[:enchant=level,...]", e.g. "end_crystal:64" or "netherite_sword:1:sharpness=5".
 * Unstackable items (totems) are split into separate slots automatically.
 *
 * Armor: every piece gets {@code armor-enchants} (e.g. protection=4, mending=1, unbreaking=3);
 * {@code leggings-enchants}, when set, replaces them for the leggings (pros wear Blast Prot IV there).
 */
public record Kit(
        @Nullable String armorMaterial,
        List<String> armorEnchants,
        @Nullable List<String> leggingsEnchants,
        @Nullable ItemStack offhand,
        List<ItemStack> hotbar,
        List<ItemStack> inventory) {

    public static Kit from(ConfigurationSection section) {
        String armor = section.getString("armor-material", "netherite");
        List<String> armorEnchants = section.isList("armor-enchants")
                ? section.getStringList("armor-enchants")
                // v1 config: a bare protection level
                : List.of("protection=" + section.getInt("armor-protection", 4));
        return new Kit(
                armor == null || armor.equalsIgnoreCase("none") ? null : armor.toUpperCase(Locale.ROOT),
                armorEnchants,
                section.isList("leggings-enchants") ? section.getStringList("leggings-enchants") : null,
                parseOrNull(section.getString("offhand")),
                parseList(section.getStringList("hotbar")),
                parseList(section.getStringList("inventory")));
    }

    /** Clears the inventory and applies the kit. */
    public void apply(PlayerInventory target) {
        target.clear();
        if (armorMaterial != null) {
            target.setHelmet(armorPiece("HELMET", armorEnchants));
            target.setChestplate(armorPiece("CHESTPLATE", armorEnchants));
            target.setLeggings(armorPiece("LEGGINGS", leggingsEnchants != null ? leggingsEnchants : armorEnchants));
            target.setBoots(armorPiece("BOOTS", armorEnchants));
        }
        if (offhand != null) {
            target.setItemInOffHand(offhand.clone());
        }
        for (int slot = 0; slot < hotbar.size() && slot < 9; slot++) {
            target.setItem(slot, hotbar.get(slot).clone());
        }
        for (ItemStack stack : inventory) {
            target.addItem(stack.clone());
        }
        target.setHeldItemSlot(0);
    }

    private ItemStack armorPiece(String slot, List<String> enchants) {
        Material material = Material.matchMaterial(armorMaterial + "_" + slot);
        if (material == null) {
            throw new IllegalArgumentException("Unknown armor material: " + armorMaterial);
        }
        ItemStack item = new ItemStack(material);
        enchant(item, enchants);
        return item;
    }

    private static void enchant(ItemStack item, List<String> enchants) {
        for (String entry : enchants) {
            String[] kv = entry.trim().split("=");
            Enchantment enchantment = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(kv[0].trim().toLowerCase(Locale.ROOT)));
            if (enchantment == null) {
                throw new IllegalArgumentException("Unknown enchantment in kit: " + entry);
            }
            int level = kv.length > 1 ? Integer.parseInt(kv[1].trim()) : 1;
            if (level > 0) {
                item.addUnsafeEnchantment(enchantment, level);
            }
        }
    }

    private static List<ItemStack> parseList(List<String> entries) {
        List<ItemStack> items = new ArrayList<>();
        for (String entry : entries) {
            ItemStack item = parseOrNull(entry);
            items.add(item == null ? new ItemStack(Material.AIR) : item);
        }
        return items;
    }

    private static @Nullable ItemStack parseOrNull(@Nullable String entry) {
        if (entry == null || entry.isBlank() || entry.equalsIgnoreCase("none")) {
            return null;
        }
        String[] parts = entry.split(":");
        Material material = Material.matchMaterial(parts[0].trim());
        if (material == null) {
            throw new IllegalArgumentException("Unknown item in kit: " + entry);
        }
        int amount = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : 1;
        ItemStack item = new ItemStack(material, amount);
        if (parts.length > 2) {
            enchant(item, List.of(parts[2].split(",")));
        }
        return item;
    }
}
