package com.liu.ccpump.gui;

import com.liu.ccpump.CustomCropsPumpPlugin;
import com.liu.ccpump.config.MachineConfig;
import com.liu.ccpump.config.MachineKind;
import com.liu.ccpump.data.BlockKey;
import com.liu.ccpump.data.MachineData;
import com.liu.ccpump.util.GuiItems;
import com.liu.ccpump.util.Messages;
import net.momirealms.customcrops.api.core.Registries;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 机器界面：三种机器共用一个界面，控制区固定，下面是各自的仓储区。
 *
 * <pre>
 *  0 开关    2 投放充能    4 储能    6 水箱/仓储    8 关闭
 *  9.. 仓储区（收割机=收获物，施肥机=肥料，洒水机没有）
 * </pre>
 *
 * <p>界面与机器的关系是“同一份数据的两个视图”：界面开着时以界面为准
 * （{@link #syncFromView} 在每次工作前把界面内容写回机器），机器产出时
 * 直接写进界面（{@link #insertIntoView}）。这样不会出现“界面里看到的和机器里的不一样”。
 *
 * <p>同一台机器只允许一个人打开界面：多个人同时打开会让仓储出现复制漏洞。
 */
public final class MachineGui {

    public static final int SLOT_TOGGLE = 0;
    public static final int SLOT_FUEL = 2;
    public static final int SLOT_CHARGE = 4;
    public static final int SLOT_INFO = 6;
    public static final int SLOT_CLOSE = 8;
    public static final int BUFFER_START = 9;

    /** 只出不进的仓储区允许的动作。 */
    private static final Set<InventoryAction> TAKE_ACTIONS = EnumSet.of(
            InventoryAction.PICKUP_ALL,
            InventoryAction.PICKUP_ONE,
            InventoryAction.PICKUP_HALF,
            InventoryAction.PICKUP_SOME,
            InventoryAction.MOVE_TO_OTHER_INVENTORY
    );

    /** 可以往仓储里放东西的动作（只有能输入的机器会用到）。 */
    private static final Set<InventoryAction> PLACE_ACTIONS = EnumSet.of(
            InventoryAction.PLACE_ALL,
            InventoryAction.PLACE_ONE,
            InventoryAction.PLACE_SOME,
            InventoryAction.SWAP_WITH_CURSOR
    );

    private final CustomCropsPumpPlugin plugin;
    private final Map<UUID, MachineGuiHolder> viewers = new HashMap<>();

    public MachineGui(CustomCropsPumpPlugin plugin) {
        this.plugin = plugin;
    }

    public static int size(MachineData machine) {
        int rows = Math.max(2, (machine.bufferSlots() + 8) / 9);
        return 9 + rows * 9;
    }

    // ------------------------------------------------------------------
    // 打开 / 关闭 / 刷新
    // ------------------------------------------------------------------

    public void open(Player player, MachineData machine) {
        MachineConfig machineConfig = plugin.configManager().machine(machine.typeKey());
        if (machineConfig == null) {
            return;
        }
        closeViewers(machine.key());
        MachineGuiHolder holder = new MachineGuiHolder(machine.key());
        Inventory inventory = Bukkit.createInventory(holder, size(machine),
                GuiItems.component(plugin.configManager().messages().text("gui-machine-title",
                        "machine", machineConfig.kind().displayName())));
        holder.inventory(inventory);
        renderAll(holder, machine, machineConfig);
        viewers.put(player.getUniqueId(), holder);
        player.openInventory(inventory);
    }

    /** 主循环刷新：只重画控制区，仓储区不动（玩家可能正拿着东西）。 */
    public void tick() {
        if (viewers.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<UUID, MachineGuiHolder>> iterator = viewers.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, MachineGuiHolder> entry = iterator.next();
            Player player = Bukkit.getPlayer(entry.getKey());
            MachineGuiHolder holder = entry.getValue();
            if (player == null || !player.isOnline()) {
                iterator.remove();
                continue;
            }
            if (player.getOpenInventory().getTopInventory().getHolder() != holder) {
                iterator.remove();
                continue;
            }
            MachineData machine = plugin.machineManager().machineAt(holder.key());
            MachineConfig machineConfig = machine == null ? null
                    : plugin.configManager().machine(machine.typeKey());
            if (machine == null || machineConfig == null) {
                iterator.remove();
                player.closeInventory();
                continue;
            }
            renderControls(holder, machine, machineConfig);
        }
    }

    /** 玩家关闭界面时调用：忘掉这个视图，之后产出直接写进机器。 */
    public void forget(Player player) {
        viewers.remove(player.getUniqueId());
    }

    public boolean hasViewer(BlockKey key) {
        return viewerOf(key) != null;
    }

    private Map.Entry<UUID, MachineGuiHolder> viewerOf(BlockKey key) {
        for (Map.Entry<UUID, MachineGuiHolder> entry : viewers.entrySet()) {
            if (entry.getValue().key().equals(key)) {
                return entry;
            }
        }
        return null;
    }

    public void closeViewers(BlockKey key) {
        Map.Entry<UUID, MachineGuiHolder> entry = viewerOf(key);
        if (entry == null) {
            return;
        }
        viewers.remove(entry.getKey());
        Player player = Bukkit.getPlayer(entry.getKey());
        if (player == null || !player.isOnline()) {
            return;
        }
        try {
            if (player.getOpenInventory().getTopInventory().getHolder() == entry.getValue()) {
                player.closeInventory();
            }
        } catch (Throwable throwable) {
            plugin.getLogger().warning("关闭机器界面失败: " + throwable);
        }
    }

    public void closeAll() {
        for (UUID uuid : List.copyOf(viewers.keySet())) {
            Player player = Bukkit.getPlayer(uuid);
            MachineGuiHolder holder = viewers.remove(uuid);
            if (player == null || holder == null) {
                continue;
            }
            try {
                if (player.getOpenInventory().getTopInventory().getHolder() == holder) {
                    player.closeInventory();
                }
            } catch (Throwable throwable) {
                plugin.getLogger().warning("关闭机器界面失败: " + throwable);
            }
        }
    }

    // ------------------------------------------------------------------
    // 界面与机器的同步
    // ------------------------------------------------------------------

    /** 把界面仓储区的内容写回机器（工作前调用）。 */
    public void syncFromView(MachineData machine) {
        Map.Entry<UUID, MachineGuiHolder> entry = viewerOf(machine.key());
        if (entry == null) {
            return;
        }
        Player player = Bukkit.getPlayer(entry.getKey());
        MachineGuiHolder holder = entry.getValue();
        if (player == null || holder.getInventory() == null) {
            return;
        }
        if (player.getOpenInventory().getTopInventory().getHolder() != holder) {
            return;
        }
        Inventory inventory = holder.getInventory();
        int slots = Math.min(machine.bufferSlots(), inventory.getSize() - BUFFER_START);
        for (int index = 0; index < slots; index++) {
            ItemStack stack = inventory.getItem(BUFFER_START + index);
            machine.buffer()[index] = stack == null || stack.getType().isAir() ? null : stack.clone();
        }
    }

    /**
     * 把物品塞进界面仓储区（界面开着时机器产出走这条路）。
     *
     * @return 塞不下的剩余部分
     */
    public ItemStack insertIntoView(MachineData machine, ItemStack stack) {
        Map.Entry<UUID, MachineGuiHolder> entry = viewerOf(machine.key());
        if (entry == null || entry.getValue().getInventory() == null) {
            return machine.add(stack);
        }
        Player viewer = Bukkit.getPlayer(entry.getKey());
        if (viewer == null || viewer.getOpenInventory().getTopInventory().getHolder() != entry.getValue()) {
            // 玩家已经关掉界面了：直接写进机器，别写进那个被丢弃的界面对象里
            return machine.add(stack);
        }
        Inventory inventory = entry.getValue().getInventory();
        int slots = Math.min(machine.bufferSlots(), inventory.getSize() - BUFFER_START);
        ItemStack remaining = stack.clone();
        for (int index = 0; index < slots && remaining.getAmount() > 0; index++) {
            ItemStack stored = inventory.getItem(BUFFER_START + index);
            if (stored == null || stored.getType().isAir() || !stored.isSimilar(remaining)) {
                continue;
            }
            int space = stored.getMaxStackSize() - stored.getAmount();
            if (space <= 0) {
                continue;
            }
            int move = Math.min(space, remaining.getAmount());
            stored.setAmount(stored.getAmount() + move);
            remaining.setAmount(remaining.getAmount() - move);
        }
        for (int index = 0; index < slots && remaining.getAmount() > 0; index++) {
            ItemStack stored = inventory.getItem(BUFFER_START + index);
            if (stored != null && !stored.getType().isAir()) {
                continue;
            }
            int move = Math.min(remaining.getMaxStackSize(), remaining.getAmount());
            ItemStack copy = remaining.clone();
            copy.setAmount(move);
            inventory.setItem(BUFFER_START + index, copy);
            remaining.setAmount(remaining.getAmount() - move);
        }
        syncFromView(machine);
        return remaining.getAmount() <= 0 ? null : remaining;
    }

    // ------------------------------------------------------------------
    // 点击
    // ------------------------------------------------------------------

    public void handleClick(Player player, MachineGuiHolder holder, InventoryClickEvent event) {
        MachineData machine = plugin.machineManager().machineAt(holder.key());
        MachineConfig machineConfig = machine == null ? null
                : plugin.configManager().machine(machine.typeKey());
        if (machine == null || machineConfig == null) {
            event.setCancelled(true);
            player.closeInventory();
            return;
        }
        Messages messages = plugin.configManager().messages();
        int raw = event.getRawSlot();
        int topSize = event.getView().getTopInventory().getSize();

        if (raw >= topSize) {
            // 玩家背包：只处理 shift 点击（等于“往机器里塞东西”）
            if (!event.getClick().isShiftClick()) {
                return;
            }
            event.setCancelled(true);
            if (!machineConfig.acceptsInput()) {
                return;
            }
            ItemStack current = event.getCurrentItem();
            if (!validInput(machineConfig, current)) {
                messages.send(player, "machine-fertilizer-invalid");
                return;
            }
            ItemStack leftover = insertIntoView(machine, current);
            if (event.getClickedInventory() != null) {
                event.getClickedInventory().setItem(event.getSlot(), leftover);
            }
            renderControls(holder, machine, machineConfig);
            return;
        }

        int bufferEnd = BUFFER_START + machine.bufferSlots();
        if (raw >= BUFFER_START && raw < bufferEnd) {
            if (!machineConfig.acceptsInput()) {
                if (!TAKE_ACTIONS.contains(event.getAction())) {
                    event.setCancelled(true);
                }
                syncLater(machine, holder);
                return;
            }
            boolean taking = TAKE_ACTIONS.contains(event.getAction());
            boolean placing = PLACE_ACTIONS.contains(event.getAction());
            if (!taking && !placing) {
                event.setCancelled(true);
                return;
            }
            if (placing && !validInput(machineConfig, event.getCursor())) {
                event.setCancelled(true);
                messages.send(player, "machine-fertilizer-invalid");
                return;
            }
            syncLater(machine, holder);
            return;
        }

        event.setCancelled(true);
        if (raw == SLOT_CLOSE) {
            player.closeInventory();
            return;
        }
        if (!player.hasPermission("customcropspump.use")) {
            messages.send(player, "no-permission");
            return;
        }
        if (raw == SLOT_TOGGLE) {
            machine.enabled(!machine.enabled());
            renderControls(holder, machine, machineConfig);
            return;
        }
        if (raw == SLOT_FUEL) {
            handleFuel(player, machine, machineConfig, event);
            return;
        }
        if (raw == SLOT_INFO && machineConfig.kind() == MachineKind.SPRINKLER) {
            handleWater(player, machine, machineConfig, event);
        }
    }

    /** 点击放行后延后一个 tick 再同步：事件里拿到的还是改之前的内容。 */
    private void syncLater(MachineData machine, MachineGuiHolder holder) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            syncFromView(machine);
            MachineConfig machineConfig = plugin.configManager().machine(machine.typeKey());
            if (machineConfig != null) {
                renderControls(holder, machine, machineConfig);
            }
        });
    }

    public void handleDrag(Player player, MachineGuiHolder holder, InventoryDragEvent event) {
        MachineData machine = plugin.machineManager().machineAt(holder.key());
        MachineConfig machineConfig = machine == null ? null
                : plugin.configManager().machine(machine.typeKey());
        if (machine == null || machineConfig == null) {
            event.setCancelled(true);
            return;
        }
        int topSize = event.getView().getTopInventory().getSize();
        boolean touchesTop = event.getRawSlots().stream().anyMatch(slot -> slot < topSize);
        if (!touchesTop) {
            return;
        }
        if (!machineConfig.acceptsInput()) {
            event.setCancelled(true);
            return;
        }
        int bufferEnd = BUFFER_START + machine.bufferSlots();
        boolean allInBuffer = event.getRawSlots().stream()
                .allMatch(slot -> slot >= BUFFER_START && slot < bufferEnd);
        boolean allValid = event.getNewItems().values().stream()
                .allMatch(stack -> validInput(machineConfig, stack));
        if (!allInBuffer || !allValid) {
            event.setCancelled(true);
            return;
        }
        syncLater(machine, holder);
    }

    // ------------------------------------------------------------------
    // 充能 / 加水
    // ------------------------------------------------------------------

    private void handleFuel(Player player, MachineData machine, MachineConfig machineConfig,
                            InventoryClickEvent event) {
        if (machineConfig.infiniteCharge()) {
            return;
        }
        // 投放槽是虚拟槽：只认鼠标光标上的物品。
        // （背包 shift 点击在 handleClick 里已经被截住了，这里拿不到背包物品）
        ItemStack offered = event.getCursor();
        if (offered == null || offered.getType().isAir()) {
            return;
        }
        Material material = offered.getType();
        Messages messages = plugin.configManager().messages();
        if (plugin.configManager().isBlockedFuel(material)) {
            messages.send(player, "machine-fuel-blocked", "item", material.name().toLowerCase(java.util.Locale.ROOT));
            return;
        }
        if (plugin.configManager().fuelValue(material) == null) {
            messages.send(player, "machine-fuel-invalid",
                    "item", material.name().toLowerCase(java.util.Locale.ROOT));
            return;
        }
        int consumed = plugin.machineManager().chargeWithFuel(player, machine, machineConfig,
                material, offered.getAmount());
        if (consumed <= 0) {
            renderControls(player, machine, machineConfig);
            return;
        }
        if (player.getGameMode() != GameMode.CREATIVE) {
            int remaining = offered.getAmount() - consumed;
            ItemStack left = null;
            if (remaining > 0) {
                left = offered.clone();
                left.setAmount(remaining);
            }
            player.setItemOnCursor(left);
        }
        renderControls(player, machine, machineConfig);
    }

    private void handleWater(Player player, MachineData machine, MachineConfig machineConfig,
                             InventoryClickEvent event) {
        ItemStack offered = event.getCursor();
        if (offered == null || offered.getType() != Material.WATER_BUCKET) {
            return;
        }
        if (!plugin.machineManager().addWater(player, machine, machineConfig)) {
            renderControls(player, machine, machineConfig);
            return;
        }
        if (player.getGameMode() != GameMode.CREATIVE) {
            player.setItemOnCursor(new ItemStack(Material.BUCKET));
        }
        renderControls(player, machine, machineConfig);
    }

    // ------------------------------------------------------------------
    // 渲染
    // ------------------------------------------------------------------

    private void renderControls(Player player, MachineData machine, MachineConfig machineConfig) {
        for (Map.Entry<UUID, MachineGuiHolder> entry : viewers.entrySet()) {
            if (!entry.getKey().equals(player.getUniqueId())) {
                continue;
            }
            if (!entry.getValue().key().equals(machine.key())) {
                continue;
            }
            renderControls(entry.getValue(), machine, machineConfig);
            return;
        }
    }

    private void renderAll(MachineGuiHolder holder, MachineData machine, MachineConfig machineConfig) {
        Inventory inventory = holder.getInventory();
        if (inventory == null) {
            return;
        }
        ItemStack filler = GuiItems.item(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, filler);
        }
        int slots = Math.min(machine.bufferSlots(), inventory.getSize() - BUFFER_START);
        for (int index = 0; index < slots; index++) {
            ItemStack stack = index < machine.buffer().length ? machine.buffer()[index] : null;
            inventory.setItem(BUFFER_START + index,
                    stack == null || stack.getType().isAir() ? null : stack.clone());
        }
        renderControls(holder, machine, machineConfig);
    }

    private void renderControls(MachineGuiHolder holder, MachineData machine, MachineConfig machineConfig) {
        Inventory inventory = holder.getInventory();
        if (inventory == null) {
            return;
        }
        Messages messages = plugin.configManager().messages();

        boolean enabled = machine.enabled();
        inventory.setItem(SLOT_TOGGLE, GuiItems.item(enabled ? Material.LIME_DYE : Material.GRAY_DYE,
                messages.text(enabled ? "gui-machine-state-on-name" : "gui-machine-state-off-name"),
                messages.list("gui-machine-toggle-lore")));

        if (machineConfig.infiniteCharge()) {
            inventory.setItem(SLOT_FUEL, GuiItems.item(Material.GRAY_STAINED_GLASS_PANE,
                    messages.text("gui-machine-fuel-disabled-name"),
                    messages.list("gui-machine-fuel-disabled-lore")));
        } else {
            inventory.setItem(SLOT_FUEL, GuiItems.item(Material.HOPPER,
                    messages.text("gui-machine-fuel-name"),
                    messages.list("gui-machine-fuel-lore",
                            "charge", String.valueOf(machine.charge()),
                            "capacity", String.valueOf(machineConfig.chargeCapacity()))));
        }

        int percent = machineConfig.infiniteCharge() ? 100
                : (int) Math.round(machine.charge() * 100.0D / Math.max(1, machineConfig.chargeCapacity()));
        if (machineConfig.infiniteCharge()) {
            inventory.setItem(SLOT_CHARGE, GuiItems.item(Material.REDSTONE,
                    messages.text("gui-machine-charge-name"),
                    messages.list("gui-machine-charge-infinite-lore")));
        } else {
            inventory.setItem(SLOT_CHARGE, GuiItems.item(Material.REDSTONE,
                    messages.text("gui-machine-charge-name"),
                    messages.list("gui-machine-charge-lore",
                            "charge", String.valueOf(machine.charge()),
                            "capacity", String.valueOf(machineConfig.chargeCapacity()),
                            "percent", String.valueOf(percent),
                            "bar", GuiItems.bar(percent))));
        }

        if (machineConfig.kind() == MachineKind.SPRINKLER) {
            int waterPercent = (int) Math.round(machine.water() * 100.0D
                    / Math.max(1, machineConfig.water().capacity()));
            inventory.setItem(SLOT_INFO, GuiItems.item(Material.WATER_BUCKET,
                    messages.text("gui-machine-water-name"),
                    messages.list("gui-machine-water-lore",
                            "water", String.valueOf(machine.water()),
                            "capacity", String.valueOf(machineConfig.water().capacity()),
                            "percent", String.valueOf(waterPercent),
                            "bar", GuiItems.bar(waterPercent))));
        } else if (machineConfig.kind() == MachineKind.FERTILIZER) {
            inventory.setItem(SLOT_INFO, GuiItems.item(Material.BONE_MEAL,
                    messages.text("gui-machine-fertilizer-name"),
                    messages.list("gui-machine-fertilizer-lore",
                            "used", String.valueOf(machine.usedSlots()),
                            "slots", String.valueOf(machine.bufferSlots()),
                            "amount", String.valueOf(machine.storedAmount()))));
        } else {
            inventory.setItem(SLOT_INFO, GuiItems.item(Material.CHEST,
                    messages.text("gui-machine-storage-name"),
                    messages.list("gui-machine-storage-lore",
                            "used", String.valueOf(machine.usedSlots()),
                            "slots", String.valueOf(machine.bufferSlots()),
                            "amount", String.valueOf(machine.storedAmount()))));
        }

        inventory.setItem(SLOT_CLOSE, GuiItems.item(Material.BARRIER,
                messages.text("gui-machine-close-name"),
                messages.list("gui-machine-close-lore")));
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /** 这个物品能不能放进机器（目前只有施肥机可输入，且只认 CustomCrops 肥料）。 */
    public boolean validInput(MachineConfig machineConfig, ItemStack stack) {
        if (machineConfig == null || !machineConfig.acceptsInput()) {
            return false;
        }
        if (stack == null || stack.getType().isAir()) {
            return false;
        }
        if (machineConfig.kind() != MachineKind.FERTILIZER) {
            return false;
        }
        return Registries.ITEM_TO_FERTILIZER.get(itemId(stack)) != null;
    }

    private String itemId(ItemStack stack) {
        try {
            return plugin.customCrops().getItemManager().id(stack);
        } catch (Throwable throwable) {
            return "";
        }
    }
}
