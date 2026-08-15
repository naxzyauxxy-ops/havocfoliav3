# Status

## What changed in this rebuild

The previous four attempts failed because I hand-derived the fork's build wiring from reading
plugin source. That was the wrong approach: **CanvasMC publishes an official fork template,
[CraftCanvasMC/Baguette](https://github.com/CraftCanvasMC/Baguette)**, and this repo is now
generated from it rather than from my inference.

Things the template supplied that I had wrong or missing entirely:

| Piece | Before | Now |
| --- | --- | --- |
| `forks.register("...") { forks = canvas }` + `activeFork` | **Missing entirely** — nothing declared this as a fork | From the template's server patch |
| `patchRepo("paperServer")` / `patchDir("canvasServer")` | Missing | From the template |
| `build-data/*.at` access transformers | Missing (7 files) | From the template |
| Module-rename patches | I tried to generate these with `sed` at CI time | Real committed `.patch` files |
| Gradle wrapper jar | Not committed; CI generated one | Committed, Gradle 9.6.1 |
| Action versions | checkout@v4, setup-java@v4, setup-gradle@v4 | v6 / v5 / v6, matching upstream |
| `canvasCommit` | Latest main HEAD | The template's pinned commit, which its patches are anchored to |
| Branding | Manifest attributes only | `ServerBuildInfo` patches, so the server reports HavocFolia properly |

The plugin-resolution failure you hit was almost certainly downstream of this: the build was
missing the fork declaration the whole time.

## Verified by running it here

| Thing | Result |
| --- | --- |
| AntiFreecam kernel | 24 assertions pass, including 8-thread concurrency and perf budget |
| CPU pin strategies | 10 assertions pass |
| `build.yml` and `upstream.yml` | Parse; every `run:` block bash-syntax-checked |
| Template provenance | Downloaded from `CraftCanvasMC/Baguette@v3`, pushed 2026-08-13 |
| Rename sweep | Zero residual `baguette` references |

## Not verified

I still cannot execute `applyAllPatches` — this sandbox cannot reach `repo.papermc.io`,
`maven.canvasmc.io`, Mojang or `services.gradle.org`. What is different now is that the parts I
could not test are upstream's own files rather than my guesses.

**The five hook patches in `havocfolia-server/minecraft-patches/sources/` are still mine, and are
still written against Paper/Folia 1.21.x method shapes.** Canvas is on Minecraft 26.2. Expect at
least `ServerPlayer#tick` and the `chunkPacketBlockController` assignment to have moved.

That is normal fork maintenance, not a config bug. The workflow uploads a `patch-rejects` artifact
naming the exact failing hunk. Fix by hand, then regenerate:

```bash
# edit havocfolia-server/src/minecraft/net/minecraft/...
./gradlew rebuildAllServerPatches
```

`docs/HOOKS.md` has the anchor string and exact insertion for each of the five. If you want a
build to succeed before touching them, delete all five — you get a clean Canvas rebrand, then add
them back one at a time.

## Task reference

| Goal | Command |
| --- | --- |
| Build the source tree | `./gradlew applyAllPatches` |
| Build the jar | `./gradlew createPaperclipJar` |
| Compile check only | `./gradlew compileJava` |
| Regenerate NMS patches | `./gradlew rebuildAllServerPatches` |
| Regenerate build-file patches | `./gradlew rebuildCanvasSingleFilePatches` |
| Regenerate API patches | `./gradlew rebuildPaperApiPatches` |
| Fuzzy re-apply after an upstream bump | `./gradlew applyCanvasSingleFilePatchesFuzzy` |
| Run the kernel tests | see `docs/TESTING.md` |

## Bumping upstream

Change `canvasCommit`, then:

```bash
./gradlew applyAllPatches || ./gradlew applyCanvasSingleFilePatchesFuzzy
./gradlew rebuildCanvasSingleFilePatches
./gradlew applyAllPatches
./gradlew rebuildPaperApiPatches
./gradlew rebuildAllServerPatches
./gradlew compileJava
```

That is the template's own sequence, and `.github/workflows/upstream.yml` automates it.
