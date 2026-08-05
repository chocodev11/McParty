package dev.epicc.lobby.parkour;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class SqliteParkourLeaderboardStore implements ParkourLeaderboardStore {

    private static final int CURRENT_SCHEMA_VERSION = 1;
    private static final int MAX_TOP_LIMIT = 100;
    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS parkour_best_times (
                course_id TEXT NOT NULL,
                player_uuid TEXT NOT NULL,
                player_name TEXT NOT NULL,
                best_time_ms INTEGER NOT NULL CHECK (best_time_ms > 0),
                attempts INTEGER NOT NULL DEFAULT 1 CHECK (attempts > 0),
                achieved_at TEXT NOT NULL,
                PRIMARY KEY (course_id, player_uuid)
            )
            """;
    private static final String CREATE_INDEX = """
            CREATE INDEX IF NOT EXISTS idx_parkour_best_times_order
            ON parkour_best_times (course_id, best_time_ms, achieved_at, player_uuid)
            """;

    private final Path databaseFile;
    private final Logger logger;
    private final ExecutorService executor;
    private final AtomicBoolean closed = new AtomicBoolean();

    public SqliteParkourLeaderboardStore(Path databaseFile, Logger logger) throws IOException, SQLException {
        this.databaseFile = Objects.requireNonNull(databaseFile, "databaseFile").toAbsolutePath();
        this.logger = Objects.requireNonNull(logger, "logger");
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException exception) {
            throw new SQLException("SQLite JDBC driver is missing", exception);
        }
        createParentDirectory();
        initializeSchema();
        executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "mcparty-parkour-db");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public CompletableFuture<ParkourSubmission> submit(
            String courseId,
            UUID playerId,
            String playerName,
            long completionTimeMs
    ) {
        String validCourseId = validateCourseId(courseId);
        Objects.requireNonNull(playerId, "playerId");
        String validPlayerName = validatePlayerName(playerName);
        if (completionTimeMs <= 0) {
            throw new IllegalArgumentException("completionTimeMs must be positive");
        }
        return execute(connection -> submit(connection, validCourseId, playerId, validPlayerName, completionTimeMs));
    }

    @Override
    public CompletableFuture<List<ParkourLeaderboardEntry>> top(String courseId, int limit) {
        String validCourseId = validateCourseId(courseId);
        int validLimit = Math.max(1, Math.min(MAX_TOP_LIMIT, limit));
        return execute(connection -> top(connection, validCourseId, validLimit));
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
            logger.log(Level.WARNING, "Interrupted while closing the parkour leaderboard database", exception);
        }
    }

    private <T> CompletableFuture<T> execute(SqlOperation<T> operation) {
        if (closed.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Parkour leaderboard store is closed"));
        }
        try {
            return CompletableFuture.supplyAsync(() -> {
                try (Connection connection = openConnection()) {
                    return operation.apply(connection);
                } catch (SQLException exception) {
                    throw new CompletionException(exception);
                }
            }, executor);
        } catch (RejectedExecutionException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    private ParkourSubmission submit(
            Connection connection,
            String courseId,
            UUID playerId,
            String playerName,
            long completionTimeMs
    ) throws SQLException {
        connection.setAutoCommit(false);
        try {
            ExistingRecord existing = find(connection, courseId, playerId);
            Instant achievedAt = Instant.now();
            ParkourLeaderboardEntry entry;
            boolean personalBest;
            if (existing == null) {
                insert(connection, courseId, playerId, playerName, completionTimeMs, 1, achievedAt);
                personalBest = true;
                entry = new ParkourLeaderboardEntry(courseId, playerId, playerName, completionTimeMs, 1, achievedAt);
            } else {
                personalBest = completionTimeMs < existing.bestTimeMs();
                int attempts = existing.attempts() + 1;
                if (personalBest) {
                    updateBest(connection, courseId, playerId, playerName, completionTimeMs, attempts, achievedAt);
                    entry = new ParkourLeaderboardEntry(
                            courseId, playerId, playerName, completionTimeMs, attempts, achievedAt
                    );
                } else {
                    updateAttemptCount(connection, courseId, playerId, playerName, attempts);
                    entry = new ParkourLeaderboardEntry(
                            courseId, playerId, playerName,
                            existing.bestTimeMs(), attempts, existing.achievedAt()
                    );
                }
            }
            connection.commit();
            return new ParkourSubmission(personalBest, entry);
        } catch (SQLException exception) {
            connection.rollback();
            throw exception;
        }
    }

    private List<ParkourLeaderboardEntry> top(Connection connection, String courseId, int limit) throws SQLException {
        String sql = """
                SELECT course_id, player_uuid, player_name, best_time_ms, attempts, achieved_at
                FROM parkour_best_times
                WHERE course_id = ?
                ORDER BY best_time_ms ASC, achieved_at ASC, player_uuid ASC
                LIMIT ?
                """;
        List<ParkourLeaderboardEntry> entries = new ArrayList<>(limit);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, courseId);
            statement.setInt(2, limit);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    entries.add(readEntry(result));
                }
            }
        }
        return List.copyOf(entries);
    }

    private ExistingRecord find(Connection connection, String courseId, UUID playerId) throws SQLException {
        String sql = """
                SELECT best_time_ms, attempts, achieved_at
                FROM parkour_best_times
                WHERE course_id = ? AND player_uuid = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, courseId);
            statement.setString(2, playerId.toString());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                return new ExistingRecord(
                        result.getLong("best_time_ms"),
                        result.getInt("attempts"),
                        Instant.parse(result.getString("achieved_at"))
                );
            }
        }
    }

    private static ParkourLeaderboardEntry readEntry(ResultSet result) throws SQLException {
        return new ParkourLeaderboardEntry(
                result.getString("course_id"),
                UUID.fromString(result.getString("player_uuid")),
                result.getString("player_name"),
                result.getLong("best_time_ms"),
                result.getInt("attempts"),
                Instant.parse(result.getString("achieved_at"))
        );
    }

    private static void insert(
            Connection connection,
            String courseId,
            UUID playerId,
            String playerName,
            long completionTimeMs,
            int attempts,
            Instant achievedAt
    ) throws SQLException {
        String sql = """
                INSERT INTO parkour_best_times
                    (course_id, player_uuid, player_name, best_time_ms, attempts, achieved_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, courseId);
            statement.setString(2, playerId.toString());
            statement.setString(3, playerName);
            statement.setLong(4, completionTimeMs);
            statement.setInt(5, attempts);
            statement.setString(6, achievedAt.toString());
            statement.executeUpdate();
        }
    }

    private static void updateBest(
            Connection connection,
            String courseId,
            UUID playerId,
            String playerName,
            long completionTimeMs,
            int attempts,
            Instant achievedAt
    ) throws SQLException {
        String sql = """
                UPDATE parkour_best_times
                SET player_name = ?, best_time_ms = ?, attempts = ?, achieved_at = ?
                WHERE course_id = ? AND player_uuid = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerName);
            statement.setLong(2, completionTimeMs);
            statement.setInt(3, attempts);
            statement.setString(4, achievedAt.toString());
            statement.setString(5, courseId);
            statement.setString(6, playerId.toString());
            statement.executeUpdate();
        }
    }

    private static void updateAttemptCount(
            Connection connection,
            String courseId,
            UUID playerId,
            String playerName,
            int attempts
    ) throws SQLException {
        String sql = """
                UPDATE parkour_best_times
                SET player_name = ?, attempts = ?
                WHERE course_id = ? AND player_uuid = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerName);
            statement.setInt(2, attempts);
            statement.setString(3, courseId);
            statement.setString(4, playerId.toString());
            statement.executeUpdate();
        }
    }

    private Connection openConnection() throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA busy_timeout = 5000");
            statement.execute("PRAGMA journal_mode = WAL");
        }
        return connection;
    }

    private void initializeSchema() throws SQLException {
        try (Connection connection = openConnection()) {
            int version = userVersion(connection);
            if (version > CURRENT_SCHEMA_VERSION) {
                throw new SQLException("Unsupported parkour database schema version: " + version);
            }
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                if (version < 1) {
                    statement.executeUpdate(CREATE_TABLE);
                    statement.executeUpdate(CREATE_INDEX);
                    setUserVersion(connection, 1);
                }
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private static int userVersion(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA user_version")) {
            return result.next() ? result.getInt(1) : 0;
        }
    }

    private static void setUserVersion(Connection connection, int version) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("PRAGMA user_version = " + version);
        }
    }

    private void createParentDirectory() throws IOException {
        Path parent = databaseFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    private static String validateCourseId(String courseId) {
        if (courseId == null || !courseId.matches("[a-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("courseId must match [a-z0-9_-]{1,64}");
        }
        return courseId;
    }

    private static String validatePlayerName(String playerName) {
        if (playerName == null || playerName.isBlank()) {
            throw new IllegalArgumentException("playerName must not be blank");
        }
        return playerName;
    }

    @FunctionalInterface
    private interface SqlOperation<T> {
        T apply(Connection connection) throws SQLException;
    }

    private record ExistingRecord(long bestTimeMs, int attempts, Instant achievedAt) {
    }
}
