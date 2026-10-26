package com.liu.ccpump.machine.behavior;

import com.liu.ccpump.config.MachineConfig;
import com.liu.ccpump.data.BlockKey;
import com.liu.ccpump.data.MachineData;
import com.liu.ccpump.machine.MachineArea;
import com.liu.ccpump.machine.MachineBehavior;
import com.liu.ccpump.machine.MachineContext;
import com.liu.ccpump.machine.MachineManager;
import net.momirealms.customcrops.api.core.block.BreakReason;
import net.momirealms.customcrops.api.core.block.CropBlock;
import net.momirealms.customcrops.api.core.mechanic.crop.CropConfig;
import net.momirealms.customcrops.api.core.world.CustomCropsBlockState;
import net.momirealms.customcrops.api.core.world.CustomCropsWorld;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 收割机：把范围内已经成熟的 CustomCrops 作物与原版作物收进自己的仓储。
 *
 * <p>收获物**不落地**：CustomCrops 作物的掉落由 {@code DropItemActionEvent} 截获
 * （见 {@code MachineDropListener}），原版作物直接用 {@code getDrops()} 收走。
 * 仓储满了以后多出来的部分会掉在机器脚下，不会凭空消失。
 *
 * <p>按设计**不自动补种**：作物收走就是从田里消失，种子也在掉落里，玩家自己决定种不种。
 */
public final class HarvesterBehavior implements MachineBehavior {

    /** 原版作物：只认这些（仙人掌/甘蔗/海带也是 Ageable，不能只看 Ageable 就收）。 */
    private static final Set<Material> VANILLA_CROPS = EnumSet.of(
            Material.WHEAT,
            Material.CARROTS,
            Material.POTATOES,
            Material.BEETROOTS,
            Material.NETHER_WART,
            Material.COCOA,
            Material.SWEET_BERRY_BUSH
    );

    /** 原版果实方块：长出来就能收，没有 age 概念。 */
    private static final Set<Material> VANILLA_FRUITS = EnumSet.of(
            Material.MELON,
            Material.PUMPKIN
    );

    @Override
    public int run(MachineContext context) {
        MachineConfig config = context.config();
        MachineData machine = context.machine();
        World world = context.world();
        BlockKey key = machine.key();

        List<Candidate> candidates = new ArrayList<>();
        if (config.targets().customCrops()) {
            collectCustomCrops(context, candidates);
        }
        if (config.targets().vanillaCrops()) {
            collectVanilla(context, candidates);
        }
        if (candidates.isEmpty()) {
            return 0;
        }
        candidates.sort(Comparator.comparingLong(Candidate::distance));

        int harvested = 0;
        for (Candidate candidate : candidates) {
            if (harvested >= context.maxActions()) {
                break;
            }
            try {
                if (candidate.state() != null) {
                    harvestCustomCrops(context, candidate);
                } else {
                    harvestVanilla(context, candidate);
                }
                harvested++;
            } catch (Throwable throwable) {
                context.manager().warn("收割 " + candidate.location() + " 失败: " + throwable);
            }
        }
        return harvested;
    }

    private void collectCustomCrops(MachineContext context, List<Candidate> candidates) {
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
            if (!(state.type() instanceof CropBlock crop)) {
                return;
            }
            CropConfig cropConfig = crop.config(state);
            if (cropConfig == null || crop.point(state) < cropConfig.maxPoints()) {
                return;
            }
            candidates.add(new Candidate(pos.toLocation(world),
                    MachineArea.distanceSquared(key, pos.x(), pos.y(), pos.z()), state));
        });
    }

    private void collectVanilla(MachineContext context, List<Candidate> candidates) {
        World world = context.world();
        BlockKey key = context.machine().key();
        MachineArea.forEachBlock(world, key, context.config().radius(), context.config().verticalRadius(), block -> {
            if (!isMature(block)) {
                return;
            }
            candidates.add(new Candidate(block.getLocation(),
                    MachineArea.distanceSquared(key,
                            block.getX(), block.getY(), block.getZ()), null));
        });
    }

    private static boolean isMature(Block block) {
        Material material = block.getType();
        if (VANILLA_FRUITS.contains(material)) {
            return true;
        }
        if (!VANILLA_CROPS.contains(material)) {
            return false;
        }
        return block.getBlockData() instanceof Ageable ageable
                && ageable.getAge() >= ageable.getMaximumAge();
    }

    private void harvestCustomCrops(MachineContext context, Candidate candidate) {
        MachineManager manager = context.manager();
        MachineData machine = context.machine();
        // 先登记“这个位置的掉落归我”，CustomCrops 的掉落动作会晚一两个 tick 才触发。
        manager.expectDrop(candidate.location(), machine);
        manager.plugin().customCrops().getAPI()
                .simulatePlayerBreakCrop(null, null, candidate.location(), BreakReason.CUSTOM);
    }

    private void harvestVanilla(MachineContext context, Candidate candidate) {
        Block block = candidate.location().getBlock();
        List<ItemStack> drops = new ArrayList<>(block.getDrops());
        if (block.getType() == Material.SWEET_BERRY_BUSH
                && block.getBlockData() instanceof Ageable ageable) {
            // 甜浆果是“摘了还会再长”的灌木：只摘果子，把灌木退回 1 龄，别把灌木连根拔了
            ageable.setAge(1);
            block.setBlockData(ageable, false);
        } else {
            block.setType(Material.AIR, false);
        }
        for (ItemStack drop : drops) {
            context.manager().store(context.machine(), context.config(), drop);
        }
    }

    private static CustomCropsWorld<?> customWorld(MachineContext context) {
        return context.manager().plugin().customCrops().getWorldManager()
                .getWorld(context.world()).orElse(null);
    }

    /** 一个候选目标：原版方块 {@code state == null}，CustomCrops 作物带状态。 */
    private record Candidate(Location location, long distance, CustomCropsBlockState state) {
    }
}