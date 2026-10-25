package com.liu.ccpump.gui;

import com.liu.ccpump.data.BlockKey;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * 水泵 GUI 的持有者，绑定到具体的水泵方块坐标。
 */
public final class PumpGuiHolder implements InventoryHolder {

    private final BlockKey key;
    private Inventory inventory;

    public PumpGuiHolder(BlockKey key) {
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
