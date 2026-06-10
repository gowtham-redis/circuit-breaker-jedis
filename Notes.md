# Notes — Setup & Build

Everything needed to stand up the **Redis Active-Active Circuit Breaker** demo on a
fresh `load` machine (ps-portal lab or any Ubuntu/x86_64 client box with internet).

The demo is a config-driven Jedis 7 `MultiDbClient` app that runs a continuous
read/write workload against a **primary** database and **automatically fails over** to a
standby when the primary is blocked — then fails back when it recovers. Supports **2 or 3**
databases with **custom names** (North/South, East/West/Central, …) and optional auth/TLS.

> **For what to do live, see [Demo_Script.md](Demo_Script.md).** This file is setup only.

---

## 0. What you need on `load`

- Ubuntu 20.04+, x86_64, internet access (Maven Central + GitHub reachable).
- `redis-cli` (pre-installed on the ps-portal `load` box).
- Network reachability from `load` to **both/all** database endpoints.
- `sudo` (only used to install Java/Maven and to run `iptables` for the failover trigger).

No Docker, no manual classpath wrangling — the build produces a single fat jar.

---

## 1. Get the code onto `load`

### Path A — git clone (preferred)

```bash
git clone <YOUR_REMOTE_REPO_URL> "Circuit Breaker Jedis"
cd "Circuit Breaker Jedis"
```

### Path B — base64 paste (no git / offline)

Terminal-pasting source corrupts it; base64 reproduces the bundle byte-for-byte. On `load`,
paste the block below, then paste the base64 from the [Appendix](#appendix--base64-install-bundle),
then the closing `B64`:

```bash
base64 -d > /tmp/cb-install.tgz <<'B64'
<paste the base64 from the Appendix here>
B64
mkdir -p "Circuit Breaker Jedis" && tar xzf /tmp/cb-install.tgz -C "Circuit Breaker Jedis"
cd "Circuit Breaker Jedis"
```

---

## 2. Three commands to run the demo

```bash
./scripts/setup.sh        # installs Java 17 + Maven if missing, builds the fat jar
./scripts/configure.sh    # interactive: endpoints, names, auth (Enter = ps-portal defaults)
./scripts/run.sh          # starts the workload + live availability meter
```

That's it. To trigger the failover during the demo, from a **second terminal**:

```bash
./scripts/block.sh   North     # simulate an outage of the primary  -> failover
./scripts/unblock.sh North     # restore it                          -> failback
./scripts/status.sh            # ping all DBs + show any active block rules
```

`block.sh`/`unblock.sh` take the **database name** from your config (default = primary);
they resolve the endpoint and add/remove a precise `iptables` REJECT rule for you.

---

## 3. Configuration reference (`demo.properties`)

`configure.sh` writes this for you; you can also hand-edit it. Endpoints are plain `host:port`.

| Key | Meaning | Default |
|-----|---------|---------|
| `db.count` | Number of databases: `2` or `3` | `2` |
| `db.N.name` | Display name (any string) | North / South / Central |
| `db.N.endpoint` | `host:port` | ps-portal cluster endpoints |
| `db.N.weight` | Higher = preferred; highest weight is the primary | `1.0`, `0.5`, `0.25` |
| `db.N.username` | ACL username (blank = none) | *(blank)* |
| `db.N.password` | Password (blank = unauthenticated) | *(blank)* |
| `db.N.tls` | `true` to use TLS | `false` |
| `workload.threads` | Concurrent worker threads | `4` |
| `workload.sleepMillis` | Pause per loop (throttle throughput) | `1` |
| `cb.slidingWindowSecs` | Circuit-breaker failure window (seconds) | `2` |
| `cb.failureRateThreshold` | % failures that opens the circuit | `50` |
| `cb.minFailures` | Min failures before the circuit can open | `3` |
| `retry.maxAttempts` | Attempts per command before it counts as a failure | `2` |
| `retry.waitMillis` | Wait between retries | `100` |
| `failback.enabled` | Auto-return to the primary on recovery | `true` |
| `failback.intervalMs` | How often to probe the primary | `15000` |
| `failback.gracePeriodMs` | Settle time before failback | `5000` |
| `socket.timeoutMs` / `connect.timeoutMs` | Per-connection timeouts | `1000` |

**Auth examples**
- Unauthenticated: leave `username` and `password` blank.
- Password only (default user): set `password`, leave `username` blank.
- ACL user: set both `username` and `password`.

> `demo.properties` is **git-ignored** because it can hold passwords. Only
> `demo.properties.example` is committed.

---

## 4. How it works (the important design choices)

- **`MultiDbClient`** (Jedis 7) holds all databases and routes every command to the
  active one. Your application code is just `client.set(...)` / `client.get(...)` — failover
  is entirely inside the client.
- **Weights** make the highest-weight database the preferred/primary; lower weights are standbys.
- **Circuit breaker** (Resilience4j under the hood) watches real command failures. After
  `cb.minFailures` failures crossing `cb.failureRateThreshold` within the window, it opens and
  reroutes. This is why a brief, tiny dip in success rate is expected and *correct* during
  failover — it's the detection cost, not a bug.
- **`InitializationPolicy.ONE_AVAILABLE`** — boot on *any* healthy database. The Jedis default
  is `MAJORITY_AVAILABLE`, which with 2 DBs needs **both** up at startup; wrong for a failover
  client, so we override it. (You can start the app mid-outage and it comes up on the survivor.)
- **Live label** — the per-second line reads `client.getActiveDatabaseEndpoint()` directly, so
  it always shows the true active database (the switch *listener* only fires on a change, not on
  the initial pick).
- **Startup probe** — one SET/GET before the workload, so connectivity problems fail loudly
  with a stack trace instead of silently showing zero throughput.

---

## 5. Dependency gotchas (already handled in `pom.xml`)

- **`resilience4j-all` is required.** Jedis's `MultiDbCommandExecutor` uses
  `io.github.resilience4j.decorators.Decorators`, which is *not* in `resilience4j-circuitbreaker`
  or `-retry`. Without `resilience4j-all` you get `NoClassDefFoundError: .../decorators/Decorators`
  on the first command.
- **`slf4j-nop:1.7.36`** matches the `slf4j-api` 1.7.x that Jedis pulls — silences logging with no
  warning. A 2.x binding (e.g. `slf4j-simple` 2.x) triggers a `StaticLoggerBinder` warning.
- **Pin `maven-compiler-plugin` 3.11.0** with `<release>17</release>` — Ubuntu 20.04's apt Maven
  defaults to plugin 3.1, which fails with *"Source option 5 is no longer supported."*
- **`maven-shade-plugin`** builds the runnable fat jar.

---

## 6. Troubleshooting

| Symptom | Fix |
|---------|-----|
| `mvn: command not found` | `./scripts/setup.sh` installs it, or `sudo apt-get install -y openjdk-17-jdk-headless maven` |
| Build: `Source option 5 is no longer supported` | pom pins compiler 3.11.0 — check `mvn -v` is using it |
| `NoClassDefFoundError: .../decorators/Decorators` | `resilience4j-all` missing — already in pom; rebuild clean |
| Startup probe FAILS with a stack trace | No DB reachable. Run `./scripts/status.sh`; check endpoints/auth/TLS |
| `Initialization failed due to initialization policy` | Old build without `ONE_AVAILABLE` — rebuild |
| Failover doesn't fire after `block.sh` | Run `./scripts/status.sh` to confirm the REJECT rule is present and the DB is DOWN |
| Failback takes ~15-20s | Normal: `failback.intervalMs` (15s) + `gracePeriodMs` (5s). Lower them in config to speed up |
| Label shows wrong DB | Ensure you're on the current build (label reads the live active endpoint) |

After every run, confirm the firewall is clean: `./scripts/status.sh` (REJECT rules → "none").

---

## 7. Source reference

The full source also lives in the repo / bundle. Reproduced here for quick reference.

### `pom.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
             http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <groupId>com.redis.demo</groupId>
  <artifactId>circuit-breaker-jedis</artifactId>
  <version>1.0</version>
  <packaging>jar</packaging>

  <properties>
    <maven.compiler.release>17</maven.compiler.release>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
  </properties>

  <dependencies>
    <dependency>
      <groupId>redis.clients</groupId>
      <artifactId>jedis</artifactId>
      <version>7.5.2</version>
    </dependency>
    <!-- resilience4j-all bundles the Decorators class MultiDbCommandExecutor needs. -->
    <dependency>
      <groupId>io.github.resilience4j</groupId>
      <artifactId>resilience4j-all</artifactId>
      <version>1.7.1</version>
    </dependency>
    <dependency>
      <groupId>io.github.resilience4j</groupId>
      <artifactId>resilience4j-circuitbreaker</artifactId>
      <version>1.7.1</version>
    </dependency>
    <dependency>
      <groupId>io.github.resilience4j</groupId>
      <artifactId>resilience4j-retry</artifactId>
      <version>1.7.1</version>
    </dependency>
    <!-- slf4j-nop matches Jedis's slf4j-api 1.7.36 -> silences logging with no warning -->
    <dependency>
      <groupId>org.slf4j</groupId>
      <artifactId>slf4j-nop</artifactId>
      <version>1.7.36</version>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-compiler-plugin</artifactId>
        <version>3.11.0</version>
        <configuration>
          <release>17</release>
        </configuration>
      </plugin>
      <!-- Fat jar: java -jar target/circuit-breaker-jedis-1.0.jar -->
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-shade-plugin</artifactId>
        <version>3.5.1</version>
        <executions>
          <execution>
            <phase>package</phase>
            <goals><goal>shade</goal></goals>
            <configuration>
              <createDependencyReducedPom>false</createDependencyReducedPom>
              <filters>
                <filter>
                  <artifact>*:*</artifact>
                  <excludes>
                    <exclude>META-INF/*.SF</exclude>
                    <exclude>META-INF/*.DSA</exclude>
                    <exclude>META-INF/*.RSA</exclude>
                  </excludes>
                </filter>
              </filters>
              <transformers>
                <transformer implementation="org.apache.maven.plugins.shade.resource.ManifestResourceTransformer">
                  <mainClass>com.redis.demo.CircuitBreakerDemo</mainClass>
                </transformer>
                <transformer implementation="org.apache.maven.plugins.shade.resource.ServicesResourceTransformer"/>
              </transformers>
            </configuration>
          </execution>
        </executions>
      </plugin>
    </plugins>
  </build>
</project>
```

### `src/main/java/com/redis/demo/CircuitBreakerDemo.java`

> The authoritative copy is the file in the repo. If you edit behavior, edit there and rebuild;
> this block is for reading. (See the repo file for the always-current version.)

The class: loads `demo.properties`, builds a `MultiDbClient` with one `DatabaseConfig` per
configured database (endpoint + weight + optional auth/TLS), sets the circuit-breaker / retry /
failback tuning and `InitializationPolicy.ONE_AVAILABLE`, runs a startup probe, then launches
worker threads doing SET+GET while a once-per-second meter prints the live active database and
success rate. A `databaseSwitchListener` prints the FAILOVER / FAILBACK banners.

### `demo.properties.example`

```properties
db.count=2

db.1.name=North
db.1.endpoint=redis-12002.re-cluster1.ps-redislabs.org:12002
db.1.weight=1.0
db.1.username=
db.1.password=
db.1.tls=false

db.2.name=South
db.2.endpoint=redis-12002.re-cluster2.ps-redislabs.org:12002
db.2.weight=0.5
db.2.username=
db.2.password=
db.2.tls=false

# db.3.* available when db.count=3

workload.threads=4
workload.sleepMillis=1
cb.slidingWindowSecs=2
cb.failureRateThreshold=50
cb.minFailures=3
retry.maxAttempts=2
retry.waitMillis=100
failback.enabled=true
failback.intervalMs=15000
failback.gracePeriodMs=5000
socket.timeoutMs=1000
connect.timeoutMs=1000
```

### Scripts (`scripts/`)

| Script | Purpose |
|--------|---------|
| `setup.sh` | Install Java 17 + Maven if missing, then `mvn clean package` (fat jar). |
| `configure.sh` | Interactive prompts → writes `demo.properties` (Enter = ps-portal defaults). |
| `run.sh` | `java -jar target/...jar demo.properties`. |
| `block.sh [name]` | Resolve a DB's endpoint from config, add an `iptables` REJECT (simulate outage). |
| `unblock.sh [name]` | Remove the REJECT rule(s) and verify none remain. |
| `status.sh` | Ping every configured DB; list any active REJECT rules. |
| `_lib.sh` | Shared config-parsing helpers (sourced by the above). |

---

## Appendix — base64 install bundle

Code-only snapshot (`pom.xml`, `src/`, `scripts/`, `demo.properties.example`). Use with **Path B**
above. The repo is the source of truth; regenerate this bundle after code changes with:

```bash
COPYFILE_DISABLE=1 tar --no-xattrs -czf /tmp/cb-install.tgz pom.xml demo.properties.example scripts src
base64 -b 100 /tmp/cb-install.tgz
```

```
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
```
