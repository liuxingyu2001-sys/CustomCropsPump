package com.liu.ccpump.data;

import org.bukkit.inventory.ItemStack;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * 机器仓储（{@code ItemStack[]}）与字节数组之间的转换。
 *
 * <p>用在两处：SQLite 的 {@code machines.buffer} 列，以及拆下机器时写进
 * 物品 PDC 的内容。用 Paper 自带的 {@code ItemStack#serializeAsBytes()}
 * 序列化单个物品，外面再套一层定长槽位表，保证槽位顺序不乱。
 */
public final class MachineCodec {

    private MachineCodec() {
    }

    /** 空槽位写成长度 -1。 */
    public static byte[] encode(ItemStack[] items, int slots) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(slots);
            for (int i = 0; i < slots; i++) {
                ItemStack stack = i < items.length ? items[i] : null;
                if (stack == null || stack.getType().isAir()) {
                    out.writeInt(-1);
                    continue;
                }
                byte[] raw = stack.serializeAsBytes();
                out.writeInt(raw.length);
                out.write(raw);
            }
        } catch (IOException error) {
            // 写入内存流不会真正抛 IOException，兜底返回空数据
            return new byte[0];
        }
        return bytes.toByteArray();
    }

    public static ItemStack[] decode(byte[] data, int slots) {
        ItemStack[] items = new ItemStack[slots];
        if (data == null || data.length == 0) {
            return items;
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            int stored = in.readInt();
            int limit = Math.min(stored, slots);
            for (int i = 0; i < stored; i++) {
                int length = in.readInt();
                if (length < 0) {
                    continue;
                }
                byte[] raw = new byte[length];
                in.readFully(raw);
                if (i < limit) {
                    items[i] = ItemStack.deserializeBytes(raw);
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // 数据损坏时当作空仓储，避免整个插件加载失败
            return new ItemStack[slots];
        }
        return items;
    }
}