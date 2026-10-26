package com.liu.ccpump.config;

import com.liu.ccpump.CustomCropsPumpPlugin;
import com.liu.ccpump.util.Messages;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
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
    private final Map<String, MachineConfig> machines = new LinkedHashMap<>();
    private final Map<String, MachineConfig> machinesByItem = new HashMap<>();
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
        machines.clear();
        machinesByItem.clear();
        fuels.clear();
        blockedFuels.clear();

        mergeDefaults();
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
        loadMachines(config.getConfigurationSection("machines"));
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
            // 耐久：每完成一次实际工作消耗 1 点，0 表示不做耐久限制
            int durability = Math.max(0, sec.getInt("durability", 86400));

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
                    durability,
                    allowToggle,
                    new PumpConfig.Source(sourceRequired, sourceRadius, sourceConsume),
                    new PumpConfig.Network(maxPipes, maxSprinklers, maxDistance),
                    new PumpConfig.Effects(effectsEnabled, particle, sound, volume, pitch, effectInterval)
            );
            pumps.put(key, pump);
            pumpsByItem.put(item, pump);
        }
    }

    /**
     * 读取机器配置（收割机 / 洒水机 / 施肥机）。
     *
     * <p>三种机器共用同一套解析：{@code type} 决定行为，其余字段按需读取，
     * 用不上的字段保持默认值。某一种机器整体缺失时补上内置默认值，
     * 避免升级后机器物品直接不可用；只缺其中一段时只补那一段。
     */
    private void loadMachines(ConfigurationSection section) {
        if (section != null) {
            for (String key : section.getKeys(false)) {
                ConfigurationSection sec = section.getConfigurationSection(key);
                if (sec == null) {
                    continue;
                }
                MachineKind kind = MachineKind.byId(sec.getString("type", key));
                if (kind == null) {
                    plugin.getLogger().warning("机器 " + key + " 的 type 无法识别（"
                            + sec.getString("type") + "），已跳过；可选值: harvester / sprinkler / fertilizer。");
                    continue;
                }
                String item = sec.getString("item", "").trim();
                if (item.isEmpty()) {
                    plugin.getLogger().warning("机器 " + key + "（" + kind.displayName() + "）没有配置 item，已跳过。");
                    continue;
                }
                if (machinesByItem.containsKey(item)) {
                    plugin.getLogger().warning("机器物品 ID 重复: " + item + "（" + key + "），已跳过。");
                    continue;
                }
                MachineConfig machine = parseMachine(key, kind, item, sec);
                machines.put(key, machine);
                machinesByItem.put(item, machine);
            }
        }
        for (MachineKind kind : MachineKind.values()) {
            if (machines.values().stream().anyMatch(machine -> machine.kind() == kind)) {
                continue;
            }
            MachineConfig fallback = defaultMachine(kind);
            machines.putIfAbsent(fallback.key(), fallback);
            machinesByItem.put(fallback.item(), fallback);
            plugin.getLogger().warning("config.yml 里没有 " + kind.displayName()
                    + "（type: " + kind.id() + "），已使用内置默认配置。");
        }
    }

    private MachineConfig parseMachine(String key, MachineKind kind, String item, ConfigurationSection sec) {
        int radius = clamp(sec.getInt("radius", switch (kind) {
            case HARVESTER -> 4;
            case SPRINKLER, FERTILIZER -> 3;
        }), 1, 16);
        int verticalRadius = clamp(sec.getInt("vertical-radius", switch (kind) {
            case HARVESTER -> 2;
            case SPRINKLER, FERTILIZER -> 1;
        }), 0, 8);
        int cycle = Math.max(5, sec.getInt("cycle", switch (kind) {
            case HARVESTER, SPRINKLER -> 40;
            case FERTILIZER -> 100;
        }));
        int maxPerCycle = clamp(sec.getInt("max-per-cycle", switch (kind) {
            case HARVESTER -> 6;
            case SPRINKLER -> 8;
            case FERTILIZER -> 4;
        }), 1, 64);
        int chargeCapacity = Math.max(0, sec.getInt("charge-capacity", switch (kind) {
            case HARVESTER -> 2400;
            case SPRINKLER -> 1200;
            case FERTILIZER -> 1600;
        }));
        int chargePerAction = chargeCapacity <= 0 ? 0 : Math.max(1,
                sec.getInt("charge-per-action", switch (kind) {
                    case HARVESTER -> 2;
                    case SPRINKLER -> 1;
                    case FERTILIZER -> 4;
                }));
        int bufferSlots = clamp(sec.getInt("buffer-slots", switch (kind) {
            case HARVESTER -> 18;
            case SPRINKLER -> 0;
            case FERTILIZER -> 9;
        }), 0, 45);
        boolean acceptsInput = sec.getBoolean("accepts-input", kind == MachineKind.FERTILIZER);
        MachineConfig.Water water = kind == MachineKind.SPRINKLER
                ? parseWater(sec.getConfigurationSection("water"))
                : MachineConfig.Water.NONE;
        MachineConfig.Targets targets = new MachineConfig.Targets(
                sec.getBoolean("custom-crops", kind != MachineKind.SPRINKLER),
                sec.getBoolean("vanilla-crops", kind == MachineKind.HARVESTER),
                sec.getBoolean("farmland", kind == MachineKind.SPRINKLER));
        return new MachineConfig(key, kind, item, radius, verticalRadius, cycle, maxPerCycle,
                chargeCapacity, chargePerAction, bufferSlots, acceptsInput, water, targets);
    }

    private MachineConfig.Water parseWater(ConfigurationSection sec) {
        if (sec == null) {
            plugin.getLogger().warning("洒水机缺少 water 段，已使用内置默认水箱配置。");
            return defaultWater();
        }
        int capacity = Math.max(1, sec.getInt("capacity", 16));
        int perAction = clamp(sec.getInt("per-action", 1), 1, 64);
        int refill = Math.max(0, sec.getInt("refill", 1));
        int bucketAmount = Math.max(1, sec.getInt("bucket-amount", 8));
        boolean sourceRequired = sec.getBoolean("source.required", true);
        int sourceRadius = Math.max(1, sec.getInt("source.radius", 2));
        boolean sourceConsume = sec.getBoolean("source.consume", false);
        return new MachineConfig.Water(capacity, perAction, refill, bucketAmount,
                new MachineConfig.Water.Source(sourceRequired, sourceRadius, sourceConsume));
    }

    private MachineConfig defaultMachine(MachineKind kind) {
        return switch (kind) {
            case HARVESTER -> new MachineConfig("harvester", kind, "ccpump:harvester",
                    4, 2, 40, 6, 2400, 2, 18, false,
                    MachineConfig.Water.NONE, new MachineConfig.Targets(true, true, false));
            case SPRINKLER -> new MachineConfig("sprinkler_machine", kind, "ccpump:sprinkler_machine",
                    3, 1, 40, 8, 1200, 1, 0, false,
                    defaultWater(), new MachineConfig.Targets(false, false, true));
            case FERTILIZER -> new MachineConfig("fertilizer_machine", kind, "ccpump:fertilizer_machine",
                    3, 1, 100, 4, 1600, 4, 9, true,
                    MachineConfig.Water.NONE, new MachineConfig.Targets(true, false, false));
        };
    }

    private static MachineConfig.Water defaultWater() {
        return new MachineConfig.Water(16, 1, 1, 8,
                new MachineConfig.Water.Source(true, 2, false));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
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
            boolean connections = sec.getBoolean("connections", true);
            String variantFormat = sec.getString("variant-format", "{item}_{shape}");
            if (variantFormat == null || variantFormat.isBlank()) {
                variantFormat = "{item}_{shape}";
            }
            PipeConfig pipe = new PipeConfig(key, item, connections, variantFormat);
            pipes.put(key, pipe);
            pipesByItem.put(item, pipe);
            if (connections) {
                // 形态方块也要登记进查找表，这样切换外观后 pipeByItem()
                // 和方块注册都能认出它们。
                for (String shape : PipeConfig.SHAPES) {
                    String variant = pipe.shapeItem(shape);
                    if (variant == null || variant.equals(item)) {
                        continue;
                    }
                    PipeConfig previous = pipesByItem.putIfAbsent(variant, pipe);
                    if (previous != null && previous != pipe) {
                        plugin.getLogger().warning("水管形态 ID 冲突: " + variant
                                + "（" + key + "），已保留先注册的那一个。");
                    }
                }
            }
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

    /**
     * 所有需要在 CustomCrops 里注册的水管物品 ID：基础物品 + 64 个连接变体。
     */
    public Collection<String> pipeItems() {
        return pipesByItem.keySet();
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

    /** 全部机器配置（按配置顺序）。 */
    public Collection<MachineConfig> machines() {
        return machines.values();
    }

    /** 按配置键取机器；不存在返回 null。 */
    public MachineConfig machine(String key) {
        return machines.get(key);
    }

    /** 按物品 ID 反查机器；不是机器物品返回 null。 */
    public MachineConfig machineByItem(String item) {
        return item == null ? null : machinesByItem.get(item);
    }

    /** 某一种机器的全部配置（可能有多个等级）。 */
    public Collection<MachineConfig> machines(MachineKind kind) {
        return machines.values().stream().filter(machine -> machine.kind() == kind).toList();
    }

    public Messages messages() {
        return messages;
    }

    /**
     * 把 jar 内默认 config.yml 里“磁盘配置没有的键”补写到磁盘。
     *
     * <p>插件升级后新增的配置项不会自动出现在老服务器的 config.yml 里
     * （{@code copyDefaults} 只影响内存读取，不写盘），玩家既看不到新选项，
     * 也没法改。这里用和 messages.yml 相同的办法：以 jar 内文件为默认值，
     * 只补缺失的键，已有值一律保留，然后回写磁盘。
     */
    private void mergeDefaults() {
        File file = new File(plugin.getDataFolder(), "config.yml");
        if (!file.exists()) {
            // 文件被删了（reload 时不会走 saveDefaultConfig），重新生成一份
            plugin.saveDefaultConfig();
            return;
        }
        YamlConfiguration current = YamlConfiguration.loadConfiguration(file);
        // 1.1.x 的收割机配置段已经改成 machines 段，老文件里残留的 harvesters 段
        // 不再有任何作用，顺手清掉，避免玩家以为是改这里生效。
        if (current.isConfigurationSection("harvesters")) {
            current.set("harvesters", null);
            plugin.getLogger().info("已移除 config.yml 里过时的 harvesters 段（机器配置改到 machines 段）。");
        }
        try (InputStream in = plugin.getResource("config.yml")) {
            if (in == null) {
                return;
            }
            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(in, StandardCharsets.UTF_8));
            current.setDefaults(defaults);
            current.options().copyDefaults(true);
            current.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("补全 config.yml 默认配置失败: " + e.getMessage());
        }
    }
}
