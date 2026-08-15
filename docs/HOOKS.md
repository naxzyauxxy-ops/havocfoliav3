# Wiring the hooks

Five one-line hooks connect HavocFolia to upstream. Everything else is ordinary source in
`havocfolia-server/src/main/java/gg/havoc/folia/` and needs no patch.

That split is deliberate. Patches that touch upstream files break on every upstream bump; source
files that upstream never sees do not. Keeping the diff surface to five single-line insertions
means an upstream rebase is a short mechanical job instead of a weekend.

## The honest caveat

The `.patch` files ship with **context lines written against Paper/Folia 1.21.x layouts, not
against your pinned commit**. Mojang renames things, Paper refactors, CanvasMC moves scheduler
internals. Some of these will not apply cleanly, and that is expected.

When one fails, do not fight the patch — apply the insertion by hand, then regenerate:

```bash
./gradlew rebuildAllPatches
```

That rewrites the patch file from what is actually in the tree, correctly anchored to your commit.

## The five hooks

### 1. Bootstrap — `DedicatedServer#initServer`

```java
gg.havoc.folia.HavocFolia.bootstrap(this.getServerDirectory());
```

First line of `initServer()`, before the console handler thread starts. Must run before any world
loads, because `ServerLevel`'s constructor reads the config this call populates.

**Anchor:** search for `public boolean initServer()`.

### 2. AntiFreecam controller — `ServerLevel` constructor

Replace the assignment to `this.chunkPacketBlockController` so it prefers HavocFolia's controller,
falling back to Paper's anti-xray selection when AntiFreecam is disabled.

**Anchor:** search for `chunkPacketBlockController =`. In some versions this lives in
`Level`, not `ServerLevel`. If Paper has moved to a factory method, hook the factory instead — the
only requirement is that each `ServerLevel` ends up holding an `AntiFreecamController`.

### 3. Scheduler start — `TickRegionScheduler#init`

```java
gg.havoc.folia.HavocFolia.onSchedulerStart();
```

Must run **after** the region worker threads exist — pinning cannot find a thread that has not
started. If CanvasMC has renamed or restructured the thread pool startup, anchor on whatever call
actually spawns the workers and place this immediately after it.

**Anchor:** search for `threadPool.start()`.

### 4. Commands — `Commands` constructor

```java
gg.havoc.folia.command.HavocCommand.register(this.dispatcher);
```

At the end of the constructor, after vanilla registrations. Registering earlier means vanilla can
overwrite the node.

**Anchor:** search for `PublishCommand.register`.

### 5. Adaptive view distance — `ServerPlayer#tick`

Calls `AdaptiveViewDistance.evaluate` with the current region's MSPT and applies the result.

**Anchor:** search for `public void tick()` in `ServerPlayer`.

Two things to verify against your upstream:

- **`setPlayerViewDistance` may not exist under that name.** Folia moved per-player view distance
  into the regionised chunk map. Find the per-player setter and call that.
- **`RegionMspt.current()` must be called on a region thread.** It returns 0 off-thread, which
  reads as "no pressure" — failing open, so a wrong answer means view distance does not shrink
  rather than the world breaking. If you move this call somewhere that is not a region tick, it
  silently stops working; there is no error.

## Verifying

The CI smoke test catches a failed wiring: it boots the jar, waits for `Done`, runs
`havoc version`, and fails if `HavocFolia` never appears in the log. Locally:

```bash
grep -i havocfolia logs/latest.log
```

You should see the branding line and the AntiFreecam state during startup. If you see neither,
hook 1 did not apply. If you see branding but `/havoc` is unknown, hook 4 did not apply.
