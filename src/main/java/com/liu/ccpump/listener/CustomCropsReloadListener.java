package com.liu.ccpump.listener;

import com.liu.ccpump.CustomCropsPumpPlugin;
import net.momirealms.customcrops.api.event.CustomCropsReloadEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * /cc reload 会清空 Registries.BLOCKS，因此每次重载后都要重新注册水泵/水管 ID。
 */
public final class CustomCropsReloadListener implements Listener {

    private final CustomCropsPumpPlugin plugin;

    public CustomCropsReloadListener(CustomCropsPumpPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCustomCropsReload(CustomCropsReloadEvent event) {
        plugin.registerBlockIds();
        plugin.debug(() -> "检测到 CustomCrops 重载，已重新注册水泵/水管 ID。");
    }
}
