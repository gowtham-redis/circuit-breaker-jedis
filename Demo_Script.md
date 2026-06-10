# Demo Script — Active-Active Circuit Breaker (Client-Side Failover)

**The story (one sentence):** A Java app streams thousands of operations per second at our
**primary** Redis Active-Active database; we sever the network to it; the Jedis client detects
the failure, trips a **circuit breaker**, and reroutes every operation to the **standby** region
with no restart and no code change — then fails back automatically when the primary returns.

**Total run time:** ~5 minutes. **You need two terminals** on the `load` machine.

---

## PART 1 — PREP (before the audience is watching)

Do all of this *ahead of time* so the live portion is clean.

### P1. Build and configure (one-time on a fresh box)
```bash
cd "Circuit Breaker Jedis"
./scripts/setup.sh        # Java + Maven + fat jar  (skip if already built)
./scripts/configure.sh    # Enter = ps-portal North/South defaults
```

### P2. Confirm everything is green
```bash
./scripts/status.sh
```
Expect **both databases UP (PONG)** and **REJECT rules: none**. If a stale block rule exists from
a previous run, clear it now: `./scripts/unblock.sh North`.

### P3. Do a full private dry run
Run the entire Act sequence below once, end to end, before you present. You want to have seen the
failover dip, the banner, and the failback with your own eyes. Confirm:
- Failover banner fires within a few seconds of `block.sh`.
- Failback banner fires within ~15-20s of `unblock.sh`.
- Final summary shows `Failover occurred: YES` and ~99.9%+ success.

### P4. Reset to a clean start
```bash
./scripts/status.sh        # both UP, no REJECT rules
```
Leave **Terminal 1** empty (ready to run the app) and **Terminal 2** empty (ready to block).

> **Optional talking-point prep:** decide your numbers up front. At ~4,500 ops/s the failover
> "dip" is ~20-30 operations out of hundreds of thousands. That ~99.99% figure *is* your headline.

---

## PART 2 — THE ACTS (live)

### ACT 1 — Steady state  (≈ 0:00–0:45)

**Terminal 1:**
```bash
./scripts/run.sh
```
You'll see the banner, a startup-probe `OK` line, then a once-per-second meter:
```
[..:..:..]  Active: North    |  4500 ops/s | Success: 100.0% | Total:     45,000
```

**Say:** *"This is a live Java application doing about 4–5 thousand reads and writes every second
against our North Active-Active database. One client, 100% success. Watch the 'Active' column and
the success rate — those are the only two things that matter for the next five minutes."*

**Let it run ~30-45s** so the audience internalizes "North, 100%."

---

### ACT 2 — Trigger the outage  (≈ 0:45–1:15)

**Terminal 2:**
```bash
./scripts/block.sh North
```

**Say (as you hit Enter):** *"I'm now cutting all network access to North — simulating a regional
outage, a severed link, a data-center going dark."*

**Watch Terminal 1.** Within ~2-5 seconds:
```
!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
  *** FAILOVER:  now serving 'South' ***
  Circuit      : OPEN - primary unavailable, traffic rerouted
!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
[..:..:..]  Active: South    |  4400 ops/s | Success: 100.0% | Total:    ...  [FAILED OVER]
```

---

### ACT 3 — The money moment: the dip  (≈ 1:15–1:30)

> 🔑 **This is the most important thing to point at in the whole demo.** Right at the switch you'll
> see one line where success drops (e.g. `Success: 68.4%`). **Do not gloss over it — feature it.**

**Say:** *"See that one dip — a few dozen failed operations out of hundreds of thousands? That's
the client **detecting** the failure. A circuit breaker isn't psychic: it watches real commands
fail, and after a few failures in a 2-second window it trips and reroutes. The total cost of losing
an entire region was a few milliseconds of traffic — and then we're back to 100%, now on South. No
restart. No config change. No human."*

---

### ACT 4 — Stable on the standby  (≈ 1:30–2:30)

Let it run on South at 100%.

**Say:** *"We've been running on South for a minute now, fully healthy. Because these are
**Active-Active** databases, both regions were always in sync — South didn't 'become' a copy at
failover, it was live the whole time. The app never knew the difference."*

---

### ACT 5 — Restore and fail back  (≈ 2:30–3:30)

**Terminal 2:**
```bash
./scripts/unblock.sh North
```

**Say:** *"Now I'll bring North back."* Then point to Terminal 1. Within ~15-20 seconds:
```
****************************************************************
  *** FAILBACK:  restored to 'North' ***
  Circuit      : CLOSED - primary healthy, traffic restored
****************************************************************
[..:..:..]  Active: North    |  4600 ops/s | Success: 100.0% | Total:    ...  [RESTORED]
```

**Say:** *"The client has been quietly probing North in the background. The moment it's healthy,
the circuit closes and traffic returns to our primary — automatically. This 15-second pause is the
**failback grace period**: it's deliberate, so a flapping region can't bounce traffic back and
forth. All tunable in config."*

---

### ACT 6 — The summary slide  (≈ 3:30–5:00)

**Terminal 1:** press `Ctrl-C`.
```
================================================================
  DEMO SUMMARY
================================================================
  Total operations : 1,200,000
  Successful       : 1,199,976
  Failed           :        24
  Overall success  : 99.9980%
  Failover occurred: YES
================================================================
```

**Say (close):** *"One client library, two regions, an entire region lost and recovered — and the
application code was just `set` and `get`. Failover, failback, health-probing, the circuit breaker:
all inside the Jedis client. **99.99% availability through a full regional outage, with zero
operational intervention.** That's client-side geographic failover."*

---

## Highlights to hit (your cheat-sheet)

| Moment | The one thing to say |
|--------|----------------------|
| Steady state | "One client, ~4,500 ops/s, 100% — watch *Active* and *Success*." |
| `block.sh` | "Cutting the network to North — a regional outage." |
| **The dip** | "Those ~24 fails are the **detection cost** — proof it's real, not staged." |
| On South | "Active-Active: South was live all along, always in sync." |
| `unblock.sh` | "Background probing → automatic failback. The pause is a deliberate grace period." |
| Summary | "99.99% through a full outage, app code is just get/set, zero intervention." |

## If someone asks…

- **"Were those failed writes lost?"** — The app got an error for them, exactly as a real app
  would, and would retry at the application layer. Active-Active keeps both regions converging;
  nothing is silently dropped.
- **"Why not zero failures?"** — A client-side breaker *must* observe failures to know the region
  is down. Zero would mean it was faked. The dip is the honesty of the demo.
- **"Can it be faster?"** — Yes: `cb.minFailures`, `failback.intervalMs`, `gracePeriodMs`,
  timeouts are all in `demo.properties`. We use production-sane values, not the absolute minimum.
- **"Three regions?"** — Set `db.count=3` in config (e.g. East/West/Central); it cascades
  primary → next-highest-weight on each failure.

---

## CLEANUP (always, after the demo)
```bash
./scripts/unblock.sh North     # remove any block rule
./scripts/status.sh            # confirm: all UP, REJECT rules → none
```
Leaving a stale `iptables` REJECT rule is the #1 thing that breaks the *next* run.
