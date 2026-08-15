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


## If you extracted this zip over an existing checkout

Unzipping adds and overwrites files. It never **deletes** files that are no longer in the archive.
So the five hand-written `.patch` files are still in your repo even though they are not in the
zip — which is why the same `Invalid patch line ... at 3:'@@'` error came back.

Clean the tree properly:

```bash
cd your-repo
git ls-files -z | xargs -0 rm -f            # drop every tracked file from disk
unzip -o ~/Downloads/HavocFolia.zip -d /tmp/hf
cp -a /tmp/hf/HavocFolia/. .                # repopulate from the archive
git add -A
git status                                  # deletions should be listed here
git commit -m "Rebuild from template"
git push
```

Nothing is lost — git history keeps everything. `git status` before committing shows exactly what
went away; you should see the five `minecraft-patches/sources/*.patch` files and the old
`src/main/java/gg/**` tree deleted.

Stale paths to expect from earlier zips:

```
havocfolia-server/minecraft-patches/sources/**
havocfolia-server/src/main/java/gg/**
havocfolia-api/src/main/java/gg/**
havocfolia-server/src/test/java/gg/**
patches/**
```

`scripts/validate-patches.py` now runs in CI before anything expensive, so a stale patch fails the
build in about five seconds with a message naming the file — rather than two minutes in, pointing
at the symptom.

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
