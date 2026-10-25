package com.liu.ccpump.util;

import com.liu.ccpump.CustomCropsPumpPlugin;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * 把 jar 内自带的 CraftEngine 资源包复制到 plugins/CraftEngine/resources/ccpump/。
 * 复制 craftengine/ccpump/ 下的全部文件（水管变体模型较多，不再维护固定清单），
 * 但不会删除用户自己添加的文件。
 */
public final class PackInstaller {

    private static final String SOURCE_ROOT = "craftengine/ccpump/";

    private PackInstaller() {
    }

    public static void install(CustomCropsPumpPlugin plugin) {
        if (!plugin.configManager().packAutoInstall()) {
            return;
        }
        Plugin craftEngine = Bukkit.getPluginManager().getPlugin("CraftEngine");
        if (craftEngine == null) {
            plugin.getLogger().info("未检测到 CraftEngine，跳过资源包自动安装。");
            return;
        }

        File root = new File(craftEngine.getDataFolder(), "resources/ccpump");
        boolean overwrite = plugin.configManager().packOverwrite();

        List<String> resources = listResources(plugin);
        if (resources.isEmpty()) {
            plugin.getLogger().warning("插件内没有找到 " + SOURCE_ROOT + " 资源，跳过资源包安装。");
            return;
        }

        int copied = 0;
        int skipped = 0;
        for (String path : resources) {
            File target = new File(root, path);
            if (target.exists() && !overwrite) {
                skipped++;
                continue;
            }
            try (InputStream in = plugin.getResource(SOURCE_ROOT + path)) {
                if (in == null) {
                    plugin.getLogger().warning("插件内缺少资源: " + SOURCE_ROOT + path);
                    continue;
                }
                File parent = target.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    plugin.getLogger().warning("无法创建目录: " + parent);
                    continue;
                }
                Files.copy(in, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                copied++;
            } catch (IOException e) {
                plugin.getLogger().warning("安装 CraftEngine 资源包失败（" + path + "）: " + e.getMessage());
            }
        }

        if (copied > 0) {
            plugin.getLogger().info("已安装 CraftEngine 资源包到 " + root
                    + "（" + copied + " 个文件），请执行 /ce reload all 或重启服务器使其生效。");
        }
        if (skipped > 0 && !overwrite) {
            plugin.getLogger().info("检测到已有 ccpump 资源包，已保留现有 " + skipped + " 个文件（未覆盖）。"
                    + "更新插件后如需同步模型，请把 craftengine-pack.overwrite 设为 true，"
                    + "或删除 " + root + " 后重启。");
        }
    }

    /**
     * 枚举 jar 内的资源包文件；开发环境（classes 目录）下退化为固定清单。
     */
    private static List<String> listResources(CustomCropsPumpPlugin plugin) {
        List<String> result = new ArrayList<>();
        try {
            File self = new File(plugin.getClass().getProtectionDomain().getCodeSource()
                    .getLocation().toURI());
            if (self.isFile()) {
                try (JarFile jar = new JarFile(self)) {
                    Enumeration<JarEntry> entries = jar.entries();
                    while (entries.hasMoreElements()) {
                        JarEntry entry = entries.nextElement();
                        String name = entry.getName();
                        if (entry.isDirectory() || !name.startsWith(SOURCE_ROOT)) {
                            continue;
                        }
                        result.add(name.substring(SOURCE_ROOT.length()));
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("读取插件 jar 内容失败: " + e.getMessage());
        }
        if (result.isEmpty()) {
            result.addAll(List.of(
                    "pack.yml",
                    "configuration/items.yml",
                    "configuration/categories.yml",
                    "configuration/pipe_variants.yml",
                    "resourcepack/assets/ccpump/models/block/water_pump.json",
                    "resourcepack/assets/ccpump/models/block/water_pipe.json"
            ));
        }
        return result;
    }
}
