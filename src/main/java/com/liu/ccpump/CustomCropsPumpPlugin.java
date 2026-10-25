package com.liu.ccpump;

import com.liu.ccpump.block.PumpPipeBlock;
import com.liu.ccpump.command.PumpCommand;
import com.liu.ccpump.config.ConfigManager;
import com.liu.ccpump.config.PipeConfig;
import com.liu.ccpump.config.PumpConfig;
import com.liu.ccpump.data.DataStore;
import com.liu.ccpump.listener.CustomCropsReloadListener;
import com.liu.ccpump.manager.PumpManager;
import com.liu.ccpump.util.PackInstaller;
import net.momirealms.customcrops.api.BukkitCustomCropsPlugin;
import net.momirealms.customcrops.api.core.Registries;
import net.momirealms.customcrops.api.core.block.CustomCropsBlock;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.function.Supplier;

public final class CustomCropsPumpPlugin extends JavaPlugin {

    private ConfigManager configManager;
    private PumpManager pumpManager;
    private DataStore dataStore;
    private BukkitTask tickTask;
    private BukkitTask saveTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.configManager = new ConfigManager(this);
        this.configManager.load();

        this.dataStore = new DataStore(this);
        this.pumpManager = new PumpManager(this, configManager);
        this.dataStore.load(pumpManager);

        this.registerBlockIds();
        getServer().getPluginManager().registerEvents(new CustomCropsReloadListener(this), this);

        PumpCommand command = new PumpCommand(this);
        PluginCommand pluginCommand = getCommand("ccpump");
        if (pluginCommand != null) {
            pluginCommand.setExecutor(command);
            pluginCommand.setTabCompleter(command);
        }

        PackInstaller.install(this);
        startTasks();

        getLogger().info("已加载 " + pumpManager.pumpCount() + " 台水泵、" + pumpManager.pipeCount() + " 根水管。");
    }

    @Override
    public void onDisable() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        if (saveTask != null) {
            saveTask.cancel();
            saveTask = null;
        }
        if (dataStore != null && pumpManager != null) {
            dataStore.save(pumpManager);
        }
    }

    /**
     * 把配置中的水泵/水管 item ID 注册到 CustomCrops 的 BLOCKS 表。
     * 注册只影响放置/破坏/交互事件的转发，不创建任何方块状态。
     */
    public void registerBlockIds() {
        if (configManager == null) {
            return;
        }
        for (PumpConfig pumpConfig : configManager.pumps()) {
            register(pumpConfig.item(), true);
        }
        for (PipeConfig pipeConfig : configManager.pipes()) {
            register(pipeConfig.item(), false);
        }
    }

    private void register(String itemId, boolean pump) {
        CustomCropsBlock existing = Registries.BLOCKS.get(itemId);
        if (existing instanceof PumpPipeBlock) {
            return;
        }
        if (existing != null) {
            getLogger().warning("物品 ID " + itemId + " 已被 CustomCrops 注册为 "
                    + existing.type() + "，无法再注册为水泵/水管，请更换配置中的 item。");
            return;
        }
        Registries.BLOCKS.register(itemId, new PumpPipeBlock(this, itemId, pump));
        debug(() -> "注册 " + (pump ? "水泵" : "水管") + " 物品 ID: " + itemId);
    }

    public void reloadPlugin() {
        configManager.load();
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        if (saveTask != null) {
            saveTask.cancel();
            saveTask = null;
        }
        registerBlockIds();
        PackInstaller.install(this);
        pumpManager.clampToConfig();
        pumpManager.invalidateNetworks();
        startTasks();
    }

    private void startTasks() {
        long interval = Math.max(1L, configManager.tickInterval());
        tickTask = getServer().getScheduler().runTaskTimer(this, pumpManager::tick, interval, interval);

        long saveInterval = configManager.saveInterval();
        if (saveInterval > 0L) {
            saveTask = getServer().getScheduler().runTaskTimer(this,
                    () -> dataStore.save(pumpManager), saveInterval, saveInterval);
        }
    }

    public ConfigManager configManager() {
        return configManager;
    }

    public PumpManager pumpManager() {
        return pumpManager;
    }

    public DataStore dataStore() {
        return dataStore;
    }

    public BukkitCustomCropsPlugin customCrops() {
        return BukkitCustomCropsPlugin.getInstance();
    }

    public void debug(Supplier<String> message) {
        if (configManager != null && configManager.debug()) {
            getLogger().info("[debug] " + message.get());
        }
    }
}
