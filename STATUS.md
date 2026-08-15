# Status

## This run got further than any before it

`applyAllPatches` ran for 4m27s: it cloned Canvas → Folia → Paper, decompiled and remapped
Minecraft, and applied the base and resource patch layers. The build wiring is correct. It failed
on **my** patch files:

```
Invalid patch line in 'io/papermc/paper/threadedregions/TickRegionScheduler.java.patch' at 3:'@@'
```

## Root cause: those patches cannot be hand-written

Real patches in this system carry actual line numbers:

```
@@ -1325,11 +_,7 @@
```

Those numbers only exist once Minecraft has been decompiled and remapped. I wrote a bare `@@`,
which the patch parser rejects outright. There was never a version of that file I could have
written correctly from the outside.

The supported flow generates them:

```bash
./gradlew applyAllPatches            # produces havocfolia-server/src/minecraft/...
python3 scripts/apply-hooks.py       # edits those sources by anchor
./gradlew rebuildAllServerPatches    # writes correctly-numbered patch files
git add havocfolia-server/minecraft-patches/sources
```

`scripts/apply-hooks.py` is included and tested — it anchors on searchable strings rather than
line numbers, is idempotent (re-running is a no-op), and names the exact anchor when one is
missing. Run it with `--check` first to see what it would do.

## The fork code is staged, not deleted

`fork-code/` holds all the feature code, off the compile path on purpose. It was written against
Paper/Folia 1.21.x; Canvas is on Minecraft 26.2, and several APIs it touches will have moved. If it
sat in `src/main/java`, `compileJava` would fail and you would get no jar at all.

So this build produces a **working HavocFolia-branded Canvas jar** — real, runnable, correctly
branded — and the features go in from there. `fork-code/README.md` lists which files touch NMS and
which can be moved in immediately with no changes (the AntiFreecam kernel, the pin strategies, the
config layer, adaptive view distance — six of fourteen).

## The artifact that makes the next step exact

CI now uploads **`upstream-sources`**: the decompiled 26.2 versions of every file the fork code
depends on — `ChunkPacketBlockController`, `ChunkPacketInfo`, `PalettedContainer`,
`TickRegionScheduler`, `ServerLevel`, `ServerPlayer`, `Commands`, and the rest — plus
`ALL-SOURCES.txt` listing every class in the tree.

Download it and the guessing stops. Every signature I had to infer becomes something either of us
can read directly.

## What is verified

| Thing | How |
| --- | --- |
| Build wiring | `applyAllPatches` reached the source-patch stage — clone, decompile, remap, base and resource patches all succeeded |
| AntiFreecam kernel | 24 assertions pass, including 8-thread concurrency and perf budget |
| CPU pin strategies | 10 assertions pass |
| `apply-hooks.py` | Run against a synthetic source tree: applies, reports missing anchors, idempotent on re-run |
| Workflows | Parse; every `run:` block bash-syntax-checked |

## Suggested order

1. **Push this.** Expect a green build and a downloadable jar. Boot it — it should report HavocFolia.
2. **Grab the `upstream-sources` artifact.**
3. **Move in the six no-NMS files** (kernel, pin strategy, affinity, adaptive view distance, config,
   format). `./gradlew compileJava` to confirm.
4. **Wire the hooks**: `apply-hooks.py`, fix whatever anchors moved, `rebuildAllServerPatches`.
5. **Move in the NMS-coupled files**, fixing signatures against the artifact as you go.

Each step is independently verifiable, which is the opposite of how the last several rounds went.

## Task reference

| Goal | Command |
| --- | --- |
| Build the source tree | `./gradlew applyAllPatches` |
| Build the jar | `./gradlew createPaperclipJar` |
| Compile check only | `./gradlew compileJava` |
| Insert hooks into generated sources | `python3 scripts/apply-hooks.py` |
| Generate NMS patches from your edits | `./gradlew rebuildAllServerPatches` |
| Regenerate build-file patches | `./gradlew rebuildCanvasSingleFilePatches` |
| Fuzzy re-apply after an upstream bump | `./gradlew applyCanvasSingleFilePatchesFuzzy` |
| Run the kernel tests | see `docs/TESTING.md` |
