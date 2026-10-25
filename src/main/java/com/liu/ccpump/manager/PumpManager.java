package com.liu.ccpump.manager;

import com.liu.ccpump.CustomCropsPumpPlugin;
import com.liu.ccpump.config.ConfigManager;
import com.liu.ccpump.config.PipeConfig;
import com.liu.ccpump.config.PumpConfig;
import com.liu.ccpump.data.BlockKey;
import com.liu.ccpump.data.Network;
import com.liu.ccpump.data.PipeInstance;
import com.liu.ccpump.data.PumpInstance;
import net.momirealms.customcrops.api.core.ExistenceForm;
import net.momirealms.customcrops.api.core.FurnitureRotation;
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
import org.bukkit.block.data.Waterlogged;
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
    private final ConfigManager config;

    private final Map<BlockKey, PumpInstance> pumps = new LinkedHashMap<>();
    private final Map<BlockKey, PipeInstance> pipes = new LinkedHashMap<>();
    private final Map<String, Set<Pos3>> pipesByWorld = new HashMap<>();

    /** 已确认存在的连接变体物品，避免每次都去查物品表。 */
    private final Set<String> knownPipeVariants = new HashSet<>();
    /** 确认不存在的变体物品：静默跳过外观切换，避免刷屏。 */
    private final Set<String> missingPipeVariants = new HashSet<>();
    /** 已提示过“缺少连接模型”的水管配置，避免重复告警。 */
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

    public void interactPump(Player player, Location location, EquipmentSlot hand, ItemStack itemInHand) {
        BlockKey key = BlockKey.of(location);
        PumpInstance pump = pumps.get(key);
        if (pump == null) {
            // data.yml 丢记录或区块刚加载时，用世界上真实的家具 ID 自愈
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

        if (itemInHand != null) {
            Material type = itemInHand.getType();
            if (type == Material.WATER_BUCKET) {
                fillFromBucket(player, pump, pumpConfig, hand);
                return;
            }
            Integer fuel = config.fuelValue(type);
            if (fuel != null) {
                chargePump(player, pump, pumpConfig, hand, itemInHand, fuel);
                return;
            }
            if (config.isBlockedFuel(type)) {
                config.messages().send(player, "pump-fuel-blocked", "item", itemName(type));
                return;
            }
        }

        if (player.isSneaking() && pumpConfig.allowToggle()) {
            togglePump(player, pump);
            return;
        }

        plugin.gui().open(player, key);
    }

    /**
     * 开关水泵并提示玩家。
     */
    public void togglePump(Player player, PumpInstance pump) {
        pump.enabled(!pump.enabled());
        config.messages().send(player, pump.enabled() ? "pump-toggled-on" : "pump-toggled-off");
    }

    /**
     * 世界上存在、但内存里没有登记的水泵：按家具 ID 重新登记。
     */
    private PumpInstance registerExistingPump(BlockKey key) {
        World world = Bukkit.getWorld(key.world());
        if (world == null || !isChunkLoaded(world, key)) {
            return null;
        }
        String itemId = furnitureId(key, world);
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

    private void chargePump(Player player, PumpInstance pump, PumpConfig pumpConfig,
                            EquipmentSlot hand, ItemStack itemInHand, int fuel) {
        if (pumpConfig.chargeCapacity() <= 0) {
            config.messages().send(player, "pump-charge-disabled");
            return;
        }
        int space = pumpConfig.chargeCapacity() - pump.charge();
        if (space <= 0) {
            config.messages().send(player, "pump-charge-full",
                    "charge", String.valueOf(pump.charge()),
                    "capacity", String.valueOf(pumpConfig.chargeCapacity()));
            return;
        }
        int amount = Math.min(space, fuel);
        pump.charge(pump.charge() + amount);

        if (player.getGameMode() != GameMode.CREATIVE) {
            consumeOne(player, hand, itemInHand.getType());
        }

        config.messages().send(player, "pump-charged",
                "amount", String.valueOf(amount),
                "charge", String.valueOf(pump.charge()),
                "capacity", String.valueOf(pumpConfig.chargeCapacity()));
    }

    private void consumeOne(Player player, EquipmentSlot hand, Material material) {
        EquipmentSlot usedHand = hand == EquipmentSlot.OFF_HAND ? EquipmentSlot.OFF_HAND : EquipmentSlot.HAND;
        ItemStack current = player.getInventory().getItem(usedHand);
        if (current == null || current.getType() != material) {
            return;
        }
        if (current.getAmount() <= 1) {
            player.getInventory().setItem(usedHand, null);
        } else {
            current.setAmount(current.getAmount() - 1);
            player.getInventory().setItem(usedHand, current);
        }
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

    public void interactPipe(Player player, Location location) {
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
        // 洒水器可能是刚放下的，顺便校正一次外观
        refreshPipeAppearance(key);

        int maxPipes = 256;
        int maxSprinklers = 64;
        int maxDistance = 128;
        for (PumpConfig pumpConfig : config.pumps()) {
            maxPipes = Math.max(maxPipes, pumpConfig.network().maxPipes());
            maxSprinklers = Math.max(maxSprinklers, pumpConfig.network().maxSprinklers());
            maxDistance = Math.max(maxDistance, pumpConfig.network().maxDistance());
        }

        Network network = buildNetwork(pipe.key(), maxPipes, maxSprinklers, maxDistance, true);
        String state = config.messages().text(network.pumps() > 0 ? "pipe-connected" : "pipe-disconnected");
        config.messages().send(player, "pipe-info",
                "pipes", String.valueOf(network.pipes().size() + 1),
                "pumps", String.valueOf(network.pumps()),
                "sprinklers", String.valueOf(network.sprinklers().size()),
                "state", state);
    }

    private PipeInstance registerExistingPipe(BlockKey key) {
        World world = Bukkit.getWorld(key.world());
        if (world == null || !isChunkLoaded(world, key)) {
            return null;
        }
        String itemId = furnitureId(key, world);
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
     * 计算一根水管应该显示的连接掩码：相邻的水泵、水管、洒水器都算连接。
     * 位顺序与 DIRECTIONS 一致：东、西、上、下、南、北。
     */
    public int connectedMask(BlockKey key) {
        int mask = 0;
        for (int i = 0; i < DIRECTIONS.length; i++) {
            int[] direction = DIRECTIONS[i];
            BlockKey neighbor = key.offset(direction[0], direction[1], direction[2]);
            if (pipes.containsKey(neighbor) || pumps.containsKey(neighbor)
                    || isSprinklerKey(neighbor)) {
                mask |= MASK_BITS[i];
            }
        }
        return mask;
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
     * 把一根水管切换成对应的连接模型。已经正确时什么都不做。
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
        String current = furnitureId(key, world);
        if (current == null) {
            // 家具还没加载出来（区块刚加载），交给下一次校验
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
            FurnitureRotation rotation = plugin.customCrops().getItemManager()
                    .remove(location, ExistenceForm.FURNITURE);
            plugin.customCrops().getItemManager()
                    .place(location, ExistenceForm.FURNITURE, desired, rotation);
            debug(() -> "水管外观 " + key + " -> " + desired);
        } catch (Throwable throwable) {
            plugin.getLogger().warning("切换水管外观失败（" + key + "）: " + throwable.getMessage());
        }
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
            plugin.getLogger().warning("找不到水管连接模型 " + variantId
                    + "，水管将保持原样。请确认已安装插件自带的 CraftEngine 资源包"
                    + "（craftengine-pack.overwrite: true 或删除旧 ccpump 目录后重启）。");
        }
        return false;
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
            pump.sourceAvailable(hasWaterSource(world, pump.key(), pumpConfig.source().radius()));
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
                    consumeWaterSource(world, pump.key(), pumpConfig.source().radius());
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
    }

    private int deliver(World world, PumpInstance pump, PumpConfig pumpConfig, List<Pos3> sprinklerPositions) {
        CustomCropsWorld<?> customCropsWorld = plugin.customCrops().getWorldManager()
                .getWorld(world).orElse(null);
        if (customCropsWorld == null) {
            return 0;
        }

        int remaining = pump.water();
        int delivered = 0;
        for (Pos3 pos : sprinklerPositions) {
            if (remaining <= 0) {
                break;
            }
            try {
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
    // 水源
    // ------------------------------------------------------------------

    private boolean hasWaterSource(World world, BlockKey key, int radius) {
        int effectiveRadius = Math.max(1, radius);
        for (int dx = -effectiveRadius; dx <= effectiveRadius; dx++) {
            for (int dy = -effectiveRadius; dy <= effectiveRadius; dy++) {
                for (int dz = -effectiveRadius; dz <= effectiveRadius; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }
                    if (!isChunkLoaded(world, key.offset(dx, dy, dz))) {
                        continue;
                    }
                    int y = key.y() + dy;
                    if (y < world.getMinHeight() || y >= world.getMaxHeight()) {
                        continue;
                    }
                    Block block = world.getBlockAt(key.x() + dx, y, key.z() + dz);
                    if (isWater(block)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void consumeWaterSource(World world, BlockKey key, int radius) {
        int effectiveRadius = Math.max(1, radius);
        for (int dx = -effectiveRadius; dx <= effectiveRadius; dx++) {
            for (int dy = -effectiveRadius; dy <= effectiveRadius; dy++) {
                for (int dz = -effectiveRadius; dz <= effectiveRadius; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }
                    if (!isChunkLoaded(world, key.offset(dx, dy, dz))) {
                        continue;
                    }
                    int y = key.y() + dy;
                    if (y < world.getMinHeight() || y >= world.getMaxHeight()) {
                        continue;
                    }
                    Block block = world.getBlockAt(key.x() + dx, y, key.z() + dz);
                    if (block.getType() == Material.WATER) {
                        block.setType(Material.AIR, false);
                        return;
                    }
                }
            }
        }
    }

    private boolean isWater(Block block) {
        Material type = block.getType();
        if (type == Material.WATER || type == Material.BUBBLE_COLUMN) {
            return true;
        }
        return block.getBlockData() instanceof Waterlogged waterlogged && waterlogged.isWaterlogged();
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
                } else if (customCropsWorld != null
                        && foundSprinklers.size() < maxSprinklers
                        && isSprinkler(customCropsWorld, next)) {
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
            String blockId = plugin.customCrops().getItemManager()
                    .id(pump.key().toLocation(world), ExistenceForm.ANY);
            if (!pumpConfig.item().equals(blockId)) {
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
            String blockId = plugin.customCrops().getItemManager()
                    .id(pipe.key().toLocation(world), ExistenceForm.ANY);
            if (!pipeConfig.item().equals(blockId)) {
                iterator.remove();
                removePipeIndex(pipe.key());
                pipeVersion++;
                debug(() -> "清理失效水管 @ " + pipe.key());
            }
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

    public void invalidateNetworks() {
        pipeVersion++;
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
