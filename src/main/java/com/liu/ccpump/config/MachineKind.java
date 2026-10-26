package com.liu.ccpump.config;

import java.util.Locale;

/**
 * 机器种类。
 *
 * <p>三种机器共用同一套方块、界面、持久化与充能逻辑，差异只在
 * “一个工作周期做什么”，由 {@code machine.behavior.MachineBehavior} 的具体实现决定。
 * 新增机器只需要：加一个枚举值 + 一个行为类 + {@code config.yml} 里一段配置。
 */
public enum MachineKind {

    /** 收割机：收割范围内的 CustomCrops 作物与原版作物。 */
    HARVESTER("harvester", "收割机"),
    /** 洒水机：给范围内的花盆/农田补水。 */
    SPRINKLER("sprinkler", "洒水机"),
    /** 施肥机：给范围内的花盆施肥。 */
    FERTILIZER("fertilizer", "施肥机");

    private final String id;
    private final String displayName;

    MachineKind(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public static MachineKind byId(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        String normalized = id.trim().toLowerCase(Locale.ENGLISH);
        for (MachineKind kind : values()) {
            if (kind.id.equals(normalized)) {
                return kind;
            }
        }
        return null;
    }
}