package com.liu.ccpump.gui;

import com.liu.ccpump.data.BlockKey;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * 机器界面的持有者：记住是哪一台机器（方块坐标）打开的。
 */
public final class MachineGuiHolder implements InventoryHolder {

    private final BlockKey key;
    private Inventory inventory;

    public MachineGuiHolder(BlockKey key) {
        this.key = key;
    }

    public BlockKey key() {
        return key;
    }

    public void inventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
