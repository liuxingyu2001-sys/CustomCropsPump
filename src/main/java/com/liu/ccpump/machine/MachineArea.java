package com.liu.ccpump.machine;

import com.liu.ccpump.data.BlockKey;
import net.momirealms.customcrops.api.core.world.Pos3;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.function.Consumer;

/**
 * 机器作用范围的遍历。
 *
 * <p>三种机器扫的是同一块长方体区域，只处理**已加载区块**，
 * 否则一台机器跨在未加载区块边上时会触发区块加载。
 */
public final class MachineArea {

    private MachineArea() {
    }

    /** 遍历范围内的方块坐标（不算机器自己那一格）。 */
    public static void forEachPos(World world, BlockKey center, int radius, int verticalRadius,
                                  Consumer<Pos3> visitor) {
        forEach(world, center, radius, verticalRadius, location ->
                visitor.accept(new Pos3(location[0], location[1], location[2])));
    }

    /** 遍历范围内的方块。 */
    public static void forEachBlock(World world, BlockKey center, int radius, int verticalRadius,
                                    Consumer<Block> visitor) {
        forEach(world, center, radius, verticalRadius, location ->
                visitor.accept(world.getBlockAt(location[0], location[1], location[2])));
    }

    private static void forEach(World world, BlockKey center, int radius, int verticalRadius,
                                Consumer<int[]> visitor) {
        int horizontal = Math.max(1, radius);
        int vertical = Math.max(0, verticalRadius);
        for (int dx = -horizontal; dx <= horizontal; dx++) {
            for (int dy = -vertical; dy <= vertical; dy++) {
                for (int dz = -horizontal; dz <= horizontal; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }
                    int x = center.x() + dx;
                    int z = center.z() + dz;
                    if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                        continue;
                    }
                    int y = center.y() + dy;
                    if (y < world.getMinHeight() || y >= world.getMaxHeight()) {
                        continue;
                    }
                    visitor.accept(new int[]{x, y, z});
                }
            }
        }
    }

    /** 坐标到机器中心的方块距离平方，用来让近处的先被处理。 */
    public static long distanceSquared(BlockKey center, int x, int y, int z) {
        long dx = (long) center.x() - x;
        long dy = (long) center.y() - y;
        long dz = (long) center.z() - z;
        return dx * dx + dy * dy + dz * dz;
    }
}