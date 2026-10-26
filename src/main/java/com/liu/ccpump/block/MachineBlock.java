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
 * 只负责把 CustomCrops 的放置/破坏/交互事件转发给 {@code MachineManager}。
 *
 * <p>机器状态（动力、水量、仓储）全部存在插件自己的 SQLite 库里，
 * 不使用 CustomCrops 的方块状态，所以 {@link #restore} 故意留空——
 * 区块卸载/`/cc reload` 都不会把机器数据弄丢。
 */
public final class MachineBlock extends AbstractCustomCropsBlock {

    private final CustomCropsPumpPlugin plugin;
    private final String itemId;
    private final String typeKey;

    /** 机制名统一加前缀，避免和方块 ID（{@code ccpump:harvester} 之类）撞在一起。 */
    public static Key mechanicKey(String typeKey) {
        return Key.key("ccpump", "machine_" + typeKey);
    }

    public MachineBlock(CustomCropsPumpPlugin plugin, String itemId, String typeKey) {
        super(mechanicKey(typeKey));
        this.plugin = plugin;
        this.itemId = itemId;
        this.typeKey = typeKey;
    }

    @Override
    public void onPlace(WrappedPlaceEvent event) {
        plugin.machineManager().place(LocationUtils.toBlockLocation(event.location()), itemId);
    }

    @Override
    public void onBreak(WrappedBreakEvent event) {
        plugin.machineManager().breakAt(LocationUtils.toBlockLocation(event.location()));
    }

    @Override
    public void onInteract(WrappedInteractEvent event) {
        if (event.hand() != EquipmentSlot.HAND) {
            return;
        }
        // 右键开界面统一由 MachineInteractListener 在 LOWEST 接管，
        // 这里只兜底拦掉原版放置（否则拿着机器右键会把方块摆到相邻位置）。
        event.setCancelled(true);
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
        return NamedTextColor.GOLD;
    }

    public String typeKey() {
        return typeKey;
    }
}
