package com.liu.ccpump.data;

import com.liu.ccpump.CustomCropsPumpPlugin;
import com.liu.ccpump.config.PumpConfig;
import com.liu.ccpump.manager.PumpManager;

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

    private static final String UPSERT_PUMP = """
            INSERT OR REPLACE INTO pumps (world, x, y, z, type, water, charge, enabled)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)""";

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
    public void load(PumpManager manager) {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            plugin.getLogger().warning("无法创建插件数据目录: " + parent);
        }
        try (Connection connection = open()) {
            try (Statement statement = connection.createStatement()) {
                statement.execute(CREATE_PUMPS);
                statement.execute(CREATE_PIPES);
            }
            loadPumps(connection, manager);
            loadPipes(connection, manager);
            this.available = true;
        } catch (SQLException | RuntimeException error) {
            this.available = false;
            warnOnce(error);
        }
    }

    private void loadPumps(Connection connection, PumpManager manager) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT world, x, y, z, type, water, charge, enabled FROM pumps");
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
     * 保存当前全部水泵/水管状态。整批写入在一个事务里完成，失败整体回滚。
     */
    public void save(PumpManager manager) {
        Collection<PumpInstance> pumps = manager.pumpInstances();
        Collection<PipeInstance> pipes = manager.pipeInstances();
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                savePumps(connection, pumps);
                savePipes(connection, pipes);
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
