package com.liu.ccpump.block;

import com.liu.ccpump.CustomCropsPumpPlugin;
import net.momirealms.customcrops.api.core.block.AbstractCustomCropsBlock;
import net.momirealms.customcrops.api.core.world.CustomCropsBlockState;
import net.momirealms.customcrops.api.core.wrapper.WrappedBreakEvent;
import net.momirealms.customcrops.api.core.wrapper.WrappedInteractEvent;
import net.momirealms.customcrops.api.core.wrapper.WrappedPlaceEvent;
import net.momirealms.customcrops.api.misc.NamedTextColor;
import net.momirealms.customcrops.api.util.LocationUtils;
import net.momirealms.customcrops.common.util.Key;
import org.bukkit.Location;
import org.bukkit.inventory.EquipmentSlot;

/**
 * 只负责“接入” CustomCrops 的放置/破坏/交互事件，真正的逻辑在 PumpManager。
 * 不创建 CustomCrops 方块状态，数据完全由插件自己的 SQLite 数据库保存。
 */
public final class PumpPipeBlock extends AbstractCustomCropsBlock {

    private final CustomCropsPumpPlugin plugin;
    private final String itemId;
    private final boolean pump;

    /**
     * 机制名（{@code type()}）不要跟方块 ID 同名。
     *
     * <p>方块 ID 是 {@code ccpump:water_pump} 这类“放下去是什么”，
     * 机制名是“这台方块按什么逻辑处理事件”。两者混用会让
     * {@code BLOCKS} 里的键和值的 type 长得一样，重载后自检无法区分
     * “我们自己注册的”和“别的插件占了这个 ID”。
     */
    public static Key mechanicKey(boolean pump) {
        return Key.key("ccpump", pump ? "pump" : "pipe");
    }

    public PumpPipeBlock(CustomCropsPumpPlugin plugin, String itemId, boolean pump) {
        super(mechanicKey(pump));
        this.plugin = plugin;
        this.itemId = itemId;
        this.pump = pump;
    }

    @Override
    public void onPlace(WrappedPlaceEvent event) {
        Location location = LocationUtils.toBlockLocation(event.location());
        if (pump) {
            plugin.pumpManager().placePump(location, itemId);
        } else {
            plugin.pumpManager().placePipe(location, itemId);
        }
    }

    @Override
    public void onBreak(WrappedBreakEvent event) {
        plugin.pumpManager().breakAt(LocationUtils.toBlockLocation(event.location()));
    }

    @Override
    public void onInteract(WrappedInteractEvent event) {
        if (event.hand() != EquipmentSlot.HAND) {
            return;
        }
        Location location = LocationUtils.toBlockLocation(event.location());
        if (pump) {
            // 水泵的右键统一由 PumpInteractListener 在 LOWEST 接管（开界面）。
            // 这里只兜底拦掉原版放置，不重复开界面，避免一次右键弹两遍。
            event.setCancelled(true);
        } else {
            plugin.pumpManager().interactPipe(event.player(), location, event.clickedBlockFace());
            // 手持水管右键水管时，由 interactPipe 负责把新水管放到点击的那个面上，
            // 这里必须拦掉原版放置，否则原版会试图把水管塞进脚下这一格。
            event.setCancelled(true);
        }
    }

    @Override
    public boolean isInstance(String id) {
        return itemId.equals(id);
    }

    @Override
    public void restore(Location location, CustomCropsBlockState state) {
        // 本插件不使用 CustomCrops 方块状态。
    }

    @Override
    public NamedTextColor insightColor() {
        return pump ? NamedTextColor.AQUA : NamedTextColor.GRAY;
    }
}
