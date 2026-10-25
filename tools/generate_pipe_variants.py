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


def build_pump_model():
    elements = []
    for frm, to, textures in PUMP_ELEMENTS:
        faces = {face: {"uv": [0, 0, 16, 16], "texture": "#" + texture}
                 for face, texture in textures.items()}
        elements.append({"from": frm, "to": to, "faces": faces})
    return {
        "credit": "CustomCropsPump - water pump",
        "textures": dict(TEXTURES_PUMP),
        "elements": elements,
        "display": DISPLAY,
    }


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


def appearance(model_path):
    """外观：借用仙人掌方块状态，但模型用自己的。"""
    return """              auto_state: cactus
              model:
                path: %s""" % model_path


def block_item(short, display_name, model_path):
    """一个 block_item：玩家手里的物品 + 它的方块定义。"""
    return """  {item}:
    data:
      item-name: "{name}"
    model: {model}
    behavior:
      type: block_item
      block:
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
        model=model_path,
        settings=PIPE_SETTINGS,
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

    write_items()
    write_categories()
    check_models()
    check_shapes()


def write_items():
    """水泵与水管的物品 + 方块定义。"""
    text = HEADER
    text += block_item("water_pump", "<!i><gold>水泵</gold>", "ccpump:block/water_pump")
    text += block_item("water_pipe", "<!i><gold>水管</gold>", "ccpump:block/water_pipe")
    for shape in SHAPES:
        text += block_item("water_pipe_" + shape,
                           "<!i><gold>水管</gold><gray> · %s</gray>" % SHAPE_LABELS[shape],
                           "ccpump:block/pipe/water_pipe_" + shape)

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
