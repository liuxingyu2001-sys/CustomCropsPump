package com.liu.ccpump.machine.behavior;

import com.liu.ccpump.data.BlockKey;
import com.liu.ccpump.data.MachineData;
import com.liu.ccpump.machine.MachineArea;
import com.liu.ccpump.machine.MachineBehavior;
import com.liu.ccpump.machine.MachineContext;
import net.momirealms.customcrops.api.core.Registries;
import net.momirealms.customcrops.api.core.block.CropBlock;
import net.momirealms.customcrops.api.core.block.PotBlock;
import net.momirealms.customcrops.api.core.mechanic.fertilizer.Fertilizer;
import net.momirealms.customcrops.api.core.mechanic.fertilizer.FertilizerConfig;
import net.momirealms.customcrops.api.core.mechanic.pot.PotConfig;
import net.momirealms.customcrops.api.core.world.CustomCropsBlockState;
import net.momirealms.customcrops.api.core.world.CustomCropsWorld;
import net.momirealms.customcrops.api.core.world.Pos3;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 施肥机：把仓储里的肥料施给范围内的 CustomCrops 花盆。
 *
 * <p>只认 CustomCrops 的肥料（{@code Registries.ITEM_TO_FERTILIZER}），
 * 每次消耗 1 个肥料物品，并遵守花盆自己的规则：
 * {@code disablePluginMechanism}、肥料白名单、{@code maxFertilizers}、
 * 以及 {@code before-plant} 肥料只在花盆里没作物时才能用。
 *
 * <p>直接调用 CustomCrops 的 {@code PotBlock} 接口，不模拟玩家点击，
 * 所以不会触发 {@code FertilizerUseEvent}（那个事件需要真实玩家）；
 * 肥料的 {@code requirements}（通常是权限/等级）同样跳过——机器没有玩家身份，
 * 由“谁把肥料放进机器”这一层来把关。
 */
public final class FertilizerBehavior implements MachineBehavior {

    @Override
    public int run(MachineContext context) {
        MachineData machine = context.machine();
        // 界面开着的时候，仓储以界面为准（每个周期都会 syncFromView 覆盖回来）。
        // 这时候消耗肥料会被覆盖回去变成“凭空多出肥料”，所以干脆等玩家关掉界面再施肥。
        if (context.manager().gui().hasViewer(machine.key())) {
            return 0;
        }
        int slot = fertilizerSlot(context);
        if (slot < 0) {
            return 0;
        }
        ItemStack source = machine.buffer()[slot];
        if (source == null || source.getType().isAir()) {
            return 0;
        }
        String itemId = context.manager().plugin().customCrops().getItemManager().id(source);
        FertilizerConfig fertilizerConfig = Registries.ITEM_TO_FERTILIZER.get(itemId);
        if (fertilizerConfig == null) {
            return 0;
        }

        List<Candidate> candidates = new ArrayList<>();
        World world = context.world();
        BlockKey key = machine.key();
        CustomCropsWorld<?> customWorld = customWorld(context);
        if (customWorld == null) {
            return 0;
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
            candidates.add(new Candidate(pos.toLocation(world),
                    MachineArea.distanceSquared(key, pos.x(), pos.y(), pos.z()), pot, state));
        });
        if (candidates.isEmpty()) {
            return 0;
        }
        candidates.sort(Comparator.comparingLong(Candidate::distance));

        int applied = 0;
        for (Candidate candidate : candidates) {
            if (applied >= context.maxActions() || source.getAmount() <= 0) {
                break;
            }
            try {
                if (!canApply(context, candidate, fertilizerConfig)) {
                    continue;
                }
                PotBlock pot = candidate.pot();
                PotConfig potConfig = pot.config(candidate.state());
                if (potConfig == null) {
                    continue;
                }
                Fertilizer fertilizer = Fertilizer.builder()
                        .id(fertilizerConfig.id())
                        .times(fertilizerConfig.times())
                        .build();
                if (!pot.addFertilizer(candidate.state(), fertilizer)) {
                    continue;
                }
                try {
                    // state 重载会把 hasWater 一起算对，肥料贴图才不会盖掉“缺水”外观
                    pot.updateBlockAppearance(candidate.location(), candidate.state());
                } catch (Throwable throwable) {
                    context.manager().warn("更新花盆外观失败: " + throwable);
                }
                source.setAmount(source.getAmount() - 1);
                applied++;
            } catch (Throwable throwable) {
                context.manager().warn("施肥 " + candidate.location() + " 失败: " + throwable);
            }
        }
        // 用完的肥料要清掉空栈：留下 amount=0 的物品会让下一轮永远选中这个空格子，
        // 机器就再也不会去用下一格里的肥料。
        if (source.getAmount() <= 0) {
            machine.buffer()[slot] = null;
        }
        return applied;
    }

    private boolean canApply(MachineContext context, Candidate candidate, FertilizerConfig fertilizerConfig) {
        PotBlock pot = candidate.pot();
        CustomCropsBlockState state = candidate.state();
        PotConfig potConfig = pot.config(state);
        if (potConfig == null || potConfig.disablePluginMechanism()) {
            return false;
        }
        if (!fertilizerConfig.whitelistPots().contains(potConfig.id())) {
            return false;
        }
        if (fertilizerConfig.beforePlant() && hasCropAbove(context, candidate.location())) {
            return false;
        }
        Fertilizer fertilizer = Fertilizer.builder()
                .id(fertilizerConfig.id())
                .times(fertilizerConfig.times())
                .build();
        return pot.canApplyFertilizer(state, fertilizer);
    }

    private boolean hasCropAbove(MachineContext context, Location location) {
        CustomCropsWorld<?> customWorld = customWorld(context);
        if (customWorld == null) {
            return false;
        }
        Pos3 above = new Pos3(location.getBlockX(), location.getBlockY() + 1, location.getBlockZ());
        return customWorld.getLoadedBlockState(above)
                .map(blockState -> blockState.type() instanceof CropBlock)
                .orElse(false);
    }

    private int fertilizerSlot(MachineContext context) {
        ItemStack[] buffer = context.machine().buffer();
        for (int index = 0; index < buffer.length; index++) {
            ItemStack stack = buffer[index];
            if (stack == null || stack.getType().isAir() || stack.getAmount() <= 0) {
                continue;
            }
            String id = context.manager().plugin().customCrops().getItemManager().id(stack);
            if (Registries.ITEM_TO_FERTILIZER.get(id) != null) {
                return index;
            }
        }
        return -1;
    }

    private static CustomCropsWorld<?> customWorld(MachineContext context) {
        return context.manager().plugin().customCrops().getWorldManager()
                .getWorld(context.world()).orElse(null);
    }

    private record Candidate(Location location, long distance,
                             PotBlock pot, CustomCropsBlockState state) {
    }
}