# HavocFolia

High performance dedicated server software based on **CanvasMC** and **Folia**. Includes region
threading fixes and native AntiFreecam.

---

## Read this first

**This is a fork source repository, not a prebuilt `server.jar`.** A Folia fork cannot ship as one
compiled jar: the patcher decompiles and remaps Minecraft, replays Canvas -> Folia -> Paper patch
stacks, then applies yours. Push this to GitHub and `.github/workflows/build.yml` produces
`havocfolia-<version>.jar`, or run the two Gradle tasks locally.

**Read [STATUS.md](STATUS.md) before you start.** It states plainly what has been verified by
running it, what was verified by reading upstream source, what has not been verified at all, and
the one bootstrap step that needs your hands on it.

Upstream is **CanvasMC** (which chains through Folia to Paper). Canvas main currently targets
Minecraft 26.2 on Java 25 with Gradle 9.6.1 — the CI reads all three out of the pinned upstream
commit rather than hardcoding them, so it cannot drift.

## Features

### Region threading
Built on Folia-style region threading with upstream stability and performance patches from
CanvasMC. Each region ticks independently on its own thread; nothing in this fork adds
cross-region synchronisation, and the diagnostics (`/havoc regions`) read only atomically
published state so they can never deadlock the thing you are trying to diagnose.

### Affinity scheduler
Optional `AFFINITY` region scheduler with CPU pinning. Folia's region workers are long-lived
threads with hot working sets; when the OS migrates one between cores its cache goes cold and the
next tick pays for it. That shows up as p99 tick variance rather than a worse average — which is
what players actually feel. Four strategies (`SPREAD`, `COMPACT`, `SMT_PAIRED`, `ISOLATED`), CPU
reservation for GC and Netty, and no JNI. See [docs/TUNING.md](docs/TUNING.md).

### Native AntiFreecam
Hides deep block data from freecam clients. Runs natively inside the chunk-packet pipeline — no
PacketEvents, no ProtocolLib, no extra packet copy. Anti-xray does not stop freecam (engine-mode 1
just swaps ore ids; the user looks at them from inside the wall). AntiFreecam instead replaces
anything more than `max-depth` blocks behind the nearest opening, so hidden terrain is not
obfuscated — it is not sent. See [docs/ANTIFREECAM.md](docs/ANTIFREECAM.md).

### Large view distance
Supports high view distance including 32 chunks **when simulation distance stays modest**. That
caveat is the whole feature: sending chunks costs bandwidth and serialisation, ticking them costs
region-thread time, and only the second one is dangerous. Adaptive view distance shrinks a
player's radius when *their own region* is over budget and grows it back when it recovers, with
hysteresis so clients do not thrash chunk loads.

### Dense server optimizations
Palette-direct chunk serialisation, cached occlusion tables, empty-section skipping, lazy entity
tracking, spawn-attempt backoff and hopper search deferral. Every one is a toggle with its
trade-off written next to it in `havocfolia.yml`, because "optimizations" that silently change
game behaviour are how forks get a bad reputation.

---

## Building

### Via GitHub Actions (recommended)

1. Push this repository to GitHub.
2. Set `upstreamCommit` in `gradle.properties` to a real CanvasMC commit:
   ```bash
   git ls-remote https://github.com/CraftCanvasMC/Canvas.git main
   ```
   The workflow **fails fast** if you leave the placeholder in place.
3. Push, or run the workflow manually from the Actions tab.
4. Download `havocfolia-<version>.jar` from the run's artifacts. Tag with `v1.2.3` to cut a
   GitHub Release instead.

The pipeline builds the jar, then boots it on a flat world, waits for `Done`, runs
`havoc version`, and fails if the fork branding never appears — so a build that compiles but does
not actually load the fork is caught in CI rather than on your server.

### Locally

```bash
./scripts/setup.sh                        # checks Java, fetches LICENSE, makes the wrapper
./gradlew applyAllPatches                 # 20-40 min the first time
./gradlew createMojmapPaperclipJar
```

Requires JDK 21+, ~10 GB free disk, and 6 GB of RAM for Gradle.

---

## Wiring the hooks

Five one-line hooks connect the fork to upstream. They live in
`havocfolia-server/minecraft-patches/features/` and are written against Paper/Folia
1.21.x layouts.

**Upstream moves these methods regularly, so treat them as templates, not gospel.**
[docs/HOOKS.md](docs/HOOKS.md) gives the exact anchor for each one and what to do when a patch
fails to apply. Everything else — all the actual logic — lives in
`havocfolia-server/src/main/java/gg/havoc/folia/` as ordinary source files that need no patch at
all, which is why re-anchoring after an upstream bump is a ten-minute job rather than a rebase
from hell.

---

## Running

```bash
JAR=havocfolia-1.21.8-42.jar MEMORY=8G ./scripts/start.sh
```

`scripts/start.sh` uses generational ZGC rather than the classic G1 "Aikar's flags". Those flags
tune G1 region sizes and trade pause time for throughput on a *single* tick thread; under Folia
you have many mutator threads and pause time is what hurts. Do not copy G1 flags onto this.

Then, in `server.properties`:

```properties
view-distance=32
simulation-distance=6
```

---

## Commands

| Command | Description |
| --- | --- |
| `/havoc version` | Build, upstream, scheduler mode, AntiFreecam state |
| `/havoc reload` | Reload `havocfolia.yml` (config only) |
| `/havoc tps` | Region tick summary — worst and median MSPT |
| `/havoc regions` | Busiest regions with chunk and player counts |
| `/havoc affinity [reapply]` | Show or re-apply the thread→CPU pin map |
| `/havoc freecam [status\|reset]` | AntiFreecam counters and cost per chunk |

Aliased to `/hf`. Requires `havocfolia.command.admin` or op level 3.

---

## Licence

Paper, Folia and CanvasMC are **GPL-3.0**, so this fork and anything you distribute from it must
be GPL-3.0 too. `scripts/setup.sh` fetches the full licence text into `LICENSE` — do that before
you publish a build. Keep your patches and sources public.
