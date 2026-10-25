package com.liu.ccpump.config;

/**
 * 一根水管的配置。
 *
 * <p>水管会按相邻连接方向切换外观。为了不耗尽 CraftEngine 的视觉状态槽位，
 * 外观只区分 4 种固定形态（而不是 64 种连接组合）：
 *
 * <ul>
 *   <li>{@code <item>_straight_ew} 横向直管（东西向）</li>
 *   <li>{@code <item>_straight_ns} 横向直管（南北向）</li>
 *   <li>{@code <item>_vertical}    纵向直管（上下向）</li>
 *   <li>{@code <item>_cross}       六面管子（端点 / 拐角 / T 形 / 十字）</li>
 * </ul>
 *
 * <p>形态 ID 规则来自 {@code variant-format}，默认 {@code {item}_{shape}}，
 * 支持 {@code {item}} 和 {@code {shape}} 占位符。
 */
public record PipeConfig(String key, String item, boolean connections, String variantFormat) {

    /** 形态 ID 的默认生成规则：{item}_{shape}。 */
    public static final String DEFAULT_SHAPE_FORMAT = "{item}_{shape}";

    /** 形态常量：横向直管（东西向）。 */
    public static final String SHAPE_STRAIGHT_EW = "straight_ew";
    /** 形态常量：横向直管（南北向）。 */
    public static final String SHAPE_STRAIGHT_NS = "straight_ns";
    /** 形态常量：纵向直管（上下向）。 */
    public static final String SHAPE_VERTICAL = "vertical";
    /** 形态常量：六面管子（拐角 / T 形 / 十字 / 完全没接）。 */
    public static final String SHAPE_CROSS = "cross";

    /** 单臂端点：只往东伸出一根管。 */
    public static final String SHAPE_END_EAST = "end_east";
    /** 单臂端点：只往西伸出一根管。 */
    public static final String SHAPE_END_WEST = "end_west";
    /** 单臂端点：只往上伸出一根管。 */
    public static final String SHAPE_END_UP = "end_up";
    /** 单臂端点：只往下伸出一根管。 */
    public static final String SHAPE_END_DOWN = "end_down";
    /** 单臂端点：只往南伸出一根管。 */
    public static final String SHAPE_END_SOUTH = "end_south";
    /** 单臂端点：只往北伸出一根管。 */
    public static final String SHAPE_END_NORTH = "end_north";

    /** 所有形态，注册家具 ID 与生成资源包时按这个顺序使用。 */
    public static final String[] SHAPES = {
            SHAPE_STRAIGHT_EW, SHAPE_STRAIGHT_NS, SHAPE_VERTICAL,
            SHAPE_END_EAST, SHAPE_END_WEST, SHAPE_END_UP,
            SHAPE_END_DOWN, SHAPE_END_SOUTH, SHAPE_END_NORTH,
            SHAPE_CROSS
    };

    /** 连接掩码位：东、西、上、下、南、北（与 PumpManager.DIRECTIONS 一致）。 */
    private static final int EAST = 1 << 0;
    private static final int WEST = 1 << 1;
    private static final int UP = 1 << 2;
    private static final int DOWN = 1 << 3;
    private static final int SOUTH = 1 << 4;
    private static final int NORTH = 1 << 5;

    private static final int HORIZONTAL = EAST | WEST | SOUTH | NORTH;

    /**
     * 按连接掩码挑选外观形态。
     *
     * <p>分三档：只连一个方向 → 单臂端点（只伸出一根管，管道尽头的样子）；
     * 正好一对相对方向 → 直管（横-东西 / 横-南北 / 纵-上下）；
     * 其余（拐角、T 形、十字、完全没接）→ 六面管子。
     */
    public static String shapeOf(int mask) {
        switch (mask) {
            case EAST -> {
                return SHAPE_END_EAST;
            }
            case WEST -> {
                return SHAPE_END_WEST;
            }
            case UP -> {
                return SHAPE_END_UP;
            }
            case DOWN -> {
                return SHAPE_END_DOWN;
            }
            case SOUTH -> {
                return SHAPE_END_SOUTH;
            }
            case NORTH -> {
                return SHAPE_END_NORTH;
            }
            default -> {
            }
        }
        int horizontal = mask & HORIZONTAL;
        int vertical = mask & (UP | DOWN);
        if (vertical == 0) {
            if (horizontal == (EAST | WEST)) {
                return SHAPE_STRAIGHT_EW;
            }
            if (horizontal == (SOUTH | NORTH)) {
                return SHAPE_STRAIGHT_NS;
            }
        } else if (horizontal == 0 && vertical == (UP | DOWN)) {
            return SHAPE_VERTICAL;
        }
        return SHAPE_CROSS;
    }

    /**
     * 该连接掩码对应的形态物品 ID；未启用连接外观时返回 null。
     */
    public String variantItem(int mask) {
        return shapeItem(shapeOf(mask));
    }

    /**
     * 指定形态的物品 ID；未启用连接外观时返回 null。
     */
    public String shapeItem(String shape) {
        if (!connections) {
            return null;
        }
        String format = variantFormat == null || variantFormat.isBlank()
                ? DEFAULT_SHAPE_FORMAT
                : variantFormat;
        return format
                .replace("{item}", item)
                .replace("{shape}", shape);
    }
}
