package com.liu.ccpump.gui;

import com.liu.ccpump.CustomCropsPumpPlugin;
import com.liu.ccpump.data.MachineData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

/**
 * 机器界面的点击/拖拽/关闭。
 *
 * <p>底部玩家背包的普通点击一律放行（那是玩家自己的东西）；
 * 顶部界面里只放行 {@link MachineGui} 允许的动作，其余全部取消。
 */
public final class MachineGuiListener implements Listener {

    private final CustomCropsPumpPlugin plugin;

    public MachineGuiListener(CustomCropsPumpPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof MachineGuiHolder holder)) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            event.setCancelled(true);
            return;
        }
        plugin.machineManager().gui().handleClick(player, holder, event);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof MachineGuiHolder holder)) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            event.setCancelled(true);
            return;
        }
        plugin.machineManager().gui().handleDrag(player, holder, event);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof MachineGuiHolder holder)) {
            return;
        }
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        MachineData machine = plugin.machineManager().machineAt(holder.key());
        if (machine != null) {
            plugin.machineManager().gui().syncFromView(machine);
        }
        plugin.machineManager().gui().forget(player);
    }
}
