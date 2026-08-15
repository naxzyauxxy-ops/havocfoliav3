# Where this actually stands

Read this before the README.

## What I can and cannot verify

I built and iterated on this without being able to run the build. This sandbox cannot reach
`repo.papermc.io`, `maven.canvasmc.io`, Mojang's servers or `services.gradle.org`, so
`applyAllPatches` has never executed here. That is why you got four rounds of fixes instead of one
working config, and it is worth being blunt about rather than shipping a fifth confident guess.

**Verified by execution:**

| Thing | How |
| --- | --- |
| AntiFreecam kernel | 24 assertions, compiled and run — including 8-thread concurrency and perf |
| CPU pin strategies | 10 assertions, compiled and run |
| Workflow YAML | Parsed; every `run:` block bash-syntax-checked |
| Preflight probe | Run against the live Canvas repo — returned Gradle 9.6.1, Java 25, mc 26.2 |
| `canvasRef` | Real commit, resolved from `git ls-remote` today |

**Verified by reading upstream source, not by running:**

| Thing | Source |
| --- | --- |
| `upstreams.canvas { }` DSL, `ref`/`patchFile`/`patchDir` shapes | weaver `UpstreamConfig.kt`, `PaperweightPatcherExtension.kt` |
| Task names `applyAllPatches`, `createPaperclipJar`, `rebuildAllServerPatches` | weaver `PaperclipTasks.kt`, `UpstreamConfigTasks.kt`, Canvas README |
| Patch layout `minecraft-patches/sources/<pkg>/<Class>.java.patch` | Canvas's own repo tree |
| Subproject build files are generated, not committed | Canvas `.gitignore` |
| No `./gradlew` shell-out for nested builds | weaver `RunNestedBuild.kt` |

**Not verified at all — the honest risk list:**

1. `patchDir("paperApi")` wiring for a fork-of-a-fork. Inferred by analogy with how Canvas patches
   Paper's API. This is the single most likely thing to still be wrong.
2. Whether `patchFile` tolerates a missing `.patch` on the first run (I believe it copies verbatim;
   the bootstrap step assumes this).
3. Whether the five source patches apply. They almost certainly do not — see below.

## The one manual step I could not automate

A paperweight fork's first build has a bootstrap that cannot be done blind. `applyAllPatches`
generates `havocfolia-server/build.gradle.kts` from Canvas's, and that copy still refers to
upstream's module names (`canvas-api`, `canvasServer`). Those do not exist in this project, so
configuration fails on the next task.

The workflow now handles this automatically with a `Bootstrap generated build files` step that
renames the references, and uploads the generated files as an artifact. To make it permanent:

```bash
gradle applyAllPatches
# edit havocfolia-server/build.gradle.kts if the sed did not catch everything
gradle rebuildCanvasSingleFilePatches
git add havocfolia-server/build.gradle.kts.patch havocfolia-api/build.gradle.kts.patch
```

Once those `.patch` files are committed the bootstrap step becomes a no-op.

## What will fail next, and why that is fine

The five hook patches in `havocfolia-server/minecraft-patches/sources/` are written against
Paper/Folia 1.21.x method shapes. Canvas main is on Minecraft **26.2**. Expect at least
`ServerPlayer#tick` and the `chunkPacketBlockController` assignment to have moved.

This is normal fork maintenance, not a broken config. The workflow uploads a `patch-rejects`
artifact on failure containing the `.rej` files, which name the exact hunk that failed. Fix by
hand, then regenerate:

```bash
# edit havocfolia-server/src/minecraft/net/minecraft/...
gradle rebuildAllServerPatches
```

`docs/HOOKS.md` has the anchor string and the exact insertion for each of the five.

## Task reference

| Goal | Command |
| --- | --- |
| Build the source tree | `gradle applyAllPatches` |
| Build the jar | `gradle :havocfolia-server:createPaperclipJar` |
| Regenerate NMS patches | `gradle rebuildAllServerPatches` |
| Regenerate build-file patches | `gradle rebuildCanvasSingleFilePatches` |
| Regenerate API patches | `gradle rebuildPaperApiPatches` |
| Run the kernel tests | see `docs/TESTING.md` |

## If you want a 1.21.x server instead

Canvas keeps version branches — `ver/1.21.11` exists and targets Java 21 with different task
names. Most plugins lag the newest Minecraft, so this may be what you actually want. Set
`canvasBranch = ver/1.21.11` in `gradle.properties`, pin a `canvasRef` from that branch, and the
preflight job will auto-detect the right Gradle and Java versions for it. Check that branch's
README for its jar task name before changing the build step.
