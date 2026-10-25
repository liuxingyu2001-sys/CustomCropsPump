#!/usr/bin/env python3
"""
生成水泵 / 水管的 CraftEngine 方块定义，以及水管 3 种固定形态模型。

用法（在 CustomCropsPump/ 目录下）：
    python3 tools/generate_pipe_variants.py

产物：
    configuration/items.yml              水泵 + 基础水管 + 3 种水管形态（block_item）
    configuration/pipe_variants.yml      3 种水管形态方块
    resourcepack/assets/ccpump/models/block/pipe/water_pipe_straight_ew.json
    resourcepack/assets/ccpump/models/block/pipe/water_pipe_straight_ns.json
    resourcepack/assets/ccpump/models/block/pipe/water_pipe_vertical.json
    resourcepack/assets/ccpump/models/block/pipe/water_pipe_cross.json
    resourcepack/assets/ccpump/models/block/water_pipe.json   （物品图标，六向十字）

水泵/水管是**真实方块**（CraftEngine custom block），不是实体家具：
    · 有真实碰撞箱
    · 能挡住水（水不会流进方块空间）
    · 可以贴墙、贴天花板放置（方块放置没有“必须有地面”的限制）

水管外观只区分 3 种形态，不再按 6 方向连接组合生成 64 种变体：
    straight_ew   横向直管（东西向）
    straight_ns   横向直管（南北向）
    vertical      纵向直管（上下向）
    cross         六面管子（端点 / 拐角 / T 形 / 十字 全用它）

这样只占 4 个视觉状态（水泵 + 3 种水管形态），远低于 CraftEngine
各视觉状态组的槽位上限（pressure_plate 组只有 28 个槽位）。

掩码位（与 PumpManager.DIRECTIONS 保持一致）：
    bit0 = EAST (+x)   bit1 = WEST (-x)
    bit2 = UP   (+y)   bit3 = DOWN (-y)
    bit4 = SOUTH(+z)   bit5 = NORTH(-z)
"""

import json
import re
import os

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
MODEL_DIR = os.path.join(
    ROOT, "src/main/resources/craftengine/ccpump/resourcepack/assets/ccpump/models/block"
)
PACK_DIR = os.path.join(ROOT, "src/main/resources/craftengine/ccpump/configuration")

TEXTURES = {
    "particle": "minecraft:block/copper_block",
    "body": "minecraft:block/copper_block",
    "dark": "minecraft:block/exposed_copper",
    "accent": "minecraft:block/weathered_copper",
}

# 6 个面统一贴图，避免出现未定义的面
FACES = ["down", "up", "north", "south", "east", "west"]
DISPLAY = {
    "gui": {"rotation": [30, 225, 0], "translation": [0, 0, 0], "scale": [0.625, 0.625, 0.625]},
    "ground": {"rotation": [0, 0, 0], "translation": [0, 3, 0], "scale": [0.25, 0.25, 0.25]},
    "fixed": {"rotation": [0, 180, 0], "translation": [0, 0, 0], "scale": [0.5, 0.5, 0.5]},
    "thirdperson_righthand": {"rotation": [75, 45, 0], "translation": [0, 2.5, 0], "scale": [0.375, 0.375, 0.375]},
    "thirdperson_lefthand": {"rotation": [75, 45, 0], "translation": [0, 2.5, 0], "scale": [0.375, 0.375, 0.375]},
    "firstperson_righthand": {"rotation": [0, 45, 0], "translation": [0, 0, 0], "scale": [0.4, 0.4, 0.4]},
    "firstperson_lefthand": {"rotation": [0, 45, 0], "translation": [0, 0, 0], "scale": [0.4, 0.4, 0.4]},
}

CORE = ([6, 6, 6], [10, 10, 10], "dark")

# bit -> (arm from, arm to, collar from, collar to)
ARMS = {
    0: ([10, 6, 6], [15, 10, 10], [14, 5, 5], [16, 11, 11]),   # EAST  +x
    1: ([1, 6, 6], [6, 10, 10], [0, 5, 5], [2, 11, 11]),       # WEST  -x
    2: ([6, 10, 6], [10, 15, 10], [5, 14, 5], [11, 16, 11]),   # UP    +y
    3: ([6, 1, 6], [10, 6, 10], [5, 0, 5], [11, 2, 11]),       # DOWN  -y
    4: ([6, 6, 10], [10, 10, 15], [5, 5, 14], [11, 11, 16]),   # SOUTH +z
    5: ([6, 6, 1], [10, 10, 6], [5, 5, 0], [11, 11, 2]),       # NORTH -z
}

# 水管外观形态。
# 直管覆盖“正好一对相对方向”，单臂覆盖“只连了一个东西”（管道尽头），
# 其余（拐角 / T 形 / 十字 / 完全没接）统一用六面管子。
SHAPES = {
    # 横向直管：东西向（bit0 + bit1）
    "straight_ew": (1 << 0) | (1 << 1),
    # 横向直管：南北向（bit4 + bit5）
    "straight_ns": (1 << 4) | (1 << 5),
    # 纵向直管：上下向（bit2 + bit3）
    "vertical": (1 << 2) | (1 << 3),
    # 单臂：只往一个方向伸出（端点）
    "end_east": 1 << 0,
    "end_west": 1 << 1,
    "end_up": 1 << 2,
    "end_down": 1 << 3,
    "end_south": 1 << 4,
    "end_north": 1 << 5,
    # 六面管子：拐角 / T 形 / 十字 / 完全没接
    "cross": 63,
}

# ---------------------------------------------------------------
# CraftEngine **实体家具**（furniture）定义模板。
#
# 为什么用实体而不是方块：
#   · 方块外观必须独占一个原版方块状态，CraftEngine 各状态组槽位有限
#     （pressure_plate 组只有 28 个，还会被别的插件/旧缓存占用），
#     数量一多就分配失败、方块放不下去。
#   · 实体家具不占方块状态，想加多少形态都行。
#   · 代价：实体不是方块，不挡水、贴面也不参与剔除。
# 碰撞用 shulker hitbox 提供，玩家走不过去、也能站上去。
# ---------------------------------------------------------------
PUMP_SETTINGS = """  ccpump:water_pump:
    material: paper
    data:
      item-name: <!i><gold>水泵</gold>
    model: ccpump:block/water_pump
    behavior:
      type: furniture_item
      rules:
        ground:
          rotation: four
          alignment: center
        wall:
          rotation: four
          alignment: center
        ceiling:
          rotation: four
          alignment: center
      ignore-placer: false
      ignore-entities: false
      furniture:
        settings:
          item: ccpump:water_pump
          sounds:
            break: minecraft:block.copper.break
            place: minecraft:block.copper.place
        variants:
          ground:
            loot-spawn-offset: 0,0.5,0
            elements:
              - item: ccpump:water_pump
                display-transform: NONE
                billboard: FIXED
                translation: 0,0.5,0
            hitboxes:
              # 水泵用 shulker 碰撞箱提供真实碰撞，同时它自己就是右键交互
              # 的判定箱（不额外加 interaction，避免两个判定箱重叠点不到）。
              # can-use-item-on: false 表示手持方块右键时优先走水泵交互
              # （打开界面 / 充能），而不是把方块放下去。
              - type: shulker
                can-use-item-on: false
                blocks-building: true
                can-be-hit-by-projectile: false
                position: 0,0,0
                scale: 0.9
                peek: 0
                interactive: true
                interaction-entity: true
                invisible: true
          # 贴墙放置：模型往墙面方向收一点，碰撞箱略小
          wall:
            loot-spawn-offset: 0,0.5,0
            elements:
              - item: ccpump:water_pump
                display-transform: NONE
                billboard: FIXED
                translation: 0,0.5,0
            hitboxes:
              - type: shulker
                can-use-item-on: false
                blocks-building: true
                can-be-hit-by-projectile: false
                position: 0,0,0
                scale: 0.7
                peek: 0
                interactive: true
                interaction-entity: true
                invisible: true
          # 吊在天花板下
          ceiling:
            loot-spawn-offset: 0,0.5,0
            elements:
              - item: ccpump:water_pump
                display-transform: NONE
                billboard: FIXED
                translation: 0,0.5,0
            hitboxes:
              - type: shulker
                can-use-item-on: false
                blocks-building: true
                can-be-hit-by-projectile: false
                position: 0,0,0
                scale: 0.7
                peek: 0
                interactive: true
                interaction-entity: true
                invisible: true
        loot:
          template: default:loot_table/basic
          arguments:
            item: ccpump:water_pump
"""

# 水管形态模板：{shape} / {label}
PIPE_TEMPLATE = """  ccpump:water_pipe_{shape}:
    material: paper
    data:
      item-name: <!i><gold>水管</gold><gray> · {label}</gray>
    model: ccpump:block/pipe/water_pipe_{shape}
    behavior:
      type: furniture_item
      rules:
        ground:
          rotation: four
          alignment: center
        wall:
          rotation: four
          alignment: center
        ceiling:
          rotation: four
          alignment: center
      ignore-placer: false
      ignore-entities: false
      furniture:
        settings:
          item: ccpump:water_pipe_{shape}
          sounds:
            break: minecraft:block.copper.break
            place: minecraft:block.copper.place
        variants:
          ground:
            loot-spawn-offset: 0,0.5,0
            elements:
              - item: ccpump:water_pipe_{shape}
                display-transform: NONE
                billboard: FIXED
                translation: 0,0.5,0
            hitboxes:
              # 水管很细：0.6 宽的碰撞箱既有实体碰撞，右键也点得到。
              # can-use-item-on: true 允许对着水管继续放方块/管道。
              - type: shulker
                can-use-item-on: true
                blocks-building: true
                can-be-hit-by-projectile: false
                position: 0,0.1,0
                scale: 0.6
                peek: 0
                interactive: true
                interaction-entity: true
                invisible: true
          # 贴墙放置
          wall:
            loot-spawn-offset: 0,0.5,0
            elements:
              - item: ccpump:water_pipe_{shape}
                display-transform: NONE
                billboard: FIXED
                translation: 0,0.5,0
            hitboxes:
              - type: shulker
                can-use-item-on: true
                blocks-building: true
                can-be-hit-by-projectile: false
                position: 0,0,0
                scale: 0.5
                peek: 0
                interactive: true
                interaction-entity: true
                invisible: true
          # 吊在天花板下
          ceiling:
            loot-spawn-offset: 0,0.5,0
            elements:
              - item: ccpump:water_pipe_{shape}
                display-transform: NONE
                billboard: FIXED
                translation: 0,0.5,0
            hitboxes:
              - type: shulker
                can-use-item-on: true
                blocks-building: true
                can-be-hit-by-projectile: false
                position: 0,0,0
                scale: 0.5
                peek: 0
                interactive: true
                interaction-entity: true
                invisible: true
"""

# 基础水管（玩家拿到的物品，外观为六面十字）
BASE_PIPE = """  ccpump:water_pipe:
    material: paper
    data:
      item-name: <!i><gold>水管</gold>
    model: ccpump:block/water_pipe
    behavior:
      type: furniture_item
      rules:
        ground:
          rotation: four
          alignment: center
        wall:
          rotation: four
          alignment: center
        ceiling:
          rotation: four
          alignment: center
      ignore-placer: false
      ignore-entities: false
      furniture:
        settings:
          item: ccpump:water_pipe
          sounds:
            break: minecraft:block.copper.break
            place: minecraft:block.copper.place
        variants:
          ground:
            loot-spawn-offset: 0,0.5,0
            elements:
              - item: ccpump:water_pipe
                display-transform: NONE
                billboard: FIXED
                translation: 0,0.5,0
            hitboxes:
              - type: shulker
                can-use-item-on: true
                blocks-building: true
                can-be-hit-by-projectile: false
                position: 0,0.1,0
                scale: 0.6
                peek: 0
                interactive: true
                interaction-entity: true
                invisible: true
          wall:
            loot-spawn-offset: 0,0.5,0
            elements:
              - item: ccpump:water_pipe
                display-transform: NONE
                billboard: FIXED
                translation: 0,0.5,0
            hitboxes:
              - type: shulker
                can-use-item-on: true
                blocks-building: true
                can-be-hit-by-projectile: false
                position: 0,0,0
                scale: 0.5
                peek: 0
                interactive: true
                interaction-entity: true
                invisible: true
          ceiling:
            loot-spawn-offset: 0,0.5,0
            elements:
              - item: ccpump:water_pipe
                display-transform: NONE
                billboard: FIXED
                translation: 0,0.5,0
            hitboxes:
              - type: shulker
                can-use-item-on: true
                blocks-building: true
                can-be-hit-by-projectile: false
                position: 0,0,0
                scale: 0.5
                peek: 0
                interactive: true
                interaction-entity: true
                invisible: true
"""


def cube(frm, to, texture):
    return {
        "from": list(frm),
        "to": list(to),
        "faces": {face: {"uv": [0, 0, 16, 16], "texture": "#" + texture} for face in FACES},
    }


def build_model(mask, label):
    elements = [cube(*CORE)]
    for bit in range(6):
        if mask & (1 << bit):
            arm_from, arm_to, collar_from, collar_to = ARMS[bit]
            elements.append(cube(arm_from, arm_to, "body"))
            elements.append(cube(collar_from, collar_to, "accent"))
    return {
        "credit": "CustomCropsPump - generated copper water pipe (%s)" % label,
        "textures": dict(TEXTURES),
        "elements": elements,
        "display": DISPLAY,
    }


SHAPE_LABELS = {
    "straight_ew": "横向直管（东西）",
    "straight_ns": "横向直管（南北）",
    "vertical": "纵向直管（上下）",
    "end_east": "端点（东）",
    "end_west": "端点（西）",
    "end_up": "端点（上）",
    "end_down": "端点（下）",
    "end_south": "端点（南）",
    "end_north": "端点（北）",
    "cross": "六面管子",
}

ITEM_TEMPLATE = """  ccpump:water_pipe_{shape}:
    data:
      item-name: <!i><gold>水管</gold><gray> · {label}</gray>
    model: ccpump:block/pipe/water_pipe_{shape}
    behavior:
      type: block_item
      block:
        loot:
          template: default:loot_table/basic
          arguments:
            item: ccpump:water_pipe
{settings}        state:
          auto_state: {auto_state}
          model:
            path: ccpump:block/pipe/water_pipe_{shape}
"""

HEADER = """# ============================================================
#  由 tools/generate_pipe_variants.py 自动生成，请勿手工编辑。
#
#  水管只区分 4 种外观形态（不是 64 种连接组合）：
#    ccpump:water_pipe_straight_ew   横向直管（东西向）
#    ccpump:water_pipe_straight_ns   横向直管（南北向）
#    ccpump:water_pipe_vertical      纵向直管（上下向）
#    ccpump:water_pipe_cross         六面管子（端点 / 拐角 / T 形 / 十字）
#
#  ccpump:water_pipe 是玩家拿到的物品；放置后插件会按相邻连接自动把它
#  换成上面某个形态，玩家也可以直接手动放置某个形态。
#  所有水管被拆掉时统一掉落回 ccpump:water_pipe。
# ============================================================
items:
"""

ITEMS_HEADER = """# ============================================================
#  由 tools/generate_pipe_variants.py 自动生成，请勿手工编辑。
#
#  水泵 / 水管是 CraftEngine **实体家具**（furniture），不是方块：
#    · 不占用原版方块状态，不受各状态组槽位数量限制
#    · 用 shulker 碰撞箱提供碰撞，走不过去、也能站上去
#    · 实体不是方块，所以不挡水、也参与方块面剔除
#  水管的 4 种外观形态见 pipe_variants.yml。
# ============================================================
items:
"""


def main():
    pipe_model_dir = os.path.join(MODEL_DIR, "pipe")
    os.makedirs(pipe_model_dir, exist_ok=True)

    # 清掉旧的 64 个变体模型，避免残留文件被当成历史包袱
    for name in os.listdir(pipe_model_dir):
        if name.startswith("water_pipe_") and name.endswith(".json"):
            os.remove(os.path.join(pipe_model_dir, name))

    for shape, mask in SHAPES.items():
        path = os.path.join(pipe_model_dir, "water_pipe_%s.json" % shape)
        with open(path, "w", encoding="utf-8") as handle:
            json.dump(build_model(mask, shape), handle, indent=2, ensure_ascii=False)
            handle.write("\n")

    # 物品图标沿用完整六向十字模型
    with open(os.path.join(MODEL_DIR, "water_pipe.json"), "w", encoding="utf-8") as handle:
        json.dump(build_model(63, "icon"), handle, indent=2, ensure_ascii=False)
        handle.write("\n")

    with open(os.path.join(PACK_DIR, "items.yml"), "w", encoding="utf-8") as handle:
        handle.write(ITEMS_HEADER)
        handle.write(PUMP_SETTINGS)
        handle.write("\n")
        handle.write(BASE_PIPE)

    with open(os.path.join(PACK_DIR, "pipe_variants.yml"), "w", encoding="utf-8") as handle:
        handle.write(HEADER)
        for shape in SHAPES:
            handle.write(PIPE_TEMPLATE.format(shape=shape, label=SHAPE_LABELS[shape]))

    write_categories()
    check_categories()

    print("生成完成：%d 个水管形态模型 + items.yml + pipe_variants.yml" % len(SHAPES))


def write_categories():
    """生成 categories.yml。

    水泵、基础水管和 4 种形态都要列进去：形态方块可以由玩家直接拿在手里
    手动放置（插件也有自动切换，两种方式共存）。
    """
    lines = [
        "categories:",
        "  ccpump:main:",
        '    name: "<!i><gold>CustomCropsPump</gold>"',
        "    lore: []",
        "    hidden: true",
        "    priority: 4",
        "    icon: ccpump:water_pump",
        "    list:",
        "      - ccpump:water_pump",
        "      - ccpump:water_pipe",
    ]
    for shape in SHAPES:
        lines.append("      - ccpump:water_pipe_%s" % shape)
    with open(os.path.join(PACK_DIR, "categories.yml"), "w", encoding="utf-8") as handle:
        handle.write("\n".join(lines) + "\n")


def check_categories():
    """校验 categories.yml 引用的物品都存在。

    categories.yml 是手写的（只列玩家能拿到的物品，形态方块故意不列），
    但 items.yml / pipe_variants.yml 是生成的；改名字或加减方块后，
    分类里的引用可能指向不存在的物品，这里提前报出来。
    """
    path = os.path.join(PACK_DIR, "categories.yml")
    if not os.path.exists(path):
        return
    with open(path, encoding="utf-8") as handle:
        content = handle.read()
    with open(os.path.join(PACK_DIR, "items.yml"), encoding="utf-8") as handle:
        defined = set(re.findall(r"^  (ccpump:[\w-]+):", handle.read(), re.MULTILINE))
    with open(os.path.join(PACK_DIR, "pipe_variants.yml"), encoding="utf-8") as handle:
        defined |= set(re.findall(r"^  (ccpump:[\w-]+):", handle.read(), re.MULTILINE))
    referenced = set(re.findall(r"^\s+- (ccpump:[\w-]+)$", content, re.MULTILINE))
    missing = sorted(referenced - defined)
    if missing:
        raise SystemExit("categories.yml 引用了未定义的物品: %s" % ", ".join(missing))


if __name__ == "__main__":
    main()
