/**
 * The Loader class is an Entity limiting plugin that manages entity limits and prevents entities from spawning
 * within a specified radius around the world's spawn point.
 */
package org.anarchadia.entitylimiter;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class Loader extends JavaPlugin implements Runnable, Listener {
    private Map<EntityType, Integer> entityLimits;
    private int scanRadius;
    private int spawnRadius;

    /**
     * Called when the plugin is enabled. Saves the default configuration, loads the configuration,
     * and registers the event listener.
     */
    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfig();
        getServer().getPluginManager().registerEvents(this, this);
    }

    /**
     * Loads the configuration values from the plugin's configuration file.
     * Sets the spawnRadius, scanRadius, scanInterval, and entityLimits based on the configuration values.
     * Schedules the task to run asynchronously at the specified scanInterval.
     */
    private void loadConfig() {
        spawnRadius = getConfig().getInt("spawn_radius", 512);
        scanRadius = getConfig().getInt("scan_radius", 64);
        int scanInterval = getConfig().getInt("scan_interval", 5);

        ConfigurationSection entityLimitsSection = getConfig().getConfigurationSection("entity_limits");
        entityLimits = new HashMap<>();

        if (entityLimitsSection != null) {
            for (String entityName : entityLimitsSection.getKeys(false)) {
                EntityType entityType = EntityType.valueOf(entityName.toUpperCase());
                int limit = entityLimitsSection.getInt(entityName);
                entityLimits.put(entityType, limit);
            }
        }

        int taskDelay = 0; // Run immediately
        int taskPeriod = 20 * scanInterval; // Convert seconds to ticks (20 ticks per second)

        Bukkit.getScheduler().runTaskTimerAsynchronously(this, this, taskDelay, taskPeriod);
    }

    /**
     * The task that runs periodically to scan for entities around each player and remove excess entities.
     * Iterates over each online player and each entity type with a configured limit.
     * Retrieves the nearby entities of the specified type within the scanRadius.
     * If the number of nearby entities exceeds the configured limit, removes the excess entities.
     */
    @Override
    public void run() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            for (Map.Entry<EntityType, Integer> entry : entityLimits.entrySet()) {
                EntityType entityType = entry.getKey();
                int limit = entry.getValue();

                List<Entity> entities = player.getNearbyEntities(scanRadius, scanRadius, scanRadius)
                        .stream()
                        .filter(entity -> entity.getType() == entityType)
                        .collect(Collectors.toList());

                if (entities.size() > limit) {
                    entities.subList(limit, entities.size()).forEach(Entity::remove);
                }
            }
        }
    }

    /**
     * Event handler for the EntitySpawnEvent.
     * Calculates the distance between the spawn location and the world's spawn location.
     * If the spawn location is within the spawnRadius, the event is canceled to prevent the entity from spawning.
     *
     * @param event The EntitySpawnEvent.
     */
    @EventHandler
    public void onEntitySpawn(EntitySpawnEvent event) {
        Location spawnLocation = event.getLocation();
        Location worldSpawn = spawnLocation.getWorld().getSpawnLocation();

        double distanceFromSpawn = spawnLocation.distanceSquared(worldSpawn);
        if (distanceFromSpawn <= spawnRadius * spawnRadius) {
            event.setCancelled(true);
        }
    }
}