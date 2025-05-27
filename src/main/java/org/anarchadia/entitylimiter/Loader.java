package org.anarchadia.entitylimiter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * The Loader class is an Entity limiting plugin that manages entity limits and prevents entities
 * from spawning within a specified radius around the world's spawn point. It also handles
 * configuration loading, command execution, and event listening.
 */
public class Loader extends JavaPlugin implements Runnable, Listener {
    private Map<EntityType, Integer> entityLimits;
    private List<Pattern> blacklistPatterns;
    private int scanRadius;
    private int spawnRadius;
    private static final String RENAME_TEXT = "Anarchadia is the BEST SERVER!";
    private List<EntityType> spawnWhitelist;

    /**
     * Called when the plugin is enabled. Saves the default configuration, loads the configuration,
     * and registers the event listener and command executor for "entitylimiter".
     */
    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfig();
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("entitylimiter").setExecutor(this);
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

        // Load entity limits
        ConfigurationSection entityLimitsSection = getConfig().getConfigurationSection("entity_limits");
        entityLimits = new HashMap<>();

        if (entityLimitsSection != null) {
            for (String entityName : entityLimitsSection.getKeys(false)) {
                EntityType entityType = EntityType.valueOf(entityName.toUpperCase());
                int limit = entityLimitsSection.getInt(entityName);
                entityLimits.put(entityType, limit);
            }
        }

        // Load blacklist patterns
        blacklistPatterns = new ArrayList<>();
        List<String> patterns = getConfig().getStringList("display_name_blacklist");
        if (patterns != null) {
            for (String pattern : patterns) {
                blacklistPatterns.add(Pattern.compile(pattern));
            }
        }

        // Load spawn whitelist
        List<String> whitelistEntities = getConfig().getStringList("spawn_whitelist");
        spawnWhitelist = new ArrayList<>();
        for (String entityName : whitelistEntities) {
            try {
                spawnWhitelist.add(EntityType.valueOf(entityName.toUpperCase()));
            } catch (IllegalArgumentException e) {
                getLogger().warning("Invalid entity type in spawn whitelist: " + entityName);
            }
        }

        int taskDelay = 0;
        int taskPeriod = 20 * scanInterval;

        Bukkit.getScheduler().runTaskTimerAsynchronously(this, this, taskDelay, taskPeriod);
    }

    /**
     * Checks if a string contains Unicode characters (non-ASCII).
     *
     * @param text The string to check for Unicode characters.
     * @return true if the string contains Unicode characters, false otherwise.
     */
    private boolean containsUnicode(String text) {
        if (text == null) {
            return false;
        }
        
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) > 127) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks and renames an entity if its display name matches any blacklisted pattern
     * or contains Unicode characters.
     *
     * @param entity The entity to check and potentially rename.
     */
    private void checkAndRenameEntity(Entity entity) {
        if (entity instanceof LivingEntity) {
            LivingEntity livingEntity = (LivingEntity) entity;
            String customName = livingEntity.getCustomName();
            
            if (customName != null) {
                // Check for Unicode characters first
                if (containsUnicode(customName)) {
                    livingEntity.setCustomName(RENAME_TEXT);
                    livingEntity.setCustomNameVisible(true);
                    return;
                }
                
                // Then check against blacklist patterns
                String lowerCustomName = customName.toLowerCase();
                for (Pattern pattern : blacklistPatterns) {
                    if (pattern.matcher(lowerCustomName).matches()) {
                        livingEntity.setCustomName(RENAME_TEXT);
                        livingEntity.setCustomNameVisible(true);
                        break;
                    }
                }
            }
        }
    }

    /**
     * Gets the effective scan radius for a player, using their render distance if configured.
     *
     * @param player The player to get scan radius for.
     * @return The scan radius in blocks.
     */
    private int getEffectiveScanRadius(Player player) {
        if (scanRadius == -1) {
            // Convert render distance from chunks to blocks (16 blocks per chunk)
            return player.getSimulationDistance() * 16;
        }
        return scanRadius;
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
            Bukkit.getScheduler().runTask(this, () -> {
                int effectiveRadius = getEffectiveScanRadius(player);
                
                // Check entity limits
                for (Map.Entry<EntityType, Integer> entry : entityLimits.entrySet()) {
                    EntityType entityType = entry.getKey();
                    int limit = entry.getValue();

                    List<Entity> entities = player.getNearbyEntities(effectiveRadius, effectiveRadius, effectiveRadius)
                            .stream()
                            .filter(entity -> entity.getType() == entityType)
                            .collect(Collectors.toList());

                    if (entities.size() > limit) {
                        entities.subList(limit, entities.size()).forEach(Entity::remove);
                    }

                    // Check display names against blacklist
                    entities.forEach(this::checkAndRenameEntity);
                }

                // Also check entities that might not be in the limits list
                player.getNearbyEntities(effectiveRadius, effectiveRadius, effectiveRadius)
                    .forEach(this::checkAndRenameEntity);
            });
        }
    }

    /**
     * Event handler for the EntitySpawnEvent.
     * Calculates the distance between the spawn location and the world's spawn location.
     * If the spawn location is within the spawnRadius, the event is canceled to prevent the entity from spawning.
     * Checks the entity's display name against the blacklist and renames it if necessary.
     *
     * @param event The EntitySpawnEvent.
     */
    @EventHandler
    public void onEntitySpawn(EntitySpawnEvent event) {
        Location spawnLocation = event.getLocation();
        Location worldSpawn = new Location(spawnLocation.getWorld(), 0, 0, 0);

        // Check spawn radius and block only mobs not in the whitelist, allowing items to spawn
        Entity entity = event.getEntity();
        if (spawnLocation.distance(worldSpawn) <= spawnRadius 
            && entity instanceof Mob 
            && !(entity instanceof Item) 
            && !spawnWhitelist.contains(event.getEntityType())) {
            event.setCancelled(true);
            return;
        }

        // Check entity name against blacklist
        checkAndRenameEntity(event.getEntity());
    }

    /**
     * Handles commands for the plugin, specifically the "entitylimiter" command.
     *
     * @param sender The command sender.
     * @param command The command object.
     * @param label The alias of the command which was used.
     * @param args The command arguments.
     * @return true if the command was handled successfully, false otherwise.
     */
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("entitylimiter")) {
            return false;
        }

        // Only allow console and ops
        if (!(sender instanceof ConsoleCommandSender) && !sender.isOp()) {
            sender.sendMessage("§cOnly operators and console can use this command!");
            return true;
        }

        if (args.length != 1 || !args[0].equalsIgnoreCase("reload")) {
            sender.sendMessage("§cUsage: /entitylimiter reload");
            return true;
        }

        // Reload config
        reloadConfig();
        loadConfig();
        sender.sendMessage("§aAnarchadiaEntityLimiter configuration reloaded!");
        return true;
    }
}