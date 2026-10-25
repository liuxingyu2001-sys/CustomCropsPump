package com.liu.ccpump.listener;

import com.liu.ccpump.CustomCropsPumpPlugin;
import com.liu.ccpump.data.BlockKey;
import net.momirealms.customcrops.api.event.SprinklerBreakEvent;
import net.momirealms.customcrops.api.event.SprinklerPlaceEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * 洒水器也是水管的一个“接口”：放下或拆掉洒水器时，
 * 相邻水管要重新计算连接模型。
 */
public final class SprinklerConnectionListener implements Listener {

    private final CustomCropsPumpPlugin plugin;

    public SprinklerConnectionListener(CustomCropsPumpPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSprinklerPlace(SprinklerPlaceEvent event) {
        plugin.pumpManager().schedulePipeRefresh(BlockKey.of(event.location()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSprinklerBreak(SprinklerBreakEvent event) {
        plugin.pumpManager().schedulePipeRefresh(BlockKey.of(event.location()));
    }
}
