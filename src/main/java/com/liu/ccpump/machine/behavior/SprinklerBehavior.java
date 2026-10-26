package com.liu.ccpump.machine.behavior;

import com.liu.ccpump.config.MachineConfig;
import com.liu.ccpump.data.BlockKey;
import com.liu.ccpump.data.MachineData;
import com.liu.ccpump.machine.MachineArea;
import com.liu.ccpump.machine.MachineBehavior;
import com.liu.ccpump.machine.MachineContext;
import com.liu.ccpump.util.WaterSources;
import net.momirealms.customcrops.api.core.block.PotBlock;
import net.momirealms.customcrops.api.core.mechanic.pot.PotConfig;
import net.momirealms.customcrops.api.core.world.CustomCropsBlockState;
import net.momirealms.customcrops.api.core.world.CustomCropsWorld;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Farmland;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 洒水机：用水箱里的水给范围内的 CustomCrops 花盆补水，顺带给原版耕地保湿。
 *
 * <p>水箱有三种加水方式，优先级从高到低：
 * <ol>
 *   <li><b>水管网络</b>：把水管接到洒水机上（和水泵直连也行），水泵每个周期往水箱里灌
 *       {@code pumps.*.push-rate} 点，灌满为止。这一段由 {@code PumpManager} 负责，
 *       机器这边只记一个“刚被管网灌过”的时间戳；</li>
 *   <li><b>本地水源</b>：{@code water.source.required: true} 且身边 {@code radius} 格内有水时，
 *       每周期自己抽 {@code water.refill} 点。管网正在供水时这一条会自动让路，
 *       避免两个水源打架、把附近的水池抽干；</li>
 *   <li><b>水桶</b>：界面里往水箱图标上点水桶，一次 {@code bucket-amount} 点。</li>
 * </ol>
 * 每浇一盆 / 每润一格耕地消耗 1 点水、1 点动力。
 *
 * <p>为什么不像水泵那样走管道：水泵管的是“水源 → 管道 → 洒水器”，
 * 洒水机是“没有管网时的一台独立浇水设备”，直接从水箱取水，互不替代。
 */
public final class SprinklerBehavior implements MachineBehavior {

    @Override
    public int run(MachineContext context) {
        MachineConfig config = context.config();
        MachineData machine = context.machine();
        World world = context.world();
        MachineConfig.Water water = config.water();
        if (water.capacity() <= 0) {
            return 0;
        }

        refillFromSource(context, water);

        if (machine.water() <= 0) {
            return 0;
        }

        List<Candidate> candidates = new ArrayList<>();
        if (config.targets().customCrops()) {
            collectPots(context, candidates);
        }
        if (config.targets().farmland()) {
            collectFarmland(context, candidates);
        }
        if (candidates.isEmpty()) {
            return 0;
        }
        candidates.sort(Comparator.comparingLong(Candidate::distance));

        int actions = 0;
        for (Candidate candidate : candidates) {
            if (actions >= context.maxActions() || machine.water() <= 0) {
                break;
            }
            try {
                boolean done = candidate.pot() != null
                        ? waterPot(context, candidate, water)
                        : moistenFarmland(candidate);
                if (done) {
                    machine.water(machine.water() - 1);
                    actions++;
                }
            } catch (Throwable throwable) {
                context.manager().warn("浇灌 " + candidate.location() + " 失败: " + throwable);
            }
        }
        return actions;
    }

    /** 管网刚灌过水的判定窗口：这段时间内不再从本地水源抽水。 */
    private static final long NETWORK_SUPPLY_WINDOW_MILLIS = 5000L;

    private void refillFromSource(MachineContext context, MachineConfig.Water water) {
        MachineData machine = context.machine();
        World world = context.world();
        long tick = context.tick();
        if (machine.lastNetworkFill() > 0
                && System.currentTimeMillis() - machine.lastNetworkFill() < NETWORK_SUPPLY_WINDOW_MILLIS) {
            // 水管网络正在供水，本地水源让路
            machine.sourceAvailable(true);
            return;
        }
        if (water.source().required()) {
            long interval = context.manager().cycles(context.manager().plugin().configManager().sourceCheckInterval());
            if (machine.lastSourceCheck() < 0 || tick - machine.lastSourceCheck() >= interval) {
                machine.sourceAvailable(WaterSources.any(world, machine.key(), water.source().radius()));
                machine.lastSourceCheck(tick);
            }
        } else {
            machine.sourceAvailable(true);
        }

        boolean available = machine.sourceAvailable() || !water.source().required();
        if (!available || machine.water() >= water.capacity() || water.refill() <= 0) {
            return;
        }
        int draw = Math.min(water.refill(), water.capacity() - machine.water());
        if (draw <= 0) {
            return;
        }
        machine.water(machine.water() + draw);
        if (water.source().consume()) {
            WaterSources.consume(world, machine.key(), water.source().radius());
        }
    }

    private void collectPots(MachineContext context, List<Candidate> candidates) {
        World world = context.world();
        BlockKey key = context.machine().key();
        CustomCropsWorld<?> customWorld = customWorld(context);
        if (customWorld == null) {
            return;
        }
        MachineArea.forEachPos(world, key, context.config().radius(), context.config().verticalRadius(), pos -> {
            Optional<CustomCropsBlockState> optional = customWorld.getLoadedBlockState(pos);
            if (optional.isEmpty()) {
                return;
            }
            CustomCropsBlockState state = optional.get();
            if (!(state.type() instanceof PotBlock pot)) {
                return;
            }
            PotConfig potConfig = pot.config(state);
            if (potConfig == null || potConfig.disablePluginMechanism()) {
                return;
            }
            if (pot.water(state) >= potConfig.storage()) {
                return;
            }
            candidates.add(new Candidate(pos.toLocation(world),
                    MachineArea.distanceSquared(key, pos.x(), pos.y(), pos.z()), pot, state));
        });
    }

    private void collectFarmland(MachineContext context, List<Candidate> candidates) {
        World world = context.world();
        BlockKey key = context.machine().key();
        MachineArea.forEachBlock(world, key, context.config().radius(), context.config().verticalRadius(), block -> {
            if (block.getType() != Material.FARMLAND) {
                return;
            }
            if (!(block.getBlockData() instanceof Farmland farmland)
                    || farmland.getMoisture() >= farmland.getMaximumMoisture()) {
                return;
            }
            // 记下候选即可，真正写方块时再取一次 BlockData，避免拿到过期快照

            candidates.add(new Candidate(block.getLocation(),
                    MachineArea.distanceSquared(key, block.getX(), block.getY(), block.getZ()),
                    null, null));
        });
    }

    private boolean waterPot(MachineContext context, Candidate candidate, MachineConfig.Water water) {
        PotBlock pot = candidate.pot();
        CustomCropsBlockState state = candidate.state();
        PotConfig potConfig = pot.config(state);
        if (potConfig == null) {
            return false;
        }
        int current = pot.water(state);
        if (current >= potConfig.storage()) {
            return false;
        }
        int give = Math.min(water.perAction(), potConfig.storage() - current);
        boolean changed = pot.addWater(state, potConfig, give);
        if (!changed) {
            return false;
        }
        try {
            // 用 state 重载：hasWater 由 pot.water(state) 自己算，不会把没水的花盆画成有水
            pot.updateBlockAppearance(candidate.location(), state);
        } catch (Throwable throwable) {
            context.manager().warn("更新花盆外观失败: " + throwable);
        }
        return true;
    }

    private boolean moistenFarmland(Candidate candidate) {
        Block block = candidate.location().getBlock();
        if (!(block.getBlockData() instanceof Farmland farmland)) {
            return false;
        }
        if (farmland.getMoisture() >= farmland.getMaximumMoisture()) {
            return false;
        }
        farmland.setMoisture(farmland.getMaximumMoisture());
        block.setBlockData(farmland, false);
        return true;
    }

    private static CustomCropsWorld<?> customWorld(MachineContext context) {
        return context.manager().plugin().customCrops().getWorldManager()
                .getWorld(context.world()).orElse(null);
    }

    private record Candidate(Location location, long distance,
                             PotBlock pot, CustomCropsBlockState state) {
    }
}