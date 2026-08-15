# HavocFolia

High performance dedicated server software based on **CanvasMC** and **Folia**. Includes region
threading fixes and native AntiFreecam.

---

## What this is

A fork source repository, not a prebuilt `server.jar`. A Folia fork cannot ship as one compiled
jar — the patcher decompiles and remaps Minecraft, replays the Canvas → Folia → Paper patch
stacks, then applies yours. Push this to GitHub and `.github/workflows/build.yml` produces
`havocfolia-<version>.jar`, or run two Gradle tasks locally.

**This repo is generated from [CraftCanvasMC/Baguette](https://github.com/CraftCanvasMC/Baguette),
the official template for maintaining a Canvas fork**, with HavocFolia's code and patches added.
That matters: the build wiring — the `forks.register` block, the access-transformer files, the
module-rename patches, the committed Gradle wrapper — is upstream's own, not hand-derived. Read
[STATUS.md](STATUS.md) for what is verified and what still needs your hands.

---

## Building

```bash
./gradlew applyAllPatches      # clones upstreams, decompiles + remaps MC, applies patches
./gradlew createPaperclipJar   # produces the runnable jar
```

Requires **JDK 25**, ~10 GB free disk, and 6 GB of RAM for Gradle. The wrapper pins Gradle 9.6.1.
First run takes 20–40 minutes; later runs hit the paperweight cache.

CI does the same on every push. Tag with `v1.2.3` to cut a GitHub Release.

`.github/workflows/upstream.yml` (inherited from the template) opens automated upstream bumps every
three days. It needs `BOT_TOKEN` and `PUSH_TOKEN` secrets — delete the file if you don't want it.

---

## Features

### Region threading
Folia-style region threading with CanvasMC's stability and performance patches on top. Nothing in
this fork adds cross-region synchronisation, and the diagnostics read only atomically published
state, so `/havoc regions` can never deadlock the region you are trying to diagnose.

### Affinity scheduler
Optional `AFFINITY` region scheduler with CPU pinning. Folia's region workers are long-lived
threads with hot working sets; when the OS migrates one between cores its cache goes cold and the
next tick pays for it. That shows up as p99 tick variance rather than a worse average — which is
what players actually feel. Four strategies, CPU reservation for GC and Netty, no JNI. See
[docs/TUNING.md](docs/TUNING.md).

### Native AntiFreecam
Hides deep block data from freecam clients, inside the chunk-packet pipeline — no PacketEvents, no
ProtocolLib, no extra packet copy. Anti-xray does not stop freecam: engine-mode 1 just swaps ore
ids and the user looks at them from inside the wall. AntiFreecam instead replaces anything more
than `max-depth` blocks behind the nearest opening, so hidden terrain is not disguised — it is
never sent. See [docs/ANTIFREECAM.md](docs/ANTIFREECAM.md).

### Large view distance
View distance 32 **when simulation distance stays modest** — that caveat is the feature. Sending
chunks costs bandwidth and serialisation; *ticking* them costs region-thread time, and only the
second is dangerous. Adaptive view distance shrinks a player's radius when their own region is over
budget and grows it back when it recovers, with hysteresis so clients don't thrash chunk loads.

### Dense server optimizations
Palette-direct chunk serialisation, cached occlusion tables, empty-section skipping, lazy entity
tracking, spawn-attempt backoff, hopper search deferral. Every one is a toggle with its trade-off
written next to it in `havocfolia.yml`.

---

## Running

```bash
JAR=havocfolia-26.2-42.jar MEMORY=8G ./scripts/start.sh
```

Uses generational ZGC, not the classic G1 "Aikar's flags". Those tune G1 for a *single* tick thread
and trade pause time for throughput; Folia has many mutator threads and pause time is what hurts.
Then set `view-distance=32` and `simulation-distance=6` in `server.properties`.

---

## Commands

| Command | Description |
| --- | --- |
| `/havoc version` | Build, upstream, scheduler mode, AntiFreecam state |
| `/havoc reload` | Reload `havocfolia.yml` |
| `/havoc tps` | Region tick summary — worst and median MSPT |
| `/havoc regions` | Busiest regions with chunk and player counts |
| `/havoc affinity [reapply]` | Show or re-apply the thread→CPU pin map |
| `/havoc freecam [status\|reset]` | AntiFreecam counters and cost per chunk |

Aliased to `/hf`. Requires `havocfolia.command.admin` or op level 3.

---

## Layout

| Path | What |
| --- | --- |
| `havocfolia-server/src/main/java/gg/havoc/folia/` | Fork code — plain sources, no patch needed |
| `havocfolia-server/minecraft-patches/sources/` | One-line hooks into NMS classes |
| `havocfolia-server/build.gradle.kts.patch` | Fork registration and branding |
| `build-data/*.at` | Access transformers per patch set |
| `docs/` | AntiFreecam internals, tuning, hooks, testing |

Generated directories (`canvas-server`, `paper-api`, `havocfolia-server/build.gradle.kts`, …) are
gitignored — they are produced by `applyAllPatches`.

---

## Licence

Paper, Folia and CanvasMC are **GPL-3.0**, so this fork is too. `scripts/setup.sh` fetches the full
licence text; do that before distributing a build, and keep your patches and sources public.
