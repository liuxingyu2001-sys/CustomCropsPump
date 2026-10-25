# CustomCropsPump

给 [CustomCrops](https://github.com/Xiao-MoMi/Custom-Crops) 的洒水器自动供水的水泵 / 水管附属插件。

- 水泵自动从附近的水源方块抽水（也支持水桶手动补水）
- 水泵需要动力：右键填充红石、青金石、粗铁、粗金或钻石
- 右键水泵打开操作界面，查看水量 / 动力 / 管网 / 水源，并在界面里开关水泵
- 水泵和水管都是**实体家具**：有 shulker 碰撞箱（走不过去、可以站上去），支持在地面、墙面、天花板右键放置
- 水管把水泵和洒水器连接成管网，按连接方向自动切换外观（横向 / 纵向 / 六面管子）
- 每隔一秒自动把水送进相连的洒水器，洒水器继续按 CustomCrops 原本的逻辑工作
- 独立插件，**不修改** CustomCrops 源码，也不占用 CustomCrops 的方块状态
- 自带一套 CraftEngine 铜质模型（可自动安装，也可以完全不用）

## 工作原理

连接规则是 6 方向（上、下、左、右、前、后），斜角不算：

```
水源
  │
[水泵] —— [水管] —— [水管] —— [洒水器]
                              │
                           [洒水器]

[水泵] —— [洒水器]        ← 水泵直接贴着洒水器也可以
```

- 水泵每 `pump-rate` 从半径 `source.radius` 内寻找水源方块，装进水箱（`capacity`）
- 一台水泵从自己出发，沿相邻水管 BFS，找到所有相邻的洒水器
- 每个周期给每个洒水器补 `push-rate` 点水，直到洒水器达到它的 `storage` 上限
- 水管本身不储水，只是“导线”；它按相邻的水管/水泵/洒水器自动切换成对应的连接模型
- 洒水器配置里 `infinite: true` 的洒水器会被跳过
- 水泵工作会消耗动力，动力耗尽后停止抽水和补水
- 水泵和水管都是 CraftEngine **实体家具**（furniture）：
  - 用 shulker 碰撞箱提供碰撞，走不过去、也能站上去
  - 可以在**地面、墙面、天花板**上右键直接放置（按点击的面自动选对应的变体）
  - 实体不是方块，所以不挡水、也不占用原版方块状态
  - 不占方块状态的好处：想加多少外观形态都不会受槽位数量限制

水管不能当洒水器用，洒水器也不能继续当管道延伸。

## 安装

1. 服务端：Paper / Leaf 1.21+（需要 Java 21+；不支持 Folia）
2. 安装 **CustomCrops 3.6.x**，并确认 `plugins/CustomCrops/contents/sprinklers/` 里已有可用的洒水器配置
3. 把 `Liu-CustomCropsPump-x.y.z.jar` 放进 `plugins/`
4. 启动服务器
5. 如果使用 CraftEngine：插件会把自带资源包复制到
   `plugins/CraftEngine/resources/ccpump/`，然后执行：

   ```
   /ce reload all
   ```

   首次安装建议直接重启服务器，让资源包完整生成。
   以后更新插件时，如果模型/物品配置有变化，需要把
   `craftengine-pack.overwrite` 设为 `true`，或删除
   `plugins/CraftEngine/resources/ccpump/` 后再重启，否则会保留旧文件。

6. 执行 `/ccpump give water_pump`、`/ccpump give water_pipe` 各拿一个物品测试
   （生存服可以再自行添加合成配方，或把物品放进商店/任务奖励）

> 不使用 CraftEngine 也可以：把 `config.yml` 里的 `pumps.*.item` / `pipes.*.item`
> 改成你服务器上真实存在的家具 ID（ItemsAdder、Oraxen、Nexo 等自定义家具也可以），
> 并把 `craftengine-pack.auto-install` 设为 `false`。

## 使用

1. 在水边放水泵（默认要求半径 2 格内有水）
2. 在水泵和洒水器之间铺水管（每根水管都要和相邻的水管/水泵/洒水器贴着）
3. 右键水泵打开界面：水量、动力、管网、水源一目了然，点“开关”按钮切换水泵
4. 手持水桶右键水泵可以手动补水
5. 手持红石、青金石、粗铁、粗金或钻石右键水泵可以充能
6. 潜行 + 右键水泵可以快速开关（不打开界面）

> 铁锭、金锭默认**不能**充能（部分服务器可以刷取）；请使用粗铁、粗金。
> 手持燃料右键 = 充能；空手右键 = 打开界面；潜行 + 空手右键 = 开关。
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

> 界面里的数据每秒刷新一次；水泵被拆掉时界面会自动关闭。
> 需要 `customcropspump.use` 权限才能打开界面和点按钮。

## 命令与权限

| 命令 | 说明 | 权限 |
| --- | --- | --- |
| `/ccpump help` | 查看帮助 | 无 |
| `/ccpump reload` | 重载 config.yml / messages.yml | `customcropspump.admin` |
| `/ccpump save` | 立即写入数据库 | `customcropspump.admin` |
| `/ccpump info` | 查看已登记的水泵/水管数量 | `customcropspump.admin` |
| `/ccpump give <类型> [玩家] [数量]` | 给予水泵/水管 | `customcropspump.admin` |

- `customcropspump.admin` 默认 OP
- `customcropspump.use` 默认所有玩家，控制补水、开关、查看信息

`/ccpump give` 的“类型”是 `config.yml` 里 `pumps` / `pipes` 下的键名，例如
`water_pump`、`water_pipe`；如果只配置了一种，也可以直接写 `pump` / `pipe`。

## 配置

`config.yml` 主要项：

| 配置 | 默认 | 说明 |
| --- | --- | --- |
| `tick-interval` | `20` | 主循环间隔（tick），20 = 1 秒 |
| `save-interval` | `6000` | 自动保存间隔（tick），0 = 只在关服时保存 |
| `source-check-interval` | `40` | 重新检测水源的间隔（tick） |
| `validate-interval` | `1200` | 校验水泵/水管还在不在的间隔（tick） |
| `network-cache-ticks` | `100` | 管网 BFS 缓存时间（tick） |
| `debug` | `false` | 输出调试日志 |

每台水泵：

| 配置 | 默认 | 说明 |
| --- | --- | --- |
| `item` | `ccpump:water_pump` | 家具物品 ID |
| `capacity` | `12` | 水箱容量 |
| `pump-rate` | `2` | 每周期抽水量 |
| `push-rate` | `2` | 每周期给单个洒水器的补水量 |
| `bucket-amount` | `4` | 一桶水补充量 |
| `charge-capacity` | `100` | 动力容量；`0` = 不需要动力 |
| `charge-per-cycle` | `1` | 每完成一次实际工作消耗的动力 |
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
| `item` | `ccpump:water_pipe` | 玩家拿到的水管物品 / 家具 ID |
| `connections` | `true` | 是否按连接方向切换外观 |
| `variant-format` | `{item}_{shape}` | 形态家具 ID 规则，`{shape}` = 形态名 |

水管外观区分 10 种形态（不是 64 种连接组合），覆盖所有连接情况：

| 形态名 | 外观 | 使用条件 |
| --- | --- | --- |
| `straight_ew` | 横向直管（东西向） | 只有东西两边连接 |
| `straight_ns` | 横向直管（南北向） | 只有南北两边连接 |
| `vertical` | 纵向直管（上下向） | 只有上下两边连接 |
| `end_east` | 端点（伸向东） | 只连了东边一个东西 |
| `end_west` | 端点（伸向西） | 只连了西边一个东西 |
| `end_up` | 端点（伸向上） | 只连了上边一个东西 |
| `end_down` | 端点（伸向下） | 只连了下边一个东西 |
| `end_south` | 端点（伸向南） | 只连了南边一个东西 |
| `end_north` | 端点（伸向北） | 只连了北边一个东西 |
| `cross` | 六面管子 | 拐角、T 形、十字、完全没接 |

也就是说：管道尽头（比如只接一个洒水器）会显示成一根伸出的单臂管，
中间段显示成直管或六面管子，不会出现“尽头也是十字”的怪样子。

`connections` 关闭、或形态方块不存在时，水管会一直用 `item` 自己的模型，
不会影响正常供水。换成 ItemsAdder/Oraxen 等没有这些形态的物品时，把
`connections` 设为 `false` 即可。

### 动力/充能

`config.yml` 顶部的 `charge` 段是**全局**充能表，所有水泵共用：

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
- 动力只在**真正抽水或补水**时消耗，水泵空转/水箱已满/洒水器已满时不掉动力
- `charge-capacity: 0` 可以关掉动力系统，变成无限动力
- 旧版 `config.yml` 没有 `charge` 段时，插件会使用内置默认表并在控制台提示；
  想自定义就把上面的 `charge` 段复制进 `config.yml`

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

每新增一种物品 ID，建议 `item` 使用你资源包里真实存在的方块 ID；
修改 item ID 后需要重启服务器，因为 CustomCrops 的注册表只增不减；
如果新增的是 CraftEngine 物品，还要执行 `/ce reload all` 让物品定义生效。

## 数据

水泵水量、开关状态、水泵/水管坐标保存在 SQLite 数据库：

```
plugins/CustomCropsPump/data.db
```

用 SQLite 而不是 yml，是因为 yml 保存要全量重写整个文件、开销随条目数线性增长，
水泵多了以后单次保存会明显卡主线程；SQLite 批量写入一个事务即可，几百条记录
通常在几毫秒内完成。运行期查询走内存，只有启动读取和定时保存才碰数据库。

驱动由 Paper 按 `plugin.yml` 的 `libraries` 自动下载（`org.xerial:sqlite-jdbc`），
第一次启动需要能联网；下载失败时插件仍可正常运行，只是数据不会持久化，日志会提示。

数据表结构：

| 表 | 字段 |
| --- | --- |
| `pumps` | `world, x, y, z`（主键）, `type, water, charge, enabled` |
| `pipes` | `world, x, y, z`（主键）, `type` |

不写入 CustomCrops 的区块数据，所以 `/cc reload`、区块卸载、重启都不会丢。
如果水泵/水管被爆炸或 WorldEdit 等非玩家方式移除，插件会在
`validate-interval` 内自动清理失效记录。

这是一个全新插件，**不提供旧版本数据迁移**：`data.yml` 已彻底移除，
换用 `data.db`。新放置的水泵从 0 动力开始，需要先充能。

当前版本说明：

- 水泵/水管是 **CraftEngine 实体家具**，支持在地面、墙面、天花板三种位置放置
- 水管外观从 64 种连接变体精简成 10 种形态
  （直管×3 + 单臂端点×6 + 六面管子），
  旧的 `ccpump:water_pipe_<掩码>` 方块 ID 不再使用
- CustomCrops 的注册表只增不减，**必须重启服务器**（`/ce reload` 不够）
- 资源包有变化：把 `craftengine-pack.overwrite` 设为 `true`，或删除
  `plugins/CraftEngine/resources/ccpump/` 后重启，再执行 `/ce reload all`

## 常见问题

**Q：水泵不抽水？**
- 右键水泵看“水源”是否为“有”；没有的话把水泵放到离水 2 格以内，或把
  `source.required` 改成 `false`
- 看“动力”是不是 0；手持红石/青金石/粗铁/粗金/钻石右键充能
- 检查 `item` 是否和实际家具 ID 一致
- 打开 `debug: true` 看控制台

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
- 更新插件后旧资源包不会被覆盖：把 `craftengine-pack.overwrite` 设为 `true`，
  或删除 `plugins/CraftEngine/resources/ccpump/` 后重启，再执行 `/ce reload all`
- 控制台出现“找不到水管连接模型”就是资源包没更新

**Q：从旧版本（家具版）升级后，之前放的水泵/水管没了？**
旧版本把水泵/水管放成了 CraftEngine 家具实体，新版本改成了真实方块。
插件会在校验时原地点检：把残留的家具实体换成对应的方块（数据不丢）；
也可以直接拆掉重放。升级后必须**重启服务器**，因为方块 ID 有变化。

**Q：右键水泵没反应 / 之前能看信息，放一会就不行了？**
- 旧版本用 `ExistenceForm.ANY` 判断家具，会把脚下方块误当成家具 ID，
  导致水泵/水管记录被误删；1.1.0 之后改成按方块 ID 判断
- 现在右键还会做一次自愈登记，记录丢失也会自动补回来
- 如果仍然没反应，确认脚下区块已加载，并打开 `debug: true` 看日志

**Q：换了 item ID 后旧水泵失效？**
CustomCrops 的 `Registries.BLOCKS` 没有注销方法，旧 ID 会保留到重启。
修改 `item` 后请重启服务器，并重新放置旧水泵。

## 构建

```bash
cd CustomCropsPump
mvn clean package
```

产物：`target/Liu-CustomCropsPump-1.1.1.jar`

编译依赖：

- Paper API `26.2.build.121-stable`（provided）
- CustomCrops API `3.6.52`（provided，运行时使用服务器上的 3.6.x）

## 目录结构

```
src/main/java/com/liu/ccpump/
  CustomCropsPumpPlugin.java     插件入口、ID 注册、任务调度
  block/PumpPipeBlock.java       接入 CustomCrops 的放置/破坏/交互
  manager/PumpManager.java       抽水、BFS 管网、补水、校验、水管外观切换
  gui/                           右键水泵打开的界面
  config/                        配置读取
  data/                          SQLite 持久化（data.db）
  command/PumpCommand.java       命令与 Tab 补全
  listener/                      CustomCrops 重载、洒水器连接监听
  util/                          消息、CraftEngine 资源包安装
src/main/resources/craftengine/ccpump/
  pack.yml
  configuration/items.yml        CraftEngine 方块定义（水泵 + 基础水管）
  configuration/pipe_variants.yml  10 种水管形态家具（自动生成）
  configuration/categories.yml
  resourcepack/assets/ccpump/models/block/
    water_pump.json
    water_pipe.json              物品图标（完整十字）
    pipe/water_pipe_0..63.json   连接模型（自动生成）
tools/generate_pipe_variants.py  生成上述模型与 pipe_variants.yml
```
