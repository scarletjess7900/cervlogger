package net.cervlogger.database;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

import net.cervlogger.CervLogger;
import net.cervlogger.config.ConfigHandler;
import net.minecraft.server.MinecraftServer;

/**
 * SQLite database layer. Uses a single shared connection guarded by {@link #lock()},
 * mirroring the original plugin's SQLite consumer design.
 *
 * Schema (simplified port of CervLogger's schema):
 *   co_user        - known users (players and #environment actors)
 *   co_world       - dimension ids ("minecraft:overworld", ...)
 *   co_block       - block changes  (action: 0=break, 1=place, 2=interact, 3=explosion, 4=burn)
 *   co_container   - container transactions (action: 0=removed, 1=added)
 *   co_entity_kill - entity kills
 *   co_sign        - sign text
 *   co_chat        - chat messages
 *   co_command     - commands
 *   co_session     - login/logout (action: 0=logout, 1=login)
 */
public final class Database {

    private static final Object LOCK = new Object();
    private static Connection connection;
    private static Path databaseFile;
    private static boolean mysql;

    private static final Map<String, Integer> USER_IDS = new ConcurrentHashMap<>();
    private static final Map<Integer, String> USER_NAMES = new ConcurrentHashMap<>();
    private static final Map<String, Integer> WORLD_IDS = new ConcurrentHashMap<>();
    private static final Map<Integer, String> WORLD_NAMES = new ConcurrentHashMap<>();

    private Database() {
    }

    public static void init(MinecraftServer server) throws Exception {
        mysql = "mysql".equalsIgnoreCase(ConfigHandler.storageType);
        synchronized (LOCK) {
            if (mysql) {
                databaseFile = null;
            }
            else {
                databaseFile = ConfigHandler.dataDir().resolve("database.db");
            }
            connection = createConnection();
            String pk = mysql ? "rowid BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY" : "rowid INTEGER PRIMARY KEY AUTOINCREMENT";
            String str = mysql ? "VARCHAR(255)" : "TEXT";
            try (Statement st = connection.createStatement()) {
                if (!mysql) {
                    st.execute("PRAGMA journal_mode=WAL");
                    st.execute("PRAGMA synchronous=NORMAL");
                }

                st.execute("CREATE TABLE IF NOT EXISTS co_user (" + pk + ", time BIGINT, user " + str + " UNIQUE, uuid " + str + ")");
                st.execute("CREATE TABLE IF NOT EXISTS co_world (" + pk + ", world " + str + " UNIQUE)");

                st.execute("CREATE TABLE IF NOT EXISTS co_block (" + pk + ", time BIGINT, user INTEGER, wid INTEGER, x INTEGER, y INTEGER, z INTEGER, action INTEGER, type TEXT, old_type TEXT, rolled_back INTEGER DEFAULT 0)");
                createIndex(st, "co_block_coords", "co_block(wid, x, z, y, time)");
                createIndex(st, "co_block_user", "co_block(user, time)");
                createIndex(st, "co_block_time", "co_block(time)");

                st.execute("CREATE TABLE IF NOT EXISTS co_container (" + pk + ", time BIGINT, user INTEGER, wid INTEGER, x INTEGER, y INTEGER, z INTEGER, action INTEGER, type TEXT, amount INTEGER, rolled_back INTEGER DEFAULT 0)");
                createIndex(st, "co_container_coords", "co_container(wid, x, z, y, time)");
                createIndex(st, "co_container_time", "co_container(time)");

                st.execute("CREATE TABLE IF NOT EXISTS co_entity_kill (" + pk + ", time BIGINT, user INTEGER, wid INTEGER, x INTEGER, y INTEGER, z INTEGER, type TEXT, rolled_back INTEGER DEFAULT 0)");
                createIndex(st, "co_entity_kill_time", "co_entity_kill(time)");

                st.execute("CREATE TABLE IF NOT EXISTS co_sign (" + pk + ", time BIGINT, user INTEGER, wid INTEGER, x INTEGER, y INTEGER, z INTEGER, text TEXT)");
                st.execute("CREATE TABLE IF NOT EXISTS co_chat (" + pk + ", time BIGINT, user INTEGER, message TEXT)");
                st.execute("CREATE TABLE IF NOT EXISTS co_command (" + pk + ", time BIGINT, user INTEGER, message TEXT)");
                st.execute("CREATE TABLE IF NOT EXISTS co_session (" + pk + ", time BIGINT, user INTEGER, wid INTEGER, x INTEGER, y INTEGER, z INTEGER, action INTEGER)");
                st.execute("CREATE TABLE IF NOT EXISTS co_version (" + pk + ", time BIGINT, version TEXT)");
            }
            try (PreparedStatement ps = connection.prepareStatement("INSERT INTO co_version(time, version) VALUES (?, ?)")) {
                ps.setLong(1, System.currentTimeMillis() / 1000L);
                ps.setString(2, CervLogger.VERSION);
                ps.executeUpdate();
            }
        }
    }

    public static Object lock() {
        return LOCK;
    }

    public static Connection connection() {
        return connection;
    }

    /**
     * Returns a live connection, attempting a single reconnect if the current one
     * is closed or unreachable. Must be called under {@link #lock()}.
     * Throws {@link SQLException} if the reconnect attempt fails.
     */
    public static Connection ensureConnection() throws SQLException {
        if (connection != null) {
            if (isConnected()) {
                return connection;
            }
            CervLogger.LOGGER.warn("[CervLogger] Database connection lost, attempting to reconnect to {}", description());
            try {
                connection.close();
            }
            catch (SQLException ignored) {
            }
            connection = null;
        }
        connection = createConnection();
        return connection;
    }

    /** Opens a new connection to the configured storage backend. */
    private static Connection createConnection() throws SQLException {
        if (mysql) {
            Properties props = new Properties();
            props.setProperty("user", ConfigHandler.mysqlUsername);
            props.setProperty("password", ConfigHandler.mysqlPassword);
            String url = "jdbc:mysql://" + ConfigHandler.mysqlHost + ":" + ConfigHandler.mysqlPort + "/" + ConfigHandler.mysqlDatabase
                    + "?useUnicode=true&characterEncoding=utf8&autoReconnect=true&useSSL=false&allowPublicKeyRetrieval=true";
            // Instantiate the driver directly: Class.forName + DriverManager fail under
            // FML module isolation (DriverManager skips drivers from other classloaders).
            return new com.mysql.cj.jdbc.Driver().connect(url, props);
        }
        // Direct factory call instead of Class.forName + DriverManager (see above).
        return org.sqlite.JDBC.createConnection("jdbc:sqlite:" + databaseFile.toAbsolutePath(), new Properties());
    }

    public static boolean isReady() {
        return connection != null;
    }

    /** True if the current connection is open and passes a validity check. Must be called under {@link #lock()}. */
    public static boolean isConnected() {
        try {
            return connection != null && !connection.isClosed() && connection.isValid(2);
        }
        catch (SQLException e) {
            return false;
        }
    }

    public static Path databaseFile() {
        return databaseFile;
    }

    public static boolean isMySql() {
        return mysql;
    }

    /** Human-readable storage description for logs and /cl status. */
    public static String description() {
        return mysql
                ? "MySQL " + ConfigHandler.mysqlHost + ":" + ConfigHandler.mysqlPort + "/" + ConfigHandler.mysqlDatabase
                : String.valueOf(databaseFile);
    }

    /** MySQL has no CREATE INDEX IF NOT EXISTS; ignore duplicate-index errors instead. */
    private static void createIndex(Statement st, String name, String def) throws SQLException {
        if (mysql) {
            try {
                st.execute("CREATE INDEX " + name + " ON " + def);
            }
            catch (SQLException e) {
                // MySQL error 1061: index already exists - safe to ignore.
            }
        }
        else {
            st.execute("CREATE INDEX IF NOT EXISTS " + name + " ON " + def);
        }
    }

    public static void close() {
        synchronized (LOCK) {
            try {
                if (connection != null) {
                    connection.close();
                }
            }
            catch (SQLException e) {
                CervLogger.LOGGER.error("[CervLogger] Failed to close database", e);
            }
            connection = null;
            USER_IDS.clear();
            USER_NAMES.clear();
            WORLD_IDS.clear();
            WORLD_NAMES.clear();
        }
    }

    /** Gets or creates the numeric id for a user name (must be called under lock()). */
    public static int userId(Connection conn, String user, String uuid) throws SQLException {
        Integer cached = USER_IDS.get(user);
        if (cached != null) {
            return cached;
        }
        try (PreparedStatement ps = conn.prepareStatement("SELECT rowid FROM co_user WHERE user = ?")) {
            ps.setString(1, user);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    int id = rs.getInt(1);
                    USER_IDS.put(user, id);
                    USER_NAMES.put(id, user);
                    return id;
                }
            }
        }
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO co_user(time, user, uuid) VALUES (?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, System.currentTimeMillis() / 1000L);
            ps.setString(2, user);
            ps.setString(3, uuid);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                int id = rs.getInt(1);
                USER_IDS.put(user, id);
                USER_NAMES.put(id, user);
                return id;
            }
        }
    }

    /** Resolves a user id back to a name (must be called under lock()). */
    public static String userName(Connection conn, int id) throws SQLException {
        String cached = USER_NAMES.get(id);
        if (cached != null) {
            return cached;
        }
        try (PreparedStatement ps = conn.prepareStatement("SELECT user FROM co_user WHERE rowid = ?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String name = rs.getString(1);
                    USER_NAMES.put(id, name);
                    USER_IDS.put(name, id);
                    return name;
                }
            }
        }
        return "#unknown";
    }

    /** Gets or creates the numeric id for a world/dimension (must be called under lock()). */
    public static int worldId(Connection conn, String world) throws SQLException {
        Integer cached = WORLD_IDS.get(world);
        if (cached != null) {
            return cached;
        }
        try (PreparedStatement ps = conn.prepareStatement("SELECT rowid FROM co_world WHERE world = ?")) {
            ps.setString(1, world);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    int id = rs.getInt(1);
                    WORLD_IDS.put(world, id);
                    WORLD_NAMES.put(id, world);
                    return id;
                }
            }
        }
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO co_world(world) VALUES (?)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, world);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                int id = rs.getInt(1);
                WORLD_IDS.put(world, id);
                WORLD_NAMES.put(id, world);
                return id;
            }
        }
    }

    /** Resolves a world id back to its name (must be called under lock()). */
    public static String worldName(Connection conn, int id) throws SQLException {
        String cached = WORLD_NAMES.get(id);
        if (cached != null) {
            return cached;
        }
        try (PreparedStatement ps = conn.prepareStatement("SELECT world FROM co_world WHERE rowid = ?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String name = rs.getString(1);
                    WORLD_NAMES.put(id, name);
                    WORLD_IDS.put(name, id);
                    return name;
                }
            }
        }
        return "#unknown";
    }
}
