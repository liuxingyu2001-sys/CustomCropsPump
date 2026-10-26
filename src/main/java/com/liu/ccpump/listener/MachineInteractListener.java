package com.liu.ccpump.listener;

import com.liu.ccpump.CustomCropsPumpPlugin;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

/**
 * 右键机器打开界面。
 *
 * <p>和水泵一样，这里必须在 {@code LOWEST} 拦：CraftEngine 的方块交互事件在
 * {@code HIGHEST} 才触发且带 {@code ignoreCancelled = true}，
 * 中间只要有插件取消事件，机器就收不到右键。
 * 取消事件同时挡住原版“把手里那块方块摆到相邻位置”的行为。
 */
public final class MachineInteractListener implements Listener {

    private final CustomCropsPumpPlugin plugin;

    public MachineInteractListener(CustomCropsPumpPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        // 客户端会同时发主手和副手的交互包，只认主手
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }
        Location location = block.getLocation();
        if (!plugin.machineManager().isMachineBlock(location)) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        plugin.machineManager().interact(player, location);
    }
}
