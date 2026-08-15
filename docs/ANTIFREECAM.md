# Native AntiFreecam

## What it actually defends against

A freecam client detaches the camera from the player and flies it through terrain, rendering
whatever chunk data the server already sent. The exploit is not in the rendering — it is that the
server sent the data at all.

This is why **anti-xray does not stop freecam**. Engine-mode 1 swaps ore block ids for stone; the
freecam user flies into the wall and looks at the stone, which is still exactly where the ore is.
Engine-mode 2 and 3 obfuscate more but still transmit a full, structurally correct chunk.

AntiFreecam takes the only approach that works: compute what a legitimate player could possibly
have seen, and replace everything else with filler before it is serialised. Hidden terrain is not
disguised — it is not sent.

## The algorithm

For each section below `surface-y`:

1. Classify every block as occluding or open (full solid cube, not in `ignored-blocks`).
2. Compute the 6-connected morphological dilation of the open set by `max-depth`.
3. Anything solid and outside that dilation is replaced with the dimension's filler block.

"Within `max-depth` steps of an opening" is exactly the set a player could reach the surface of by
mining, or glimpse through a cave mouth. Everything else is interior rock.

The dilation runs over a bitmask — one bit per cell in a padded 18×18×18 grid stored as
`long[324]`, 18 bits of x per row — so neighbours are a bit shift or an array step, and the whole
working set fits in L1.

### Why padding matters

The one-cell shell around each section is **solid by default**, then overwritten from the
neighbouring section. Two details are load-bearing:

- A face whose neighbour is **not loaded** is marked *open*. Hiding a surface the adjacent chunk
  will render produces a visible seam, which is worse than hiding slightly less.
- Edge and corner padding cells stay **solid**. Leaving them open lets the dilation leak
  diagonally inward, silently un-hiding the outer shell of every section. This was a real bug
  during development, caught by `KernelTest` case 1 — a fully enclosed solid section reported 2200
  hidden blocks instead of 4096.

## Cost

Measured on JDK 21, worst case (random terrain, every face supplied per-cell, `max-depth: 6`):

| Path | Cost |
| --- | --- |
| Packed palette (typical) | ~11 µs / section |
| Resolved states (wide palettes) | ~10 µs / section |
| Uniform section vs uniform faces | ~0.8 µs / section |
| Realistic overworld chunk | ~70 µs |

The realistic number is far below 24 × 11 because `surface-y: 63` skips roughly two thirds of a
384-tall world's sections outright, and uniform stone or air sections take the O(1) path.

`/havoc freecam` reports the live average per chunk on your hardware. If it climbs above a few
hundred microseconds, lower `surface-y` before you lower `max-depth` — it is the cheaper knob.

## Thread safety under Folia

Chunk packets are built on the region thread that owns the chunk, and regions run concurrently.
The kernel holds no shared mutable state: scratch buffers are per-thread, and the occlusion table
is an immutable `long[]` swapped wholesale on reload. Neighbour lookups use `getChunkNow`, which
returns null rather than reaching into a region this thread does not own — and null is treated as
open, so the failure mode is "hides less", never "corrupts a chunk".

`KernelTest` case 11 runs eight threads over the same input for 2000 iterations each and asserts
they all agree.

## Tuning

| Setting | Effect |
| --- | --- |
| `max-depth: 4` | Aggressive. Smallest packets. Occasional pop-in when tunnelling fast. |
| `max-depth: 6` | Default. No visible artefacts in normal play. |
| `max-depth: 10` | Conservative. Use with mods that render through walls. |
| `surface-y` | The biggest cost lever. Raise it only if you have deep surface builds. |
| `update-on-block-change: false` | Saves packets; breakthroughs look solid until chunk reload. |

Turn off Paper's anti-xray when AntiFreecam is on. It already hides everything anti-xray would
obfuscate, so running both pays for two passes for no benefit. HavocFolia logs a warning at
startup if it detects this.

## Limits

- **Players with `havocfolia.antifreecam.bypass` see the real world.** Staff running freecam are
  not defended against, by design.
- **This is not an anti-cheat.** It removes the information advantage; it does not detect or
  punish the client.
- **Preset blocks must be full solid cubes.** A non-cube filler leaves sight lines. Invalid values
  fall back to stone with a warning.
