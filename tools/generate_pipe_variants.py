#!/usr/bin/env python3
"""
生成水管的 64 种连接模型（6 个方向各一位掩码）以及对应的 CraftEngine 物品定义。

用法（在 CustomCropsPump/ 目录下）：
    python3 tools/generate_pipe_variants.py

掩码位（与 PumpManager.MASK_DIRS 保持一致）：
    bit0 = EAST (+x)   bit1 = WEST (-x)
    bit2 = UP   (+y)   bit3 = DOWN (-y)
    bit4 = SOUTH(+z)   bit5 = NORTH(-z)
"""

import json
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


def cube(frm, to, texture):
    return {
        "from": list(frm),
        "to": list(to),
        "faces": {face: {"uv": [0, 0, 16, 16], "texture": "#" + texture} for face in FACES},
    }


def build_model(mask):
    elements = [cube(*CORE)]
    for bit in range(6):
        if mask & (1 << bit):
            arm_from, arm_to, collar_from, collar_to = ARMS[bit]
            elements.append(cube(arm_from, arm_to, "body"))
            elements.append(cube(collar_from, collar_to, "accent"))
    return {
        "credit": "CustomCropsPump - generated connected copper water pipe (mask %d)" % mask,
        "textures": dict(TEXTURES),
        "elements": elements,
        "display": DISPLAY,
    }


ITEM_TEMPLATE = """  ccpump:water_pipe_{mask}:
    material: paper
    data:
      item-name: <!i><gold>水管</gold>
    model: ccpump:block/pipe/water_pipe_{mask}
    behavior:
      type: furniture_item
      ignore-placer: true
      ignore-entities: true
      rules:
        ground:
          rotation: four
          alignment: center
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
              - item: ccpump:water_pipe_{mask}
                display-transform: NONE
                billboard: FIXED
                translation: 0,0.5,0
            hitboxes:
              - type: interaction
                can-use-item-on: true
                can-be-hit-by-projectile: false
                blocks-building: false
                position: 0,0,0
                scale: 0.9,0.55
                interactive: true
                invisible: false
              - type: shulker
                can-use-item-on: false
                blocks-building: true
                can-be-hit-by-projectile: false
                position: 0,0.25,0
                scale: 0.5
                peek: 0
                interactive: false
                interaction-entity: false
                invisible: true
        loot:
          template: default:loot_table/basic
          arguments:
            item: ccpump:water_pipe
"""

HEADER = """# ============================================================
#  由 tools/generate_pipe_variants.py 自动生成，请勿手工编辑。
#
#  水管按相邻连接方向切换外观：掩码 0~63，每个方向一位。
#    bit0 EAST(+x)  bit1 WEST(-x)
#    bit2 UP(+y)    bit3 DOWN(-y)
#    bit4 SOUTH(+z) bit5 NORTH(-z)
#
#  ccpump:water_pipe 是玩家拿到的物品；放置后插件会立即把它
#  换成 ccpump:water_pipe_<掩码>，所以这些变体物品不需要出现在
#  创造模式菜单里（categories.yml 未列出它们）。
# ============================================================
items:
"""


def main():
    pipe_model_dir = os.path.join(MODEL_DIR, "pipe")
    os.makedirs(pipe_model_dir, exist_ok=True)

    for mask in range(64):
        path = os.path.join(pipe_model_dir, "water_pipe_%d.json" % mask)
        with open(path, "w", encoding="utf-8") as handle:
            json.dump(build_model(mask), handle, indent=2, ensure_ascii=False)
            handle.write("\n")

    # 物品图标沿用完整十字模型
    with open(os.path.join(MODEL_DIR, "water_pipe.json"), "w", encoding="utf-8") as handle:
        json.dump(build_model(63), handle, indent=2, ensure_ascii=False)
        handle.write("\n")

    with open(os.path.join(PACK_DIR, "pipe_variants.yml"), "w", encoding="utf-8") as handle:
        handle.write(HEADER)
        for mask in range(64):
            handle.write(ITEM_TEMPLATE.format(mask=mask))
    print("生成完成：64 个模型 + pipe_variants.yml")


if __name__ == "__main__":
    main()
