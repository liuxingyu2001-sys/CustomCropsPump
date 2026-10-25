package com.liu.ccpump.config;

/**
 * 一根水管的配置。
 *
 * <p>水管是 CraftEngine 自定义方块（{@code block_item}），方块状态借用原版
 * 仙人掌的 16 个 age 槽位（{@code auto_state: cactus}），但外观是插件自己的
 * 铜管模型。外观区分 4 种形态：
 *
 * <ul>
 *   <li>{@code <item>_straight_ew} 横向直管（东西向）</li>
 *   <li>{@code <item>_straight_ns} 横向直管（南北向）</li>
 *   <li>{@code <item>_vertical}    纵向直管（上下向）</li>
 *   <li>{@code <item>_cross}      水平十字（拐角 / T 形 / 十字 / 没接）</li>
 * </ul>
 *
 * <p>连通规则（见 {@code PumpManager.connectedMask}）：
 * <ul>
 *   <li>水平方向（东西南北）：相邻的水泵、水管、洒水器都算连接；</li>
 *   <li>垂直方向（上下）：只有相邻水管算连接，水泵与洒水器都不接，
 *       所以竖管自成一路，不会和平面管网串起来。</li>
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
    /** 形态常量：水平十字（拐角 / T 形 / 十字 / 没接）。 */
    public static final String SHAPE_CROSS = "cross";

    /** 所有形态，注册方块 ID 与生成资源包时按这个顺序使用。 */
    public static final String[] SHAPES = {
            SHAPE_STRAIGHT_EW, SHAPE_STRAIGHT_NS, SHAPE_VERTICAL, SHAPE_CROSS
    };

    /** 连接掩码位：东、西、上、下、南、北（与 PumpManager.DIRECTIONS 一致）。 */
    private static final int EAST = 1 << 0;
    private static final int WEST = 1 << 1;
    private static final int UP = 1 << 2;
    private static final int DOWN = 1 << 3;
    private static final int SOUTH = 1 << 4;
    private static final int NORTH = 1 << 5;

    private static final int HORIZONTAL = EAST | WEST | SOUTH | NORTH;
    private static final int VERTICAL = UP | DOWN;

    /**
     * 按连接掩码挑选外观形态。
     *
     * <p>水平与垂直是两套互不相通的管网：
     * <ul>
     *   <li>只有垂直连接（上下）→ {@code vertical} 直管；</li>
     *   <li>水平方向连着（含只连一个方向）→ 直管或十字管；</li>
     *   <li>水平面内的拐角 / T 形 / 十字 / 完全没接 → {@code cross}。</li>
     * </ul>
     */
    public static String shapeOf(int mask) {
        int vertical = mask & VERTICAL;
        int horizontal = mask & HORIZONTAL;
        if (vertical != 0 && horizontal == 0) {
            return SHAPE_VERTICAL;
        }
        if (vertical == 0) {
            // 只有东西方向通（含只连一边）→ 东西直管；只有南北方向通 → 南北直管。
            // 只连一个方向时另一端收进中心块，外观差别只在最末端那一格，
            // 没必要单独做六种端点模型。
            if ((horizontal & (SOUTH | NORTH)) == 0 && horizontal != 0) {
                return SHAPE_STRAIGHT_EW;
            }
            if ((horizontal & (EAST | WEST)) == 0 && horizontal != 0) {
                return SHAPE_STRAIGHT_NS;
            }
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
