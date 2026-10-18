package com.crystalai.plugin.npc;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.spigotmc.event.player.PlayerSpawnLocationEvent;

public final class NpcListener implements Listener {

    private final JavaPlugin plugin;
    private final NpcManager npcs;

    public NpcListener(JavaPlugin plugin, NpcManager npcs) {
        this.plugin = plugin;
        this.npcs = npcs;
    }

    private boolean silent() {
        return plugin.getConfig().getBoolean("npc.silent-join-quit", true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSpawnLocation(PlayerSpawnLocationEvent event) {
        org.bukkit.Location location = npcs.spawnLocation(event.getPlayer().getUniqueId());
        if (location != null) {
            event.setSpawnLocation(location);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onJoin(PlayerJoinEvent event) {
        if (silent() && npcs.isNpc(event.getPlayer().getUniqueId())) {
            event.joinMessage(null);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onQuit(PlayerQuitEvent event) {
        if (silent() && npcs.isNpc(event.getPlayer().getUniqueId())) {
            event.quitMessage(null);
        }
    }

    /** NPCs earn advancements (e.g. Postmortal) like players; keep that out of chat. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAdvancement(PlayerAdvancementDoneEvent event) {
        if (silent() && npcs.isNpc(event.getPlayer().getUniqueId())) {
            event.message(null);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (plugin.getConfig().getBoolean("debug.log-damage", false)
                && event.getEntity() instanceof Player player && npcs.isNpc(player.getUniqueId())) {
            plugin.getLogger().info(String.format(java.util.Locale.ROOT, "%s took %.2f (raw %.2f) from %s, health %.2f",
                    player.getName(), event.getFinalDamage(), event.getDamage(), event.getCause(), player.getHealth()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onResurrect(EntityResurrectEvent event) {
        if (plugin.getConfig().getBoolean("debug.log-totems", true)
                && event.getEntity() instanceof Player player && npcs.isNpc(player.getUniqueId())) {
            plugin.getLogger().info(player.getName() + " popped a totem");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        CrystalNpc npc = npcs.get(event.getEntity().getUniqueId());
        if (npc == null) {
            return;
        }
        plugin.getLogger().info(npc.name() + " died");
        if (plugin.getConfig().getBoolean("npc.remove-on-death", true)) {
            long delay = Math.max(1, plugin.getConfig().getLong("npc.death-remove-delay-ticks", 20));
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (npcs.get(npc.handle().getUUID()) != null) {
                    npcs.remove(npc);
                }
            }, delay);
        }
    }
}
