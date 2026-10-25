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
 * 不创建 CustomCrops 方块状态，数据完全由插件自己的 data.yml 保存。
 */
public final class PumpPipeBlock extends AbstractCustomCropsBlock {

    private final CustomCropsPumpPlugin plugin;
    private final String itemId;
    private final boolean pump;

    public PumpPipeBlock(CustomCropsPumpPlugin plugin, String itemId, boolean pump) {
        super(Key.key("ccpump", pump ? "water_pump" : "water_pipe"));
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
            plugin.pumpManager().interactPump(event.player(), location, event.hand(), event.itemInHand());
        } else {
            plugin.pumpManager().interactPipe(event.player(), location);
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
