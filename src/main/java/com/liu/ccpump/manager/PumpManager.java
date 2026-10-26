package com.liu.ccpump.manager;

import com.liu.ccpump.CustomCropsPumpPlugin;
import com.liu.ccpump.config.ConfigManager;
import com.liu.ccpump.config.PipeConfig;
import com.liu.ccpump.config.PumpConfig;
import com.liu.ccpump.data.BlockKey;
import com.liu.ccpump.data.Network;
import com.liu.ccpump.data.PipeInstance;
import com.liu.ccpump.data.PumpInstance;
import com.liu.ccpump.util.WaterSources;
import net.momirealms.customcrops.api.core.ExistenceForm;
import net.momirealms.customcrops.api.core.block.SprinklerBlock;
import net.momirealms.customcrops.api.core.mechanic.sprinkler.SprinklerConfig;
import net.momirealms.customcrops.api.core.world.CustomCropsBlockState;
import net.momirealms.customcrops.api.core.world.CustomCropsWorld;
import net.momirealms.customcrops.api.core.world.Pos3;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 水泵/水管的运行时管理：放置、破坏、交互、抽水、管网寻路与补水。
 * 所有方法都应在主线程调用。
 */
public final class PumpManager {

    private static final int[][] DIRECTIONS = {
            {1, 0, 0}, {-1, 0, 0},
            {0, 1, 0}, {0, -1, 0},
            {0, 0, 1}, {0, 0, -1}
    };

    /** 与 DIRECTIONS 一一对应的连接掩码位：东、西、上、下、南、北。 */
    private static final int[] MASK_BITS = {1, 2, 4, 8, 16, 32};

    private final CustomCropsPumpPlugin plugin;
    /** 耐久耗尽后掉落的物品 ID（纯物品，不是方块）。 */
    public static final String BROKEN_PUMP_ITEM = "ccpump:broken_pump";

    private final ConfigManager config;

    private final Map<BlockKey, PumpInstance> pumps = new LinkedHashMap<>();
    private final Map<BlockKey, PipeInstance> pipes = new LinkedHashMap<>();
    private final Map<String, Set<Pos3>> pipesByWorld = new HashMap<>();

    /** 已确认存在的形态方块，避免每次都去查物品表。 */
    private final Set<String> knownPipeVariants = new HashSet<>();
    /** 确认不存在的形态：静默跳过外观切换，避免刷屏。 */
    private final Set<String> missingPipeVariants = new HashSet<>();
    /** 已提示过“缺少形态”的水管配置，避免重复告警。 */
    private final Set<String> warnedPipeVariants = new HashSet<>();

    private long pipeVersion;
    private long tick;

    public PumpManager(CustomCropsPumpPlugin plugin, ConfigManager config) {
        this.plugin = plugin;
        this.config = config;
    }

    // ------------------------------------------------------------------
    // 放置 / 破坏
    // ------------------------------------------------------------------

    public void placePump(Location location, String itemId) {
        PumpConfig pumpConfig = config.pumpByItem(itemId);
        if (pumpConfig == null) {
            return;
        }
        BlockKey key = BlockKey.of(location);

        PipeInstance oldPipe = pipes.remove(key);
        if (oldPipe != null) {
            removePipeIndex(key);
        }

        PumpInstance existing = pumps.get(key);
        if (existing != null && existing.typeKey().equals(pumpConfig.key())) {
            return;
        }
        pumps.put(key, new PumpInstance(key, pumpConfig.key()));
        pipeVersion++;
        debug(() -> "放置水泵 " + pumpConfig.key() + " @ " + key);
        // 新水泵会让相邻水管多出一个接口，延迟一 tick 再刷新外观
        schedulePipeRefresh(key);
    }

    public void placePipe(Location location, String itemId) {
        PipeConfig pipeConfig = config.pipeByItem(itemId);
        if (pipeConfig == null) {
            return;
        }
        BlockKey key = BlockKey.of(location);

        if (pumps.remove(key) != null) {
            pipeVersion++;
        }

        PipeInstance existing = pipes.get(key);
        if (existing != null && existing.typeKey().equals(pipeConfig.key())) {
            // 已经是同一种水管：仍然刷新一次连接状态，避免“放手后连接没更新”
            schedulePipeRefresh(key);
            return;
        }
        pipes.put(key, new PipeInstance(key, pipeConfig.key()));
        addPipeIndex(key);
        pipeVersion++;
        debug(() -> "放置水管 " + pipeConfig.key() + " @ " + key);
        schedulePipeRefresh(key);
    }

    public void breakAt(Location location) {
        BlockKey key = BlockKey.of(location);
        boolean changed = pumps.remove(key) != null;
        PipeInstance pipe = pipes.remove(key);
        if (pipe != null) {
            removePipeIndex(key);
            changed = true;
        }
        if (changed) {
            pipeVersion++;
            debug(() -> "移除水泵/水管 @ " + key);
            plugin.gui().closeViewers(key);
            schedulePipeRefresh(key);
        }
    }

    // ------------------------------------------------------------------
    // 交互
    // ------------------------------------------------------------------

    /**
     * 该位置是不是本插件的水泵方块。
     *
     * <p>只认 CustomCrops 报上来的方块 ID，不依赖内存里的 {@code pumps} 表，
     * 这样即使数据库丢了记录（区块刚加载、手工编辑过库），右键也能自愈。
     */
    public boolean isPumpBlock(Location location) {
        World world = location.getWorld();
        if (world == null) {
            return false;
        }
        BlockKey key = BlockKey.of(location);
        if (!isChunkLoaded(world, key)) {
            return false;
        }
        String id = blockId(key, world);
        return id != null && config.isPumpItem(id);
    }

    public void interactPump(Player player, Location location, EquipmentSlot hand, ItemStack itemInHand) {
        BlockKey key = BlockKey.of(location);
        PumpInstance pump = pumps.get(key);
        if (pump == null) {
            // 数据库丢记录或区块刚加载时，用世界上真实的方块 ID 自愈
            pump = registerExistingPump(key);
        }
        if (pump == null) {
            return;
        }
        PumpConfig pumpConfig = config.pump(pump.typeKey());
        if (pumpConfig == null) {
            return;
        }
        if (!player.hasPermission("customcropspump.use")) {
            config.messages().send(player, "no-permission");
            return;
        }

        // 水桶是对着水泵直接倒水，与“开界面”不冲突，保留这个快捷动作。
        if (itemInHand != null && itemInHand.getType() == Material.WATER_BUCKET) {
            fillFromBucket(player, pump, pumpConfig, hand);
            return;
        }

        if (player.isSneaking() && pumpConfig.allowToggle()) {
            togglePump(player, pump);
            return;
        }

        // 右键一律打开界面。
        //
        // 水泵改成方块以后，手持红石这类“可以放置的方块物品”右键时，
        // 原版会优先尝试把它摆到相邻位置，充电那条分支根本轮不到，
        // 表现就是“手持红石右键无法充能”。所以充能改到界面里投放，
        // 右键只负责开界面。
        plugin.getServer().getScheduler().runTask(plugin, () -> plugin.gui().open(player, key));
    }

    /**
     * 开关水泵并提示玩家。
     */
    public void togglePump(Player player, PumpInstance pump) {
        pump.enabled(!pump.enabled());
        config.messages().send(player, pump.enabled() ? "pump-toggled-on" : "pump-toggled-off");
    }

    /**
     * 世界上存在、但内存里没有登记的水泵：按方块 ID 重新登记。
     */
    private PumpInstance registerExistingPump(BlockKey key) {
        World world = Bukkit.getWorld(key.world());
        if (world == null || !isChunkLoaded(world, key)) {
            return null;
        }
        String itemId = blockId(key, world);
        PumpConfig pumpConfig = itemId == null ? null : config.pumpByItem(itemId);
        if (pumpConfig == null) {
            return null;
        }
        PumpInstance instance = new PumpInstance(key, pumpConfig.key());
        if (pumpConfig.chargeCapacity() > 0) {
            instance.charge(pumpConfig.chargeCapacity());
        }
        pumps.put(key, instance);
        pipeVersion++;
        debug(() -> "补登记水泵 " + pumpConfig.key() + " @ " + key);
        return instance;
    }

    private String itemName(Material material) {
        return switch (material) {
            case IRON_INGOT -> "铁锭";
            case GOLD_INGOT -> "金锭";
            case RAW_IRON -> "粗铁";
            case RAW_GOLD -> "粗金";
            case REDSTONE -> "红石";
            case LAPIS_LAZULI -> "青金石";
            case DIAMOND -> "钻石";
            default -> material.name().toLowerCase(Locale.ROOT);
        };
    }

    /**
     * 用某种燃料给水泵充能。
     *
     * <p>只消耗真正用得上的数量：动力还差 30 点、红石每块 5 点时只吃 6 块，
     * 不会把玩家那一整组燃料一口气吞掉再丢掉溢出的部分。
     *
     * @param available 手头可用的燃料数量
     * @return 实际消耗的物品数（0 表示没充上，调用方不必扣物品）
     */
    public int chargeWithFuel(Player player, PumpInstance pump, PumpConfig pumpConfig,
                              Material material, int available) {
        if (pumpConfig.chargeCapacity() <= 0) {
            config.messages().send(player, "pump-charge-disabled");
            return 0;
        }
        int space = pumpConfig.chargeCapacity() - pump.charge();
        if (space <= 0) {
            config.messages().send(player, "pump-charge-full",
                    "charge", String.valueOf(pump.charge()),
                    "capacity", String.valueOf(pumpConfig.chargeCapacity()));
            return 0;
        }
        Integer fuel = config.fuelValue(material);
        if (fuel == null || fuel <= 0) {
            config.messages().send(player, "pump-fuel-invalid", "item", itemName(material));
            return 0;
        }
        if (available <= 0) {
            return 0;
        }

        int consume = Math.min(available, (space + fuel - 1) / fuel);
        int amount = Math.min(space, consume * fuel);
        pump.charge(pump.charge() + amount);

        config.messages().send(player, "pump-charged",
                "amount", String.valueOf(amount),
                "charge", String.valueOf(pump.charge()),
                "capacity", String.valueOf(pumpConfig.chargeCapacity()));
        return consume;
    }

    private void fillFromBucket(Player player, PumpInstance pump, PumpConfig pumpConfig, EquipmentSlot hand) {
        int space = pumpConfig.capacity() - pump.water();
        if (space <= 0) {
            config.messages().send(player, "pump-full",
                    "water", String.valueOf(pump.water()),
                    "capacity", String.valueOf(pumpConfig.capacity()));
            return;
        }
        int amount = Math.min(space, pumpConfig.bucketAmount());
        pump.water(pump.water() + amount);

        if (player.getGameMode() != GameMode.CREATIVE) {
            EquipmentSlot usedHand = hand == EquipmentSlot.OFF_HAND ? EquipmentSlot.OFF_HAND : EquipmentSlot.HAND;
            ItemStack current = player.getInventory().getItem(usedHand);
            if (current != null && current.getType() == Material.WATER_BUCKET) {
                player.getInventory().setItem(usedHand, new ItemStack(Material.BUCKET));
            }
        }

        config.messages().send(player, "pump-added-water",
                "amount", String.valueOf(amount),
                "water", String.valueOf(pump.water()),
                "capacity", String.valueOf(pumpConfig.capacity()));
    }

    /**
     * 右键水管。
     *
     * <p>手持水管时把新水管接到点击的那个面上（原版做不到这一点：方块右键
     * 的目标是水管自己，原版只会尝试把方块塞进同一格）。手持其它物品时
     * 只刷新一次连接状态，不发任何提示。
     */
    public void interactPipe(Player player, Location location, BlockFace face) {
        BlockKey key = BlockKey.of(location);
        PipeInstance pipe = pipes.get(key);
        if (pipe == null) {
            pipe = registerExistingPipe(key);
        }
        if (pipe == null) {
            return;
        }
        if (!player.hasPermission("customcropspump.use")) {
            config.messages().send(player, "no-permission");
            return;
        }

        ItemStack hand = player.getInventory().getItemInMainHand();
        String handItem = plugin.customCrops().getItemManager().id(hand);
        PipeConfig handPipe = handItem == null ? null : config.pipeByItem(handItem);
        if (handPipe != null) {
            tryPlacePipeAgainst(player, key, face, handPipe);
            return;
        }

        // 右键水管不显示任何提示：这里刻意不发消息。
        schedulePipeRefresh(key);
    }

    /**
     * 把手持的水管放到 {@code key} 的 {@code face} 那一面上。
     *
     * <p>只在目标位置为空、且区块已加载时放置；放置后立刻交给
     * {@code placePipe} 走正常的注册流程，连接状态随之刷新。
     */
    private void tryPlacePipeAgainst(Player player, BlockKey key, BlockFace face, PipeConfig pipeConfig) {
        if (face == null || player.getGameMode() == GameMode.SPECTATOR) {
            return;
        }
        World world = Bukkit.getWorld(key.world());
        if (world == null) {
            return;
        }
        int[] offset = switch (face) {
            case EAST -> new int[]{1, 0, 0};
            case WEST -> new int[]{-1, 0, 0};
            case UP -> new int[]{0, 1, 0};
            case DOWN -> new int[]{0, -1, 0};
            case SOUTH -> new int[]{0, 0, 1};
            case NORTH -> new int[]{0, 0, -1};
            default -> null;
        };
        if (offset == null) {
            return;
        }
        BlockKey target = key.offset(offset[0], offset[1], offset[2]);
        if (!isChunkLoaded(world, target)) {
            return;
        }
        Block block = target.toLocation(world).getBlock();
        if (!block.getType().isAir()) {
            // 目标格被占：交给原版流程（可能是在叠加其它方块）
            return;
        }
        Location placeLocation = target.toLocation(world);
        try {
            plugin.customCrops().getItemManager().placeBlock(placeLocation, pipeConfig.item());
        } catch (Throwable throwable) {
            plugin.getLogger().warning("右键水管放置新水管失败（" + target + "）: "
                    + throwable.getMessage());
            return;
        }
        placePipe(placeLocation, pipeConfig.item());
        // 生存模式消耗一个
        if (player.getGameMode() == GameMode.SURVIVAL) {
            ItemStack hand = player.getInventory().getItemInMainHand();
            hand.setAmount(hand.getAmount() - 1);
        }
    }

    private PipeInstance registerExistingPipe(BlockKey key) {
        World world = Bukkit.getWorld(key.world());
        if (world == null || !isChunkLoaded(world, key)) {
            return null;
        }
        String itemId = blockId(key, world);
        PipeConfig pipeConfig = itemId == null ? null : config.pipeByItem(itemId);
        if (pipeConfig == null) {
            return null;
        }
        PipeInstance instance = new PipeInstance(key, pipeConfig.key());
        pipes.put(key, instance);
        addPipeIndex(key);
        pipeVersion++;
        debug(() -> "补登记水管 " + pipeConfig.key() + " @ " + key);
        return instance;
    }

    // ------------------------------------------------------------------
    // 水管外观（按连接方向切换模型）
    // ------------------------------------------------------------------

    /**
     * 计算一根水管应该显示的连接掩码。
     *
     * <p>水平与垂直是两套互不相通的管网，连通规则：
     * <ul>
     *   <li>水平方向（东西南北）：相邻的水泵 / 水管 / 洒水器 / 洒水机都算连接；</li>
     *   <li>垂直方向（上下）：只有相邻的水管算连接，水泵和取水设备都不接，
     *       因此竖管自成一路，不会和平面管网串起来。</li>
     * </ul>
     *
     * <p>位顺序与 DIRECTIONS 一致：东、西、上、下、南、北。
     */
    public int connectedMask(BlockKey key) {
        int mask = 0;
        for (int i = 0; i < DIRECTIONS.length; i++) {
            int[] direction = DIRECTIONS[i];
            boolean vertical = direction[1] != 0;
            BlockKey neighbor = key.offset(direction[0], direction[1], direction[2]);
            if (vertical) {
                // 垂直方向只认水管，水泵/洒水器不算，避免竖管接到平面管网上
                if (pipes.containsKey(neighbor)) {
                    mask |= MASK_BITS[i];
                }
            } else if (pipes.containsKey(neighbor) || pumps.containsKey(neighbor)
                    || isSprinklerKey(neighbor) || isMachineEndpoint(neighbor)) {
                mask |= MASK_BITS[i];
            }
        }
        return mask;
    }

    /** 洒水机也算一个取水点：水管可以直接接到它上面供水。 */
    private boolean isMachineEndpoint(BlockKey key) {
        return plugin.machineManager().isWaterMachine(key);
    }

    private boolean isSprinklerKey(BlockKey key) {
        World world = Bukkit.getWorld(key.world());
        if (world == null || !isChunkLoaded(world, key)) {
            return false;
        }
        CustomCropsWorld<?> customCropsWorld = plugin.customCrops().getWorldManager()
                .getWorld(world).orElse(null);
        if (customCropsWorld == null) {
            return false;
        }
        return customCropsWorld.getLoadedBlockState(key.pos())
                .map(state -> state.type() instanceof SprinklerBlock)
                .orElse(false);
    }

    /**
     * 水管只有一种外观（仙人掌方块），不再按连接方向切换模型。
     *
     * <p>保留这个方法是为了兼容外部（校验循环、监听器）的调用点，
     * 现在它只在调试模式里打印当前连接掩码，便于排查连通问题。
     */
    public void refreshPipeAppearance(BlockKey key) {
        PipeInstance pipe = pipes.get(key);
        if (pipe == null) {
            return;
        }
        PipeConfig pipeConfig = config.pipe(pipe.typeKey());
        if (pipeConfig == null || !pipeConfig.connections()) {
            return;
        }
        World world = Bukkit.getWorld(key.world());
        if (world == null || !isChunkLoaded(world, key)) {
            return;
        }
        String current = blockId(key, world);
        if (current == null) {
            // 方块还没就绪（区块刚加载），交给下一次校验
            return;
        }
        String desired = pipeConfig.variantItem(connectedMask(key));
        if (desired == null || desired.equals(current)) {
            return;
        }
        if (!variantAvailable(pipeConfig, desired)) {
            return;
        }
        Location location = key.toLocation(world);
        try {
            // 方块形态：直接把这一格换成目标形态的方块
            plugin.customCrops().getItemManager().placeBlock(location, desired);
            if (!desired.equals(blockId(key, world))) {
                // 切换失败：恢复原样，别把水管弄丢
                plugin.customCrops().getItemManager().placeBlock(location, current);
                return;
            }
            debug(() -> "水管外观 " + key + " -> " + desired);
        } catch (Throwable throwable) {
            plugin.getLogger().warning("切换水管外观失败（" + key + "）: " + throwable.getMessage());
        }
    }

    private boolean variantAvailable(PipeConfig pipeConfig, String variantId) {
        if (knownPipeVariants.contains(variantId)) {
            return true;
        }
        if (missingPipeVariants.contains(variantId)) {
            return false;
        }
        boolean exists;
        try {
            exists = plugin.customCrops().getItemManager().build(null, variantId) != null;
        } catch (Throwable throwable) {
            exists = false;
        }
        if (exists) {
            knownPipeVariants.add(variantId);
            return true;
        }
        missingPipeVariants.add(variantId);
        if (warnedPipeVariants.add(pipeConfig.key())) {
            plugin.getLogger().warning("找不到水管形态 " + variantId
                    + "，水管将保持原样。请确认已安装插件自带的 CraftEngine 资源包。");
        }
        return false;
    }

    /**
     * 延迟一 tick 刷新该方块自己以及 6 个相邻水管的外观，避免在放置事件里重入。
     */
    public void schedulePipeRefresh(BlockKey key) {
        List<BlockKey> targets = new ArrayList<>(7);
        targets.add(key);
        for (int[] direction : DIRECTIONS) {
            targets.add(key.offset(direction[0], direction[1], direction[2]));
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            for (BlockKey target : targets) {
                refreshPipeAppearance(target);
            }
        });
    }

    // ------------------------------------------------------------------
    // 主循环
    // ------------------------------------------------------------------

    public void tick() {
        tick++;

        if (config.validateInterval() > 0 && tick % cycles(config.validateInterval()) == 0) {
            validatePumps();
            validatePipes();
        }

        plugin.gui().refreshAll();

        if (pumps.isEmpty()) {
            return;
        }

        for (PumpInstance pump : new ArrayList<>(pumps.values())) {
            PumpConfig pumpConfig = config.pump(pump.typeKey());
            if (pumpConfig == null) {
                continue;
            }
            World world = Bukkit.getWorld(pump.key().world());
            if (world == null || !isChunkLoaded(world, pump.key())) {
                continue;
            }
            tickPump(world, pump, pumpConfig);
        }
    }

    private void tickPump(World world, PumpInstance pump, PumpConfig pumpConfig) {
        if (!pumpConfig.source().required()) {
            pump.sourceAvailable(true);
        } else if (pump.lastSourceCheck() < 0 || tick - pump.lastSourceCheck() >= cycles(config.sourceCheckInterval())) {
            pump.sourceAvailable(WaterSources.any(world, pump.key(), pumpConfig.source().radius()));
            pump.lastSourceCheck(tick);
        }

        if (!pump.enabled()) {
            return;
        }
        if (pumpConfig.chargeCapacity() > 0 && pump.charge() <= 0) {
            return;
        }

        boolean worked = false;
        if (pump.water() < pumpConfig.capacity()
                && (!pumpConfig.source().required() || pump.sourceAvailable())) {
            int draw = Math.min(pumpConfig.pumpRate(), pumpConfig.capacity() - pump.water());
            if (draw > 0) {
                pump.water(pump.water() + draw);
                if (pumpConfig.source().consume()) {
                    WaterSources.consume(world, pump.key(), pumpConfig.source().radius());
                }
                worked = true;
            }
        }

        if (pump.water() > 0) {
            Network network = network(pump, false, pumpConfig);
            if (!network.sprinklers().isEmpty()) {
                int delivered = deliver(world, pump, pumpConfig, network.sprinklers());
                if (delivered > 0) {
                    worked = true;
                    if (pumpConfig.effects().enabled() && tick >= pump.nextEffectTick()) {
                        playEffects(world, pump, pumpConfig);
                        pump.nextEffectTick(tick + cycles(pumpConfig.effects().interval()));
                    }
                }
            }
        }

        if (worked && pumpConfig.chargeCapacity() > 0) {
            pump.charge(Math.max(0, pump.charge() - pumpConfig.chargePerCycle()));
        }

        // 耐久只在“真的干活”时扣：空转、停机、没有水源都不磨损。
        if (worked && pumpConfig.durability() > 0) {
            pump.wear(pump.wear() + 1);
            if (pump.wear() >= pumpConfig.durability()) {
                wearOut(world, pump, pumpConfig);
            }
        }
    }

    /**
     * 耐久耗尽：水泵消失，原地掉落一个破损水泵。
     *
     * <p>刻意不走 CE 的破坏流程：那条路会按战利品表掉出**完好的**水泵，
     * 玩家等于零成本换新。这里直接把方块设成空气（不产生掉落），
     * 再手动丢一个破损件。
     */
    private void wearOut(World world, PumpInstance pump, PumpConfig pumpConfig) {
        BlockKey key = pump.key();
        Location location = key.toLocation(world);
        try {
            location.getBlock().setType(Material.AIR, false);
        } catch (Throwable throwable) {
            plugin.getLogger().warning("移除耐久耗尽的水泵失败（" + key + "）: " + throwable.getMessage());
        }
        dropBrokenPump(world, location);
        // breakAt 负责清记录、关界面、刷新相邻水管外观
        breakAt(location);

        String name = pumpConfig.key();
        plugin.getLogger().info("水泵 " + key + "（" + name + "）耐久耗尽，已掉落破损水泵。");
        for (Player nearby : world.getPlayers()) {
            if (nearby.getLocation().distanceSquared(location) <= 16 * 16) {
                config.messages().send(nearby, "pump-worn-out");
            }
        }
    }

    /**
     * 掉落一个破损水泵。物品缺失（资源包没装好）时只告警，不影响主循环。
     */
    private void dropBrokenPump(World world, Location location) {
        ItemStack broken = null;
        try {
            broken = plugin.customCrops().getItemManager().build(null, BROKEN_PUMP_ITEM);
        } catch (Throwable throwable) {
            plugin.getLogger().warning("生成破损水泵物品失败: " + throwable.getMessage());
        }
        if (broken == null) {
            plugin.getLogger().warning("找不到物品 " + BROKEN_PUMP_ITEM
                    + "，耐久耗尽的水泵将不会掉落任何东西。请确认 CraftEngine 资源包已加载。");
            return;
        }
        world.dropItemNaturally(location.clone().add(0.5D, 0.5D, 0.5D), broken);
    }

    private int deliver(World world, PumpInstance pump, PumpConfig pumpConfig, List<Pos3> endpoints) {
        CustomCropsWorld<?> customCropsWorld = plugin.customCrops().getWorldManager()
                .getWorld(world).orElse(null);

        int remaining = pump.water();
        int delivered = 0;
        for (Pos3 pos : endpoints) {
            if (remaining <= 0) {
                break;
            }
            try {
                // 洒水机：和 CustomCrops 洒水器一样是取水点，水管直接把它的水箱灌满
                int push = Math.min(remaining, pumpConfig.pushRate());
                int filled = plugin.machineManager().fillFromNetwork(
                        new BlockKey(world.getName(), pos.x(), pos.y(), pos.z()), push);
                if (filled > 0) {
                    remaining -= filled;
                    delivered += filled;
                    continue;
                }
                if (customCropsWorld == null) {
                    continue;
                }
                Optional<CustomCropsBlockState> optional = customCropsWorld.getLoadedBlockState(pos);
                if (optional.isEmpty()) {
                    continue;
                }
                CustomCropsBlockState state = optional.get();
                if (!(state.type() instanceof SprinklerBlock sprinkler)) {
                    continue;
                }
                SprinklerConfig sprinklerConfig = sprinkler.config(state);
                if (sprinklerConfig == null || sprinklerConfig.infinite()) {
                    continue;
                }
                int current = sprinkler.water(state);
                int needed = sprinklerConfig.storage() - current;
                if (needed <= 0) {
                    continue;
                }
                int give = Math.min(Math.min(needed, remaining), pumpConfig.pushRate());
                if (give <= 0) {
                    continue;
                }
                boolean appearanceChanged = sprinkler.addWater(state, sprinklerConfig, give);
                if (appearanceChanged && !java.util.Objects.equals(
                        sprinklerConfig.threeDItem(), sprinklerConfig.threeDItemWithWater())) {
                    try {
                        sprinkler.updateBlockAppearance(pos.toLocation(world), sprinklerConfig, true);
                    } catch (Throwable throwable) {
                        plugin.getLogger().warning("更新洒水器外观失败: " + throwable.getMessage());
                    }
                }
                remaining -= give;
                delivered += give;
            } catch (Throwable throwable) {
                plugin.getLogger().warning("处理洒水器 " + pos + " 失败: " + throwable);
            }
        }

        if (delivered > 0) {
            pump.water(remaining);
        }
        return delivered;
    }

    private void playEffects(World world, PumpInstance pump, PumpConfig pumpConfig) {
        Location center = pump.key().toLocation(world).add(0.5D, 0.9D, 0.5D);
        if (pumpConfig.effects().particle() != null) {
            world.spawnParticle(pumpConfig.effects().particle(), center, 5, 0.25D, 0.2D, 0.25D, 0.01D);
        }
        String sound = pumpConfig.effects().sound();
        if (sound != null && !sound.isBlank()) {
            world.playSound(center, sound, pumpConfig.effects().volume(), pumpConfig.effects().pitch());
        }
    }

    // ------------------------------------------------------------------
    // 管网
    // ------------------------------------------------------------------

    private Network network(PumpInstance pump, boolean force, PumpConfig pumpConfig) {
        if (!force
                && pump.network() != null
                && pump.networkVersion() == pipeVersion
                && tick - pump.networkBuiltTick() < cycles(config.networkCacheTicks())) {
            return pump.network();
        }
        Network network = buildNetwork(pump.key(),
                pumpConfig.network().maxPipes(),
                pumpConfig.network().maxSprinklers(),
                pumpConfig.network().maxDistance(),
                false);
        pump.network(network);
        pump.networkVersion(pipeVersion);
        pump.networkBuiltTick(tick);
        return network;
    }

    private Network buildNetwork(BlockKey start, int maxPipes, int maxSprinklers,
                                 int maxDistance, boolean countPumps) {
        Set<Pos3> worldPipes = pipesByWorld.getOrDefault(start.world(), Collections.emptySet());
        World bukkitWorld = Bukkit.getWorld(start.world());
        CustomCropsWorld<?> customCropsWorld = null;
        if (bukkitWorld != null) {
            customCropsWorld = plugin.customCrops().getWorldManager().getWorld(bukkitWorld).orElse(null);
        }

        Pos3 startPos = start.pos();
        long maxDistanceSquared = (long) maxDistance * maxDistance;

        Deque<Pos3> queue = new ArrayDeque<>();
        Set<Pos3> visited = new HashSet<>();
        List<Pos3> nodes = new ArrayList<>();
        List<Pos3> foundPipes = new ArrayList<>();
        Set<Pos3> foundSprinklers = new LinkedHashSet<>();

        visited.add(startPos);
        queue.add(startPos);
        nodes.add(startPos);

        while (!queue.isEmpty()) {
            Pos3 node = queue.poll();
            for (int[] direction : DIRECTIONS) {
                Pos3 next = node.add(direction[0], direction[1], direction[2]);
                if (!visited.add(next)) {
                    continue;
                }
                if (distanceSquared(next, startPos) > maxDistanceSquared) {
                    continue;
                }
                if (worldPipes.contains(next)) {
                    if (foundPipes.size() >= maxPipes) {
                        continue;
                    }
                    foundPipes.add(next);
                    nodes.add(next);
                    queue.add(next);
                } else if (foundSprinklers.size() < maxSprinklers
                        && (isMachineEndpoint(new BlockKey(start.world(), next.x(), next.y(), next.z()))
                        || (customCropsWorld != null && isSprinkler(customCropsWorld, next)))) {
                    // 洒水器与洒水机同样是管网终点，一起按距离/顺序补水
                    foundSprinklers.add(next);
                }
            }
        }

        int adjacentPumps = 0;
        if (countPumps) {
            Set<BlockKey> found = new HashSet<>();
            for (Pos3 node : nodes) {
                for (int[] direction : DIRECTIONS) {
                    BlockKey key = new BlockKey(start.world(),
                            node.x() + direction[0],
                            node.y() + direction[1],
                            node.z() + direction[2]);
                    if (pumps.containsKey(key)) {
                        found.add(key);
                    }
                }
            }
            adjacentPumps = found.size();
        }

        return new Network(List.copyOf(foundPipes), List.copyOf(foundSprinklers), adjacentPumps);
    }

    private boolean isSprinkler(CustomCropsWorld<?> world, Pos3 pos) {
        return world.getLoadedBlockState(pos)
                .map(state -> state.type() instanceof SprinklerBlock)
                .orElse(false);
    }

    private static long distanceSquared(Pos3 a, Pos3 b) {
        long dx = (long) a.x() - b.x();
        long dy = (long) a.y() - b.y();
        long dz = (long) a.z() - b.z();
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * 把配置里的 Minecraft tick 换算成主循环次数（主循环默认每 20 tick 执行一次）。
     */
    private long cycles(long ticks) {
        long interval = Math.max(1L, config.tickInterval());
        return Math.max(1L, (ticks + interval - 1L) / interval);
    }

    private static boolean isChunkLoaded(World world, BlockKey key) {
        return world.isChunkLoaded(key.x() >> 4, key.z() >> 4);
    }

    private void addPipeIndex(BlockKey key) {
        pipesByWorld.computeIfAbsent(key.world(), ignored -> new HashSet<>()).add(key.pos());
    }

    private void removePipeIndex(BlockKey key) {
        Set<Pos3> set = pipesByWorld.get(key.world());
        if (set != null) {
            set.remove(key.pos());
            if (set.isEmpty()) {
                pipesByWorld.remove(key.world());
            }
        }
    }

    // ------------------------------------------------------------------
    // 校验
    // ------------------------------------------------------------------

    private void validatePumps() {
        Iterator<Map.Entry<BlockKey, PumpInstance>> iterator = pumps.entrySet().iterator();
        while (iterator.hasNext()) {
            PumpInstance pump = iterator.next().getValue();
            PumpConfig pumpConfig = config.pump(pump.typeKey());
            if (pumpConfig == null) {
                continue;
            }
            World world = Bukkit.getWorld(pump.key().world());
            if (world == null || !isChunkLoaded(world, pump.key())) {
                continue;
            }
            if (!isStillThere(pump.key(), world, pumpConfig)) {
                iterator.remove();
                pipeVersion++;
                debug(() -> "清理失效水泵 @ " + pump.key());
            }
        }
    }

    private void validatePipes() {
        Iterator<Map.Entry<BlockKey, PipeInstance>> iterator = pipes.entrySet().iterator();
        while (iterator.hasNext()) {
            PipeInstance pipe = iterator.next().getValue();
            PipeConfig pipeConfig = config.pipe(pipe.typeKey());
            if (pipeConfig == null) {
                continue;
            }
            World world = Bukkit.getWorld(pipe.key().world());
            if (world == null || !isChunkLoaded(world, pipe.key())) {
                continue;
            }
            String itemId = blockId(pipe.key(), world);
            PipeConfig current = itemId == null ? null : config.pipeByItem(itemId);
            boolean valid = current != null && current.key().equals(pipeConfig.key());
            if (!valid) {
                iterator.remove();
                removePipeIndex(pipe.key());
                pipeVersion++;
                debug(() -> "清理失效水管 @ " + pipe.key());
            } else {
                refreshPipeAppearance(pipe.key());
            }
        }
    }

    /**
     * 判断水泵是否还在：只认方块 ID。
     * <p>
     * 注意：不能用 {@code ExistenceForm.ANY}。水泵脚下通常是草方块/水，
     * ANY 会优先返回脚下方块 ID，把水泵误判成“已消失”并在
     * validate-interval 后删掉记录（表现就是放一会儿再右键没反应）。
     */
    private boolean isStillThere(BlockKey key, World world, PumpConfig pumpConfig) {
        String currentId = blockId(key, world);
        if (currentId == null) {
            return false;
        }
        PumpConfig current = config.pumpByItem(currentId);
        return current != null && pumpConfig.item().equals(current.item());
    }

    /**
     * 读该位置的自定义方块 ID；不是本插件的水泵/水管时返回 null。
     *
     * <p>水泵与水管都是 CraftEngine 自定义方块（{@code block_item}），
     * 所以这里必须用 {@code ExistenceForm.BLOCK}。
     */
    private String blockId(BlockKey key, World world) {
        try {
            return plugin.customCrops().getItemManager()
                    .id(key.toLocation(world), ExistenceForm.BLOCK);
        } catch (Throwable throwable) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 数据/查询
    // ------------------------------------------------------------------

    public void addLoadedPump(PumpInstance pump) {
        pumps.put(pump.key(), pump);
    }

    public void addLoadedPipe(PipeInstance pipe) {
        pipes.put(pipe.key(), pipe);
        addPipeIndex(pipe.key());
    }

    /** 管网拓扑变了（多/少了一个取水点），让所有水泵下一轮重建网络。 */
    public void invalidateNetworks() {
        pipeVersion++;
    }

    /**
     * 重载后清掉“形态是否存在”的缓存：资源包可能刚刚更新。
     */
    public void clearAppearanceCache() {
        knownPipeVariants.clear();
        missingPipeVariants.clear();
        warnedPipeVariants.clear();
    }

    /**
     * 重载配置后把水量/动力夹到新上限，避免旧数据超过新容量。
     */
    public void clampToConfig() {
        for (PumpInstance pump : pumps.values()) {
            PumpConfig pumpConfig = config.pump(pump.typeKey());
            if (pumpConfig == null) {
                continue;
            }
            pump.water(Math.min(pump.water(), pumpConfig.capacity()));
            pump.charge(pumpConfig.chargeCapacity() <= 0
                    ? 0
                    : Math.min(pump.charge(), pumpConfig.chargeCapacity()));
        }
        pipeVersion++;
    }

    public Collection<PumpInstance> pumpInstances() {
        return List.copyOf(pumps.values());
    }

    public PumpInstance pumpAt(BlockKey key) {
        return pumps.get(key);
    }

    /**
     * 查询某台水泵当前的管网（带缓存），供 GUI 显示。
     */
    public Network networkOf(BlockKey key) {
        PumpInstance pump = pumps.get(key);
        if (pump == null) {
            return Network.EMPTY;
        }
        PumpConfig pumpConfig = config.pump(pump.typeKey());
        if (pumpConfig == null) {
            return Network.EMPTY;
        }
        return network(pump, false, pumpConfig);
    }

    public Collection<PipeInstance> pipeInstances() {
        return List.copyOf(pipes.values());
    }

    public int pumpCount() {
        return pumps.size();
    }

    public int pipeCount() {
        return pipes.size();
    }

    public int worldCount() {
        Set<String> worlds = new HashSet<>();
        for (BlockKey key : pumps.keySet()) {
            worlds.add(key.world());
        }
        for (BlockKey key : pipes.keySet()) {
            worlds.add(key.world());
        }
        return worlds.size();
    }

    private void debug(java.util.function.Supplier<String> message) {
        if (config.debug()) {
            plugin.getLogger().info("[debug] " + message.get());
        }
    }
}
