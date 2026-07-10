# Redis Active-Active Circuit Breaker Demo (Jedis 7)

A plug-and-play demo of **client-side geographic failover**. A small Java app runs a continuous
read/write workload against a **primary** Redis Active-Active database. Using the Jedis 7
`MultiDbClient` circuit breaker, it **automatically fails over** to a standby region when the
primary goes down — and **fails back** when it recovers. No restart, no application code change.

- Works with **2 or 3** databases
- **Custom names** (North/South, East/West/Central, …)
- Optional **auth** (none / password / ACL user) and **TLS** per database
- Everything driven by one `demo.properties` file

> **Presenting this to a customer?** The live run book — what to do and what to say, minute by
> minute — is in **[Demo_Guide.md](Demo_Guide.md)**. This README is how to set it up.

---

## Requirements

Run everything on the **machine that reaches your Redis endpoints** (on ps-labs, that's the `load`
box). Works on any host; the only hard requirement is for the failover *trigger*:

- **Internet access** (to fetch Java/Maven and the build dependencies) and **`git`**
- **`redis-cli`** (handy for the health checks; pre-installed on the ps-labs `load` box)
- Network reachability from this machine to **all** your Redis database endpoints
- **For the failover trigger only:** **Linux + `iptables` + `sudo`** (the `block.sh`/`unblock.sh`
  scripts). The app itself runs anywhere with Java — on a non-Linux host you'd simulate the outage
  another way (host firewall, or take the endpoint down).

Java 17 + Maven are installed automatically by `setup.sh` (detects `apt`/`dnf`/`yum`/`brew`) if
they're missing. No Docker — the build produces a single self-contained fat jar.

---

## Setup — four steps

### 1. Download
```bash
git clone https://github.com/gowtham-redis/circuit-breaker-jedis.git "Circuit Breaker Jedis"
cd "Circuit Breaker Jedis"
```
> This repo is **private** — cloning over HTTPS will prompt for your GitHub username and a
> **Personal Access Token** (a password won't work). Anyone you've granted repo access can clone
> the same way.

### 2. Build
```bash
./scripts/setup.sh
```
Installs Java 17 + Maven if needed (via `apt`/`dnf`/`yum`/`brew`), then builds the fat jar.
(First run downloads dependencies, ~1 min.)

### 3. Configure your endpoints
```bash
./scripts/configure.sh
```
Interactive prompts for each database — **press Enter to accept the default in brackets**
(defaults target the ps-portal North/South lab). It asks for:

- **How many databases** (2 or 3)
- For each: **name**, **endpoint** (`host:port`), **weight**, **auth type** (none / password / acl), **TLS**

This writes `demo.properties`. You can also copy `demo.properties.example` and edit it by hand.

### 4. Run
```bash
./scripts/run.sh
```
You'll see a startup banner, a connectivity probe, then a once-per-second live meter:
```
[14:02:11]  Active: North    |  4500 ops/s | Success: 100.0% | Total:     45,000
```

---

## Triggering failover

From a **second terminal** on the same machine (the workload keeps running in the first):

```bash
./scripts/block.sh   North     # simulate an outage of a database  -> failover
./scripts/unblock.sh North     # restore it                         -> failback
./scripts/status.sh            # ping every database + show active block rules
```

`block.sh` / `unblock.sh` take a **database name** from your config (default = the primary). They
resolve its endpoint and add/remove a precise `iptables` REJECT rule for you — no manual IP lookup.

> **Always finish with `./scripts/unblock.sh` and `./scripts/status.sh`** (rules → "none").
> A leftover REJECT rule is the #1 thing that breaks the next run.

---

## Input parameters (`demo.properties`)

`configure.sh` writes this; edit it any time to retune (no rebuild needed). Endpoints are plain
`host:port` (no `redis://` scheme).

| Key | Meaning | Default |
|-----|---------|---------|
| `db.count` | Number of databases: `2` or `3` | `2` |
| `db.N.name` | Display name (any string) | North / South / Central |
| `db.N.endpoint` | `host:port` | ps-portal cluster endpoints |
| `db.N.weight` | Higher = preferred; the highest-weight DB is the **primary** | `1.0`, `0.5`, `0.25` |
| `db.N.username` | ACL username (blank = none) | *(blank)* |
| `db.N.password` | Password (blank = unauthenticated) | *(blank)* |
| `db.N.tls` | `true` to connect over TLS | `false` |
| `workload.threads` | Concurrent worker threads | `4` |
| `workload.sleepMillis` | Pause per loop (throttle throughput) | `1` |
| `cb.slidingWindowSecs` | Circuit-breaker failure window (seconds) | `2` |
| `cb.failureRateThreshold` | % failures that opens the circuit | `50` |
| `cb.minFailures` | Minimum failures before the circuit can open | `3` |
| `retry.maxAttempts` | Attempts per command before it counts as a failure | `2` |
| `retry.waitMillis` | Wait between retries (ms) | `100` |
| `failback.enabled` | Auto-return to the primary on recovery | `true` |
| `failback.intervalMs` | How often to probe the primary (ms) | `15000` |
| `failback.gracePeriodMs` | Settle time before failback (ms) | `5000` |
| `socket.timeoutMs` / `connect.timeoutMs` | Per-connection timeouts (ms) | `1000` |

**Auth:** unauthenticated → leave both blank · password-only (default user) → set `password` ·
ACL user → set both `username` and `password`.

> `demo.properties` is **git-ignored** because it can contain passwords. Only
> `demo.properties.example` is committed. Never commit real credentials.

---

## How it works

- **`MultiDbClient`** (Jedis 7) holds every database and routes each command to the active one —
  so your application code stays plain `client.set(...)` / `client.get(...)`. All failover logic
  lives inside the client.
- **Weights** pick the preferred database; the highest weight is the primary, the rest are standbys.
- The **circuit breaker** (Resilience4j) watches real command failures. Once `cb.minFailures`
  failures cross `cb.failureRateThreshold` within the window, it opens and reroutes. A brief, tiny
  dip in success rate at the moment of failover is therefore **expected and correct** — it's the
  detection cost, not a bug. (It's also your proof the failover is real, not staged.)
- **Boots on any healthy database** (`InitializationPolicy.ONE_AVAILABLE`), so you can even start
  the app during an outage and it comes up on a surviving region.
- The live meter reads the **true active endpoint** each second, so the label is always accurate.
- A **startup probe** runs one SET/GET before the workload, so connectivity problems fail loudly
  with a clear error instead of silently showing zero throughput.

---

## Project layout

```
README.md                  This file — setup & reference
Demo_Guide.md              The live presentation run book (prep + acts + what to say)
pom.xml                    Maven build (fat jar)
demo.properties.example    Config template (copy to demo.properties)
scripts/
  setup.sh                 Install Java+Maven, build the fat jar
  configure.sh             Interactive config generator
  run.sh                   Start the demo
  block.sh / unblock.sh    Trigger / clear a failover by database name
  status.sh                Ping all databases + show active block rules
src/main/java/com/redis/demo/CircuitBreakerDemo.java
```

---

## Troubleshooting

| Symptom | Fix |
|---------|-----|
| `mvn: command not found` | `./scripts/setup.sh` installs it (or `sudo apt-get install -y openjdk-17-jdk-headless maven`) |
| Build: `Source option 5 is no longer supported` | The pom pins compiler plugin 3.11.0 — confirm `mvn -v` uses it |
| `NoClassDefFoundError: .../decorators/Decorators` | Stale build; rebuild clean (`resilience4j-all` is already in the pom) |
| Startup probe FAILS with a stack trace | No database reachable. Run `./scripts/status.sh`; check endpoints, auth, TLS |
| `Initialization failed due to initialization policy` | Old jar; rebuild (current build boots on any one healthy DB) |
| Failover doesn't fire after `block.sh` | `./scripts/status.sh` — confirm the REJECT rule is present and the DB shows DOWN |
| Failback takes ~15–20s | Normal: `failback.intervalMs` (15s) + `gracePeriodMs` (5s). Lower them in config to speed up |

---

## Notes

Sample/demo asset, provided as-is for evaluation and enablement. Built on
[Jedis 7](https://redis.io/docs/latest/develop/clients/jedis/failover/) `MultiDbClient`.
