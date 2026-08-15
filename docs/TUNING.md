# Tuning HavocFolia

## The one thing to understand first

Under Folia there is no meaningful "server TPS". Work is split across region threads; one region
can be melting while the rest of the world idles. Every tuning decision here keys off *per-region*
tick time, and so should yours. `/havoc regions` shows which region is actually in trouble —
`/havoc tps` alone will mislead you, because a healthy median hides one pathological region.

## Large view distance

The feature is "view distance 32 **when simulation distance stays modest**", and the caveat is
the entire point.

- **View distance** costs bandwidth and chunk serialisation. It scales with area, and it is
  largely off the critical path.
- **Simulation distance** costs region-thread time. It scales with area *and* entity count, and it
  lands directly on the thread that ticks your players.

So:

```properties
view-distance=32
simulation-distance=6
```

Raising `simulation-distance` to 32 will destroy tick times no matter what else you configure.
Raising `view-distance` to 32 mostly costs network.

### Adaptive view distance

When a region's 15-second mean tick time exceeds `shrink-above-mspt`, players in *that region*
lose one chunk of view distance. When it drops below `grow-below-mspt`, they gain one back.

The gap between the two thresholds is hysteresis, and narrowing it is the most common way to make
things worse: clients unload and reload the same ring of chunks forever, which costs more than the
tick time you saved. `adjust-interval-ticks: 100` (five seconds) is a floor on how often anything
can change. Do not lower it below ~60.

## Affinity scheduler

### When it helps

Pinning helps when tick time is **inconsistent** rather than uniformly high. If `/havoc regions`
shows a region averaging 20ms but spiking to 60ms with no obvious cause, thread migration is a
plausible culprit and pinning is worth trying. If every region sits at a flat 50ms, you have too
much work, and pinning will not create CPU that does not exist.

### Setup

```yaml
scheduler:
  mode: "AFFINITY"
  pin-strategy: "SPREAD"
  reserved-cores: 2
```

Then verify with `/havoc affinity`. If it reports inactive, the reason is printed — it will be one
of: not Linux, no `taskset` (`apt install util-linux`), cgroup does not permit those CPUs, or
thread names collide within 15 characters.

### Strategies

| Strategy | Use when |
| --- | --- |
| `SPREAD` | Default. One worker per physical core, skipping SMT siblings. |
| `COMPACT` | You have more regions than cores and accept contention. |
| `SMT_PAIRED` | Rarely. Only for IO-heavy region loads. |
| `ISOLATED` | You have `isolcpus` set and want exactly those CPUs. |

`SPREAD` is the default because putting two region workers on the two hyperthreads of one physical
core roughly halves each one's throughput. `SMT_PAIRED` exists for the uncommon case where regions
are blocked on IO rather than CPU.

### The flattest setup

Isolate cores at the kernel level, then hand them to HavocFolia and keep the JVM's own threads
away:

```
# kernel cmdline
isolcpus=4-15 nohz_full=4-15 rcu_nocbs=4-15
```

```yaml
scheduler:
  mode: "AFFINITY"
  pin-strategy: "ISOLATED"
  cpu-set: "4-15"
```

```bash
exec taskset -c 0-3 java "${FLAGS[@]}" -jar havocfolia.jar --nogui
```

### How pinning works, and why it might not

There is no `sched_setaffinity` in the JDK. Rather than ship a native library, HavocFolia uses a
property of the Linux JVM: it sets each OS thread's `comm` to the Java thread name, so
`/proc/self/task/<tid>/comm` identifies the thread and `taskset -cp` pins it.

Every limitation degrades to "no pinning", never to something broken:

- **Linux only.** Other platforms log and continue in `PARALLEL`.
- **`comm` truncates to 15 characters.** If two region workers share their first 15 characters,
  the manager refuses to pin anything rather than risk pinning the wrong thread.
- **Containers.** Pins are relative to the cgroup's CPU set; pinning outside it fails and is
  logged.
- **Needs `util-linux`.**

## JVM flags

Use `scripts/start.sh`. It uses **generational ZGC**, not the classic G1 "Aikar's flags".

Those flags are excellent for Paper and wrong for Folia. They tune G1 region sizing and trade
pause time for throughput on a *single* tick thread. Folia has many mutator threads, and a GC
pause stalls all of them at once — pause time is the metric that matters. Copying G1 flags onto a
Folia server is one of the most common causes of "Folia is slower than Paper for me".

Leave 4–8 GB of RAM to the OS page cache. Chunk IO lives there, and starving it costs more than
the extra heap gains.

## Optimization toggles

All in `havocfolia.yml` under `optimizations`, each with its trade-off written next to it. The two
worth knowing about:

- **`throttle-failed-spawn-attempts`** changes spawn *timing* under heavy load. Disable it if you
  run spawn-rate-sensitive farms.
- **`hopper-cooldown-ticks`** at 8 is invisible. Above ~16, players notice slow item sorters.

## A tuning order that works

1. `/havoc regions` — find the region that is actually slow.
2. Fix the cause if there is one (entity cramming, a farm, a chunk loader).
3. `simulation-distance` down before `view-distance` down.
4. ZGC flags from `start.sh`.
5. `AFFINITY` only if tick time is *inconsistent*.
6. Optimization toggles last — they change behaviour, and behaviour changes generate bug reports.
