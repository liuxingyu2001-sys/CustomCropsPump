package com.liu.ccpump.listener;

import com.liu.ccpump.CustomCropsPumpPlugin;
import com.liu.ccpump.data.MachineData;
import net.momirealms.customcrops.api.event.DropItemActionEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * 把收割机收割时 CustomCrops 产生的掉落直接收进机器，而不是掉在地上。
 *
 * <p>为什么不用“先掉地上再吸走”：那样有竞态、会有一地掉落物、多台机器还会抢。
 * CustomCrops 的掉落动作是延后一两个 tick 触发的，所以收割前先在
 * {@code MachineManager.pendingDrops} 里按作物坐标登记归属，这里按坐标认领。
 */
public final class MachineDropListener implements Listener {

    private final CustomCropsPumpPlugin plugin;

    public MachineDropListener(CustomCropsPumpPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrop(DropItemActionEvent event) {
        MachineData machine = plugin.machineManager().takeDrop(event.location());
        if (machine == null) {
            return;
        }
        MachineData current = plugin.machineManager().machineAt(machine.key());
        if (current == null) {
            return;
        }
        // 取消事件 = 不在地面生成掉落物，物品直接进机器
        event.setCancelled(true);
        plugin.machineManager().store(current,
                plugin.configManager().machine(current.typeKey()), event.item());
    }
}
