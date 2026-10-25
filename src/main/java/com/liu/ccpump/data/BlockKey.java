package com.liu.ccpump.data;

import net.momirealms.customcrops.api.core.world.Pos3;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.Objects;

/**
 * 以“世界名 + 方块坐标”作为水泵/水管的唯一键。
 */
public record BlockKey(String world, int x, int y, int z) {

    public static BlockKey of(Location location) {
        Objects.requireNonNull(location, "location");
        World world = Objects.requireNonNull(location.getWorld(), "world");
        return new BlockKey(world.getName(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    public Pos3 pos() {
        return new Pos3(x, y, z);
    }

    public Location toLocation(World world) {
        return new Location(world, x, y, z);
    }

    public BlockKey offset(int dx, int dy, int dz) {
        return new BlockKey(world, x + dx, y + dy, z + dz);
    }

    @Override
    public String toString() {
        return world + "@" + x + "," + y + "," + z;
    }
}
