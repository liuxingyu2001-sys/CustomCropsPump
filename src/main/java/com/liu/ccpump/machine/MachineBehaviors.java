package com.liu.ccpump.machine;

import com.liu.ccpump.config.MachineKind;
import com.liu.ccpump.machine.behavior.FertilizerBehavior;
import com.liu.ccpump.machine.behavior.HarvesterBehavior;
import com.liu.ccpump.machine.behavior.SprinklerBehavior;

import java.util.EnumMap;
import java.util.Map;

/**
 * 机器种类 → 行为实现的登记表。
 *
 * <p>新增一台机器：写一个 {@link MachineBehavior}，在这里登记，
 * 再在 {@link MachineKind} 里加一个枚举值即可。
 */
public final class MachineBehaviors {

    private static final Map<MachineKind, MachineBehavior> BEHAVIORS = new EnumMap<>(MachineKind.class);

    static {
        BEHAVIORS.put(MachineKind.HARVESTER, new HarvesterBehavior());
        BEHAVIORS.put(MachineKind.SPRINKLER, new SprinklerBehavior());
        BEHAVIORS.put(MachineKind.FERTILIZER, new FertilizerBehavior());
    }

    private MachineBehaviors() {
    }

    public static MachineBehavior of(MachineKind kind) {
        return kind == null ? null : BEHAVIORS.get(kind);
    }
}