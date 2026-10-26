package com.liu.ccpump.command;

import com.liu.ccpump.CustomCropsPumpPlugin;
import com.liu.ccpump.config.MachineConfig;
import com.liu.ccpump.config.PipeConfig;
import com.liu.ccpump.config.PumpConfig;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class PumpCommand implements CommandExecutor, TabCompleter {

    private final CustomCropsPumpPlugin plugin;

    public PumpCommand(CustomCropsPumpPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sendUsage(sender);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload" -> {
                if (!requireAdmin(sender)) {
                    return true;
                }
                plugin.reloadPlugin();
                plugin.configManager().messages().send(sender, "reload-success");
                return true;
            }
            case "save" -> {
                if (!requireAdmin(sender)) {
                    return true;
                }
                plugin.dataStore().save(plugin.pumpManager(), plugin.machineManager());
                plugin.configManager().messages().send(sender, "save-success");
                return true;
            }
            case "info" -> {
                if (!requireAdmin(sender)) {
                    return true;
                }
                plugin.configManager().messages().send(sender, "info",
                        "pumps", String.valueOf(plugin.pumpManager().pumpCount()),
                        "pipes", String.valueOf(plugin.pumpManager().pipeCount()),
                        "machines", String.valueOf(plugin.machineManager().machineCount()),
                        "worlds", String.valueOf(plugin.pumpManager().worldCount()));
                return true;
            }
            case "give" -> {
                if (!requireAdmin(sender)) {
                    return true;
                }
                return give(sender, args);
            }
            default -> {
                sendUsage(sender);
                return true;
            }
        }
    }

    private boolean give(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sendUsage(sender);
            return true;
        }

        String type = args[1];
        String itemId = null;
        PumpConfig pumpConfig = plugin.configManager().pump(type);
        PipeConfig pipeConfig = plugin.configManager().pipe(type);
        MachineConfig machineConfig = plugin.configManager().machine(type);

        if (pumpConfig != null) {
            itemId = pumpConfig.item();
        } else if (pipeConfig != null) {
            itemId = pipeConfig.item();
        } else if (machineConfig != null) {
            itemId = machineConfig.item();
        } else if (type.equalsIgnoreCase("pump") && plugin.configManager().pumps().size() == 1) {
            itemId = plugin.configManager().pumps().iterator().next().item();
        } else if (type.equalsIgnoreCase("pipe") && plugin.configManager().pipes().size() == 1) {
            itemId = plugin.configManager().pipes().iterator().next().item();
        }

        if (itemId == null) {
            Set<String> types = new LinkedHashSet<>();
            for (PumpConfig config : plugin.configManager().pumps()) {
                types.add(config.key());
            }
            for (PipeConfig config : plugin.configManager().pipes()) {
                types.add(config.key());
            }
            for (MachineConfig config : plugin.configManager().machines()) {
                types.add(config.key());
            }
            plugin.configManager().messages().send(sender, "unknown-type",
                    "type", type,
                    "types", String.join(", ", types));
            return true;
        }

        Player target;
        if (args.length >= 3) {
            target = Bukkit.getPlayerExact(args[2]);
            if (target == null) {
                plugin.configManager().messages().send(sender, "player-not-found", "player", args[2]);
                return true;
            }
        } else if (sender instanceof Player player) {
            target = player;
        } else {
            plugin.configManager().messages().send(sender, "player-only");
            return true;
        }

        int amount = 1;
        if (args.length >= 4) {
            try {
                amount = Math.max(1, Math.min(2304, Integer.parseInt(args[3])));
            } catch (NumberFormatException exception) {
                amount = 1;
            }
        }

        ItemStack item;
        try {
            item = plugin.customCrops().getItemManager().build(target, itemId);
        } catch (Throwable throwable) {
            plugin.getLogger().warning("生成物品 " + itemId + " 失败: " + throwable.getMessage());
            item = null;
        }
        if (item == null || item.getType().isAir()) {
            plugin.configManager().messages().send(sender, "item-not-found", "item", itemId);
            return true;
        }

        int remaining = amount;
        int maxStack = Math.max(1, item.getMaxStackSize());
        while (remaining > 0) {
            int stackSize = Math.min(remaining, maxStack);
            ItemStack stack = item.clone();
            stack.setAmount(stackSize);
            Map<Integer, ItemStack> leftover = target.getInventory().addItem(stack);
            for (ItemStack drop : leftover.values()) {
                target.getWorld().dropItemNaturally(target.getLocation(), drop);
            }
            remaining -= stackSize;
        }

        if (sender == target) {
            plugin.configManager().messages().send(sender, "give-self",
                    "item", itemId, "amount", String.valueOf(amount));
        } else {
            plugin.configManager().messages().send(sender, "give-success",
                    "player", target.getName(), "item", itemId, "amount", String.valueOf(amount));
        }
        return true;
    }

    private boolean requireAdmin(CommandSender sender) {
        if (!sender.hasPermission("customcropspump.admin")) {
            plugin.configManager().messages().send(sender, "no-permission");
            return false;
        }
        return true;
    }

    private void sendUsage(CommandSender sender) {
        plugin.configManager().messages().send(sender, "usage");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(List.of("help", "reload", "save", "info", "give"), args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            List<String> types = new ArrayList<>();
            for (PumpConfig config : plugin.configManager().pumps()) {
                types.add(config.key());
            }
            for (PipeConfig config : plugin.configManager().pipes()) {
                types.add(config.key());
            }
            for (MachineConfig config : plugin.configManager().machines()) {
                types.add(config.key());
            }
            types.add("pump");
            types.add("pipe");
            return filter(types, args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            List<String> names = new ArrayList<>();
            for (Player player : Bukkit.getOnlinePlayers()) {
                names.add(player.getName());
            }
            return filter(names, args[2]);
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("give")) {
            return filter(List.of("1", "8", "16", "32", "64"), args[3]);
        }
        return List.of();
    }

    private List<String> filter(List<String> values, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String value : values) {
            if (value.toLowerCase(Locale.ROOT).startsWith(lower)) {
                result.add(value);
            }
        }
        return result;
    }
}
