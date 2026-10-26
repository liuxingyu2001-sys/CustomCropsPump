# CustomCropsPump

给 [CustomCrops](https://github.com/Xiao-MoMi/Custom-Crops) 的洒水器自动供水的水泵 / 水管附属插件，
外加三台自动农机：**收割机**、**洒水机**、**施肥机**。

- 水泵自动从附近的水源方块抽水（也支持水桶手动补水）
- 水泵需要动力：在界面里投放红石、青金石、粗铁、粗金或钻石充能
- 右键水泵打开操作界面，查看水量 / 动力 / 管网 / 水源，并在界面里开关水泵
- 水管把水泵和洒水器连接成管网，按连接方向自动切换外观（横向 / 纵向 / 十字）
- **收割机**：自动收割范围内成熟的 CustomCrops 作物与原版作物，收获物进机器仓储
- **洒水机**：自带水箱，给范围内的 CustomCrops 花盆补水，顺带给原版耕地保湿
- **施肥机**：把仓储里的 CustomCrops 肥料施给范围内的花盆（尊重花盆白名单与施肥规则）
- 水泵、水管、三台机器**都是真实方块**（CraftEngine 自定义方块），有碰撞箱、能挡水，
  和 CustomCrops 的方块状态完全无关
- 独立插件，**不修改** CustomCrops 源码，也不占用 CustomCrops 的方块状态
- 自带一套 CraftEngine 铜质模型（可自动安装，也可以完全不用）

## 工作原理

水泵管网：连接规则是 6 方向（上、下、左、右、前、后），斜角不算。

```
水源
  │
[水泵] —— [水管] —— [水管] —— [洒水器]
                      │
                   [洒水机]      ← 洒水机也是取水点，水管直接接上去

[水泵] —— [洒水器]        ← 水泵直接贴着洒水器/洒水机也可以
```

- 水泵每 `pump-rate` 从半径 `source.radius` 内寻找水源方块，装进水箱（`capacity`）
- 一台水泵从自己出发，沿相邻水管 BFS，找到所有相邻的**取水点**：
  CustomCrops 的洒水器，以及本插件的**洒水机**（水箱容量 &gt; 0 的机器都算）
- 每个周期给每个取水点补 `push-rate` 点水，直到洒水器达到 `storage`、
  洒水机达到自己的水箱 `capacity`
- 水管本身不储水，只是“导线”；它按相邻的水管/水泵/洒水器/洒水机自动切换成对应的连接模型
- 洒水器配置里 `infinite: true` 的洒水器会被跳过
- 水泵工作会消耗动力，动力耗尽后停止抽水和补水

三台机器是**独立的方块设备**，各自扫自己周围的一块长方体区域：

| 机器 | 干什么 | 需要的资源 |
| --- | --- | --- |
| 收割机 | 收割成熟的作物，收获物进仓储 | 动力 |
| 洒水机 | 给花盆补水、给耕地保湿 | 动力 + 水（水箱） |
| 施肥机 | 给花盆施肥 | 动力 + 仓储里的肥料 |

水管不能当洒水器用，洒水器也不能继续当管道延伸。**洒水机可以当管网终点**
（把水管接到它旁边就行，水泵会把它的水箱灌满）；收割机和施肥机不用水，不参与管网连接。

## 安装

1. 服务端：Paper / Leaf 1.21+（需要 Java 21+；不支持 Folia）
2. 安装 **CustomCrops 3.6.x**，并确认 `plugins/CustomCrops/contents/` 里已有可用的
   洒水器 / 花盆 / 肥料配置
3. 把 `Liu-CustomCropsPump-x.y.z.jar` 放进 `plugins/`
4. 启动服务器
5. 如果使用 CraftEngine：插件会把自带资源包复制到
   `plugins/CraftEngine/resources/ccpump/`，然后执行：

   ```
   /ce reload all
   ```

   首次安装建议直接重启服务器，让资源包完整生成。
   以后更新插件时插件会比对 `pack.yml` 里的版本号，**内置资源有新版本会自动覆盖一次**，
   不需要手动删目录。
6. 执行 `/ccpump give water_pump`、`/ccpump give water_pipe`、`/ccpump give harvester`
   各拿一个物品测试

> 不使用 CraftEngine 也可以：把 `config.yml` 里的 `pumps.*.item` / `pipes.*.item` /
> `machines.*.item` 改成你服务器上真实存在的方块 ID，并把
> `craftengine-pack.auto-install` 设为 `false`。机器必须是 CraftEngine 的 `block_item`
> 方块（需要真实方块，不能是家具/实体）。

## 合成配方

配方由插件自带（`plugins/CraftEngine/resources/ccpump/configuration/recipes.yml`），
不需要额外装别的包。

**水管**（有序，一次出 4 根）：4 铜锭 + 1 铁锭，十字形

```
 .  铜  .
 铜  铁  铜
 .  铜  .
```

**水泵**（有序，出一台）：整机不便宜

```
 铜块   铁锭   铜块
 铁块  侦测器  铁块
 铜块  红石块  铜块
```

合计 **36 铜锭 + 19 铁锭 + 1 侦测器 + 9 红石**。水泵是整套灌溉系统的核心，
一台就能带动一片农田，所以成本按中后期设备来定。

**收割机**（有序，出一台）：比水泵更贵，一台顶一套农机

```
 铁块   漏斗   铁块
 铜块   铁栏杆  铜块
 侦测器 铁栏杆  侦测器
```

合计 **18 铁锭（2 铁块）+ 18 铜锭（2 铜块）+ 1 漏斗 + 3 铁锭（6 铁栏杆）+ 2 侦测器**。

**洒水机**（有序，出一台）：水箱 + 喷头

```
 铁锭   铜块   铁锭
 铜块   水桶   铜块
 铁锭  侦测器  铁锭
```

合计 **4 铁锭 + 18 铜锭（2 铜块）+ 1 水桶 + 1 侦测器**。

**施肥机**（有序，出一台）：料斗 + 骨粉

```
 铁锭   骨粉   铁锭
 铜块   漏斗   铜块
 铁锭  侦测器  铁锭
```

合计 **4 铁锭 + 18 铜锭（2 铜块）+ 1 骨粉 + 1 漏斗 + 1 侦测器**。

**修复水泵**（无序）：`1 个破损水泵 + 8 个侦测器 → 1 台新水泵`（比重新造一台便宜，修才有意义）

> 想调整价格就改 `recipes.yml` 里的 `pattern` 与 `ingredients`；
> 改完 `/ce reload all` 即可，不用重启。但该文件由
> `tools/generate_pipe_variants.py` 生成，如果你会重跑脚本，请改脚本里的 `write_recipes()`。

## 使用：水泵与水管

1. 在水边放水泵（默认要求半径 2 格内有水）
2. 在水泵和洒水器之间铺水管（每根水管都要和相邻的水管/水泵/洒水器贴着）
3. 右键水泵打开界面：水量、动力、管网、水源一目了然，点“开关”按钮切换水泵
4. 手持水桶右键水泵可以手动补水
5. 把红石、青金石、粗铁、粗金或钻石点进界面里的“投放充能”槽即可充能
6. 潜行 + 右键水泵可以快速开关（不打开界面）
7. 界面里的“耐久”槽显示剩余耐久；水泵用久了会损坏，掉落破损水泵，用 8 个侦测器在工作台修好

> 铁锭、金锭默认**不能**充能（部分服务器可以刷取）；请使用粗铁、粗金。
> 右键水泵 = 打开界面（不管手里拿着什么）；潜行 + 右键 = 快速开关。
> 充能在界面里投放：点光标上的那一组，或 shift 点击背包格子，物品会被按需消耗。
> 创造模式填充动力不消耗物品。

默认燃料数值（已被大幅削弱）：红石 5、青金石 15、粗铁 25、粗金 35、钻石 100。
动力容量默认 100，所以一次投料最多囤 100 点。

一台默认水泵每秒工作消耗 1 点动力，换算下来：

| 燃料 | 能撑多久 |
| --- | --- |
| 红石 | 约 5 秒 |
| 青金石 | 约 15 秒 |
| 粗铁 | 约 25 秒 |
| 粗金 | 约 35 秒 |
| 钻石 | 约 1 分 40 秒 |

想更省（或更肝）就调 `charge.fuels.*.amount` 和 `pumps.*.charge-capacity`；
把 `charge-capacity` 设成 `0` 就是不消耗动力的无限水模式。

右键水管可以查看它所在管网连接了多少水泵、多少洒水器。水管的外观会自动
跟着相邻的水管/水泵/洒水器变化：接哪边，哪边才伸出管口。

> 界面里的数据每秒刷新一次；水泵/机器被拆掉时界面会自动关闭。
> 需要 `customcropspump.use` 权限才能打开界面和点按钮。

## 使用：三台机器

三台机器用法一致：**拿在手上右键地面放下**，然后**右键机器打开界面**。

界面布局（三种机器共用，数据各自独立）：

```
 ┌───────────────────────────────┐
 │ 开关   ·  充能   ·  储能   ·  仓储/水箱   ·  关闭 │
 ├───────────────────────────────┤
 │ 仓储区（收割机的收获物 / 施肥机的肥料）          │
 └───────────────────────────────┘
```

- 动力和燃料规则与水泵完全一样（同一张 `charge.fuels` 表，界面里投放）
- **仓储区**里放的就是机器的真实库存：收割机存收获物，施肥机放肥料
- 收割机、洒水机的仓储**只出不进**（不能当免费箱子用）；施肥机可以往里放肥料，
  但只认 CustomCrops 的肥料物品
- 拆掉机器时，**仓储里的东西会同掉落出**（机器本体也按正常掉落）；动力和水量**不保留**

### 收割机

自动收割方圆 `radius` 格、上下各 `vertical-radius` 格内**已经成熟**的作物：

| 项目 | 默认值 |
| --- | --- |
| 工作范围 | 水平半径 4 格（9×9），上下各 2 格 |
| 工作周期 | 每 2 秒一批，每批最多 6 株 |
| 动力容量 | 2400 点，每收割一株消耗 2 点 |
| 内部仓储 | 18 格 |

动力换算：**1 块红石 = 5 点 = 收割 2.5 株**；满罐 2400 点约等于 1200 株。
一片 9×9 的田（81 株）扫一遍约 162 点，也就是 33 块红石。

支持的作物：

- CustomCrops 作物：任意已成熟的作物（按 `point >= max-points` 判断）
- 原版作物：小麦、胡萝卜、马铃薯、甜菜根、下界疣、可可豆、甜浆果
- 原版果实：西瓜、南瓜（长成即收）

**收割后不会自动补种**，需要玩家自己重新种。甜浆果只摘果子、灌木保留；
其余作物被收割就是从田里消失，掉落（含种子）全部进机器仓储。

> 收获物**不会掉在地上**：作物一被收割，掉落就被直接收进机器仓储，
> 所以不会出现“掉一地再被吸走”的卡顿与抢物。
> 仓储满了以后多出来的部分会掉在机器脚下，不会凭空消失。

### 洒水机

给范围内的 CustomCrops 花盆补水，顺带给原版耕地保湿：

| 项目 | 默认值 |
| --- | --- |
| 工作范围 | 水平半径 3 格（7×7），上下各 1 格 |
| 工作周期 | 每 2 秒一批，每批最多 8 盆 |
| 动力容量 | 1200 点，每浇一次消耗 1 点 |
| 水箱 | 16 点，每浇一盆/一格耕地消耗 1 点 |
| 加水方式 | ① 水管接到机器上 → 水泵自动灌水（主水源）<br>② 身边 2 格内有水 → 每周期自抽 1 点<br>③ 界面里投水桶 → 一次 +8 |

- **接了水管就不用管水**：水泵每周期往里灌 `push-rate` 点，灌满为止；
  管网在供水时机器不会再从身边的水源抽水（避免两个水源打架、把水池抽干）
- 没有管网时它是一台独立设备：身边有水就自己抽，没水就靠界面里投水桶
- 已经满水的花盆 / 湿度满的耕地**不会被重复浇**，不消耗水和动力
- 花盆里没有作物时也会补水（CustomCrops 自己会在合适的时候用掉）
- `disable-plugin-mechanism: true` 的花盆会跳过，交给别的插件管
- 水源是“可选”的：把 `water.source.required` 设成 `false` 就变成凭空出水的水箱

### 施肥机

把仓储里的肥料施给范围内的花盆：

| 项目 | 默认值 |
| --- | --- |
| 工作范围 | 水平半径 3 格（7×7），上下各 1 格 |
| 工作周期 | 每 5 秒一批，每批最多 4 盆 |
| 动力容量 | 1600 点，每施一次消耗 4 点 |
| 仓储 | 9 格（只能放 CustomCrops 肥料） |

- 只认 CustomCrops 的肥料物品，别的物品放不进去
- 遵守 CustomCrops 自己的规则：花盆白名单、`max-fertilizers`、
  `before-plant` 肥料只在花盆里没有作物时才能施
- 界面开着的时候机器会暂停施肥（避免“界面里看到的数量”和机器里的对不上）

## 命令与权限

| 命令 | 说明 | 权限 |
| --- | --- | --- |
| `/ccpump help` | 查看帮助 | 无 |
| `/ccpump reload` | 重载 config.yml / messages.yml | `customcropspump.admin` |
| `/ccpump save` | 立即写入数据库 | `customcropspump.admin` |
| `/ccpump info` | 查看已登记的水泵/水管/机器数量 | `customcropspump.admin` |
| `/ccpump give <类型> [玩家] [数量]` | 给予水泵/水管/机器 | `customcropspump.admin` |

- `customcropspump.admin` 默认 OP
- `customcropspump.use` 默认所有玩家，控制补水、开关、查看信息

`/ccpump give` 的“类型”是 `config.yml` 里 `pumps` / `pipes` / `machines` 下的键名：
`water_pump`、`water_pipe`、`harvester`、`sprinkler_machine`、`fertilizer_machine`。
每一类只配置了一种时，也可以用 `pump` / `pipe` 简写。

## 配置

`config.yml` 主要项：

| 配置 | 默认 | 说明 |
| --- | --- | --- |
| `tick-interval` | `20` | 主循环间隔（tick），20 = 1 秒 |
| `save-interval` | `6000` | 自动保存间隔（tick），0 = 只在关服时保存 |
| `source-check-interval` | `40` | 重新检测水源的间隔（tick） |
| `validate-interval` | `1200` | 校验水泵/水管/机器还在不在的间隔（tick） |
| `network-cache-ticks` | `100` | 管网 BFS 缓存时间（tick） |
| `debug` | `false` | 输出调试日志 |
| `craftengine-pack.auto-install` | `true` | 自动安装自带资源包 |
| `craftengine-pack.overwrite` | `false` | 强制覆盖已存在的资源文件 |

每台水泵：

| 配置 | 默认 | 说明 |
| --- | --- | --- |
| `item` | `ccpump:water_pump` | 方块物品 ID |
| `capacity` | `12` | 水箱容量 |
| `pump-rate` | `2` | 每周期抽水量 |
| `push-rate` | `2` | 每周期给单个洒水器的补水量 |
| `bucket-amount` | `4` | 一桶水补充量 |
| `charge-capacity` | `100` | 动力容量；`0` = 不需要动力 |
| `charge-per-cycle` | `1` | 每完成一次实际工作消耗的动力 |
| `durability` | `86400` | 耐久，`0` = 不限；见下文“耐久” |
| `allow-toggle` | `true` | 允许潜行右键开关 |
| `source.required` | `true` | `false` = 无限水，不需要水源 |
| `source.radius` | `2` | 水源检测半径 |
| `source.consume` | `false` | 抽水是否消耗水源方块 |
| `network.max-pipes` | `64` | 单台水泵最多水管数 |
| `network.max-sprinklers` | `16` | 单台水泵最多洒水器数 |
| `network.max-distance` | `32` | 水泵到最远管网节点的直线距离 |
| `effects.*` | 见文件 | 工作粒子 / 声音 |

每种水管：

| 配置 | 默认 | 说明 |
| --- | --- | --- |
| `item` | `ccpump:water_pipe` | 玩家拿到的水管物品 / 方块 ID |
| `connections` | `true` | 是否按连接方向切换外观 |
| `variant-format` | `{item}_{shape}` | 形态方块 ID 规则，`{shape}` = 形态名 |

水管外观区分 4 种形态（不是 64 种连接组合），覆盖所有连接情况：

| 形态名 | 外观 | 使用条件 |
| --- | --- | --- |
| `straight_ew` | 横向直管（东西向） | 只连东西两边 |
| `straight_ns` | 横向直管（南北向） | 只连南北两边 |
| `vertical` | 纵向直管（上下向） | 只连上下两边 |
| `cross` | 水平十字 | 拐角、T 形、十字、单边、完全没接 |

也就是说：管道尽头（比如只接一个方向）也显示十字管的一个方向，
不会出现“缺一角”的模型。`connections` 关闭、或形态方块不存在时，
水管会一直用 `item` 自己的模型，不会影响正常供水。

### 机器参数

`machines.<键>` 下每一个条目都是一台机器，`type` 决定它会干什么：

| 配置 | 默认（按 type） | 说明 |
| --- | --- | --- |
| `type` | — | `harvester` / `sprinkler` / `fertilizer` |
| `item` | — | 放置用的方块物品 ID，拆下来也掉它 |
| `radius` | 收割 4 / 其余 3 | 水平半径（格） |
| `vertical-radius` | 收割 2 / 其余 1 | 上下各几格的垂直范围 |
| `cycle` | 收割·洒水 40 / 施肥 100 | 工作周期（tick） |
| `max-per-cycle` | 收割 6 / 洒水 8 / 施肥 4 | 每周期最多处理几个目标 |
| `charge-capacity` | 收割 2400 / 洒水 1200 / 施肥 1600 | 动力上限，`0` = 不需要动力 |
| `charge-per-action` | 收割 2 / 洒水 1 / 施肥 4 | 每处理一个目标消耗的动力 |
| `buffer-slots` | 收割 18 / 洒水 0 / 施肥 9 | 仓储格数（9 的倍数最整齐） |
| `accepts-input` | 施肥 `true`，其余 `false` | 仓储是否允许玩家往里放东西 |
| `custom-crops` | 收割·洒水·施肥 `true` | 是否处理 CustomCrops 的目标 |
| `vanilla-crops` | 收割 `true`，其余 `false` | 是否处理原版作物 / 果实 |
| `farmland` | 洒水 `true`，其余 `false` | 是否给原版耕地保湿 |
| `water.*` | 仅洒水机 | 水箱容量、每周期补水量、水桶量、水源设置 |

想加一台新机器：复制一段、改 `key` / `type` / `item` 即可，代码不用动。

### 动力/充能

`config.yml` 顶部的 `charge` 段是**全局**充能表，所有水泵和机器共用：

```yaml
charge:
  fuels:
    redstone:
      item: minecraft:redstone
      amount: 5
    lapis_lazuli:
      item: minecraft:lapis_lazuli
      amount: 15
    raw_iron:
      item: minecraft:raw_iron
      amount: 25
    raw_gold:
      item: minecraft:raw_gold
      amount: 35
    diamond:
      item: minecraft:diamond
      amount: 100
  blocked-items:
    - minecraft:iron_ingot
    - minecraft:gold_ingot
```

- `amount` 是“填一个物品给多少动力”
- `blocked-items` 优先级最高，即使同时写进 `fuels` 也会被拒绝
- 默认只允许红石、青金石、粗铁、粗金、钻石；铁锭/金锭明确禁止
- 动力只在**真正干活**时消耗，空转/停机/没有目标时不掉动力
- `charge-capacity: 0` 可以关掉动力系统，变成无限动力
- 旧版 `config.yml` 没有 `charge` 段时，插件会使用内置默认表并在控制台提示

### 耐久

水泵会磨损，用久了会彻底损坏（机器**没有**耐久）：

```yaml
pumps:
  water_pump:
    durability: 86400
```

- **每完成一次“实际工作”消耗 1 点耐久**（抽水或补水都算一次）。空转、停机、没水源、水箱已满都不会磨损
- `tick-interval: 20` 时每秒约 1 个周期，换算参考：

| durability | 连续工作时长 |
| --- | --- |
| `10000` | ≈ 2.8 小时 |
| `86400` | ≈ 24 小时（默认） |
| `432000` | ≈ 5 天 |

- 耐久掉到 0 时，水泵方块**直接消失**，并原地掉落一个**破损水泵**
- 耐久上限设为 `0` 表示不做限制
- 剩余耐久可以在界面的“耐久”槽里看到（含进度条，低于 20% 换成裂纹铁砧图标）

**修复**：工作台无序合成

```
1 个破损水泵 + 8 个侦测器  →  1 个新的水泵
```

侦测器按“同一槽位 N 个”匹配，把 8 个叠在任意一格即可，不必占满九宫格。
修复刻意比重新造一台便宜（8 侦测器 ≪ 36 铜锭 + 19 铁锭），玩家才有理由去修而不是扔掉重做。

### 多等级水泵示例

```yaml
pumps:
  water_pump:
    item: ccpump:water_pump
    capacity: 12
    pump-rate: 2
    push-rate: 2
    charge-capacity: 1000
    charge-per-cycle: 1
    network:
      max-pipes: 64
      max-sprinklers: 16
      max-distance: 32

  iron_pump:
    item: mypack:iron_pump
    capacity: 32
    pump-rate: 6
    push-rate: 4
    charge-capacity: 3000
    charge-per-cycle: 2
    network:
      max-pipes: 128
      max-sprinklers: 32
      max-distance: 48
```

每新增一种物品 ID，`item` 必须是资源包里真实存在的方块 ID；
修改 item ID 后需要重启服务器，因为 CustomCrops 的注册表只增不减；
如果新增的是 CraftEngine 物品，还要执行 `/ce reload all` 让物品定义生效。

## 数据

水泵水量、开关状态、机器仓储、水泵/水管/机器坐标保存在 SQLite 数据库：

```
plugins/CustomCropsPump/data.db
```

用 SQLite 而不是 yml，是因为 yml 保存要全量重写整个文件、开销随条目数线性增长，
设备多了以后单次保存会明显卡主线程；SQLite 批量写入一个事务即可，几百条记录
通常在几毫秒内完成。运行期查询走内存，只有启动读取和定时保存才碰数据库。

驱动由 Paper 按 `plugin.yml` 的 `libraries` 自动下载（`org.xerial:sqlite-jdbc`），
第一次启动需要能联网；下载失败时插件仍可正常运行，只是数据不会持久化，日志会提示。

数据表结构：

| 表 | 字段 |
| --- | --- |
| `pumps` | `world, x, y, z`（主键）, `type, water, charge, enabled, wear` |
| `pipes` | `world, x, y, z`（主键）, `type` |
| `machines` | `world, x, y, z`（主键）, `type, charge, enabled, water, buffer` |

`machines.buffer` 是仓储内容的二进制列（`ItemStack#serializeAsBytes`），
`type` 是 `config.yml` 里 `machines` 段下的键名。

`pumps.wear`、`machines.water` 这些后加的列在老数据库上会自动 `ALTER TABLE` 补上；
旧版本（实体收割机）的 `machines` 表主键是 UUID，和新版不兼容，
插件检测到后会删掉重建并在控制台说明一句，不需要手动删库。

不写入 CustomCrops 的区块数据，所以 `/cc reload`、区块卸载、重启都不会丢。
如果水泵/水管/机器被爆炸或 WorldEdit 等非玩家方式移除，插件会在
`validate-interval` 内自动清理失效记录（机器里的东西也会一起丢失，这是已知限制）。

## 常见问题

**Q：水泵不抽水？**
- 右键水泵看“水源”是否为“有”；没有的话把水泵放到离水 2 格以内，或把
  `source.required` 改成 `false`
- 看“动力”是不是 0；把红石/青金石/粗铁/粗金/钻石放进投放槽充能
- 检查 `item` 是否和实际方块 ID 一致
- 打开 `debug: true` 看控制台

**Q：机器放了但不干活？**
- 先看界面里的“开关”是不是“运行中”，再确认动力不为 0
- 收割机只收**已经成熟**的作物；洒水机只浇**没满水**的花盆/耕地；
  施肥机要仓储里**有肥料**、花盆**还能施肥**
- 洒水机还要有水：接一根水管到水泵上（推荐），或在界面里投水桶，
  或让它离水源 2 格以内
- 机器只处理**已加载区块**内的目标，人跑远了自然就停了

**Q：洒水机还要一直投水桶吗？**
不用。把水管接到洒水机旁边（或让它直接贴着水泵），水泵每个周期就会往水箱里灌水，
灌满就停；只要水泵有动力、有水源，这台机器就能一直浇下去。
水桶只是“附近没水、又懒得铺管道”时的临时方案。

**Q：洒水机接了水管，为什么水箱还是不满？**
- 看水泵的水量：`pump-rate` 太少、`push-rate` 太低，或者水源被抽干（`source.consume: true`）
- 看管网规模：一台水泵默认最多带 16 个取水点（`network.max-sprinklers`），超出的不供水
- 看水管是否真的接上了：右键水管，状态应为“已接入”；只能上下左右前后贴着，斜角不算
- 洒水机被拆过又放回来时，水箱是空的（水量不跟机器走），等它自己灌满即可

**Q：机器里的东西去哪了？**
- 收割机/洒水机的仓储只出不进，拆掉机器时仓储里的东西会一起掉在原地
- 动力和水量不会跟着机器走，拆了就是没了（和水泵一致）

**Q：为什么铁锭/金锭不能充能？**
默认配置把 `minecraft:iron_ingot`、`minecraft:gold_ingot` 放进了
`charge.blocked-items`，避免刷取物被当成燃料。需要调整就改这个列表。
粗铁/粗金默认是允许的。

**Q：洒水器不加满？**
- 洒水器自身有 `storage` 上限，到了就不再加
- 检查 `push-rate` 和 `network.max-sprinklers`
- 检查洒水器是否 `infinite: true`

**Q：水管不连？**
- 只能上下左右前后贴着，斜角不算
- 水管不能穿过洒水器继续延伸；洒水器是终点
- 右键水管查看“状态”是否为“已接入”

**Q：水管外观不跟着连接变化？**
- 确认 `pipes.*.connections` 为 `true`，且已安装插件自带的 CraftEngine 资源包
- 资源包版本变化时插件会自动覆盖一次；如果手动改过模型，记得
  `craftengine-pack.overwrite` 的行为（默认不会覆盖你的改动）
- 控制台出现“找不到水管连接模型”就是资源包没更新

**Q：右键水泵没反应 / 之前能看信息，放一会就不行了？**
- 旧版本用 `ExistenceForm.ANY` 判断方块，会把脚下方块误当成设备 ID，
  导致记录被误删；1.1.0 之后改成按方块 ID 判断
- 现在右键还会做一次自愈登记，记录丢失也会自动补回来
- 如果仍然没反应，确认脚下区块已加载，并打开 `debug: true` 看日志

**Q：换了 item ID 后旧设备失效？**
CustomCrops 的 `Registries.BLOCKS` 没有注销方法，旧 ID 会保留到重启。
修改 `item` 后请重启服务器，并重新放置旧设备。

**Q：从实体收割机那个版本升级上来，之前放的收割机没了？**
实体收割机已经整体删除，机器的数据表结构也换了（旧的 UUID 表会被清理）。
用 `/ccpump give harvester` 重新拿一台方块机器放下即可，新机器是真实方块，
不再需要矿车，也不会再丢实体。

## 构建

```bash
cd CustomCropsPump
mvn clean package
```

产物：`target/Liu-CustomCropsPump-1.1.2.jar`

编译依赖：

- Paper API `26.2.build.121-stable`（provided）
- CustomCrops API `3.6.52`（provided，运行时使用服务器上的 3.6.x）

## 目录结构

```
src/main/java/com/liu/ccpump/
  CustomCropsPumpPlugin.java     插件入口、ID 注册、任务调度
  block/PumpPipeBlock.java       水泵/水管的放置/破坏/交互转发
  block/MachineBlock.java        三台机器的放置/破坏/交互转发
  manager/PumpManager.java       抽水、BFS 管网、补水、校验、水管外观切换
  machine/                       机器：状态、配置、调度、范围扫描、三种行为
    behavior/HarvesterBehavior   收割
    behavior/SprinklerBehavior   洒水
    behavior/FertilizerBehavior  施肥
  gui/                           水泵界面、机器界面
  config/                        配置读取
  data/                          SQLite 持久化（data.db）
  command/PumpCommand.java       命令与 Tab 补全
  listener/                      CustomCrops 重载、洒水器连接、右键、掉落捕获
  util/                          消息、界面物品、水源查找、CraftEngine 资源包安装
src/main/resources/craftengine/ccpump/
  pack.yml
  configuration/items.yml          CraftEngine 方块定义（水泵 + 水管 + 三台机器）
  configuration/categories.yml
  configuration/recipes.yml
  resourcepack/assets/ccpump/models/block/
    water_pump.json
    broken_pump.json
    harvester.json                收割机
    sprinkler_machine.json        洒水机
    fertilizer_machine.json       施肥机
    water_pipe.json               物品图标（完整十字）
    pipe/water_pipe_*.json        4 种连接形态
tools/generate_pipe_variants.py   生成上述模型与 items.yml / recipes.yml / categories.yml
```
