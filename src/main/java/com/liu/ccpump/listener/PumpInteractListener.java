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
import org.bukkit.inventory.ItemStack;

/**
 * 右键水泵打开界面。
 *
 * <p>为什么不用 CustomCrops 的 {@code onInteract} 来做这件事：
 * CraftEngine 的方块交互事件在 {@code EventPriority.HIGHEST} 才触发，而且带
 * {@code ignoreCancelled = true}，只要中间有任何插件把事件取消掉，水泵就收不到
 * 右键，表现就是“拿红石右键没反应、界面也不开”。
 *
 * <p>这里在 {@code LOWEST} 直接拦下右键：只要点的是本插件的水泵方块就取消事件
 * （顺带挡住原版把手里那块红石摆到相邻位置），再走正常的开界面流程。
 */
public final class PumpInteractListener implements Listener {

    private final CustomCropsPumpPlugin plugin;

    public PumpInteractListener(CustomCropsPumpPlugin plugin) {
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
        Player player = event.getPlayer();
        Location location = block.getLocation();
        if (!plugin.pumpManager().isPumpBlock(location)) {
            return;
        }

        event.setCancelled(true);
        ItemStack itemInHand = player.getInventory().getItemInMainHand();
        plugin.pumpManager().interactPump(player, location, EquipmentSlot.HAND, itemInHand);
    }
}
