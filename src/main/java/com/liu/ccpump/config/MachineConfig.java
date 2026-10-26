package com.liu.ccpump.config;

/**
 * 一台机器的完整配置。
 *
 * <p>三种机器的差异全部收敛在这里的字段里，代码里不需要为每种机器写一套解析：
 * 用不上的字段留默认值即可（例如收割机的 {@link Water} 全是 0，洒水机的
 * {@link #bufferSlots()} 是 0）。
 */
public record MachineConfig(
        String key,
        MachineKind kind,
        String item,
        int radius,
        int verticalRadius,
        int cycle,
        int maxPerCycle,
        int chargeCapacity,
        int chargePerAction,
        int bufferSlots,
        boolean acceptsInput,
        Water water,
        Targets targets
) {

    /**
     * 洒水机的水箱参数。
     *
     * @param capacity     水箱容量，0 表示这台机器不需要水
     * @param perAction    每浇一盆花消耗的水量
     * @param refill       每个周期从附近水源自动抽取的水量
     * @param bucketAmount 界面上投一个水桶能加多少水
     * @param source       附近水源判定
     */
    public record Water(int capacity, int perAction, int refill, int bucketAmount, Source source) {

        public static final Water NONE = new Water(0, 0, 0, 0, Source.NONE);

        public record Source(boolean required, int radius, boolean consume) {
            public static final Source NONE = new Source(false, 0, false);
        }
    }

    /**
     * 作用目标开关。
     *
     * @param customCrops  CustomCrops 作物（收割机）/ 花盆（洒水机、施肥机）
     * @param vanillaCrops 原版作物（收割机）
     * @param farmland     原版耕地补水（洒水机）
     */
    public record Targets(boolean customCrops, boolean vanillaCrops, boolean farmland) {
    }

    /** 动力无限：不消耗燃料，界面里也不显示投放槽。 */
    public boolean infiniteCharge() {
        return chargeCapacity <= 0;
    }

    /** 这台机器有没有自己的仓储区（收割机=收获物，施肥机=肥料）。 */
    public boolean hasBuffer() {
        return bufferSlots > 0;
    }

    public boolean needsWater() {
        return water.capacity() > 0;
    }
}