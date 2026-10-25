package com.liu.ccpump.config;

import com.liu.ccpump.CustomCropsPumpPlugin;
import com.liu.ccpump.util.Messages;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * config.yml / messages.yml 的读取与缓存。
 */
public final class ConfigManager {

    private final CustomCropsPumpPlugin plugin;

    private long tickInterval = 20L;
    private long saveInterval = 6000L;
    private long sourceCheckInterval = 40L;
    private long validateInterval = 1200L;
    private long networkCacheTicks = 100L;
    private boolean debug;

    private boolean packAutoInstall = true;
    private boolean packOverwrite;

    private final Map<String, PumpConfig> pumps = new LinkedHashMap<>();
    private final Map<String, PumpConfig> pumpsByItem = new HashMap<>();
    private final Map<String, PipeConfig> pipes = new LinkedHashMap<>();
    private final Map<String, PipeConfig> pipesByItem = new HashMap<>();

    private final Map<Material, Integer> fuels = new EnumMap<>(Material.class);
    private final Set<Material> blockedFuels = EnumSet.noneOf(Material.class);

    private Messages messages;

    public ConfigManager(CustomCropsPumpPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        pumps.clear();
        pumpsByItem.clear();
        pipes.clear();
        pipesByItem.clear();
        fuels.clear();
        blockedFuels.clear();

        plugin.reloadConfig();
        FileConfiguration config = plugin.getConfig();

        tickInterval = Math.max(1L, config.getLong("tick-interval", 20L));
        saveInterval = Math.max(0L, config.getLong("save-interval", 6000L));
        sourceCheckInterval = Math.max(1L, config.getLong("source-check-interval", 40L));
        validateInterval = Math.max(0L, config.getLong("validate-interval", 1200L));
        networkCacheTicks = Math.max(1L, config.getLong("network-cache-ticks", 100L));
        debug = config.getBoolean("debug", false);

        packAutoInstall = config.getBoolean("craftengine-pack.auto-install", true);
        packOverwrite = config.getBoolean("craftengine-pack.overwrite", false);

        if (config.contains("charge", true)) {
            loadCharge(config.getConfigurationSection("charge"));
        } else {
            // 旧版 config.yml 没有 charge 段：使用内置默认值，避免升级后无法充能。
            loadDefaultCharge();
            plugin.getLogger().warning("config.yml 缺少 charge 段，已使用内置默认充能表；"
                    + "如需自定义燃料，请参考 README 添加 charge 段。");
        }
        if (fuels.isEmpty()) {
            plugin.getLogger().warning("charge.fuels 为空，水泵将无法通过物品充能。");
        }
        loadPumps(config.getConfigurationSection("pumps"));
        loadPipes(config.getConfigurationSection("pipes"));
        this.messages = Messages.load(plugin);

        if (pumps.isEmpty()) {
            plugin.getLogger().warning("config.yml 中没有有效的水泵定义，水泵将不会工作。");
        }
        if (pipes.isEmpty()) {
            plugin.getLogger().warning("config.yml 中没有有效的水管定义。");
        }
    }

    private void loadPumps(ConfigurationSection section) {
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            ConfigurationSection sec = section.getConfigurationSection(key);
            if (sec == null) {
                continue;
            }
            String item = sec.getString("item", "").trim();
            if (item.isEmpty()) {
                plugin.getLogger().warning("水泵 " + key + " 没有配置 item，已跳过。");
                continue;
            }
            if (pumpsByItem.containsKey(item)) {
                plugin.getLogger().warning("水泵物品 ID 重复: " + item + "（" + key + "），已跳过。");
                continue;
            }

            int capacity = Math.max(1, sec.getInt("capacity", 12));
            int pumpRate = Math.max(0, sec.getInt("pump-rate", 2));
            int pushRate = Math.max(1, sec.getInt("push-rate", 2));
            int bucketAmount = Math.max(1, sec.getInt("bucket-amount", 4));
            int chargeCapacity = Math.max(0, sec.getInt("charge-capacity", 1000));
            int chargePerCycle = chargeCapacity <= 0 ? 0 : Math.max(1, sec.getInt("charge-per-cycle", 1));
            boolean allowToggle = sec.getBoolean("allow-toggle", true);

            boolean sourceRequired = sec.getBoolean("source.required", true);
            int sourceRadius = Math.max(0, sec.getInt("source.radius", 2));
            boolean sourceConsume = sec.getBoolean("source.consume", false);

            int maxPipes = Math.max(1, sec.getInt("network.max-pipes", 64));
            int maxSprinklers = Math.max(1, sec.getInt("network.max-sprinklers", 16));
            int maxDistance = Math.max(1, sec.getInt("network.max-distance", 32));

            boolean effectsEnabled = sec.getBoolean("effects.enabled", true);
            Particle particle = parseParticle(sec.getString("effects.particle", "SPLASH"), key);
            String sound = sec.getString("effects.sound", "minecraft:block.water.ambient");
            float volume = (float) Math.max(0.0D, sec.getDouble("effects.volume", 0.35D));
            float pitch = (float) Math.max(0.01D, sec.getDouble("effects.pitch", 1.2D));
            int effectInterval = Math.max(1, sec.getInt("effects.interval", 40));

            PumpConfig pump = new PumpConfig(
                    key,
                    item,
                    capacity,
                    pumpRate,
                    pushRate,
                    bucketAmount,
                    chargeCapacity,
                    chargePerCycle,
                    allowToggle,
                    new PumpConfig.Source(sourceRequired, sourceRadius, sourceConsume),
                    new PumpConfig.Network(maxPipes, maxSprinklers, maxDistance),
                    new PumpConfig.Effects(effectsEnabled, particle, sound, volume, pitch, effectInterval)
            );
            pumps.put(key, pump);
            pumpsByItem.put(item, pump);
        }
    }

    private void loadPipes(ConfigurationSection section) {
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            ConfigurationSection sec = section.getConfigurationSection(key);
            if (sec == null) {
                continue;
            }
            String item = sec.getString("item", "").trim();
            if (item.isEmpty()) {
                plugin.getLogger().warning("水管 " + key + " 没有配置 item，已跳过。");
                continue;
            }
            if (pipesByItem.containsKey(item)) {
                plugin.getLogger().warning("水管物品 ID 重复: " + item + "（" + key + "），已跳过。");
                continue;
            }
            PipeConfig pipe = new PipeConfig(key, item);
            pipes.put(key, pipe);
            pipesByItem.put(item, pipe);
        }
    }

    private void loadDefaultCharge() {
        fuels.put(Material.REDSTONE, 50);
        fuels.put(Material.LAPIS_LAZULI, 100);
        fuels.put(Material.RAW_IRON, 200);
        fuels.put(Material.RAW_GOLD, 250);
        fuels.put(Material.DIAMOND, 1000);
        blockedFuels.add(Material.IRON_INGOT);
        blockedFuels.add(Material.GOLD_INGOT);
    }

    private void loadCharge(ConfigurationSection section) {
        if (section == null) {
            return;
        }
        ConfigurationSection fuelSection = section.getConfigurationSection("fuels");
        if (fuelSection != null) {
            for (String key : fuelSection.getKeys(false)) {
                ConfigurationSection fuel = fuelSection.getConfigurationSection(key);
                if (fuel == null) {
                    continue;
                }
                String item = fuel.getString("item", "").trim();
                int amount = Math.max(1, fuel.getInt("amount", 1));
                Material material = parseMaterial(item);
                if (material == null) {
                    plugin.getLogger().warning("充能物品 " + key + " 的 item 无效: " + item + "，已跳过。");
                    continue;
                }
                if (fuels.put(material, amount) != null) {
                    plugin.getLogger().warning("充能物品重复: " + item + "（" + key + "），后一个值生效。");
                }
            }
        }
        for (String raw : section.getStringList("blocked-items")) {
            Material material = parseMaterial(raw);
            if (material == null) {
                plugin.getLogger().warning("charge.blocked-items 中的物品无效: " + raw + "，已跳过。");
                continue;
            }
            blockedFuels.add(material);
        }
    }

    private Material parseMaterial(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (!value.contains(":")) {
            value = "minecraft:" + value;
        }
        NamespacedKey key = NamespacedKey.fromString(value);
        if (key == null) {
            return null;
        }
        return Registry.MATERIAL.get(key);
    }

    private Particle parseParticle(String name, String pumpKey) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return Particle.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("水泵 " + pumpKey + " 的粒子名称无效: " + name + "，将只播放声音。");
            return null;
        }
    }

    public PumpConfig pump(String key) {
        return pumps.get(key);
    }

    public PumpConfig pumpByItem(String item) {
        return pumpsByItem.get(item);
    }

    public PipeConfig pipe(String key) {
        return pipes.get(key);
    }

    public PipeConfig pipeByItem(String item) {
        return pipesByItem.get(item);
    }

    public Collection<PumpConfig> pumps() {
        return pumps.values();
    }

    public Collection<PipeConfig> pipes() {
        return pipes.values();
    }

    public boolean isPumpItem(String item) {
        return pumpsByItem.containsKey(item);
    }

    public boolean isPipeItem(String item) {
        return pipesByItem.containsKey(item);
    }

    /**
     * 返回该物品能提供的动力；被禁用的物品返回 null。
     */
    public Integer fuelValue(Material material) {
        if (material == null || blockedFuels.contains(material)) {
            return null;
        }
        return fuels.get(material);
    }

    public boolean isBlockedFuel(Material material) {
        return material != null && blockedFuels.contains(material);
    }

    public long tickInterval() {
        return tickInterval;
    }

    public long saveInterval() {
        return saveInterval;
    }

    public long sourceCheckInterval() {
        return sourceCheckInterval;
    }

    public long validateInterval() {
        return validateInterval;
    }

    public long networkCacheTicks() {
        return networkCacheTicks;
    }

    public boolean debug() {
        return debug;
    }

    public boolean packAutoInstall() {
        return packAutoInstall;
    }

    public boolean packOverwrite() {
        return packOverwrite;
    }

    public Messages messages() {
        return messages;
    }
}
