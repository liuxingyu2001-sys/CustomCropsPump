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
 * 复制 craftengine/ccpump/ 下的全部文件，但不会删除用户自己添加的文件。
 *
 * <p>水泵/水管的方块状态借用原版仙人掌（auto_state: cactus），
 * 但外观用自带的铜管模型，所以包里既有配置也有模型 JSON。
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
        // 插件自带的资源包升级时强制同步一次：
        // 否则老服务器会留着旧模型/旧方块定义，新加的机器放下去是空的。
        boolean outdated = isOutdated(plugin, root);
        boolean overwrite = plugin.configManager().packOverwrite() || outdated;
        if (outdated) {
            plugin.getLogger().info("检测到插件版本变化，正在覆盖更新资源包 " + root + "。");
        }

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

        if (copied > 0 || overwrite) {
            int removed = pruneStale(root, resources);
            writeStamp(plugin, root);
            writeManifest(root, resources);
            plugin.getLogger().info("已释放 CraftEngine 资源包到 " + root
                    + "（写入 " + copied + " 个文件"
                    + (removed > 0 ? "，清理 " + removed + " 个旧文件" : "") + "）"
                    + "，请执行 /ce reload all 或重启服务器使其生效。");
        }
        if (skipped > 0 && !overwrite) {
            plugin.getLogger().info("检测到已有 ccpump 资源包，已保留现有 " + skipped + " 个文件（未覆盖）。"
                    + "更新插件后如需同步模型，请把 craftengine-pack.overwrite 设为 true，"
                    + "或删除 " + root + " 后重启。");
        }
    }

    /** 记录“上一次是从哪个插件版本释放出来的”的标记文件。 */
    private static final String STAMP_FILE = ".ccpump-pack-version";
    /** 记录“上次释放了哪些文件”，用来清理插件自己写过、但新版本已不再提供的文件。 */
    private static final String MANIFEST_FILE = ".ccpump-pack-files";

    /**
     * 服务器上已安装的资源包是不是旧的。
     *
     * <p>判定依据是**插件版本**，不是 {@code pack.yml} 里的 {@code version:}：
     * 后者要人工维护，漏改一次（改模型却忘升版本号）就会让老服务器一直留着旧资源，
     * 而插件版本每次发版必然变化，跟它走就不会漏。
     *
     * <p>仍然保留 pack.yml 的比对作为兜底：手工改了包里的 version 也能触发一次同步。
     * 都不比对文件内容，因此玩家改过的模型在日常重启时不会被覆盖。
     */
    private static boolean isOutdated(CustomCropsPumpPlugin plugin, File root) {
        String pluginVersion = plugin.getPluginMeta().getVersion();
        if (!root.isDirectory()) {
            // 没装过：交给下面的复制流程处理，不算“版本变化”
            return false;
        }
        String stamped = readStamp(root);
        if (!pluginVersion.equals(stamped)) {
            return true;
        }
        String bundled = readVersion(plugin.getResource(SOURCE_ROOT + "pack.yml"));
        if (bundled == null) {
            return false;
        }
        File installed = new File(root, "pack.yml");
        if (!installed.isFile()) {
            return true;
        }
        try (InputStream in = Files.newInputStream(installed.toPath())) {
            String current = readVersion(in);
            return current != null && !bundled.equals(current);
        } catch (IOException error) {
            return true;
        }
    }

    /**
     * 删掉“上次我们写过、这次包里已经没有”的文件。
     *
     * <p>只按清单删：玩家自己往包里加的文件不在清单里，绝不会被碰。
     * 这样改名/删除模型时不会留下孤儿文件——CE 会继续加载它们，
     * 表现为“明明删了物品，服务器里还能刷出来”。
     */
    private static int pruneStale(File root, List<String> current) {
        File manifest = new File(root, MANIFEST_FILE);
        if (!manifest.isFile()) {
            return 0;
        }
        List<String> previous;
        try {
            previous = Files.readAllLines(manifest.toPath(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException error) {
            return 0;
        }
        int removed = 0;
        for (String line : previous) {
            String path = line.trim();
            if (path.isEmpty() || current.contains(path)) {
                continue;
            }
            try {
                java.nio.file.Path target = new File(root, path).toPath().normalize();
                if (!target.startsWith(root.toPath().normalize()) || !Files.isRegularFile(target)) {
                    continue;
                }
                Files.delete(target);
                removed++;
                // 顺手删掉空目录，避免留下 configuration/ 这类空壳
                java.nio.file.Path parent = target.getParent();
                while (parent != null && !parent.equals(root.toPath().normalize())) {
                    try (java.util.stream.Stream<java.nio.file.Path> entries = Files.list(parent)) {
                        if (entries.findAny().isEmpty()) {
                            Files.delete(parent);
                            parent = parent.getParent();
                        } else {
                            break;
                        }
                    }
                }
            } catch (IOException ignored) {
                // 删不掉就算了，下次启动再试
            }
        }
        return removed;
    }

    private static void writeManifest(File root, List<String> resources) {
        try {
            Files.write(new File(root, MANIFEST_FILE).toPath(), resources,
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // 清单写失败只影响下次的陈旧文件清理，不影响本次释放
        }
    }

    private static String readStamp(File root) {
        File stamp = new File(root, STAMP_FILE);
        if (!stamp.isFile()) {
            return "";
        }
        try {
            return Files.readString(stamp.toPath(), java.nio.charset.StandardCharsets.UTF_8).trim();
        } catch (IOException error) {
            return "";
        }
    }

    private static void writeStamp(CustomCropsPumpPlugin plugin, File root) {
        File stamp = new File(root, STAMP_FILE);
        try {
            if (!root.isDirectory() && !root.mkdirs()) {
                return;
            }
            Files.writeString(stamp.toPath(), plugin.getPluginMeta().getVersion(),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException error) {
            plugin.getLogger().warning("写入资源包版本标记失败: " + error.getMessage());
        }
    }

    private static String readVersion(InputStream in) {
        if (in == null) {
            return null;
        }
        try (InputStream stream = in;
             java.io.BufferedReader reader = new java.io.BufferedReader(
                     new java.io.InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.startsWith("version:")) {
                    return trimmed.substring("version:".length()).replace("\"", "").replace("'", "").trim();
                }
            }
        } catch (IOException ignored) {
            return null;
        }
        return null;
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
                    "configuration/recipes.yml",
                    "resourcepack/assets/ccpump/models/block/water_pump.json",
                    "resourcepack/assets/ccpump/models/block/water_pipe.json",
                    "resourcepack/assets/ccpump/models/block/broken_pump.json",
                    "resourcepack/assets/ccpump/models/block/harvester.json",
                    "resourcepack/assets/ccpump/models/block/sprinkler_machine.json",
                    "resourcepack/assets/ccpump/models/block/fertilizer_machine.json",
                    "resourcepack/assets/ccpump/models/block/pipe/water_pipe_straight_ew.json",
                    "resourcepack/assets/ccpump/models/block/pipe/water_pipe_straight_ns.json",
                    "resourcepack/assets/ccpump/models/block/pipe/water_pipe_vertical.json",
                    "resourcepack/assets/ccpump/models/block/pipe/water_pipe_cross.json"
            ));
        }
        return result;
    }
}
