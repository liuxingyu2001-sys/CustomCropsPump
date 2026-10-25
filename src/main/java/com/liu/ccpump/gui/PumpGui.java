package com.liu.ccpump.gui;

import com.liu.ccpump.CustomCropsPumpPlugin;
import com.liu.ccpump.config.PumpConfig;
import com.liu.ccpump.data.BlockKey;
import com.liu.ccpump.data.Network;
import com.liu.ccpump.data.PumpInstance;
import com.liu.ccpump.util.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 水泵右键打开的界面：显示水量、动力、管网、水源，并提供开关按钮。
 */
public final class PumpGui {

    private static final int SIZE = 27;
    private static final int SLOT_TOGGLE = 4;
    private static final int SLOT_WATER = 10;
    private static final int SLOT_CHARGE = 12;
    private static final int SLOT_NETWORK = 14;
    private static final int SLOT_SOURCE = 16;
    private static final int SLOT_CLOSE = 22;

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

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
        Inventory inventory = Bukkit.createInventory(holder, SIZE, component(messages.text("gui-title")));
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

    public void handleClick(Player player, PumpGuiHolder holder, int slot) {
        if (slot == SLOT_CLOSE) {
            player.closeInventory();
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

        ItemStack filler = item(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
        for (int slot = 0; slot < SIZE; slot++) {
            inventory.setItem(slot, filler);
        }

        Network network = plugin.pumpManager().networkOf(holder.key());

        inventory.setItem(SLOT_WATER, item(Material.WATER_BUCKET,
                messages.text("gui-water-name"),
                messages.list("gui-water-lore",
                        "water", String.valueOf(pump.water()),
                        "capacity", String.valueOf(pumpConfig.capacity()))));

        if (pumpConfig.chargeCapacity() <= 0) {
            inventory.setItem(SLOT_CHARGE, item(Material.REDSTONE,
                    messages.text("gui-charge-name"),
                    messages.list("gui-charge-infinite-lore")));
        } else {
            inventory.setItem(SLOT_CHARGE, item(Material.REDSTONE,
                    messages.text("gui-charge-name"),
                    messages.list("gui-charge-lore",
                            "charge", String.valueOf(pump.charge()),
                            "capacity", String.valueOf(pumpConfig.chargeCapacity()))));
        }

        inventory.setItem(SLOT_NETWORK, item(Material.HOPPER,
                messages.text("gui-network-name"),
                messages.list("gui-network-lore",
                        "pipes", String.valueOf(network.pipes().size()),
                        "sprinklers", String.valueOf(network.sprinklers().size()))));

        String source = !pumpConfig.source().required()
                ? messages.text("source-infinite")
                : messages.text(pump.sourceAvailable() ? "source-yes" : "source-no");
        inventory.setItem(SLOT_SOURCE, item(Material.SEAGRASS,
                messages.text("gui-source-name"),
                messages.list("gui-source-lore", "source", source)));

        boolean enabled = pump.enabled();
        inventory.setItem(SLOT_TOGGLE, item(enabled ? Material.LIME_DYE : Material.GRAY_DYE,
                messages.text(enabled ? "gui-state-on-name" : "gui-state-off-name"),
                messages.list("gui-toggle-lore")));

        inventory.setItem(SLOT_CLOSE, item(Material.BARRIER,
                messages.text("gui-close-name"),
                messages.list("gui-close-lore")));
    }

    private static ItemStack item(Material material, String name, List<String> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(component(name));
            if (!lore.isEmpty()) {
                meta.lore(lore.stream().map(PumpGui::component).toList());
            }
            stack.setItemMeta(meta);
        }
        return stack;
    }

    /**
     * 把 & 颜色码转成文本，并强制关闭斜体。
     *
     * <p>Minecraft 的物品名称默认为斜体，不显式关闭的话界面里的
     * 名字和说明全是斜的。这里统一加 {@code italic = false}，
     * 配置里就不必每行都写 {@code &o} 或 {@code <!i>}。
     */
    private static Component component(String legacy) {
        return LEGACY.deserialize(legacy == null ? "" : legacy)
                .decoration(TextDecoration.ITALIC, false);
    }
}
