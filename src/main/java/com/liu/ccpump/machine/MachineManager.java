package com.liu.ccpump.machine;

import com.liu.ccpump.CustomCropsPumpPlugin;
import com.liu.ccpump.config.ConfigManager;
import com.liu.ccpump.config.MachineConfig;
import com.liu.ccpump.data.BlockKey;
import com.liu.ccpump.data.MachineData;
import com.liu.ccpump.gui.MachineGui;
import com.liu.ccpump.listener.MachineDropListener;
import net.momirealms.customcrops.api.core.ExistenceForm;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 三台机器的生命周期与公共逻辑。
 *
 * <p>机器是 **CraftEngine 自定义方块**，不是实体：数据跟着方块坐标走，
 * 存在自己的 SQLite 库里（{@code machines} 表），和 CustomCrops 的方块状态无关。
 * 放置/破坏由 {@code MachineBlock} 转发进来，右键开界面由
 * {@code MachineInteractListener} 在 LOWEST 拦下。
 *
 * <p>每种机器“一个周期干什么”交给 {@link MachineBehavior}，这里只负责：
 * 周期调度、动力扣减、仓储写入、掉落捕获、失效校验、持久化。
 */
public final class MachineManager {

    /** CustomCrops 的掉落动作会晚一两个 tick 才触发，等这么久还没等到就不再认领。 */
    private static final long PENDING_DROP_TICKS = 8L;

    private final CustomCropsPumpPlugin plugin;
    private final ConfigManager config;
    private final MachineGui gui;

    private final Map<BlockKey, MachineData> machines = new LinkedHashMap<>();
    private final Map<BlockKey, PendingDrop> pendingDrops = new LinkedHashMap<>();

    private long tick;
    private long lastValidate;

    public MachineManager(CustomCropsPumpPlugin plugin, ConfigManager config) {
        this.plugin = plugin;
        this.config = config;
        this.gui = new MachineGui(plugin);
    }

    public CustomCropsPumpPlugin plugin() {
        return plugin;
    }

    public MachineGui gui() {
        return gui;
    }

    public void warn(String message) {
        plugin.getLogger().warning(message);
    }

    public void debug(java.util.function.Supplier<String> message) {
        plugin.debug(message);
    }

    /** 把“服务器 tick 数”换算成主循环次数（主循环默认 1 秒一次）。 */
    public long cycles(long ticks) {
        return Math.max(1L, ticks / Math.max(1L, config.tickInterval()));
    }

    // ------------------------------------------------------------------
    // 放置 / 破坏 / 交互
    // ------------------------------------------------------------------

    /** CraftEngine 方块放下后由 {@code MachineBlock} 调进来。 */
    public void place(Location location, String itemId) {
        MachineConfig machineConfig = config.machineByItem(itemId);
        if (machineConfig == null) {
            warn("未配置的机器物品 ID: " + itemId);
            return;
        }
        BlockKey key = BlockKey.of(location);
        MachineData existing = machines.get(key);
        if (existing != null && existing.typeKey().equals(machineConfig.key())) {
            return;
        }
        machines.put(key, new MachineData(key, machineConfig.key(),
                new ItemStack[machineConfig.bufferSlots()], 0, 0, true));
        debug(() -> "放置" + machineConfig.kind().displayName() + " @ " + key);
        if (machineConfig.needsWater()) {
            // 洒水机是水管网络的一个接口：拓扑变了，让相邻水管换模型、水泵重建管网
            plugin.pumpManager().invalidateNetworks();
            plugin.pumpManager().schedulePipeRefresh(key);
        }
    }

    /**
     * 方块被拆掉：记录删除，界面关闭，仓储里的东西掉在原地。
     *
     * <p>机器本体由 CraftEngine 的战利品表掉回来（每种机器只有一个方块 ID），
     * 这里只负责仓储内容，避免玩家拆机器时把里面的收获物/肥料一起弄丢。
     */
    public void breakAt(Location location) {
        BlockKey key = BlockKey.of(location);
        MachineData machine = machines.remove(key);
        gui.closeViewers(key);
        if (machine == null) {
            return;
        }
        MachineConfig machineConfig = config.machine(machine.typeKey());
        if (machineConfig != null && machineConfig.needsWater()) {
            // 少了一个取水点：相邻水管的模型和水泵的管网都要重算
            plugin.pumpManager().invalidateNetworks();
            plugin.pumpManager().schedulePipeRefresh(key);
        }
        World world = location.getWorld();
        if (world == null) {
            return;
        }
        Location dropAt = location.clone().add(0.5D, 0.5D, 0.5D);
        for (ItemStack stack : machine.buffer()) {
            if (stack == null || stack.getType().isAir() || stack.getAmount() <= 0) {
                continue;
            }
            dropAt(world, dropAt, stack);
        }
        machine.clearBuffer();
    }

    public boolean isMachineBlock(Location location) {
        BlockKey key = BlockKey.of(location);
        if (machines.containsKey(key)) {
            return true;
        }
        String itemId = blockId(location);
        return itemId != null && config.machineByItem(itemId) != null;
    }

    public MachineData machineAt(BlockKey key) {
        return machines.get(key);
    }

    public void interact(Player player, Location location) {
        MachineData machine = ensure(location);
        if (machine == null) {
            return;
        }
        gui.open(player, machine);
    }

    /**
     * 这个位置是不是“用水机器”（水箱容量 &gt; 0，能当水管网络的终点）。
     *
     * <p>先查内存记录（绝大多数情况一击命中），查不到再读一次方块 ID：
     * 记录丢了但方块还在时（换库、崩服），管道依然能接上去，不用玩家先右键自愈。
     */
    public boolean isWaterMachine(BlockKey key) {
        MachineData machine = machines.get(key);
        if (machine != null) {
            MachineConfig machineConfig = config.machine(machine.typeKey());
            return machineConfig != null && machineConfig.needsWater();
        }
        World world = Bukkit.getWorld(key.world());
        if (world == null || !world.isChunkLoaded(key.x() >> 4, key.z() >> 4)) {
            return false;
        }
        String itemId = blockId(key.toLocation(world));
        MachineConfig machineConfig = itemId == null ? null : config.machineByItem(itemId);
        return machineConfig != null && machineConfig.needsWater();
    }

    /**
     * 水管网络往机器水箱里灌水（不消耗水桶、不发消息）。
     *
     * @return 实际灌进去的水量，0 表示这台机器不需要水或者已经满了
     */
    public int fillFromNetwork(BlockKey key, int amount) {
        if (amount <= 0) {
            return 0;
        }
        MachineData machine = machines.get(key);
        if (machine == null) {
            return 0;
        }
        MachineConfig machineConfig = config.machine(machine.typeKey());
        if (machineConfig == null || !machineConfig.needsWater()) {
            return 0;
        }
        int space = machineConfig.water().capacity() - machine.water();
        if (space <= 0) {
            return 0;
        }
        int give = Math.min(space, amount);
        machine.water(machine.water() + give);
        machine.lastNetworkFill(System.currentTimeMillis());
        return give;
    }

    /** 记录丢了（换库/崩服）但方块还在时，补一条空记录，至少界面能开。 */
    private MachineData ensure(Location location) {
        BlockKey key = BlockKey.of(location);
        MachineData machine = machines.get(key);
        if (machine != null) {
            return machine;
        }
        String itemId = blockId(location);
        MachineConfig machineConfig = itemId == null ? null : config.machineByItem(itemId);
        if (machineConfig == null) {
            return null;
        }
        machine = new MachineData(key, machineConfig.key(),
                new ItemStack[machineConfig.bufferSlots()], 0, 0, true);
        machines.put(key, machine);
        debug(() -> "补登记机器 @ " + key);
        return machine;
    }

    // ------------------------------------------------------------------
    // 主循环
    // ------------------------------------------------------------------

    public void tick() {
        tick++;
        gui.tick();
        if (!machines.isEmpty()) {
            for (MachineData machine : new ArrayList<>(machines.values())) {
                try {
                    tickMachine(machine);
                } catch (Throwable throwable) {
                    warn("机器 " + machine.key() + " 工作异常: " + throwable);
                }
            }
        }
        long validateInterval = cycles(Math.max(1L, config.validateInterval()));
        if (tick - lastValidate >= validateInterval) {
            lastValidate = tick;
            validate();
        }
        sweepPendingDrops();
    }

    private void tickMachine(MachineData machine) {
        if (machine.nextTick() > tick) {
            return;
        }
        MachineConfig machineConfig = config.machine(machine.typeKey());
        if (machineConfig == null) {
            return;
        }
        World world = Bukkit.getWorld(machine.key().world());
        if (world == null) {
            return;
        }
        BlockKey key = machine.key();
        if (!world.isChunkLoaded(key.x() >> 4, key.z() >> 4)) {
            return;
        }
        machine.nextTick(tick + cycles(machineConfig.cycle()));
        if (!machine.enabled()) {
            return;
        }
        // 界面开着的时候以界面为准，先把玩家刚放进去的肥料等同步回机器。
        gui.syncFromView(machine);
        int budget = budget(machine, machineConfig);
        if (budget <= 0) {
            return;
        }
        MachineBehavior behavior = MachineBehaviors.of(machineConfig.kind());
        if (behavior == null) {
            return;
        }
        int actions = behavior.run(new MachineContext(this, machine, machineConfig, world, tick,
                Math.min(budget, machineConfig.maxPerCycle())));
        if (actions > 0) {
            consume(machine, machineConfig, actions);
        }
    }

    /** 本周期还能做几次动作：动力 / 每次消耗，向下取整。 */
    public int budget(MachineData machine, MachineConfig machineConfig) {
        if (machineConfig.infiniteCharge()) {
            return machineConfig.maxPerCycle();
        }
        int perAction = Math.max(1, machineConfig.chargePerAction());
        return machine.charge() / perAction;
    }

    public void consume(MachineData machine, MachineConfig machineConfig, int actions) {
        if (machineConfig.infiniteCharge() || actions <= 0) {
            return;
        }
        machine.charge(Math.max(0, machine.charge() - actions * machineConfig.chargePerAction()));
    }

    /** 定期校验方块还在不在（爆炸、WorldEdit、别的插件拆掉的情况）。 */
    private void validate() {
        Iterator<Map.Entry<BlockKey, MachineData>> iterator = machines.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<BlockKey, MachineData> entry = iterator.next();
            MachineData machine = entry.getValue();
            World world = Bukkit.getWorld(machine.key().world());
            if (world == null) {
                continue;
            }
            BlockKey key = machine.key();
            if (!world.isChunkLoaded(key.x() >> 4, key.z() >> 4)) {
                continue;
            }
            MachineConfig machineConfig = config.machine(machine.typeKey());
            if (machineConfig == null) {
                continue;
            }
            if (!isStillThere(key, world, machineConfig)) {
                iterator.remove();
                gui.closeViewers(key);
                debug(() -> "机器已消失，清理记录 @ " + key);
            }
        }
    }

    private boolean isStillThere(BlockKey key, World world, MachineConfig machineConfig) {
        String itemId = blockId(key.toLocation(world));
        return machineConfig.item().equals(itemId);
    }

    private String blockId(Location location) {
        try {
            return plugin.customCrops().getItemManager().id(location, ExistenceForm.BLOCK);
        } catch (Throwable throwable) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 仓储 / 掉落
    // ------------------------------------------------------------------

    /**
     * 把收获物写进机器。
     *
     * <p>界面开着时直接写进界面（玩家能立刻看到），否则写进机器自身；
     * 实在塞不下的部分掉在机器脚下，绝不静默丢弃。
     */
    public void store(MachineData machine, MachineConfig machineConfig, ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return;
        }
        ItemStack leftover;
        if (gui.hasViewer(machine.key())) {
            leftover = gui.insertIntoView(machine, stack);
        } else {
            leftover = machine.add(stack);
        }
        if (leftover != null && leftover.getAmount() > 0) {
            World world = Bukkit.getWorld(machine.key().world());
            if (world != null) {
                dropAt(world, machine.key().toLocation(world).add(0.5D, 1.0D, 0.5D), leftover);
            }
        }
    }

    public void dropAt(World world, Location location, ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return;
        }
        world.dropItemNaturally(location, stack);
    }

    /** 收割前登记“这个位置的掉落归这台机器”，由 {@link MachineDropListener} 消费。 */
    public void expectDrop(Location location, MachineData machine) {
        pendingDrops.put(BlockKey.of(location), new PendingDrop(machine, tick + PENDING_DROP_TICKS));
    }

    /** 认领某个位置的掉落；没有登记或已过期返回 null。 */
    public MachineData takeDrop(Location location) {
        PendingDrop pending = pendingDrops.get(BlockKey.of(location));
        if (pending == null || pending.expires() < tick) {
            return null;
        }
        return pending.machine();
    }

    private void sweepPendingDrops() {
        pendingDrops.entrySet().removeIf(entry -> entry.getValue().expires() < tick);
    }

    // ------------------------------------------------------------------
    // 充能 / 加水（界面调用）
    // ------------------------------------------------------------------

    public int chargeWithFuel(Player player, MachineData machine, MachineConfig machineConfig,
                              Material material, int available) {
        if (machineConfig.infiniteCharge()) {
            config.messages().send(player, "machine-charge-disabled");
            return 0;
        }
        int space = machineConfig.chargeCapacity() - machine.charge();
        if (space <= 0) {
            config.messages().send(player, "machine-charge-full",
                    "charge", String.valueOf(machine.charge()),
                    "capacity", String.valueOf(machineConfig.chargeCapacity()));
            return 0;
        }
        Integer fuel = config.fuelValue(material);
        if (fuel == null || fuel <= 0) {
            config.messages().send(player, "machine-fuel-invalid", "item", itemName(material));
            return 0;
        }
        if (available <= 0) {
            return 0;
        }
        int consume = Math.min(available, (space + fuel - 1) / fuel);
        int amount = Math.min(space, consume * fuel);
        machine.charge(machine.charge() + amount);
        config.messages().send(player, "machine-charged",
                "amount", String.valueOf(amount),
                "charge", String.valueOf(machine.charge()),
                "capacity", String.valueOf(machineConfig.chargeCapacity()));
        return consume;
    }

    /** 界面上投一个水桶：加满能加的部分，返回是否真的加到了水。 */
    public boolean addWater(Player player, MachineData machine, MachineConfig machineConfig) {
        MachineConfig.Water water = machineConfig.water();
        if (water.capacity() <= 0) {
            return false;
        }
        int space = water.capacity() - machine.water();
        if (space <= 0) {
            config.messages().send(player, "machine-water-full",
                    "water", String.valueOf(machine.water()),
                    "capacity", String.valueOf(water.capacity()));
            return false;
        }
        int amount = Math.min(space, water.bucketAmount());
        machine.water(machine.water() + amount);
        config.messages().send(player, "machine-water-added",
                "amount", String.valueOf(amount),
                "water", String.valueOf(machine.water()),
                "capacity", String.valueOf(water.capacity()));
        return true;
    }

    private String itemName(Material material) {
        return material == null ? "未知物品"
                : material.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    // ------------------------------------------------------------------
    // 查询 / 持久化
    // ------------------------------------------------------------------

    public Collection<MachineData> machines() {
        return machines.values();
    }

    public int machineCount() {
        return machines.size();
    }

    public void addLoaded(MachineData machine) {
        // 仓储格数必须和当前配置一致：从数据库读出来的记录可能来自旧配置，
        // 格数对不上会让机器“存不进东西”或把物品丢在脚下。
        MachineConfig machineConfig = config.machine(machine.typeKey());
        if (machineConfig != null && machine.bufferSlots() != machineConfig.bufferSlots()) {
            machine.resize(machineConfig.bufferSlots());
        }
        machines.put(machine.key(), machine);
    }

    /** 配置重载后按新配置调整仓储区大小，多出来的物品掉在原地。 */
    public void applyConfig() {
        for (MachineData machine : new ArrayList<>(machines.values())) {
            MachineConfig machineConfig = config.machine(machine.typeKey());
            if (machineConfig == null || machineConfig.bufferSlots() == machine.bufferSlots()) {
                continue;
            }
            List<ItemStack> overflow = machine.resize(machineConfig.bufferSlots());
            World world = Bukkit.getWorld(machine.key().world());
            if (world == null || overflow.isEmpty()) {
                continue;
            }
            Location dropAt = machine.key().toLocation(world).add(0.5D, 1.0D, 0.5D);
            for (ItemStack stack : overflow) {
                dropAt(world, dropAt, stack);
            }
        }
        gui.closeAll();
    }

    public void closeAllViews() {
        gui.closeAll();
    }

    private record PendingDrop(MachineData machine, long expires) {
    }
}