package com.liu.ccpump.gui;

import com.liu.ccpump.CustomCropsPumpPlugin;
import com.liu.ccpump.config.PumpConfig;
import com.liu.ccpump.data.BlockKey;
import com.liu.ccpump.data.Network;
import com.liu.ccpump.data.PumpInstance;
import com.liu.ccpump.util.GuiItems;
import com.liu.ccpump.util.Messages;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 水泵右键打开的界面：显示水量、动力、管网、水源，并提供开关按钮与充能投放槽。
 */
public final class PumpGui {

    private static final int SIZE = 27;
    private static final int SLOT_TOGGLE = 4;
    private static final int SLOT_WATER = 10;
    private static final int SLOT_CHARGE = 12;
    private static final int SLOT_NETWORK = 14;
    private static final int SLOT_SOURCE = 16;
    /**
     * 投放槽：把燃料放进来就立刻充能。
     *
     * <p>水泵是方块，手持红石右键时原版会先把方块摆到相邻位置，
     * 充电轮不到，所以充能改成在界面里投放燃料。
     */
    private static final int SLOT_FUEL = 22;
    /** 耐久：显示剩余百分比和进度条。 */
    private static final int SLOT_DURABILITY = 20;
    private static final int SLOT_CLOSE = 26;

    private final CustomCropsPumpPlugin plugin;
    private final Map<UUID, PumpGuiHolder> viewers = new HashMap<>();

    public PumpGui(CustomCropsPumpPlugin plugin) {
        this.plugin = plugin;
    }

    public void open(Player player, BlockKey key) {
        PumpInstance pump = plugin.pumpManager().pumpAt(key);
        if (pump == null) {
            return;
        }
        Messages messages = plugin.configManager().messages();
        PumpGuiHolder holder = new PumpGuiHolder(key);
        Inventory inventory = Bukkit.createInventory(holder, SIZE, GuiItems.component(messages.text("gui-title")));
        holder.inventory(inventory);
        render(holder);
        viewers.put(player.getUniqueId(), holder);
        player.openInventory(inventory);
    }

    /**
     * 每秒刷新一次所有打开的界面（水量/动力会变化）。
     */
    public void refreshAll() {
        if (viewers.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<UUID, PumpGuiHolder>> iterator = viewers.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, PumpGuiHolder> entry = iterator.next();
            Player player = Bukkit.getPlayer(entry.getKey());
            PumpGuiHolder holder = entry.getValue();
            if (player == null || !player.isOnline()) {
                iterator.remove();
                continue;
            }
            if (player.getOpenInventory().getTopInventory().getHolder() != holder) {
                iterator.remove();
                continue;
            }
            if (plugin.pumpManager().pumpAt(holder.key()) == null) {
                iterator.remove();
                player.closeInventory();
                continue;
            }
            render(holder);
        }
    }

    public void handleClick(Player player, PumpGuiHolder holder, InventoryClickEvent event) {
        int slot = event.getRawSlot();
        int topSize = event.getView().getTopInventory().getSize();
        if (slot >= topSize) {
            // 背包格子：只有 shift 点击会被转到这里，等于“往投放槽里投燃料”
            if (event.getClick().isShiftClick()) {
                handleFuel(player, holder, event);
            }
            return;
        }
        if (slot == SLOT_CLOSE) {
            player.closeInventory();
            return;
        }
        if (slot == SLOT_FUEL) {
            handleFuel(player, holder, event);
            return;
        }
        if (slot != SLOT_TOGGLE) {
            return;
        }
        PumpInstance pump = plugin.pumpManager().pumpAt(holder.key());
        if (pump == null) {
            player.closeInventory();
            return;
        }
        PumpConfig pumpConfig = plugin.configManager().pump(pump.typeKey());
        if (pumpConfig == null) {
            return;
        }
        if (!player.hasPermission("customcropspump.use")) {
            plugin.configManager().messages().send(player, "no-permission");
            return;
        }
        if (!pumpConfig.allowToggle()) {
            plugin.configManager().messages().send(player, "pump-toggle-disabled");
            return;
        }
        plugin.pumpManager().togglePump(player, pump);
        render(holder);
    }

    /**
     * 投放槽充能。
     *
     * <p>槽位本身不放东西：物品进来就按需要量消耗掉，所以这里先把点击
     * 事件取消掉，再手动扣物品，避免槽位里残留一份“影子物品”。
     *
     * <p>两种投放方式都支持：
     * <ul>
     *   <li>正常点击：用光标上拿着的那一组；</li>
     *   <li>shift 点击背包格子：用被点击的那一格（整个界面只有投放槽可以
     *       这样投，其它格子仍然禁止 shift）。</li>
     * </ul>
     */
    private void handleFuel(Player player, PumpGuiHolder holder, InventoryClickEvent event) {
        PumpInstance pump = plugin.pumpManager().pumpAt(holder.key());
        if (pump == null) {
            player.closeInventory();
            return;
        }
        PumpConfig pumpConfig = plugin.configManager().pump(pump.typeKey());
        if (pumpConfig == null) {
            return;
        }
        if (!player.hasPermission("customcropspump.use")) {
            plugin.configManager().messages().send(player, "no-permission");
            return;
        }

        boolean fromInventory = false;
        ItemStack offered = event.getCursor();
        if (offered == null || offered.getType().isAir()) {
            if (!event.getClick().isShiftClick()) {
                // 空手点投放槽：什么都不做（不能把槽位里那个提示图标当成燃料）
                return;
            }
            offered = event.getCurrentItem();
            fromInventory = true;
        }
        if (offered == null || offered.getType().isAir()) {
            return;
        }

        Material material = offered.getType();
        Messages messages = plugin.configManager().messages();
        if (plugin.configManager().isBlockedFuel(material)) {
            messages.send(player, "pump-fuel-blocked", "item", material.name().toLowerCase(Locale.ROOT));
            return;
        }
        if (plugin.configManager().fuelValue(material) == null) {
            messages.send(player, "pump-fuel-invalid",
                    "item", material.name().toLowerCase(Locale.ROOT));
            return;
        }

        int consumed = plugin.pumpManager().chargeWithFuel(player, pump, pumpConfig,
                material, offered.getAmount());
        if (consumed <= 0) {
            render(holder);
            return;
        }

        if (player.getGameMode() == GameMode.CREATIVE) {
            // 创造模式充能不消耗物品：只刷新界面，不动光标/背包
            render(holder);
            return;
        }

        int remaining = offered.getAmount() - consumed;
        ItemStack left = null;
        if (remaining > 0) {
            left = offered.clone();
            left.setAmount(remaining);
        }
        if (fromInventory) {
            // 直接写玩家背包：shift 点击的事件已经取消，走 event.setCurrentItem
            // 客户端有时不会同步
            player.getInventory().setItem(event.getSlot(), left);
        } else {
            player.setItemOnCursor(left);
        }
        render(holder);
    }

    public void close(Player player) {
        viewers.remove(player.getUniqueId());
    }

    /**
     * 水泵被拆掉时关掉正在看它的界面。
     */
    public void closeViewers(BlockKey key) {
        if (viewers.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<UUID, PumpGuiHolder>> iterator = viewers.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, PumpGuiHolder> entry = iterator.next();
            if (!entry.getValue().key().equals(key)) {
                continue;
            }
            iterator.remove();
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null && player.isOnline()) {
                player.closeInventory();
            }
        }
    }

    private void render(PumpGuiHolder holder) {
        PumpInstance pump = plugin.pumpManager().pumpAt(holder.key());
        if (pump == null) {
            return;
        }
        PumpConfig pumpConfig = plugin.configManager().pump(pump.typeKey());
        if (pumpConfig == null) {
            return;
        }
        Messages messages = plugin.configManager().messages();
        Inventory inventory = holder.getInventory();
        if (inventory == null) {
            return;
        }

        ItemStack filler = GuiItems.item(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
        for (int slot = 0; slot < SIZE; slot++) {
            inventory.setItem(slot, filler);
        }

        Network network = plugin.pumpManager().networkOf(holder.key());

        inventory.setItem(SLOT_WATER, GuiItems.item(Material.WATER_BUCKET,
                messages.text("gui-water-name"),
                messages.list("gui-water-lore",
                        "water", String.valueOf(pump.water()),
                        "capacity", String.valueOf(pumpConfig.capacity()))));

        if (pumpConfig.chargeCapacity() <= 0) {
            inventory.setItem(SLOT_CHARGE, GuiItems.item(Material.REDSTONE,
                    messages.text("gui-charge-name"),
                    messages.list("gui-charge-infinite-lore")));
        } else {
            inventory.setItem(SLOT_CHARGE, GuiItems.item(Material.REDSTONE,
                    messages.text("gui-charge-name"),
                    messages.list("gui-charge-lore",
                            "charge", String.valueOf(pump.charge()),
                            "capacity", String.valueOf(pumpConfig.chargeCapacity()))));
        }

        inventory.setItem(SLOT_NETWORK, GuiItems.item(Material.HOPPER,
                messages.text("gui-network-name"),
                messages.list("gui-network-lore",
                        "pipes", String.valueOf(network.pipes().size()),
                        "sprinklers", String.valueOf(network.sprinklers().size()))));

        String source = !pumpConfig.source().required()
                ? messages.text("source-infinite")
                : messages.text(pump.sourceAvailable() ? "source-yes" : "source-no");
        inventory.setItem(SLOT_SOURCE, GuiItems.item(Material.SEAGRASS,
                messages.text("gui-source-name"),
                messages.list("gui-source-lore", "source", source)));

        renderDurability(inventory, messages, pump, pumpConfig);

        if (pumpConfig.chargeCapacity() <= 0) {
            inventory.setItem(SLOT_FUEL, GuiItems.item(Material.GRAY_STAINED_GLASS_PANE,
                    messages.text("gui-fuel-disabled-name"),
                    messages.list("gui-fuel-disabled-lore")));
        } else {
            inventory.setItem(SLOT_FUEL, GuiItems.item(Material.HOPPER,
                    messages.text("gui-fuel-name"),
                    messages.list("gui-fuel-lore")));
        }

        boolean enabled = pump.enabled();
        inventory.setItem(SLOT_TOGGLE, GuiItems.item(enabled ? Material.LIME_DYE : Material.GRAY_DYE,
                messages.text(enabled ? "gui-state-on-name" : "gui-state-off-name"),
                messages.list("gui-toggle-lore")));

        inventory.setItem(SLOT_CLOSE, GuiItems.item(Material.BARRIER,
                messages.text("gui-close-name"),
                messages.list("gui-close-lore")));
    }

    /**
     * 耐久槽：显示剩余耐久与进度条。
     *
     * <p>耐久上限配置为 0 时表示不做限制，显示成“不限制”。
     * 低于 20% 换成裂纹铁砧提醒，避免玩家在毫无察觉的情况下报废。
     */
    private void renderDurability(Inventory inventory, Messages messages,
                                  PumpInstance pump, PumpConfig pumpConfig) {
        int max = pumpConfig.durability();
        if (max <= 0) {
            inventory.setItem(SLOT_DURABILITY, GuiItems.item(Material.ANVIL,
                    messages.text("gui-durability-name"),
                    messages.list("gui-durability-unlimited-lore")));
            return;
        }
        int remaining = pump.remainingDurability(max);
        int percent = (int) Math.round(remaining * 100.0D / max);
        boolean low = percent <= 20;
        inventory.setItem(SLOT_DURABILITY, GuiItems.item(low ? Material.DAMAGED_ANVIL : Material.ANVIL,
                messages.text("gui-durability-name"),
                messages.list("gui-durability-lore",
                        "remaining", String.valueOf(remaining),
                        "max", String.valueOf(max),
                        "percent", String.valueOf(percent),
                        "bar", GuiItems.bar(percent))));
    }

}
