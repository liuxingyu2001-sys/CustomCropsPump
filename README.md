# CustomCropsPump

给 [CustomCrops](https://github.com/Xiao-MoMi/Custom-Crops) 的洒水器自动供水的水泵 / 水管附属插件。

- 水泵自动从附近的水源方块抽水（也支持水桶手动补水）
- 水泵需要动力：右键填充红石、青金石、粗铁、粗金或钻石
- 水管把水泵和洒水器连接成管网
- 每隔一秒自动把水送进相连的洒水器，洒水器继续按 CustomCrops 原本的逻辑工作
- 独立插件，**不修改** CustomCrops 源码，也不占用 CustomCrops 的方块状态
- 自带一套 CraftEngine 简易铜质模型（可自动安装，也可以完全不用）

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
- 水管本身不储水，只是“导线”
- 洒水器配置里 `infinite: true` 的洒水器会被跳过
- 水泵工作会消耗动力，动力耗尽后停止抽水和补水

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
> 改成你服务器上真实存在的家具/方块 ID（ItemsAdder、Oraxen、Nexo 等都可以），
> 并把 `craftengine-pack.auto-install` 设为 `false`。

## 使用

1. 在水边放水泵（默认要求半径 2 格内有水）
2. 在水泵和洒水器之间铺水管（每根水管都要和相邻的水管/水泵/洒水器贴着）
3. 右键水泵查看水量、动力、管网、水源状态
4. 手持水桶右键水泵可以手动补水
5. 手持红石、青金石、粗铁、粗金或钻石右键水泵可以充能
6. 潜行 + 右键水泵可以开关它

> 铁锭、金锭默认**不能**充能（部分服务器可以刷取）；请使用粗铁、粗金。
> 手持燃料右键 = 充能；空手或手持非燃料物品潜行右键 = 开关。
> 创造模式填充动力不消耗物品。

默认燃料数值：红石 50、青金石 100、粗铁 200、粗金 250、钻石 1000，
一台默认水泵每秒工作消耗 1 点动力。

鼠标右键水管可以查看它所在管网连接了多少水泵、多少洒水器。

## 命令与权限

| 命令 | 说明 | 权限 |
| --- | --- | --- |
| `/ccpump help` | 查看帮助 | 无 |
| `/ccpump reload` | 重载 config.yml / messages.yml | `customcropspump.admin` |
| `/ccpump save` | 立即保存 data.yml | `customcropspump.admin` |
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
| `validate-interval` | `1200` | 校验家具是否存在的间隔（tick） |
| `network-cache-ticks` | `100` | 管网 BFS 缓存时间（tick） |
| `debug` | `false` | 输出调试日志 |

每台水泵：

| 配置 | 默认 | 说明 |
| --- | --- | --- |
| `item` | `ccpump:water_pump` | 家具/方块 ID |
| `capacity` | `12` | 水箱容量 |
| `pump-rate` | `2` | 每周期抽水量 |
| `push-rate` | `2` | 每周期给单个洒水器的补水量 |
| `bucket-amount` | `4` | 一桶水补充量 |
| `charge-capacity` | `1000` | 动力容量；`0` = 不需要动力 |
| `charge-per-cycle` | `1` | 每完成一次实际工作消耗的动力 |
| `allow-toggle` | `true` | 允许潜行右键开关 |
| `source.required` | `true` | `false` = 无限水，不需要水源 |
| `source.radius` | `2` | 水源检测半径 |
| `source.consume` | `false` | 抽水是否消耗水源方块 |
| `network.max-pipes` | `64` | 单台水泵最多水管数 |
| `network.max-sprinklers` | `16` | 单台水泵最多洒水器数 |
| `network.max-distance` | `32` | 水泵到最远管网节点的直线距离 |
| `effects.*` | 见文件 | 工作粒子 / 声音 |

### 动力/充能

`config.yml` 顶部的 `charge` 段是**全局**充能表，所有水泵共用：

```yaml
charge:
  fuels:
    redstone:
      item: minecraft:redstone
      amount: 50
    lapis_lazuli:
      item: minecraft:lapis_lazuli
      amount: 100
    raw_iron:
      item: minecraft:raw_iron
      amount: 200
    raw_gold:
      item: minecraft:raw_gold
      amount: 250
    diamond:
      item: minecraft:diamond
      amount: 1000
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

每新增一种物品 ID，建议 `item` 使用你资源包里真实存在的家具 ID；
修改 item ID 后需要重启服务器，因为 CustomCrops 的注册表只增不减；
如果新增的是 CraftEngine 物品，还要执行 `/ce reload all` 让物品定义生效。

## 数据

水泵水量、开关状态、水泵/水管坐标保存在：

```
plugins/CustomCropsPump/data.yml
```

不写入 CustomCrops 的区块数据，所以 `/cc reload`、区块卸载、重启都不会丢。
如果水泵/水管被爆炸或 WorldEdit 等非玩家方式移除，插件会在
`validate-interval` 内自动清理失效记录。

从 1.0.0 升级到 1.1.0 时，旧 `data.yml` 里没有 `charge` 字段的水泵会
自动补满一次动力；新放置的水泵从 0 动力开始，需要先充能。

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

**Q：换了 item ID 后旧水泵失效？**
CustomCrops 的 `Registries.BLOCKS` 没有注销方法，旧 ID 会保留到重启。
修改 `item` 后请重启服务器，并重新放置旧水泵。

## 构建

```bash
cd CustomCropsPump
mvn clean package
```

产物：`target/Liu-CustomCropsPump-1.1.0.jar`

编译依赖：

- Paper API `26.2.build.121-stable`（provided）
- CustomCrops API `3.6.52`（provided，运行时使用服务器上的 3.6.x）

## 目录结构

```
src/main/java/com/liu/ccpump/
  CustomCropsPumpPlugin.java     插件入口、ID 注册、任务调度
  block/PumpPipeBlock.java       接入 CustomCrops 的放置/破坏/交互
  manager/PumpManager.java       抽水、BFS 管网、补水、校验
  config/                        配置读取
  data/                          data.yml 持久化
  command/PumpCommand.java       命令与 Tab 补全
  listener/                      CustomCrops 重载监听
  util/                          消息、CraftEngine 资源包安装
src/main/resources/craftengine/ccpump/
  pack.yml
  configuration/items.yml        CraftEngine 物品/家具定义
  configuration/categories.yml
  resourcepack/assets/ccpump/models/block/*.json
```
