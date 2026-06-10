package com.redis.demo;

import redis.clients.jedis.*;
import redis.clients.jedis.MultiDbConfig.DatabaseConfig;
import redis.clients.jedis.exceptions.JedisConnectionException;
import redis.clients.jedis.mcf.DatabaseSwitchEvent;
import redis.clients.jedis.mcf.InitializationPolicy;
import redis.clients.jedis.mcf.JedisFailoverException;

import java.io.FileInputStream;
import java.io.InputStream;
import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Redis Active-Active Circuit Breaker Demo - Jedis 7 MultiDbClient.
 *
 * Fully config-driven (see demo.properties): 2 or 3 databases, custom names,
 * per-database endpoint / weight / auth / TLS, and all circuit-breaker tuning.
 *
 * A continuous mixed read/write workload runs against the highest-weight (primary)
 * database. When that database is blocked (e.g. via scripts/block.sh), Jedis detects
 * the failures, opens the circuit, and automatically routes traffic to the next
 * healthy database - no restart, no code change. When the primary recovers, Jedis
 * probes it, closes the circuit, and fails back.
 *
 * Config file path resolution order:
 *   1. first CLI argument            (java -jar app.jar /path/to/demo.properties)
 *   2. environment variable DEMO_CONFIG
 *   3. ./demo.properties             (default)
 *
 * See Notes.md (setup) and Demo_Script.md (run book).
 */
public class CircuitBreakerDemo {

    // --- Loaded configuration (populated in main) ---------------------------
    private static List<Db> DBS = new ArrayList<>();
    private static String   PRIMARY_NAME = "PRIMARY";
    private static int      WORKER_THREADS    = 4;
    private static int      SLEEP_PER_LOOP_MS = 1;
    private static int      FAILBACK_INTERVAL_MS = 15_000;

    // --- Metrics (shared across worker threads) -----------------------------
    private static final AtomicLong totalOps     = new AtomicLong(0);
    private static final AtomicLong totalFailed  = new AtomicLong(0);
    private static final AtomicLong windowOps    = new AtomicLong(0);
    private static final AtomicLong windowFailed = new AtomicLong(0);

    // Set true the first time traffic leaves the primary. The live label is read
    // from the client each tick (see printMetrics) so it is always accurate.
    private static volatile boolean everFailedOver = false;

    // Print the first worker error once (diagnostic), don't spam the console
    private static final AtomicBoolean firstErrorLogged = new AtomicBoolean(false);

    // --- Console formatting (ASCII only - renders on any locale) ------------
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final String LINE = "=".repeat(64);
    private static final String BANG = "!".repeat(64);
    private static final String STAR = "*".repeat(64);
    private static final String SEP  = " | ";

    /** One configured database (region). */
    private static final class Db {
        final String name, host, user, pass;
        final int port;
        final float weight;
        final boolean tls;
        Db(String name, String host, int port, float weight, String user, String pass, boolean tls) {
            this.name = name; this.host = host; this.port = port; this.weight = weight;
            this.user = user; this.pass = pass; this.tls = tls;
        }
        String endpoint() { return host + ":" + port; }
    }

    // ------------------------------------------------------------------------

    public static void main(String[] args) throws Exception {

        // --- Load config --------------------------------------------------
        String configPath = args.length > 0 ? args[0]
                : System.getenv().getOrDefault("DEMO_CONFIG", "demo.properties");
        Properties p = new Properties();
        try (InputStream in = new FileInputStream(configPath)) {
            p.load(in);
        } catch (Exception e) {
            System.out.println("[FATAL] Could not read config file '" + configPath + "': " + e.getMessage());
            System.out.println("[FATAL] Create it with ./scripts/configure.sh, or pass a path as the first argument.");
            System.exit(2);
        }

        int dbCount = intProp(p, "db.count", 2);
        WORKER_THREADS       = intProp(p, "workload.threads", 4);
        SLEEP_PER_LOOP_MS    = intProp(p, "workload.sleepMillis", 1);
        FAILBACK_INTERVAL_MS = intProp(p, "failback.intervalMs", 15_000);

        int   cbWindowSecs   = intProp(p, "cb.slidingWindowSecs", 2);
        float cbThreshold    = floatProp(p, "cb.failureRateThreshold", 50.0f);
        int   cbMinFailures  = intProp(p, "cb.minFailures", 3);
        int   retryAttempts  = intProp(p, "retry.maxAttempts", 2);
        int   retryWaitMs    = intProp(p, "retry.waitMillis", 100);
        boolean failbackOn   = boolProp(p, "failback.enabled", true);
        int   gracePeriodMs  = intProp(p, "failback.gracePeriodMs", 5_000);
        int   socketTimeout  = intProp(p, "socket.timeoutMs", 1_000);
        int   connectTimeout = intProp(p, "connect.timeoutMs", 1_000);

        for (int i = 1; i <= dbCount; i++) {
            String name = strProp(p, "db." + i + ".name", "DB" + i);
            String ep   = strProp(p, "db." + i + ".endpoint", "");
            if (ep.isEmpty()) {
                System.out.println("[FATAL] db." + i + ".endpoint is missing in " + configPath);
                System.exit(2);
            }
            // Accept host:port (strip any redis:// or rediss:// scheme just in case)
            ep = ep.replaceFirst("^rediss?://", "");
            String host = ep.substring(0, ep.lastIndexOf(':'));
            int port    = Integer.parseInt(ep.substring(ep.lastIndexOf(':') + 1).trim());
            float weight = floatProp(p, "db." + i + ".weight", i == 1 ? 1.0f : 1.0f / (i + 1));
            String user = strProp(p, "db." + i + ".username", "");
            String pass = strProp(p, "db." + i + ".password", "");
            boolean tls = boolProp(p, "db." + i + ".tls", false);
            DBS.add(new Db(name, host, port, weight, user, pass, tls));
        }
        // Primary = highest weight (ties broken by config order)
        Db primary = DBS.get(0);
        for (Db d : DBS) if (d.weight > primary.weight) primary = d;
        PRIMARY_NAME = primary.name;

        printBanner(primary);

        // --- Build the MultiDbConfig from the configured databases --------
        MultiDbConfig.Builder mb = MultiDbConfig.builder();
        for (Db d : DBS) {
            DefaultJedisClientConfig.Builder cc = DefaultJedisClientConfig.builder()
                    .socketTimeoutMillis(socketTimeout)
                    .connectionTimeoutMillis(connectTimeout);
            if (!d.user.isEmpty()) cc.user(d.user);     // ACL user (optional)
            if (!d.pass.isEmpty()) cc.password(d.pass);  // password (default user or ACL)
            if (d.tls)            cc.ssl(true);          // TLS (optional)
            JedisClientConfig clientConfig = cc.build();

            ConnectionPoolConfig pool = new ConnectionPoolConfig();
            pool.setMaxTotal(16);
            pool.setMaxIdle(8);
            pool.setMinIdle(2);
            pool.setTestWhileIdle(true);
            pool.setTimeBetweenEvictionRuns(Duration.ofSeconds(1));
            pool.setBlockWhenExhausted(true);
            pool.setMaxWait(Duration.ofSeconds(2));   // never block a worker forever

            mb.database(DatabaseConfig.builder(new HostAndPort(d.host, d.port), clientConfig)
                    .connectionPoolConfig(pool)
                    .weight(d.weight)
                    .build());
        }

        MultiDbConfig config = mb
                // Circuit breaker: open at >= threshold% failure rate after >= minFailures
                // failures inside the sliding window.
                .failureDetector(MultiDbConfig.CircuitBreakerConfig.builder()
                        .slidingWindowSize(cbWindowSecs)
                        .failureRateThreshold(cbThreshold)
                        .minNumOfFailures(cbMinFailures)
                        .build())
                // Retry each command before counting it as a circuit-breaker failure.
                .commandRetry(MultiDbConfig.RetryConfig.builder()
                        .maxAttempts(retryAttempts)
                        .waitDuration(retryWaitMs)
                        .build())
                // Boot on ANY healthy database (default MAJORITY_AVAILABLE would require
                // 2-of-2 up at startup - wrong for a failover client). Weight still
                // decides which database is preferred once more than one is healthy.
                .initializationPolicy(InitializationPolicy.BuiltIn.ONE_AVAILABLE)
                // Failback: probe the higher-weight database and return to it on recovery.
                .failbackSupported(failbackOn)
                .failbackCheckInterval(FAILBACK_INTERVAL_MS)
                .gracePeriod(gracePeriodMs)
                .fastFailover(true)
                .retryOnFailover(true)
                .build();

        MultiDbClient client = MultiDbClient.builder()
                .multiDbConfig(config)
                .databaseSwitchListener(CircuitBreakerDemo::onSwitch)
                .build();

        // --- Startup probe: prove connectivity BEFORE launching the workload --
        System.out.println("[INFO] Built MultiDbClient. Running startup connectivity probe...");
        try {
            String pk = "cb:demo:startup-probe";
            client.set(pk, "ok");
            String got = client.get(pk);
            String liveOn = nameFor(String.valueOf(client.getActiveDatabaseEndpoint()));
            System.out.println("[INFO] Probe OK - SET/GET round-tripped (value=" + got + "). "
                    + "Live on '" + liveOn + "' (preferred: '" + PRIMARY_NAME + "').");
        } catch (Throwable t) {
            System.out.println();
            System.out.println("[FATAL] Startup probe FAILED - no database is reachable. Details:");
            t.printStackTrace(System.out);
            System.out.println();
            System.out.println("[FATAL] Check connectivity to your endpoints, e.g.:");
            for (Db d : DBS) {
                System.out.println("          redis-cli -h " + d.host + " -p " + d.port + " ping");
            }
            try { client.close(); } catch (Exception ignored) {}
            System.exit(1);
        }

        System.out.println("[INFO] Starting workload (" + WORKER_THREADS + " threads)...");
        printCommandHints(primary);
        System.out.println();

        // Background metrics line printed once per second
        ScheduledExecutorService metricsSched = Executors.newSingleThreadScheduledExecutor(
                r -> { Thread t = new Thread(r, "metrics"); t.setDaemon(true); return t; });
        metricsSched.scheduleAtFixedRate(() -> printMetrics(client), 1, 1, TimeUnit.SECONDS);

        // Shutdown hook: summary on Ctrl-C
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            metricsSched.shutdown();
            printSummary();
            try { client.close(); } catch (Exception ignored) {}
        }, "shutdown"));

        // Worker threads - each does SET + GET in a tight loop
        ExecutorService workers = Executors.newFixedThreadPool(WORKER_THREADS,
                r -> new Thread(r, "worker"));
        for (int i = 0; i < WORKER_THREADS; i++) {
            final long keyBase = (long) i * 1_000_000L;
            workers.submit(() -> runWorker(client, keyBase));
        }
        workers.awaitTermination(Long.MAX_VALUE, TimeUnit.DAYS);
    }

    // --- Worker -------------------------------------------------------------

    private static void runWorker(MultiDbClient client, long keyBase) {
        long seq = keyBase;
        while (!Thread.currentThread().isInterrupted()) {
            String key = "cb:demo:" + (seq % 50_000);
            seq++;
            boolean ok = true;
            try {
                client.set(key, "v" + seq);
                client.get(key);
            } catch (JedisFailoverException e) {
                ok = false;  // all databases temporarily unavailable (failover in progress)
            } catch (JedisConnectionException e) {
                ok = false;  // connection error in the brief pre-failover window
            } catch (Throwable t) {
                // Catch Errors too (e.g. NoClassDefFoundError from a bad fat jar) so a
                // worker never dies silently. Surface the first one for diagnosis.
                ok = false;
                if (firstErrorLogged.compareAndSet(false, true)) {
                    System.out.println("[WARN] First worker error (" + t.getClass().getSimpleName()
                            + "): " + t.getMessage());
                }
            }
            totalOps.addAndGet(2);
            windowOps.addAndGet(2);
            if (!ok) {
                totalFailed.addAndGet(2);
                windowFailed.addAndGet(2);
            }
            try { Thread.sleep(SLEEP_PER_LOOP_MS); } catch (InterruptedException e) { break; }
        }
    }

    // --- Failover / failback event ------------------------------------------

    private static void onSwitch(DatabaseSwitchEvent event) {
        String ep   = String.valueOf(event.getEndpoint());
        String name = nameFor(ep);
        boolean toPrimary = name.equals(PRIMARY_NAME);

        System.out.println();
        if (!toPrimary) {
            everFailedOver = true;
            System.out.println(BANG);
            System.out.println("  *** FAILOVER:  now serving '" + name + "' ***");
            System.out.println("  New endpoint : " + ep);
            System.out.println("  Circuit      : OPEN - primary unavailable, traffic rerouted");
            System.out.println("  Recovery     : probing '" + PRIMARY_NAME + "' every "
                    + (FAILBACK_INTERVAL_MS / 1000) + "s for failback");
            System.out.println(BANG);
        } else {
            System.out.println(STAR);
            System.out.println("  *** FAILBACK:  restored to '" + name + "' ***");
            System.out.println("  New endpoint : " + ep);
            System.out.println("  Circuit      : CLOSED - primary healthy, traffic restored");
            System.out.println(STAR);
        }
        System.out.println();
    }

    // --- Metrics ------------------------------------------------------------

    private static void printMetrics(MultiDbClient client) {
        long ops    = windowOps.getAndSet(0);
        long failed = windowFailed.getAndSet(0);
        long total  = totalOps.get();
        double pct  = ops > 0 ? 100.0 * (ops - failed) / ops : 100.0;

        // Read the REAL active database from the client every tick. The switch
        // listener does NOT fire on the initial selection, so deriving the label
        // from listener state alone would mislabel a boot-during-outage.
        String active = PRIMARY_NAME;
        try {
            active = nameFor(String.valueOf(client.getActiveDatabaseEndpoint()));
        } catch (Throwable ignored) { /* mid-switch transient - keep last */ }
        boolean onPrimary = active.equals(PRIMARY_NAME);

        String tag = "";
        if (everFailedOver) tag = onPrimary ? "  [RESTORED]" : "  [FAILED OVER]";

        System.out.printf("[%s]  Active: %-8s%s%5d ops/s%sSuccess: %5.1f%%%sTotal: %,10d%s%n",
                LocalTime.now().format(TIME_FMT), active, SEP, ops, SEP, pct, SEP, total, tag);
    }

    // --- Helpers ------------------------------------------------------------

    /** Map an endpoint string (host:port) back to its configured database name. */
    private static String nameFor(String epStr) {
        for (Db d : DBS) {
            if (epStr.equals(d.endpoint()) || epStr.contains(d.host)) return d.name;
        }
        return epStr;
    }

    private static String strProp(Properties p, String k, String def) {
        String v = p.getProperty(k);
        return v == null ? def : v.trim();
    }
    private static int intProp(Properties p, String k, int def) {
        try { return Integer.parseInt(strProp(p, k, String.valueOf(def))); }
        catch (Exception e) { return def; }
    }
    private static float floatProp(Properties p, String k, float def) {
        try { return Float.parseFloat(strProp(p, k, String.valueOf(def))); }
        catch (Exception e) { return def; }
    }
    private static boolean boolProp(Properties p, String k, boolean def) {
        String v = p.getProperty(k);
        return v == null ? def : Boolean.parseBoolean(v.trim());
    }

    // --- Console helpers ----------------------------------------------------

    private static void printBanner(Db primary) {
        System.out.println();
        System.out.println(LINE);
        System.out.println("  REDIS ACTIVE-ACTIVE  -  CIRCUIT BREAKER DEMO  (Jedis 7.x)");
        System.out.println(LINE);
        for (Db d : DBS) {
            String role = d == primary ? "PRIMARY" : "standby";
            System.out.printf("  %-7s %-8s : %s  (weight %.2f%s)%n",
                    role, d.name, d.endpoint(), d.weight, d.tls ? ", TLS" : "");
        }
        System.out.println(LINE);
        System.out.println();
    }

    private static void printCommandHints(Db primary) {
        System.out.println("[HINT] Trigger failover from a SECOND terminal on this machine:");
        System.out.println();
        System.out.println("  ./scripts/block.sh   " + primary.name
                + "    # block the primary  -> failover");
        System.out.println("  ./scripts/unblock.sh " + primary.name
                + "    # restore the primary -> failback");
        System.out.println();
        System.out.println("  (block.sh resolves the endpoint from demo.properties and applies an");
        System.out.println("   iptables REJECT rule; unblock.sh removes it.)");
    }

    private static void printSummary() {
        long total  = totalOps.get();
        long failed = totalFailed.get();
        System.out.println();
        System.out.println(LINE);
        System.out.println("  DEMO SUMMARY");
        System.out.println(LINE);
        System.out.printf("  Total operations : %,d%n",   total);
        System.out.printf("  Successful       : %,d%n",   total - failed);
        System.out.printf("  Failed           : %,d%n",   failed);
        System.out.printf("  Overall success  : %.4f%%%n", 100.0 * (total - failed) / Math.max(1, total));
        System.out.printf("  Failover occurred: %s%n",     everFailedOver ? "YES" : "NO");
        System.out.println(LINE);
    }
}
