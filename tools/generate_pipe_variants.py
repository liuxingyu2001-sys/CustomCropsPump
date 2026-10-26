#!/usr/bin/env python3
"""
生成水泵 / 水管的 CraftEngine 自定义方块定义与水管形态模型。

用法（在 CustomCropsPump/ 目录下）：
    python3 tools/generate_pipe_variants.py

产物：
    configuration/items.yml        水泵 + 水管的物品与方块定义
    configuration/categories.yml   创造模式物品分类
    resourcepack/assets/ccpump/models/block/pipe/*.json      水管形态模型
    resourcepack/assets/ccpump/models/block/water_pipe.json  物品图标
    resourcepack/assets/ccpump/models/block/water_pump.json  水泵模型

关键点：**方块状态借用原版仙人掌，外观是自己的铜管模型。**

    auto_state: cactus      ← 只决定方块状态从哪个原版方块借
    model:
      path: ccpump:block/pipe/water_pipe_cross   ← 外观完全自定义

这两件事互不冲突：仙人掌有 16 个 age 槽位，水泵 + 4 种水管形态只用 5 个，
绰绰有余（早期担心的是 pressure_plate 组只有 28 个槽位、会被历史缓存占满，
仙人掌没有这个问题）。

水管外观区分 4 种形态：
    straight_ew   横向直管（东西向）
    straight_ns   横向直管（南北向）
    vertical      纵向直管（上下向）
    cross         水平十字（拐角 / T 形 / 十字 / 完全没接）

东西和南北必须分成两个形态：方块模式下没有“旋转”，placeBlock 只认方块 ID。

连通规则（PumpManager.connectedMask）：
    · 水平方向（东西南北）：水管、水泵、洒水器互相连通
    · 垂直方向（上下）：只有水管互相连通，自成一路

掩码位（与 PumpManager.DIRECTIONS 一致）：
    bit0 = EAST (+x)   bit1 = WEST (-x)
    bit2 = UP   (+y)   bit3 = DOWN (-y)
    bit4 = SOUTH(+z)   bit5 = NORTH(-z)
"""

import json
import os
import re

import yaml


def yaml_load(handle):
    """读 YAML（统一走 PyYAML，避免和 Bukkit 的解析差异）。"""
    return yaml.safe_load(handle) or {}


ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
PACK_DIR = os.path.join(ROOT, "src/main/resources/craftengine/ccpump/configuration")
MODEL_ROOT = os.path.join(
    ROOT, "src/main/resources/craftengine/ccpump/resourcepack/assets/ccpump/models")
MODEL_DIR = os.path.join(MODEL_ROOT, "block")
PIPE_MODEL_DIR = os.path.join(MODEL_DIR, "pipe")

# 整根管子统一用 copper_block：早先中心块 exposed_copper（深锈红）、管身
# copper_block、法兰 weathered_copper（青绿），三种颜色拼在一起看起来像
# 断成了好几截。现在靠法兰的几何粗细体现接口，不再靠颜色区分。
TEXTURES_PIPE = {
    "particle": "minecraft:block/copper_block",
    "body": "minecraft:block/copper_block",
}
TEXTURES_PUMP = {
    "particle": "minecraft:block/copper_block",
    "body": "minecraft:block/copper_block",
    "dark": "minecraft:block/exposed_copper",
    "accent": "minecraft:block/weathered_copper",
}

# 洒水机：铜壳 + 深蓝水位窗
TEXTURES_SPRINKLER = {
    "particle": "minecraft:block/copper_block",
    "body": "minecraft:block/copper_block",
    "dark": "minecraft:block/exposed_copper",
    "accent": "minecraft:block/weathered_copper",
    "water": "minecraft:block/lapis_block",
}

# 施肥机：铜壳 + 骨粉料斗
TEXTURES_FERTILIZER = {
    "particle": "minecraft:block/copper_block",
    "body": "minecraft:block/copper_block",
    "dark": "minecraft:block/exposed_copper",
    "accent": "minecraft:block/weathered_copper",
    "bone": "minecraft:block/bone_block_side",
}

# 破损水泵：整机换成氧化最严重的两级铜，一眼能看出“不能再用了”
TEXTURES_BROKEN = {
    "particle": "minecraft:block/oxidized_copper",
    "body": "minecraft:block/weathered_copper",
    "dark": "minecraft:block/oxidized_copper",
    "accent": "minecraft:block/oxidized_cut_copper",
}

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

CORE = ([6, 6, 6], [10, 10, 10], "body")

# bit -> (arm from, arm to, collar from, collar to)
#
# 管臂一直延伸到方块边界（16 / 0），相邻两格在交界处严丝合缝，不会出现
# “只伸出半截”的观感。法兰（collar）是管口那圈略粗的凸起。
ARMS = {
    0: ([10, 6, 6], [16, 10, 10], [13, 5, 5], [16, 11, 11]),   # EAST  +x
    1: ([0, 6, 6], [6, 10, 10], [0, 5, 5], [3, 11, 11]),       # WEST  -x
    2: ([6, 10, 6], [10, 16, 10], [5, 13, 5], [11, 16, 11]),   # UP    +y
    3: ([6, 0, 6], [10, 6, 10], [5, 0, 5], [11, 3, 11]),       # DOWN  -y
    4: ([6, 6, 10], [10, 10, 16], [5, 5, 13], [11, 11, 16]),   # SOUTH +z
    5: ([6, 6, 0], [10, 10, 6], [5, 5, 0], [11, 11, 3]),       # NORTH -z
}

# 水管外观形态。
#
# 东西和南北必须分成两个独立形态：方块没有“旋转”这回事，
# placeBlock 只认方块 ID，靠旋转来区分方向在方块模式下是行不通的。
SHAPES = {
    # 横向直管：东西向（bit0 + bit1）
    "straight_ew": (1 << 0) | (1 << 1),
    # 横向直管：南北向（bit4 + bit5）
    "straight_ns": (1 << 4) | (1 << 5),
    # 纵向直管：上下向（bit2 + bit3）
    "vertical": (1 << 2) | (1 << 3),
    # 水平十字：拐角 / T 形 / 十字 / 完全没接
    "cross": (1 << 0) | (1 << 1) | (1 << 4) | (1 << 5),
}

SHAPE_LABELS = {
    "straight_ew": "横向直管（东西）",
    "straight_ns": "横向直管（南北）",
    "vertical": "纵向直管（上下）",
    "cross": "水平十字管",
}


def cube(frm, to, texture):
    return {
        "from": list(frm),
        "to": list(to),
        "faces": {face: {"uv": [0, 0, 16, 16], "texture": "#" + texture} for face in FACES},
    }


def build_model(mask, label):
    """按连接掩码生成铜管模型。

    掩码为 0 时也画一个中心块，避免出现空模型（空模型客户端会报错）。
    """
    elements = [cube(*CORE)]
    for bit in range(6):
        if mask & (1 << bit):
            arm_from, arm_to, collar_from, collar_to = ARMS[bit]
            elements.append(cube(arm_from, arm_to, "body"))
            elements.append(cube(collar_from, collar_to, "body"))
    return {
        "credit": "CustomCropsPump - generated copper water pipe (%s)" % label,
        "textures": dict(TEXTURES_PIPE),
        "elements": elements,
        "display": DISPLAY,
    }


# 水泵模型：底座 + 主体 + 顶盖 + 出水口 + 两侧把手 + 两块面板
PUMP_ELEMENTS = [
    ([1, 0, 1], [15, 2, 15], {"down": "dark", "up": "dark", "north": "dark",
                              "south": "dark", "west": "dark", "east": "dark"}),
    ([2, 2, 2], [14, 13, 14], {"down": "dark", "up": "body", "north": "accent",
                               "south": "accent", "west": "body", "east": "body"}),
    ([3, 13, 3], [13, 14, 13], {"down": "dark", "up": "body", "north": "dark",
                                "south": "dark", "west": "dark", "east": "dark"}),
    ([7, 14, 7], [9, 16, 9], {"down": "dark", "up": "body", "north": "body",
                              "south": "body", "west": "body", "east": "body"}),
    ([0.5, 4, 7], [2, 7, 9], {"down": "dark", "up": "dark", "north": "dark",
                              "south": "dark", "west": "dark", "east": "dark"}),
    ([14, 4, 7], [15.5, 7, 9], {"down": "dark", "up": "dark", "north": "dark",
                                "south": "dark", "west": "dark", "east": "dark"}),
    ([4, 5, 1.8], [12, 11, 2.1], {"north": "accent", "south": "accent"}),
    ([4, 5, 13.9], [12, 11, 14.2], {"north": "accent", "south": "accent"}),
]


def build_pump_model(textures=None, credit="water pump"):
    """水泵模型。破损水泵复用同一套几何，只换锈蚀材质。"""
    elements = []
    for frm, to, faces_textures in PUMP_ELEMENTS:
        faces = {face: {"uv": [0, 0, 16, 16], "texture": "#" + texture}
                 for face, texture in faces_textures.items()}
        elements.append({"from": frm, "to": to, "faces": faces})
    return {
        "credit": "CustomCropsPump - " + credit,
        "textures": dict(textures or TEXTURES_PUMP),
        "elements": elements,
        "display": DISPLAY,
    }


# 收割机模型：底座 + 机身 + 顶部料斗 + 前置进料口 + 两侧滚筒 + 排气管
HARVESTER_ELEMENTS = [
    ([1, 0, 1], [15, 2, 15], {"down": "dark", "up": "dark", "north": "dark",
                              "south": "dark", "west": "dark", "east": "dark"}),
    ([2, 2, 2], [14, 11, 14], {"down": "dark", "up": "body", "north": "accent",
                               "south": "body", "west": "body", "east": "body"}),
    ([3, 11, 3], [13, 12, 13], {"down": "dark", "up": "body", "north": "accent",
                                "south": "accent", "west": "accent", "east": "accent"}),
    ([5, 12, 5], [11, 13.5, 11], {"down": "dark", "up": "dark", "north": "dark",
                                  "south": "dark", "west": "dark", "east": "dark"}),
    ([3, 2, 13], [13, 6, 16], {"down": "dark", "up": "dark", "north": "dark",
                               "south": "dark", "west": "body", "east": "body"}),
    ([4, 3, 15.6], [12, 5, 16], {"north": "accent", "south": "accent"}),
    ([1, 2, 4], [2, 7, 12], {"down": "dark", "up": "dark", "north": "dark",
                             "south": "dark", "west": "body", "east": "dark"}),
    ([14, 2, 4], [15, 7, 12], {"down": "dark", "up": "dark", "north": "dark",
                               "south": "dark", "west": "dark", "east": "body"}),
    ([9, 11, 9], [12, 15, 12], {"down": "dark", "up": "dark", "north": "accent",
                                "south": "accent", "west": "accent", "east": "accent"}),
    ([3, 7, 13.6], [13, 9, 14], {"north": "accent", "south": "accent"}),
]

# 洒水机模型：底座 + 水罐 + 侧面水位窗 + 顶盖 + 喷头 + 出水口
SPRINKLER_ELEMENTS = [
    ([1, 0, 1], [15, 2, 15], {"down": "dark", "up": "dark", "north": "dark",
                              "south": "dark", "west": "dark", "east": "dark"}),
    ([2, 2, 2], [14, 12, 14], {"down": "dark", "up": "body", "north": "body",
                               "south": "body", "west": "body", "east": "body"}),
    # 水位窗：贴在罐体正面的浅蓝色条，一眼能看出这是个装水的机器
    ([4, 3.5, 1.8], [12, 10.5, 2.05], {"north": "water", "south": "water"}),
    ([1.5, 12, 1.5], [14.5, 13.2, 14.5], {"down": "dark", "up": "accent",
                                          "north": "accent", "south": "accent",
                                          "west": "accent", "east": "accent"}),
    ([6, 13.2, 6], [10, 15, 10], {"down": "dark", "up": "water", "north": "body",
                                  "south": "body", "west": "body", "east": "body"}),
    ([7, 15, 7], [9, 16, 9], {"down": "water", "up": "water", "north": "water",
                              "south": "water", "west": "water", "east": "water"}),
    # 出水口：伸出方块边界一点，朝东
    ([14, 6, 7], [16, 9, 9], {"down": "dark", "up": "dark", "north": "body",
                              "south": "body", "west": "body", "east": "water"}),
    ([3, 2, 14], [13, 4, 16], {"down": "dark", "up": "dark", "north": "dark",
                               "south": "dark", "west": "body", "east": "body"}),
]

# 施肥机模型：底座 + 料罐 + 顶部骨粉料斗 + 出料口 + 侧面摇柄 + 正面面板
FERTILIZER_ELEMENTS = [
    ([1, 0, 1], [15, 2, 15], {"down": "dark", "up": "dark", "north": "dark",
                              "south": "dark", "west": "dark", "east": "dark"}),
    ([2, 2, 2], [14, 10, 14], {"down": "dark", "up": "body", "north": "body",
                               "south": "body", "west": "body", "east": "body"}),
    ([1.5, 9.5, 1.5], [14.5, 10.5, 14.5], {"down": "dark", "up": "accent",
                                           "north": "accent", "south": "accent",
                                           "west": "accent", "east": "accent"}),
    # 顶部料斗：骨粉色，中间挖一个深色口
    ([4, 10.5, 4], [12, 14, 12], {"down": "bone", "up": "bone", "north": "bone",
                                  "south": "bone", "west": "bone", "east": "bone"}),
    ([6.5, 13.4, 6.5], [9.5, 14.2, 9.5], {"down": "dark", "up": "dark", "north": "dark",
                                          "south": "dark", "west": "dark", "east": "dark"}),
    # 出料口
    ([3, 2, 13], [13, 5, 16], {"down": "dark", "up": "dark", "north": "dark",
                               "south": "dark", "west": "body", "east": "body"}),
    # 正面面板
    ([4, 4, 1.8], [12, 8, 2.05], {"north": "accent", "south": "accent"}),
    # 侧面摇柄
    ([14, 4, 6], [16, 7, 10], {"down": "dark", "up": "dark", "north": "dark",
                               "south": "dark", "west": "body", "east": "dark"}),
]


def build_machine_model(elements, textures, credit):
    """把元素定义转成 Blockbench 模型。"""
    result = []
    for frm, to, faces_textures in elements:
        faces = {face: {"uv": [0, 0, 16, 16], "texture": "#" + texture}
                 for face, texture in faces_textures.items()}
        result.append({"from": frm, "to": to, "faces": faces})
    return {
        "credit": "CustomCropsPump - " + credit,
        "textures": dict(textures),
        "elements": result,
        "display": DISPLAY,
    }


def build_harvester_model():
    """收割机方块模型。"""
    return build_machine_model(HARVESTER_ELEMENTS, TEXTURES_PUMP, "harvester machine")


def build_sprinkler_model():
    """洒水机方块模型。"""
    return build_machine_model(SPRINKLER_ELEMENTS, TEXTURES_SPRINKLER, "sprinkler machine")


def build_fertilizer_model():
    """施肥机方块模型。"""
    return build_machine_model(FERTILIZER_ELEMENTS, TEXTURES_FERTILIZER, "fertilizer machine")


HEADER = """# ============================================================
#  由 tools/generate_pipe_variants.py 自动生成，请勿手工编辑。
#
#  水泵 / 水管是 CraftEngine **自定义方块**（block_item）：
#    · 有真实碰撞箱，能挡水，也能贴墙/贴天花板放置
#    · 方块状态借用原版 minecraft:cactus 的 16 个 age 槽位
#      （auto_state: cactus），但外观用插件自己的铜管模型
#
#  连通规则：
#    · 水平（东西南北）：水管、水泵、洒水器互相连通
#    · 垂直（上下）：只有水管互相连通，自成一路
# ============================================================
items:
"""

# 战利品表：CE 的自定义方块**必须显式配 loot**，不配就什么都不掉。
#
# 这里内联写死战利品表，而不是引用 CE 自带的 default:loot_table/basic，
# 免得依赖 default_templates 那个内置资源包有没有被启用。
#
# 写死“固定的基础物品 ID”是为了：
# 水管有 4 个形态方块，如果用 default:loot_table/self，玩家拆掉一个
# 东西向直管会掉出 ccpump:water_pipe_straight_ew 这个形态物品，
# 背包里会攒出 4 种不同的“水管”。统一掉基础物品 ccpump:water_pipe，
# 放下后由插件重新算形态，背包里始终只有一种水管。
LOOT = """        loot:
          pools:
            - rolls: 1
              conditions:
                - type: survives_explosion
              entries:
                - type: item
                  item: {drop}
                  functions:
                    - type: set_count
                      add: false
                      count: 1"""


# 方块公共设置。管道不是完整固体方块，因此不是窒息方块、也不挡光。
PIPE_SETTINGS = """          hardness: 0.6
          resistance: 0.6
          is_suffocating: false
          is_redstone_conductor: false
          is_view_blocking: false
          can_occlude: false
          replaceable: false
          sounds:
            break: minecraft:block.copper.break
            place: minecraft:block.copper.place
            hit: minecraft:block.copper.hit
            step: minecraft:block.copper.step
            fall: minecraft:block.copper.fall"""


# 机器设置：完整的金属机身，挡光（is_view_blocking），但模型不是满方块，
# 所以 can_occlude 必须是 false，否则相邻方块的面会被错误剔除。
MACHINE_SETTINGS = """          hardness: 3.5
          resistance: 6.0
          is_suffocating: false
          is_redstone_conductor: false
          is_view_blocking: true
          can_occlude: false
          replaceable: false
          sounds:
            break: minecraft:block.copper.break
            place: minecraft:block.copper.place
            hit: minecraft:block.copper.hit
            step: minecraft:block.copper.step
            fall: minecraft:block.copper.fall"""


def appearance(model_path):
    """外观：借用仙人掌方块状态，但模型用自己的。"""
    return """              auto_state: cactus
              model:
                path: %s""" % model_path


def block_item(short, display_name, model_path, drop, settings=None, lore=None):
    """一个 block_item：玩家手里的物品 + 它的方块定义。

    {@code drop} 是拆掉这个方块时掉落的物品 ID，写死成基础物品，
    避免形态水管掉出形态物品。
    """
    lore_text = ""
    if lore:
        lore_text = "      lore:\n" + "".join(
            '        - "<!i><gray>%s</gray>"\n' % line for line in lore)
    return """  {item}:
    data:
      item-name: "{name}"
{lore}    model: {model}
    behavior:
      type: block_item
      block:
{loot}
        settings:
{settings}
        states:
          appearances:
            default:
{app}
          variants:
            default:
              appearance: default
""".format(
        item="ccpump:" + short,
        name=display_name,
        lore=lore_text,
        model=model_path,
        loot=LOOT.format(drop=drop),
        settings=settings or PIPE_SETTINGS,
        app=appearance(model_path),
    )


def main():
    os.makedirs(PIPE_MODEL_DIR, exist_ok=True)

    # 清掉旧的形态模型，避免改名后残留
    for name in os.listdir(PIPE_MODEL_DIR):
        if name.startswith("water_pipe_") and name.endswith(".json"):
            os.remove(os.path.join(PIPE_MODEL_DIR, name))

    for shape, mask in SHAPES.items():
        path = os.path.join(PIPE_MODEL_DIR, "water_pipe_%s.json" % shape)
        with open(path, "w", encoding="utf-8") as handle:
            json.dump(build_model(mask, shape), handle, indent=2, ensure_ascii=False)
            handle.write("\n")

    # 物品图标：完整六向十字
    with open(os.path.join(MODEL_DIR, "water_pipe.json"), "w", encoding="utf-8") as handle:
        json.dump(build_model(63, "icon"), handle, indent=2, ensure_ascii=False)
        handle.write("\n")

    with open(os.path.join(MODEL_DIR, "water_pump.json"), "w", encoding="utf-8") as handle:
        json.dump(build_pump_model(), handle, indent=2, ensure_ascii=False)
        handle.write("\n")

    for name, builder in (("harvester", build_harvester_model),
                          ("sprinkler_machine", build_sprinkler_model),
                          ("fertilizer_machine", build_fertilizer_model)):
        with open(os.path.join(MODEL_DIR, name + ".json"), "w", encoding="utf-8") as handle:
            json.dump(builder(), handle, indent=2, ensure_ascii=False)
            handle.write("\n")

    with open(os.path.join(MODEL_DIR, "broken_pump.json"), "w", encoding="utf-8") as handle:
        json.dump(build_pump_model(TEXTURES_BROKEN, "broken water pump"),
                  handle, indent=2, ensure_ascii=False)
        handle.write("\n")

    write_items()
    write_recipes()
    write_categories()
    check_models()
    check_shapes()


def broken_pump_item():
    """破损水泵：纯物品，没有方块定义（不能放置，只能拿去修）。"""
    return """  ccpump:broken_pump:
    data:
      item-name: "<!i><red>破损水泵</red>"
      lore:
        - "<!i><gray>铜壳已经锈透，抽不动水了。</gray>"
        - "<!i><gray>在工作台用 <white>1 组侦测器</white> 修好它。</gray>"
    model: ccpump:block/broken_pump
"""


def machine_items():
    """三台机器：都是 CraftEngine 自定义方块（block_item + 战利品表）。"""
    return (
        block_item("harvester", "<!i><gold>收割机</gold>",
                   "ccpump:block/harvester", "ccpump:harvester",
                   settings=MACHINE_SETTINGS,
                   lore=["右键地面放下，右键机器打开界面",
                         "自动收割范围里已经成熟的作物",
                         "收获物存在机器里，拆掉时一起掉出",
                         "需要动力；不会自动补种"])
        + block_item("sprinkler_machine", "<!i><aqua>洒水机</aqua>",
                     "ccpump:block/sprinkler_machine", "ccpump:sprinkler_machine",
                     settings=MACHINE_SETTINGS,
                     lore=["右键地面放下，右键机器打开界面",
                           "给范围里的花盆补水、给耕地保湿",
                           "水箱没水时往界面里投水桶",
                           "身边有水时会自动补水"])
        + block_item("fertilizer_machine", "<!i><green>施肥机</green>",
                     "ccpump:block/fertilizer_machine", "ccpump:fertilizer_machine",
                     settings=MACHINE_SETTINGS,
                     lore=["右键地面放下，右键机器打开界面",
                           "把 CustomCrops 肥料放进界面下半部分",
                           "自动施给范围里能施肥的花盆",
                           "遵守花盆白名单与施肥上限"])
    )


def write_recipes():
    """全部工作台配方：合成水泵、合成水管、修复破损水泵。"""
    text = """# ============================================================
#  由 tools/generate_pipe_variants.py 自动生成，请勿手工编辑。
#
#  依次生成的配方：
#    1. 合成水管   —— 十字形，一次出 4 根
#    2. 合成水泵   —— 九宫格，整机不便宜
#    3. 修复水泵   —— 破损水泵 + 8 个侦测器（比重新造一台明显便宜）
#    4. 合成收割机 —— 九宫格，比水泵更贵
#
#  修复配方靠 CE 的 ingredient count 支持，一次匹配 N 个（可少于一组），
#  玩家把 8 个侦测器叠在任意一格即可。
# ============================================================
recipes:
  # ---- 水管：4 铜锭 + 1 铁锭 → 4 根 ----
  #   十字形状和管道的 cross 模型一致
  ccpump:water_pipe:
    type: shaped
    pattern:
      - " C "
      - "CIC"
      - " C "
    ingredients:
      C: [minecraft:copper_ingot]
      I: [minecraft:iron_ingot]
    result:
      id: ccpump:water_pipe
      count: 4

  # ---- 水泵：整机 ----
  #   4 铜块(36 铜锭) + 2 铁块(18 铁锭) + 1 铁锭
  #   + 1 侦测器 + 1 红石块(9 红石)
  #   铜壳在外、铁块做腔体、侦测器当控制器、红石块当动力核心
  ccpump:water_pump:
    type: shaped
    pattern:
      - "CIC"
      - "BOB"
      - "CRC"
    ingredients:
      C: [minecraft:copper_block]
      I: [minecraft:iron_ingot]
      B: [minecraft:iron_block]
      O: [minecraft:observer]
      R: [minecraft:redstone_block]
    result:
      id: ccpump:water_pump
      count: 1

  # ---- 收割机：整机，比水泵更贵 ----
  #   2 铁块(18 铁锭) + 2 铜块(18 铜锭) + 漏斗 + 2 铁栏杆 + 2 侦测器
  #   铁块做机架、铜壳做外壳、漏斗当进料口、铁栏杆当分禾器、侦测器当“眼睛”
  ccpump:harvester:
    type: shaped
    pattern:
      - "IHI"
      - "CBC"
      - "OBO"
    ingredients:
      I: [minecraft:iron_block]
      H: [minecraft:hopper]
      C: [minecraft:copper_block]
      B: [minecraft:iron_bars]
      O: [minecraft:observer]
    result:
      id: ccpump:harvester
      count: 1

  # ---- 洒水机：水箱 + 喷头 ----
  #   4 铁锭 + 2 铜块 + 水桶(当水箱) + 侦测器
  ccpump:sprinkler_machine:
    type: shaped
    pattern:
      - "ICI"
      - "CWC"
      - "IOI"
    ingredients:
      I: [minecraft:iron_ingot]
      C: [minecraft:copper_block]
      W: [minecraft:water_bucket]
      O: [minecraft:observer]
    result:
      id: ccpump:sprinkler_machine
      count: 1

  # ---- 施肥机：料斗 + 骨粉 ----
  #   4 铁锭 + 2 铜块 + 骨粉 + 漏斗 + 侦测器
  ccpump:fertilizer_machine:
    type: shaped
    pattern:
      - "IBI"
      - "CHC"
      - "IOI"
    ingredients:
      I: [minecraft:iron_ingot]
      B: [minecraft:bone_meal]
      C: [minecraft:copper_block]
      H: [minecraft:hopper]
      O: [minecraft:observer]
    result:
      id: ccpump:fertilizer_machine
      count: 1

  # ---- 修复：破损水泵 + 8 个侦测器 → 新的水泵 ----
  #   刻意比重新造一台便宜，玩家才有理由去修而不是扔掉重做
  ccpump:repair_water_pump:
    type: shapeless
    ingredients:
      - items: [ccpump:broken_pump]
      - items: [minecraft:observer]
        count: 8
    result:
      id: ccpump:water_pump
      count: 1
"""
    with open(os.path.join(PACK_DIR, "recipes.yml"), "w", encoding="utf-8") as handle:
        handle.write(text)


def write_items():
    """水泵与水管的物品 + 方块定义。"""
    text = HEADER
    text += block_item("water_pump", "<!i><gold>水泵</gold>",
                       "ccpump:block/water_pump", "ccpump:water_pump")
    text += block_item("water_pipe", "<!i><gold>水管</gold>",
                       "ccpump:block/water_pipe", "ccpump:water_pipe")
    for shape in SHAPES:
        # 所有形态都掉基础水管，背包里只会出现一种水管
        text += block_item("water_pipe_" + shape,
                           "<!i><gold>水管</gold><gray> · %s</gray>" % SHAPE_LABELS[shape],
                           "ccpump:block/pipe/water_pipe_" + shape,
                           "ccpump:water_pipe")
    text += broken_pump_item()
    text += machine_items()

    with open(os.path.join(PACK_DIR, "items.yml"), "w", encoding="utf-8") as handle:
        handle.write(text)


def write_categories():
    """生成 categories.yml。"""
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
    lines.append("      - ccpump:broken_pump")
    lines.append("      - ccpump:harvester")
    lines.append("      - ccpump:sprinkler_machine")
    lines.append("      - ccpump:fertilizer_machine")
    with open(os.path.join(PACK_DIR, "categories.yml"), "w", encoding="utf-8") as handle:
        handle.write("\n".join(lines) + "\n")


def check_models():
    """所有 model 引用必须指向真实存在的模型文件。"""
    missing = []
    for name in ("items.yml", "categories.yml"):
        path = os.path.join(PACK_DIR, name)
        if not os.path.exists(path):
            continue
        with open(path, encoding="utf-8") as handle:
            text = handle.read()
        for model in re.findall(r"^\s+model: (\S+)\s*$", text, re.M):
            if model.startswith("minecraft:"):
                continue
            relative = model.replace("ccpump:", "", 1) + ".json"
            if not os.path.exists(os.path.join(MODEL_ROOT, relative)):
                missing.append(model)
    if missing:
        raise SystemExit("以下物品引用了不存在的模型:\n  " + "\n  ".join(missing))


def check_shapes():
    """形态列表必须和 Java 侧 PipeConfig.SHAPES 一致。"""
    java_path = os.path.join(ROOT, "src/main/java/com/liu/ccpump/config/PipeConfig.java")
    if not os.path.exists(java_path):
        return
    with open(java_path, encoding="utf-8") as handle:
        java_text = handle.read()

    block = re.search(r"String\[\] SHAPES\s*=\s*\{(.*?)\}", java_text, re.S)
    if not block:
        raise SystemExit("PipeConfig.java 里找不到 SHAPES 定义")
    constants = dict(re.findall(r'String (SHAPE_[A-Z_]+)\s*=\s*"([a-z_]+)"', java_text))
    java_shapes = []
    for name in re.findall(r"\b(SHAPE_[A-Z_]+)\b", block.group(1)):
        if name not in constants:
            raise SystemExit("PipeConfig.java 里的常量 %s 没有赋值" % name)
        java_shapes.append(constants[name])

    if java_shapes != list(SHAPES.keys()):
        raise SystemExit("形态列表不一致:\n  Java   = %s\n  Python = %s"
                         % (java_shapes, list(SHAPES.keys())))


if __name__ == "__main__":
    main()
