# Testing

Two suites, both dependency-free. No JUnit, no Gradle, no network — they run in ten seconds.

That is deliberate. The AntiFreecam kernel is the one piece of this fork where a subtle bug is
invisible in play (you cannot see blocks that were *wrongly* hidden) and catastrophic when
inverted (you leak the entire world to every client). It needs to be trivially runnable.

```bash
mkdir -p /tmp/hf

javac -d /tmp/hf \
  havocfolia-server/src/main/java/gg/havoc/folia/antifreecam/DepthObfuscator.java \
  havocfolia-server/src/main/java/gg/havoc/folia/scheduler/PinStrategy.java \
  havocfolia-server/src/test/java/gg/havoc/folia/*.java

java -cp /tmp/hf gg.havoc.folia.AntiFreecamKernelTest
java -cp /tmp/hf gg.havoc.folia.PinStrategyTest
```

## AntiFreecamKernelTest — 24 assertions

| Case | Checks |
| --- | --- |
| 1 | Fully enclosed solid section hides all 4096 blocks |
| 2 | Uniform-face encoding gives byte-identical results to per-cell |
| 3 | Depth semantics — dist 1/3 visible, dist 4+ hidden, exact count `4096-63` |
| 4 | Ore with no opening anywhere is hidden |
| 5 | Unloaded neighbours read as open; exact count `12³` |
| 6 | No diagonal leak through padded edges at large depth |
| 7 | Air column to the sky reveals the correct radius |
| 8 | `max-depth: 0` hides exactly one isolated block |
| 9 | Packed path matches the fallback path for palette widths 4, 5 and 8 |
| 10 | Single-value palette handled both solid and air |
| 11 | Eight threads, 2000 iterations each, all agree — no scratch bleed |
| 12 | Performance within budget |

Cases 1 and 3 were both written after finding real bugs. Case 1 caught the padded-shell leak
described in `ANTIFREECAM.md`. Case 9 was initially useless — it compared two paths that both
returned zero — and was rewritten with sparser air so the counts are non-trivial (~1600–2000
blocks hidden per run).

## PinStrategyTest — 10 assertions

Covers `SPREAD` skipping SMT siblings, `COMPACT` filling in order, `SMT_PAIRED` using both
siblings, `ISOLATED` respecting `cpu-set` and wrapping round-robin, plus the degenerate cases: a
one-core box with `reserved-cores: 8` must still return a usable pool, and `ISOLATED` with no
`cpu-set` must return nothing rather than silently falling back.

## Reference numbers

Measured on JDK 21, worst case (random terrain, per-cell faces, `max-depth: 6`):

```
fallback  : ~10.4 us/section
packed    : ~11.7 us/section
uniform   : ~0.85 us/section
realistic chunk (6 packed + 2 uniform): ~72 us
```

`computeHiddenPacked` is *not* faster on a microbenchmark — it does more ALU work per entry. It
wins in production because it never asks the caller to materialise 4096 resolved block states,
which costs far more than the kernel itself.

## What these do not cover

Anything touching NMS: the controller, the codec, the commands, the hooks. Those need a running
server, which is what the CI smoke test in `.github/workflows/build.yml` is for — it boots the
built jar, waits for `Done`, runs `havoc version`, and fails if the fork branding never appears.
