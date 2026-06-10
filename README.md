# Redis Active-Active Circuit Breaker Demo (Jedis 7)

A plug-and-play demo of **client-side geographic failover**: a Java app runs a continuous
read/write workload against a **primary** Redis Active-Active database and, using the Jedis 7
`MultiDbClient` circuit breaker, **automatically fails over** to a standby region when the primary
goes down — then **fails back** when it recovers. No restart, no code change.

Works with **2 or 3** databases, **custom names** (North/South, East/West/Central, …), and optional
auth/TLS — all driven by a `demo.properties` file.

## Quick start (on the `load` / client machine)

```bash
git clone <YOUR_REMOTE_REPO_URL> "Circuit Breaker Jedis"
cd "Circuit Breaker Jedis"

./scripts/setup.sh        # install Java 17 + Maven (if needed) and build the fat jar
./scripts/configure.sh    # set endpoints / names / auth  (Enter = ps-portal defaults)
./scripts/run.sh          # start the workload + live availability meter
```

Trigger the failover from a second terminal:

```bash
./scripts/block.sh   North     # simulate an outage of the primary  -> failover
./scripts/unblock.sh North     # restore it                          -> failback
./scripts/status.sh            # ping all DBs + show active block rules
```

## Docs

- **[Notes.md](Notes.md)** — full setup, configuration reference, design notes, dependency
  gotchas, troubleshooting, and an offline base64 install bundle.
- **[Demo_Script.md](Demo_Script.md)** — the live run book: prep, the timed acts, and exactly
  what to say/highlight.

## Requirements

Ubuntu/x86_64 client with internet (Maven Central + GitHub), `redis-cli`, `sudo` (for installing
the toolchain and running `iptables` to trigger failover), and network reachability to your Redis
endpoints. Builds a single fat jar — no Docker.

## License

Internal demo asset.
