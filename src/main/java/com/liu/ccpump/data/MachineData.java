package com.liu.ccpump.data;

import org.bukkit.inventory.ItemStack;

/**
 * 一台机器的运行时状态。
 *
 * <p>坐标三元组是唯一键：机器的数据跟着**方块**走，不跟实体走。
 * 持久化由 {@code DataStore} 负责，这里只管内存中的读写。
 */
public final class MachineData {

    private final BlockKey key;
    /** 配置键（{@code config.yml} 里 machines 段下的名字），用来反查 {@code MachineConfig}。 */
    private final String typeKey;
    /** 仓储区：收割机放收获物，施肥机放肥料，洒水机没有。改格子数时会整个换掉。 */
    private ItemStack[] buffer;

    private int charge;
    private int water;
    private boolean enabled = true;
    private long nextTick;
    private long lastSourceCheck = -1L;
    private boolean sourceAvailable;
    /** 上一次被水管网络灌水的时间（毫秒）。有管网供水时就不再从本地水源抽水。 */
    private long lastNetworkFill;

    public MachineData(BlockKey key, String typeKey, ItemStack[] buffer,
                       int charge, int water, boolean enabled) {
        this.key = key;
        this.typeKey = typeKey;
        this.buffer = buffer == null ? new ItemStack[0] : buffer;
        this.charge = Math.max(0, charge);
        this.water = Math.max(0, water);
        this.enabled = enabled;
    }

    public BlockKey key() {
        return key;
    }

    public String typeKey() {
        return typeKey;
    }

    public int charge() {
        return charge;
    }

    public void charge(int value) {
        this.charge = Math.max(0, value);
    }

    public int water() {
        return water;
    }

    public void water(int value) {
        this.water = Math.max(0, value);
    }

    public boolean enabled() {
        return enabled;
    }

    public void enabled(boolean value) {
        this.enabled = value;
    }

    public long nextTick() {
        return nextTick;
    }

    public void nextTick(long value) {
        this.nextTick = value;
    }

    public long lastSourceCheck() {
        return lastSourceCheck;
    }

    public void lastSourceCheck(long value) {
        this.lastSourceCheck = value;
    }

    public boolean sourceAvailable() {
        return sourceAvailable;
    }

    public void sourceAvailable(boolean value) {
        this.sourceAvailable = value;
    }

    public long lastNetworkFill() {
        return lastNetworkFill;
    }

    public void lastNetworkFill(long millis) {
        this.lastNetworkFill = millis;
    }

    public ItemStack[] buffer() {
        return buffer;
    }

    public int bufferSlots() {
        return buffer.length;
    }

    /** 空位：{@code null}、空气、数量 0 都算空（数量 0 的物品既看不见也塞不进东西）。 */
    private static boolean isBlank(ItemStack stack) {
        return stack == null || stack.getType().isAir() || stack.getAmount() <= 0;
    }

    public int usedSlots() {
        int used = 0;
        for (ItemStack stack : buffer) {
            if (!isBlank(stack)) {
                used++;
            }
        }
        return used;
    }

    public int storedAmount() {
        int amount = 0;
        for (ItemStack stack : buffer) {
            if (!isBlank(stack)) {
                amount += stack.getAmount();
            }
        }
        return amount;
    }

    /**
     * 把物品塞进仓储区（先叠到同类物品上，再找空格）。
     *
     * @return 塞不下的剩余部分；全部塞下返回 null
     */
    public ItemStack add(ItemStack stack) {
        if (isBlank(stack) || buffer.length == 0) {
            return stack;
        }
        ItemStack remaining = stack.clone();
        int max = remaining.getMaxStackSize();
        for (int index = 0; index < buffer.length && remaining.getAmount() > 0; index++) {
            ItemStack stored = buffer[index];
            if (isBlank(stored) || !stored.isSimilar(remaining)) {
                continue;
            }
            int space = max - stored.getAmount();
            if (space <= 0) {
                continue;
            }
            int move = Math.min(space, remaining.getAmount());
            stored.setAmount(stored.getAmount() + move);
            remaining.setAmount(remaining.getAmount() - move);
        }
        for (int index = 0; index < buffer.length && remaining.getAmount() > 0; index++) {
            ItemStack stored = buffer[index];
            if (!isBlank(stored)) {
                continue;
            }
            int move = Math.min(max, remaining.getAmount());
            ItemStack copy = remaining.clone();
            copy.setAmount(move);
            buffer[index] = copy;
            remaining.setAmount(remaining.getAmount() - move);
        }
        return remaining.getAmount() <= 0 ? null : remaining;
    }

    /** 仓储区是否还有空间放下这个物品。 */
    public boolean canAccept(ItemStack stack) {
        if (isBlank(stack)) {
            return false;
        }
        if (buffer.length == 0) {
            return false;
        }
        int max = stack.getMaxStackSize();
        for (ItemStack stored : buffer) {
            if (isBlank(stored)) {
                return true;
            }
            if (stored.isSimilar(stack) && stored.getAmount() < max) {
                return true;
            }
        }
        return false;
    }

    /** 丢弃仓储区里的全部内容（拆机器时用）。 */
    public void clearBuffer() {
        java.util.Arrays.fill(buffer, null);
    }

    /**
     * 按新的格子数重建仓储区（配置重载后用）。
     *
     * @return 放不下的物品，调用方负责掉在原地
     */
    public java.util.List<ItemStack> resize(int slots) {
        int target = Math.max(0, slots);
        java.util.List<ItemStack> dropped = new java.util.ArrayList<>();
        java.util.List<ItemStack> tail = new java.util.ArrayList<>();
        for (int index = target; index < buffer.length; index++) {
            ItemStack stack = buffer[index];
            if (!isBlank(stack)) {
                tail.add(stack);
            }
        }
        ItemStack[] resized = java.util.Arrays.copyOf(buffer, target);
        for (ItemStack stack : tail) {
            ItemStack leftover = addTo(resized, stack);
            if (leftover != null && leftover.getAmount() > 0) {
                dropped.add(leftover);
            }
        }
        this.buffer = resized;
        return dropped;
    }

    private static ItemStack addTo(ItemStack[] target, ItemStack stack) {
        ItemStack remaining = stack.clone();
        for (int index = 0; index < target.length && remaining.getAmount() > 0; index++) {
            ItemStack stored = target[index];
            if (isBlank(stored) || !stored.isSimilar(remaining)) {
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
        for (int index = 0; index < target.length && remaining.getAmount() > 0; index++) {
            ItemStack stored = target[index];
            if (!isBlank(stored)) {
                continue;
            }
            int move = Math.min(remaining.getMaxStackSize(), remaining.getAmount());
            ItemStack copy = remaining.clone();
            copy.setAmount(move);
            target[index] = copy;
            remaining.setAmount(remaining.getAmount() - move);
        }
        return remaining.getAmount() <= 0 ? null : remaining;
    }
}