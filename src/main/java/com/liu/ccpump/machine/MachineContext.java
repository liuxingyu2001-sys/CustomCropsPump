package com.liu.ccpump.machine;

import com.liu.ccpump.config.MachineConfig;
import com.liu.ccpump.data.MachineData;
import org.bukkit.World;

/**
 * 一个工作周期的上下文，避免行为类的参数列表越写越长。
 *
 * @param manager    机器管理器（存物品、登记掉落用）
 * @param machine    机器状态
 * @param config     机器配置
 * @param world      机器所在世界
 * @param tick       当前主循环计数
 * @param maxActions 本周期最多能做几次动作（由动力与 {@code max-per-cycle} 决定）
 */
public record MachineContext(
        MachineManager manager,
        MachineData machine,
        MachineConfig config,
        World world,
        long tick,
        int maxActions
) {

    /** 机器方块坐标。 */
    public org.bukkit.Location location() {
        return machine.key().toLocation(world);
    }
}