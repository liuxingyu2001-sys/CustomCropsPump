package com.liu.ccpump.util;

import com.liu.ccpump.data.BlockKey;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Waterlogged;

/**
 * 水源判定与抽取。
 *
 * <p>水泵和洒水机都要判断“身边有没有水”，逻辑完全一样，
 * 这里抽成工具类，两边共用，免得以后再改一遍。
 */
public final class WaterSources {

    private WaterSources() {
    }

    /** 附近半径内有没有水（水源方块、含水方块、气泡柱都算）。 */
    public static boolean any(World world, BlockKey key, int radius) {
        return find(world, key, radius, false);
    }

    /** 抽走附近一格水源方块；没有水源返回 false。 */
    public static boolean consume(World world, BlockKey key, int radius) {
        return find(world, key, radius, true);
    }

    private static boolean find(World world, BlockKey key, int radius, boolean remove) {
        int effectiveRadius = Math.max(1, radius);
        for (int dx = -effectiveRadius; dx <= effectiveRadius; dx++) {
            for (int dy = -effectiveRadius; dy <= effectiveRadius; dy++) {
                for (int dz = -effectiveRadius; dz <= effectiveRadius; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }
                    int x = key.x() + dx;
                    int z = key.z() + dz;
                    if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                        continue;
                    }
                    int y = key.y() + dy;
                    if (y < world.getMinHeight() || y >= world.getMaxHeight()) {
                        continue;
                    }
                    Block block = world.getBlockAt(x, y, z);
                    if (!isWater(block)) {
                        continue;
                    }
                    if (remove) {
                        if (block.getType() != Material.WATER) {
                            continue;
                        }
                        block.setType(Material.AIR, false);
                    }
                    return true;
                }
            }
        }
        return false;
    }

    public static boolean isWater(Block block) {
        Material type = block.getType();
        if (type == Material.WATER || type == Material.BUBBLE_COLUMN) {
            return true;
        }
        return block.getBlockData() instanceof Waterlogged waterlogged && waterlogged.isWaterlogged();
    }
}