package com.liu.ccpump.machine;

/**
 * 机器行为：一个工作周期干什么。
 *
 * <p>三种机器（收割/洒水/施肥）共用同一套生命周期、界面、持久化与充能，
 * 只有“这一周期做什么”不同，所以差异全部收敛在这里。
 * 新增机器只需要实现这个接口，再在 {@link MachineBehaviors} 里登记。
 */
public interface MachineBehavior {

    /**
     * 执行一个工作周期。
     *
     * @param context 本次工作的上下文
     * @return 实际完成的动作次数（每收割一株作物 / 每浇一盆 / 每施一次肥都算一次）；
     *         返回 0 表示这一周期没干活
     */
    int run(MachineContext context);
}