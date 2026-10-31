package net.cervlogger.database;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import net.cervlogger.CervLogger;

/**
 * Asynchronous database consumer, mirroring CervLogger's consumer design:
 * listeners enqueue lightweight tasks, and this thread writes them to the
 * database in batched transactions off the main server thread.
 */
public final class ConsumerThread implements Runnable {

    /** A single unit of database work, executed on the consumer thread. */
    @FunctionalInterface
    public interface DbTask {
        void run(Connection connection) throws Exception;
    }

    private static final BlockingQueue<DbTask> QUEUE = new LinkedBlockingQueue<>();
    private static final int MAX_BATCH = 1000;

    private static volatile boolean running = false;
    private static Thread thread;

    public static void start() {
        if (running) {
            return;
        }
        running = true;
        thread = new Thread(new ConsumerThread(), "CervLogger-Consumer");
        thread.setDaemon(false);
        thread.start();
    }

    public static void queue(DbTask task) {
        if (running && Database.isReady()) {
            QUEUE.add(task);
        }
    }

    public static int size() {
        return QUEUE.size();
    }

    public static void shutdown() {
        running = false;
        if (thread != null) {
            try {
                thread.join(30000);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            thread = null;
        }
    }

    @Override
    public void run() {
        while (running || !QUEUE.isEmpty()) {
            try {
                DbTask first = QUEUE.poll(500, TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                List<DbTask> batch = new ArrayList<>();
                batch.add(first);
                QUEUE.drainTo(batch, MAX_BATCH - 1);
                process(batch);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            catch (Exception e) {
                CervLogger.LOGGER.error("[CervLogger] Consumer error", e);
            }
        }
    }

    private static void process(List<DbTask> batch) {
        synchronized (Database.lock()) {
            Connection connection;
            try {
                connection = Database.ensureConnection();
            }
            catch (Exception e) {
                CervLogger.LOGGER.error("[CervLogger] Failed to reconnect to database, dropping batch", e);
                return;
            }
            try {
                connection.setAutoCommit(false);
                for (DbTask task : batch) {
                    try {
                        task.run(connection);
                    }
                    catch (Exception e) {
                        CervLogger.LOGGER.error("[CervLogger] Failed to write log entry", e);
                    }
                }
                connection.commit();
            }
            catch (Exception e) {
                CervLogger.LOGGER.error("[CervLogger] Failed to commit batch", e);
            }
            finally {
                try {
                    connection.setAutoCommit(true);
                }
                catch (Exception ignored) {
                }
            }
        }
    }
}
