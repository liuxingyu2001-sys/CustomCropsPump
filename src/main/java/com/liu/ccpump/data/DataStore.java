package com.liu.ccpump.data;

import com.liu.ccpump.CustomCropsPumpPlugin;
import com.liu.ccpump.config.PipeConfig;
import com.liu.ccpump.config.PumpConfig;
import com.liu.ccpump.manager.PumpManager;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 独立的 data.yml 持久化，不依赖 CustomCrops 的方块状态。
 */
public final class DataStore {

    private final CustomCropsPumpPlugin plugin;
    private final File file;

    public DataStore(CustomCropsPumpPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "data.yml");
    }

    public void load(PumpManager manager) {
        if (!file.exists()) {
            return;
        }
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        int pumps = 0;
        int pipes = 0;

        for (Map<?, ?> raw : config.getMapList("pumps")) {
            String world = string(raw, "world");
            Integer x = integer(raw, "x");
            Integer y = integer(raw, "y");
            Integer z = integer(raw, "z");
            String type = string(raw, "type");
            if (world == null || x == null || y == null || z == null || type == null) {
                plugin.getLogger().warning("data.yml 中存在不完整的水泵记录，已跳过。");
                continue;
            }
            PumpConfig configForType = plugin.configManager().pump(type);
            if (configForType == null) {
                plugin.getLogger().warning("data.yml 中的水泵类型 " + type + " 已不存在，已跳过该水泵。");
                continue;
            }
            PumpInstance instance = new PumpInstance(new BlockKey(world, x, y, z), type);
            instance.water(Math.min(configForType.capacity(), Math.max(0, integer(raw, "water", 0))));
            int loadedCharge = integer(raw, "charge", -1);
            int chargeCapacity = configForType.chargeCapacity();
            if (chargeCapacity <= 0) {
                instance.charge(0);
            } else if (loadedCharge < 0) {
                // 旧版本没有 charge 字段：补满一次，避免升级后旧水泵立刻停摆。
                instance.charge(chargeCapacity);
            } else {
                instance.charge(Math.min(chargeCapacity, Math.max(0, loadedCharge)));
            }
            instance.enabled(bool(raw, "enabled", true));
            manager.addLoadedPump(instance);
            pumps++;
        }

        for (Map<?, ?> raw : config.getMapList("pipes")) {
            String world = string(raw, "world");
            Integer x = integer(raw, "x");
            Integer y = integer(raw, "y");
            Integer z = integer(raw, "z");
            String type = string(raw, "type");
            if (world == null || x == null || y == null || z == null || type == null) {
                plugin.getLogger().warning("data.yml 中存在不完整的水管记录，已跳过。");
                continue;
            }
            PipeConfig configForType = plugin.configManager().pipe(type);
            if (configForType == null) {
                plugin.getLogger().warning("data.yml 中的水管类型 " + type + " 已不存在，已跳过该水管。");
                continue;
            }
            manager.addLoadedPipe(new PipeInstance(new BlockKey(world, x, y, z), type));
            pipes++;
        }

        if (pumps > 0 || pipes > 0) {
            plugin.getLogger().info("已从 data.yml 恢复 " + pumps + " 台水泵、" + pipes + " 根水管。");
        }
    }

    public void save(PumpManager manager) {
        YamlConfiguration config = new YamlConfiguration();

        List<Map<String, Object>> pumpList = new ArrayList<>();
        for (PumpInstance pump : manager.pumpInstances()) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("world", pump.key().world());
            map.put("x", pump.key().x());
            map.put("y", pump.key().y());
            map.put("z", pump.key().z());
            map.put("type", pump.typeKey());
            map.put("water", pump.water());
            map.put("charge", pump.charge());
            map.put("enabled", pump.enabled());
            pumpList.add(map);
        }
        config.set("pumps", pumpList);

        List<Map<String, Object>> pipeList = new ArrayList<>();
        for (PipeInstance pipe : manager.pipeInstances()) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("world", pipe.key().world());
            map.put("x", pipe.key().x());
            map.put("y", pipe.key().y());
            map.put("z", pipe.key().z());
            map.put("type", pipe.typeKey());
            pipeList.add(map);
        }
        config.set("pipes", pipeList);

        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                plugin.getLogger().warning("无法创建插件数据目录: " + parent);
            }
            config.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("保存 data.yml 失败: " + e.getMessage());
        }
    }

    private static String string(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isEmpty() ? null : text;
    }

    private static Integer integer(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value instanceof Number number ? number.intValue() : null;
    }

    private static int integer(Map<?, ?> map, String key, int fallback) {
        Object value = map.get(key);
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private static boolean bool(Map<?, ?> map, String key, boolean fallback) {
        Object value = map.get(key);
        return value instanceof Boolean bool ? bool : fallback;
    }
}
