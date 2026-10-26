package com.liu.ccpump;

import com.liu.ccpump.block.MachineBlock;
import com.liu.ccpump.block.PumpPipeBlock;
import com.liu.ccpump.command.PumpCommand;
import com.liu.ccpump.config.ConfigManager;
import com.liu.ccpump.config.MachineConfig;
import com.liu.ccpump.config.PumpConfig;
import com.liu.ccpump.data.DataStore;
import com.liu.ccpump.gui.PumpGui;
import com.liu.ccpump.gui.PumpGuiListener;
import com.liu.ccpump.listener.CustomCropsReloadListener;
import com.liu.ccpump.listener.PumpInteractListener;
import com.liu.ccpump.listener.SprinklerConnectionListener;
import com.liu.ccpump.gui.MachineGuiListener;
import com.liu.ccpump.listener.MachineDropListener;
import com.liu.ccpump.listener.MachineInteractListener;
import com.liu.ccpump.manager.PumpManager;
import com.liu.ccpump.machine.MachineManager;
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
    private MachineManager machineManager;
    private DataStore dataStore;
    private PumpGui gui;
    private BukkitTask tickTask;
    private BukkitTask saveTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.configManager = new ConfigManager(this);
        this.configManager.load();

        this.dataStore = new DataStore(this);
        this.pumpManager = new PumpManager(this, configManager);
        this.machineManager = new MachineManager(this, configManager);
        this.gui = new PumpGui(this);
        this.dataStore.load(pumpManager, machineManager);

        this.registerBlockIds();
        getServer().getPluginManager().registerEvents(new CustomCropsReloadListener(this), this);
        getServer().getPluginManager().registerEvents(new PumpGuiListener(this), this);
        getServer().getPluginManager().registerEvents(new SprinklerConnectionListener(this), this);
        getServer().getPluginManager().registerEvents(new PumpInteractListener(this), this);
        getServer().getPluginManager().registerEvents(new MachineInteractListener(this), this);
        getServer().getPluginManager().registerEvents(new MachineDropListener(this), this);
        getServer().getPluginManager().registerEvents(new MachineGuiListener(this), this);

        PumpCommand command = new PumpCommand(this);
        PluginCommand pluginCommand = getCommand("ccpump");
        if (pluginCommand != null) {
            pluginCommand.setExecutor(command);
            pluginCommand.setTabCompleter(command);
        }

        PackInstaller.install(this);
        startTasks();

        getLogger().info("已加载 " + pumpManager.pumpCount() + " 台水泵、" + pumpManager.pipeCount()
                + " 根水管、" + machineManager.machineCount() + " 台机器。");
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
        // 关服时不要主动关界面：此时玩家正在断开，closeInventory 容易炸
        if (dataStore != null && pumpManager != null) {
            dataStore.save(pumpManager, machineManager);
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
        // 水管只有一种方块，不需要额外的变体 ID。
        for (String pipeItem : configManager.pipeItems()) {
            register(pipeItem, false);
        }
        for (MachineConfig machineConfig : configManager.machines()) {
            registerMachine(machineConfig);
        }
    }

    private void registerMachine(MachineConfig machineConfig) {
        String itemId = machineConfig.item();
        CustomCropsBlock existing = Registries.BLOCKS.get(itemId);
        if (existing instanceof MachineBlock machineBlock
                && machineBlock.typeKey().equals(machineConfig.key())) {
            return;
        }
        if (existing != null) {
            getLogger().warning("物品 ID " + itemId + " 已被 CustomCrops 注册为 "
                    + existing.type() + "，无法再注册为机器，请更换配置中的 item。");
            return;
        }
        Registries.BLOCKS.register(itemId, new MachineBlock(this, itemId, machineConfig.key()));
        debug(() -> "注册" + machineConfig.kind().displayName() + " 物品 ID: " + itemId);
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
        pumpManager.clearAppearanceCache();
        pumpManager.clampToConfig();
        pumpManager.invalidateNetworks();
        machineManager.applyConfig();
        startTasks();
    }

    private void startTasks() {
        long interval = Math.max(1L, configManager.tickInterval());
        tickTask = getServer().getScheduler().runTaskTimer(this, () -> {
            pumpManager.tick();
            machineManager.tick();
        }, interval, interval);

        long saveInterval = configManager.saveInterval();
        if (saveInterval > 0L) {
            saveTask = getServer().getScheduler().runTaskTimer(this,
                    () -> dataStore.save(pumpManager, machineManager), saveInterval, saveInterval);
        }
    }

    public ConfigManager configManager() {
        return configManager;
    }

    public PumpManager pumpManager() {
        return pumpManager;
    }

    public MachineManager machineManager() {
        return machineManager;
    }

    public DataStore dataStore() {
        return dataStore;
    }

    public PumpGui gui() {
        return gui;
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
