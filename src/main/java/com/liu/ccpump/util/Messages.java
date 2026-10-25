package com.liu.ccpump.util;

import com.liu.ccpump.CustomCropsPumpPlugin;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 读取 messages.yml，支持 & 颜色代码和 {占位符}。
 * 最终文本通过 Adventure 的 legacy 序列化器发送，避免使用已过时的 ChatColor。
 */
public final class Messages {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private final CustomCropsPumpPlugin plugin;
    private final YamlConfiguration config;
    private String prefix;

    private Messages(CustomCropsPumpPlugin plugin, YamlConfiguration config) {
        this.plugin = plugin;
        this.config = config;
        this.prefix = config.getString("prefix", "&b[水泵] &r");
    }

    public static Messages load(CustomCropsPumpPlugin plugin) {
        File file = new File(plugin.getDataFolder(), "messages.yml");
        if (!file.exists()) {
            plugin.saveResource("messages.yml", false);
        }
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        try (InputStream in = plugin.getResource("messages.yml")) {
            if (in != null) {
                YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                        new InputStreamReader(in, StandardCharsets.UTF_8));
                config.setDefaults(defaults);
                config.options().copyDefaults(true);
                config.save(file);
            }
        } catch (IOException e) {
            plugin.getLogger().warning("读取默认 messages.yml 失败: " + e.getMessage());
        }
        return new Messages(plugin, config);
    }

    public String prefix() {
        return prefix;
    }

    /**
     * 带前缀的完整消息（原始 & 颜色代码）。
     */
    public String message(String key, String... placeholders) {
        String raw = config.getString(key, key);
        return prefix + apply(raw, placeholders);
    }

    /**
     * 只取消息正文（原始 & 颜色代码），便于嵌入其他消息。
     */
    public String text(String key, String... placeholders) {
        String raw = config.getString(key, key);
        return apply(raw, placeholders);
    }

    /**
     * 读取多行文本（例如 GUI 的 lore），逐行替换占位符。
     */
    public List<String> list(String key, String... placeholders) {
        List<String> raw = config.getStringList(key);
        if (raw.isEmpty()) {
            String single = config.getString(key);
            if (single == null) {
                return List.of();
            }
            raw = List.of(single.split("\\n", -1));
        }
        List<String> result = new ArrayList<>(raw.size());
        for (String line : raw) {
            result.add(apply(line, placeholders));
        }
        return result;
    }

    public void send(CommandSender sender, String key, String... placeholders) {
        String raw = config.getString(key);
        if (raw == null) {
            plugin.getLogger().warning("messages.yml 缺少键: " + key);
            return;
        }
        String body = apply(raw, placeholders);
        for (String line : body.split("\\n", -1)) {
            sender.sendMessage(LEGACY.deserialize(prefix + line));
        }
    }

    public String apply(String raw, String... placeholders) {
        String result = raw;
        if (placeholders != null) {
            for (int i = 0; i + 1 < placeholders.length; i += 2) {
                result = result.replace("{" + placeholders[i] + "}", String.valueOf(placeholders[i + 1]));
            }
        }
        return result;
    }
}
