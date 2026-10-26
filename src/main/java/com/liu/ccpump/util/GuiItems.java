package com.liu.ccpump.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

/**
 * 界面物品的通用构造：把 &amp; 颜色码转成文本、强制关闭斜体、拼进度条。
 *
 * <p>水泵界面和机器界面都要用，抽出来以后文案风格才不会两边走偏。
 */
public final class GuiItems {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private GuiItems() {
    }

    public static ItemStack item(Material material, String name, List<String> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(component(name));
            if (lore != null && !lore.isEmpty()) {
                meta.lore(lore.stream().map(GuiItems::component).toList());
            }
            stack.setItemMeta(meta);
        }
        return stack;
    }

    /**
     * 把 &amp; 颜色码转成文本，并强制关闭斜体。
     *
     * <p>Minecraft 的物品名称默认为斜体，不显式关闭的话界面里的
     * 名字和说明全是斜的。这里统一加 {@code italic = false}，
     * 配置里就不必每行都写 {@code &o} 或 {@code <!i>}。
     */
    public static Component component(String legacy) {
        return LEGACY.deserialize(legacy == null ? "" : legacy)
                .decoration(TextDecoration.ITALIC, false);
    }

    /** 用方块字符拼一条 10 格的进度条。 */
    public static String bar(int percent) {
        int filled = Math.max(0, Math.min(10, (int) Math.round(percent / 10.0D)));
        StringBuilder builder = new StringBuilder();
        builder.append(filled >= 4 ? "&a" : filled >= 2 ? "&e" : "&c");
        builder.append("|".repeat(filled));
        builder.append("&8").append("|".repeat(10 - filled));
        return builder.toString();
    }
}