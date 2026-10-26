package com.liu.ccpump.data;

import com.liu.ccpump.CustomCropsPumpPlugin;
import com.liu.ccpump.config.MachineConfig;
import com.liu.ccpump.config.PumpConfig;
import com.liu.ccpump.manager.PumpManager;
import com.liu.ccpump.machine.MachineManager;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collection;

/**
 * 使用 SQLite 持久化水泵/水管数据（{@code data.db}）。
 *
 * <p>为什么不用 yml：YAML 保存是“全量重写 + 同步写盘”，物品一多单次保存会阻塞
 * 主线程几百毫秒甚至更久，而且每次都要重建整个文档树。SQLite 只写入变化的行，
 * 单条记录的开销和总条目数无关。
 *
 * <p>读写策略：内存里仍维护一份 {@code Map}（见 {@link PumpManager}），运行期查询
 * 完全不碰数据库；启动时一次性读进内存，定时保存时把当前状态每行
 * {@code INSERT OR REPLACE} 一遍并删掉多余记录，整体在一个事务里提交。
 *
 * <p>驱动由 Paper 按 {@code plugin.yml} 的 {@code libraries} 自动下载，不打进 jar。
 * 驱动不可用时降级为“不持久化”，只告警一次，不影响水泵正常工作。
 */
public final class DataStore {

    private static final String CREATE_PUMPS = """
            CREATE TABLE IF NOT EXISTS pumps (
                world   TEXT    NOT NULL,
                x       INTEGER NOT NULL,
                y       INTEGER NOT NULL,
                z       INTEGER NOT NULL,
                type    TEXT    NOT NULL,
                water   INTEGER NOT NULL,
                charge  INTEGER NOT NULL,
                enabled INTEGER NOT NULL,
                wear    INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (world, x, y, z)
            )""";

    private static final String CREATE_PIPES = """
            CREATE TABLE IF NOT EXISTS pipes (
                world TEXT    NOT NULL,
                x     INTEGER NOT NULL,
                y     INTEGER NOT NULL,
                z     INTEGER NOT NULL,
                type  TEXT    NOT NULL,
                PRIMARY KEY (world, x, y, z)
            )""";

    private static final String CREATE_MACHINES = """
            CREATE TABLE IF NOT EXISTS machines (
                world   TEXT    NOT NULL,
                x       INTEGER NOT NULL,
                y       INTEGER NOT NULL,
                z       INTEGER NOT NULL,
                type    TEXT    NOT NULL,
                charge  INTEGER NOT NULL,
                enabled INTEGER NOT NULL,
                water   INTEGER NOT NULL DEFAULT 0,
                buffer  BLOB,
                PRIMARY KEY (world, x, y, z)
            )""";

    private static final String UPSERT_MACHINE = """
            INSERT OR REPLACE INTO machines (world, x, y, z, type, charge, enabled, water, buffer)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""";

    private static final String UPSERT_PUMP = """
            INSERT OR REPLACE INTO pumps (world, x, y, z, type, water, charge, enabled, wear)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""";

    private static final String UPSERT_PIPE = """
            INSERT OR REPLACE INTO pipes (world, x, y, z, type)
            VALUES (?, ?, ?, ?, ?)""";

    private final CustomCropsPumpPlugin plugin;
    private final File file;

    private boolean available;
    private boolean warned;

    public DataStore(CustomCropsPumpPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "data.db");
    }

    /**
     * 建表并读入内存。数据库不可用时只告警，插件照常运行（关服不保存）。
     */
    public void load(PumpManager manager, MachineManager machines) {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            plugin.getLogger().warning("无法创建插件数据目录: " + parent);
        }
        try (Connection connection = open()) {
            try (Statement statement = connection.createStatement()) {
                statement.execute(CREATE_PUMPS);
                statement.execute(CREATE_PIPES);
                migrateMachinesTable(connection);
                statement.execute(CREATE_MACHINES);
                addColumnIfMissing(connection, "pumps", "wear", "INTEGER NOT NULL DEFAULT 0");
            }
            loadPumps(connection, manager);
            loadPipes(connection, manager);
            loadMachines(connection, machines);
            this.available = true;
        } catch (SQLException | RuntimeException error) {
            this.available = false;
            warnOnce(error);
        }
    }

    /**
     * 给已存在的老库补列。
     *
     * <p>{@code CREATE TABLE IF NOT EXISTS} 不会给已经建好的表加字段，
     * 所以升级插件后旧 {@code data.db} 会缺 {@code wear} 列，直接查就会
     * {@code no such column}。这里逐列尝试一次，已经存在就跳过。
     */
    private void addColumnIfMissing(Connection connection, String table, String column, String definition) {
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
            plugin.getLogger().info("已为数据表 " + table + " 补上列 " + column + "。");
        } catch (SQLException ignored) {
            // 列已经存在：正常情况，不做任何处理
        }
    }

    private void loadPumps(Connection connection, PumpManager manager) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT world, x, y, z, type, water, charge, enabled, wear FROM pumps");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                String type = rows.getString("type");
                PumpConfig pumpConfig = plugin.configManager().pump(type);
                if (pumpConfig == null) {
                    plugin.getLogger().warning("数据库中的水泵类型 " + type + " 已不存在，已跳过该水泵。");
                    continue;
                }
                BlockKey key = new BlockKey(rows.getString("world"),
                        rows.getInt("x"), rows.getInt("y"), rows.getInt("z"));
                PumpInstance instance = new PumpInstance(key, type);
                instance.water(Math.min(pumpConfig.capacity(), Math.max(0, rows.getInt("water"))));
                int capacity = pumpConfig.chargeCapacity();
                instance.charge(capacity <= 0
                        ? 0
                        : Math.min(capacity, Math.max(0, rows.getInt("charge"))));
                instance.enabled(rows.getInt("enabled") != 0);
                instance.wear(Math.max(0, rows.getInt("wear")));
                manager.addLoadedPump(instance);
            }
        }
    }

    private void loadPipes(Connection connection, PumpManager manager) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT world, x, y, z, type FROM pipes");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                String type = rows.getString("type");
                if (plugin.configManager().pipe(type) == null) {
                    continue;
                }
                BlockKey key = new BlockKey(rows.getString("world"),
                        rows.getInt("x"), rows.getInt("y"), rows.getInt("z"));
                manager.addLoadedPipe(new PipeInstance(key, type));
            }
        }
    }

    /**
     * 旧版（实体收割机）的 machines 表主键是 UUID，和“按方块坐标”的新表不兼容。
     *
     * <p>建表前先看表结构：有 {@code id} 列就是旧表，直接删掉重建。
     * 旧表里只有开发期的测试数据，删掉不会影响玩家。
     */
    private void migrateMachinesTable(Connection connection) throws SQLException {
        boolean legacy = false;
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("PRAGMA table_info(machines)")) {
            while (rows.next()) {
                if ("id".equalsIgnoreCase(rows.getString("name"))) {
                    legacy = true;
                }
            }
        }
        if (!legacy) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE machines");
        }
        plugin.getLogger().warning("检测到旧版（实体收割机）机器数据表，已清理并改为按方块坐标记录。");
    }

    /**
     * 读入机器记录。
     *
     * <p>只恢复数据，不碰方块：方块是否还在由 {@code MachineManager} 定期校验。
     */
    private void loadMachines(Connection connection, MachineManager manager) throws SQLException {
        if (manager == null) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT world, x, y, z, type, charge, enabled, water, buffer FROM machines");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                String type = rows.getString("type");
                MachineConfig machineConfig = plugin.configManager().machine(type);
                if (machineConfig == null) {
                    plugin.getLogger().warning("数据库中的机器类型 " + type + " 已不存在，已跳过该机器。");
                    continue;
                }
                BlockKey key = new BlockKey(rows.getString("world"),
                        rows.getInt("x"), rows.getInt("y"), rows.getInt("z"));
                int slots = machineConfig.bufferSlots();
                // 一定要按配置的格数先分配好：buffer 列是 NULL（还没存过东西）
                // 的时候如果给个长度 0 的数组，机器就永远存不进任何物品。
                ItemStack[] buffer = new ItemStack[slots];
                byte[] raw = rows.getBytes("buffer");
                if (raw != null) {
                    ItemStack[] restored = MachineCodec.decode(raw, slots);
                    System.arraycopy(restored, 0, buffer, 0, Math.min(restored.length, slots));
                }
                int capacity = machineConfig.chargeCapacity();
                int charge = capacity <= 0 ? 0 : Math.min(capacity, Math.max(0, rows.getInt("charge")));
                int water = Math.min(Math.max(0, machineConfig.water().capacity()),
                        Math.max(0, rows.getInt("water")));
                manager.addLoaded(new MachineData(key, type, buffer, charge, water,
                        rows.getInt("enabled") != 0));
            }
        }
    }

    /**
     * 保存当前全部水泵/水管状态。整批写入在一个事务里完成，失败整体回滚。
     */
    public void save(PumpManager manager, MachineManager machineManager) {
        Collection<PumpInstance> pumps = manager.pumpInstances();
        Collection<PipeInstance> pipes = manager.pipeInstances();
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                savePumps(connection, pumps);
                savePipes(connection, pipes);
                if (machineManager != null) {
                    saveMachines(connection, machineManager);
                }
                connection.commit();
                this.available = true;
            } catch (SQLException | RuntimeException error) {
                connection.rollback();
                throw error;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException | RuntimeException error) {
            warnOnce(error);
        }
    }

    private void savePumps(Connection connection, Collection<PumpInstance> pumps) throws SQLException {
        // 先清空再整批写入：一次性事务，既保证“拆掉的水泵不会留在库里”，
        // 又只做两次固定开销的批量操作，和条目数线性相关（不像 YAML 要重建整个文档树）
        try (Statement clear = connection.createStatement()) {
            clear.execute("DELETE FROM pumps");
        }
        try (PreparedStatement insert = connection.prepareStatement(UPSERT_PUMP)) {
            for (PumpInstance pump : pumps) {
                BlockKey key = pump.key();
                insert.setString(1, key.world());
                insert.setInt(2, key.x());
                insert.setInt(3, key.y());
                insert.setInt(4, key.z());
                insert.setString(5, pump.typeKey());
                insert.setInt(6, pump.water());
                insert.setInt(7, pump.charge());
                insert.setInt(8, pump.enabled() ? 1 : 0);
                insert.setInt(9, pump.wear());
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    private void savePipes(Connection connection, Collection<PipeInstance> pipes) throws SQLException {
        try (Statement clear = connection.createStatement()) {
            clear.execute("DELETE FROM pipes");
        }
        try (PreparedStatement insert = connection.prepareStatement(UPSERT_PIPE)) {
            for (PipeInstance pipe : pipes) {
                BlockKey key = pipe.key();
                insert.setString(1, key.world());
                insert.setInt(2, key.x());
                insert.setInt(3, key.y());
                insert.setInt(4, key.z());
                insert.setString(5, pipe.typeKey());
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    private void saveMachines(Connection connection, MachineManager manager) throws SQLException {
        try (Statement clear = connection.createStatement()) {
            clear.execute("DELETE FROM machines");
        }
        Collection<MachineData> machines = manager.machines();
        if (machines.isEmpty()) {
            return;
        }
        try (PreparedStatement insert = connection.prepareStatement(UPSERT_MACHINE)) {
            for (MachineData machine : machines) {
                BlockKey key = machine.key();
                insert.setString(1, key.world());
                insert.setInt(2, key.x());
                insert.setInt(3, key.y());
                insert.setInt(4, key.z());
                insert.setString(5, machine.typeKey());
                insert.setInt(6, machine.charge());
                insert.setInt(7, machine.enabled() ? 1 : 0);
                insert.setInt(8, machine.water());
                insert.setBytes(9, MachineCodec.encode(machine.buffer(), machine.bufferSlots()));
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    /**
     * 是否成功初始化。为 false 表示数据库不可用，数据不会持久化。
     */
    public boolean available() {
        return available;
    }

    /**
     * 打开连接。
     *
     * <p>SQLite 没有连接池，每次操作开一个新连接是常规用法；WAL 让读写并发更顺畅，
     * {@code busy_timeout} 避免偶发锁冲突直接抛异常。{@code synchronous=NORMAL}
     * 在 WAL 下已经足够安全，比 FULL 快很多。
     */
    private Connection open() throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=NORMAL");
            statement.execute("PRAGMA busy_timeout=3000");
        }
        return connection;
    }

    private void warnOnce(Throwable error) {
        if (this.warned) {
            return;
        }
        this.warned = true;
        plugin.getLogger().warning("SQLite 数据库不可用，水泵数据将不会保存: " + error.getMessage());
        plugin.getLogger().warning("请确认服务器能下载 org.xerial:sqlite-jdbc（plugin.yml 的 libraries 段）。");
    }
}
