package gg.havoc.folia.antifreecam;

import java.util.Arrays;

/**
 * The AntiFreecam kernel.
 *
 * <p>A freecam client detaches the camera from the player and flies through terrain, reading
 * whatever the server already sent. Anti-xray does not stop this: engine-mode 1 only swaps ore
 * ids, and a freecam user simply looks at the disguised blocks from inside the wall. The only
 * real defence is to never send data the player could not legitimately have seen — so we
 * compute, per section, how far each block sits behind solid material and replace anything past
 * a threshold with a dimension-appropriate filler.
 *
 * <h2>Algorithm</h2>
 * "Visible" is exactly the 6-connected morphological dilation of the open (non-occluding) set by
 * {@code maxDepth}. A BFS computes the same answer but pays for a queue, random memory access and
 * a per-cell predicate call; a dilation is a handful of shifts and ORs over a bitmask that fits in
 * L1. The section lives in a padded 18x18x18 grid stored as {@code long[18*18]} — 18 bits of x per
 * row — so x neighbours are a bit shift and y/z neighbours are an array index step.
 *
 * <h2>Padding</h2>
 * The one-cell shell is solid by default. Faces are then overwritten from the neighbouring
 * section, and a face whose neighbour is <em>not loaded</em> is marked fully open: an unloaded
 * neighbour must never cause us to hide a surface the adjacent chunk will render, because a
 * visible seam is worse than a slightly smaller hidden volume. Edge and corner padding cells stay
 * solid — leaving them open lets the dilation leak diagonally into the interior, which silently
 * un-hides the outer shell of every section.
 *
 * <h2>Performance</h2>
 * Measured on JDK 21 (see {@code KernelTest}), worst case of random terrain with every face
 * supplied per-cell and maxDepth 6: about 10-12 us per section. A uniform section against uniform
 * faces takes an O(1) path at ~0.8 us. The controller skips everything at or above
 * {@code surface-y}, so a realistic overworld chunk lands near 70 us rather than 24 x 12.
 *
 * <p>Note that {@link #computeHiddenPacked} is not faster than {@link #computeHidden} on a
 * microbenchmark — it does more ALU work per entry. It wins in production because it never asks
 * the caller to materialise 4096 resolved block states, which costs far more than the kernel
 * itself.
 *
 * <h2>Folia thread-safety</h2>
 * Chunk packets are built on the region thread that owns the chunk and several regions run
 * concurrently. This class holds no shared mutable state — the caller supplies a {@link Scratch}
 * buffer and {@link #scratch()} keeps one per thread, so the hot path allocates nothing after
 * warm-up.
 */
public final class DepthObfuscator {

    /** 16 + 1 cell of padding on each side. */
    static final int PADDED = 18;
    /** One long per (y,z) row; 18 bits of x live in each. */
    static final int ROWS = PADDED * PADDED;
    private static final long ROW_MASK = (1L << PADDED) - 1L;
    /** Bits covering the real x range (1..16) inside a padded row. */
    private static final long INNER_MASK = ((1L << 16) - 1L) << 1;

    /** 4096 cells, one bit each. */
    public static final int MASK_WORDS = 4096 / 64;

    /** Face indices for the {@code border} argument. */
    public static final int FACE_DOWN = 0, FACE_UP = 1, FACE_NORTH = 2,
                            FACE_SOUTH = 3, FACE_WEST = 4, FACE_EAST = 5;

    private DepthObfuscator() {
    }

    /** Per-thread scratch, reused for every section that thread serialises. */
    public static final class Scratch {
        final long[] solid = new long[ROWS];
        final long[] bufA = new long[ROWS];
        final long[] bufB = new long[ROWS];
        /** Flattened section state ids, YZX order — only used by the fallback entry point. */
        public final int[] states = new int[4096];
        /** Output bitmask: bit i set means block i must be replaced. */
        public final long[] hidden = new long[MASK_WORDS];
    }

    private static final ThreadLocal<Scratch> POOL = ThreadLocal.withInitial(Scratch::new);

    public static Scratch scratch() {
        return POOL.get();
    }

    /** True when block index {@code i} (YZX order) is marked hidden. */
    public static boolean isHidden(long[] mask, int i) {
        return (mask[i >>> 6] & (1L << i)) != 0L;
    }

    // ------------------------------------------------------------------ entry points

    /**
     * Fallback entry point taking resolved global block state ids. Straightforward and easy to
     * test, but forces the caller to resolve 4096 palette lookups — prefer
     * {@link #computeHiddenPacked} on the hot path.
     *
     * @param states        4096 global block state ids in YZX order
     * @param border        six faces (see {@link #FACE_DOWN}); {@code null} array or {@code null}
     *                      face means open, a length-1 face means "uniformly this id",
     *                      a length-256 face is per-cell
     * @param occludingBits bitset words indexed by block state id; set = fully blocks vision
     * @param maxDepth      blocks of real data kept behind the nearest opening
     * @param scratch       per-thread buffer; {@code scratch.hidden} receives the result
     * @return number of blocks marked for replacement
     */
    public static int computeHidden(int[] states, int[][] border, long[] occludingBits,
                                    int maxDepth, Scratch scratch) {
        final long[] solid = scratch.solid;
        initPadding(solid);

        int i = 0;
        for (int y = 0; y < 16; y++) {
            final int rowBase = (y + 1) * PADDED + 1;
            for (int z = 0; z < 16; z++) {
                long row = 0L;
                for (int x = 0; x < 16; x++) {
                    int id = states[i++];
                    row |= ((occludingBits[id >>> 6] >>> id) & 1L) << (x + 1);
                }
                int r = rowBase + z;
                solid[r] = (solid[r] & ~INNER_MASK) | row;
            }
        }
        return finish(solid, border, occludingBits, maxDepth, scratch);
    }

    /**
     * Hot path. Reads the section's packed palette data directly, so the only registry work is
     * one occlusion lookup per <em>palette entry</em> rather than per block.
     *
     * @param data          the section's packed block data ({@code null} when the palette holds a
     *                      single value)
     * @param bitsPerEntry  bits per palette index; 0 means single-value palette
     * @param paletteOcc    bit i set = palette entry i occludes vision (palette size must be
     *                      &le; 64; callers fall back to {@link #computeHidden} above that)
     */
    public static int computeHiddenPacked(long[] data, int bitsPerEntry, long paletteOcc,
                                          int[][] border, long[] occludingBits,
                                          int maxDepth, Scratch scratch) {
        final long[] solid = scratch.solid;
        initPadding(solid);

        if (bitsPerEntry <= 0 || data == null || data.length == 0) {
            // Single-value palette: the whole section is one block state.
            boolean occludes = (paletteOcc & 1L) != 0L;
            long fill = occludes ? INNER_MASK : 0L;
            for (int y = 0; y < 16; y++) {
                final int rowBase = (y + 1) * PADDED + 1;
                for (int z = 0; z < 16; z++) {
                    int r = rowBase + z;
                    solid[r] = (solid[r] & ~INNER_MASK) | fill;
                }
            }
            return finish(solid, border, occludingBits, maxDepth, scratch);
        }

        final long mask = (1L << bitsPerEntry) - 1L;
        final int entriesPerWord = 64 / bitsPerEntry;

        if ((entriesPerWord & (entriesPerWord - 1)) == 0) {
            // Aligned palette widths (4, 8, 16 bits — the common cases). entriesPerWord is a
            // power of two, so the word index and shift are pure bit math: no division, and no
            // per-entry carry branch in the inner loop.
            final int wordShift = Integer.numberOfTrailingZeros(entriesPerWord);
            final int slotMask = entriesPerWord - 1;
            int i = 0;
            for (int y = 0; y < 16; y++) {
                final int rowBase = (y + 1) * PADDED + 1;
                for (int z = 0; z < 16; z++) {
                    long row = 0L;
                    for (int x = 0; x < 16; x++, i++) {
                        long paletteIndex = (data[i >>> wordShift] >>> ((i & slotMask) * bitsPerEntry)) & mask;
                        row |= ((paletteOcc >>> (int) paletteIndex) & 1L) << (x + 1);
                    }
                    int r = rowBase + z;
                    solid[r] = (solid[r] & ~INNER_MASK) | row;
                }
            }
            return finish(solid, border, occludingBits, maxDepth, scratch);
        }

        // Unaligned widths (5, 6, 7 bits). Vanilla pads rather than straddling a long boundary,
        // so we carry the cursor forward manually.
        int wordIndex = 0;
        int shift = 0;
        for (int y = 0; y < 16; y++) {
            final int rowBase = (y + 1) * PADDED + 1;
            for (int z = 0; z < 16; z++) {
                long row = 0L;
                for (int x = 0; x < 16; x++) {
                    long paletteIndex = (data[wordIndex] >>> shift) & mask;
                    shift += bitsPerEntry;
                    if (shift + bitsPerEntry > 64) {
                        shift = 0;
                        wordIndex++;
                    }
                    row |= ((paletteOcc >>> (int) paletteIndex) & 1L) << (x + 1);
                }
                int r = rowBase + z;
                solid[r] = (solid[r] & ~INNER_MASK) | row;
            }
        }
        return finish(solid, border, occludingBits, maxDepth, scratch);
    }

    // ------------------------------------------------------------------ shared tail

    private static void initPadding(long[] solid) {
        final long sideBits = 1L | (1L << (PADDED - 1));
        for (int y = 0; y < PADDED; y++) {
            final boolean yShell = (y == 0 || y == PADDED - 1);
            final int base = y * PADDED;
            for (int z = 0; z < PADDED; z++) {
                solid[base + z] = (yShell || z == 0 || z == PADDED - 1) ? ROW_MASK : sideBits;
            }
        }
    }

    private static int finish(long[] solid, int[][] border, long[] occludingBits,
                              int maxDepth, Scratch scratch) {
        applyBorder(border, occludingBits, solid);

        long[] cur = scratch.bufA;
        long[] next = scratch.bufB;

        boolean anyOpen = false;
        for (int r = 0; r < ROWS; r++) {
            long open = ~solid[r] & ROW_MASK;
            cur[r] = open;
            anyOpen |= open != 0L;
        }
        if (!anyOpen) {
            // Fully sealed: nothing here is observable, hide the whole section.
            Arrays.fill(scratch.hidden, -1L);
            return 4096;
        }

        for (int step = 0; step < maxDepth; step++) {
            boolean changed = false;
            for (int y = 0; y < PADDED; y++) {
                final int base = y * PADDED;
                final boolean hasDown = y > 0;
                final boolean hasUp = y < PADDED - 1;
                for (int z = 0; z < PADDED; z++) {
                    final int r = base + z;
                    final long v = cur[r];
                    long n = v | (v << 1) | (v >>> 1);
                    if (z > 0)          n |= cur[r - 1];
                    if (z < PADDED - 1) n |= cur[r + 1];
                    if (hasDown)        n |= cur[r - PADDED];
                    if (hasUp)          n |= cur[r + PADDED];
                    n &= ROW_MASK;
                    next[r] = n;
                    changed |= (n != v);
                }
            }
            long[] swap = cur;
            cur = next;
            next = swap;
            if (!changed) {
                break; // Saturated early — nothing further can be reached.
            }
        }

        Arrays.fill(scratch.hidden, 0L);
        int count = 0;
        for (int y = 0; y < 16; y++) {
            final int rowBase = (y + 1) * PADDED + 1;
            final int outBase = y << 8;
            for (int z = 0; z < 16; z++) {
                long hiddenRow = (solid[rowBase + z] & ~cur[rowBase + z] & INNER_MASK) >>> 1;
                if (hiddenRow == 0L) {
                    continue;
                }
                count += Long.bitCount(hiddenRow);
                int base = outBase | (z << 4);
                // 16 contiguous bits at a multiple of 16 — never straddles a 64-bit word.
                scratch.hidden[base >>> 6] |= hiddenRow << (base & 63);
            }
        }
        return count;
    }

    /**
     * Overwrites the padded shell faces. A {@code null} face is left open so we never hide blocks
     * an adjacent chunk renders; a length-1 face is a uniform neighbour and takes an O(1) path.
     */
    private static void applyBorder(int[][] border, long[] occludingBits, long[] solid) {
        for (int face = 0; face < 6; face++) {
            int[] data = (border == null || face >= border.length) ? null : border[face];

            if (data == null) {
                fillFace(solid, face, false);
                continue;
            }
            if (data.length == 1) {
                int id = data[0];
                fillFace(solid, face, ((occludingBits[id >>> 6] >>> id) & 1L) != 0L);
                continue;
            }
            switch (face) {
                case FACE_DOWN, FACE_UP -> {
                    int y = (face == FACE_DOWN) ? 0 : PADDED - 1;
                    for (int a = 0; a < 16; a++) {
                        int r = y * PADDED + (a + 1);
                        solid[r] = (solid[r] & ~INNER_MASK) | packRow(data, a, occludingBits);
                    }
                }
                case FACE_NORTH, FACE_SOUTH -> {
                    int z = (face == FACE_NORTH) ? 0 : PADDED - 1;
                    for (int a = 0; a < 16; a++) {
                        int r = (a + 1) * PADDED + z;
                        solid[r] = (solid[r] & ~INNER_MASK) | packRow(data, a, occludingBits);
                    }
                }
                default -> {
                    int x = (face == FACE_WEST) ? 0 : PADDED - 1;
                    long bit = 1L << x;
                    for (int a = 0; a < 16; a++) {
                        int rowBase = (a + 1) * PADDED + 1;
                        for (int b = 0; b < 16; b++) {
                            int id = data[(a << 4) | b];
                            long occ = (occludingBits[id >>> 6] >>> id) & 1L;
                            int r = rowBase + b;
                            solid[r] = (solid[r] & ~bit) | (occ << x);
                        }
                    }
                }
            }
        }
    }

    private static long packRow(int[] face, int a, long[] occludingBits) {
        long row = 0L;
        int base = a << 4;
        for (int b = 0; b < 16; b++) {
            int id = face[base + b];
            row |= ((occludingBits[id >>> 6] >>> id) & 1L) << (b + 1);
        }
        return row;
    }

    private static void fillFace(long[] solid, int face, boolean occ) {
        switch (face) {
            case FACE_DOWN, FACE_UP -> {
                int y = (face == FACE_DOWN) ? 0 : PADDED - 1;
                for (int a = 0; a < 16; a++) {
                    int r = y * PADDED + (a + 1);
                    solid[r] = occ ? (solid[r] | INNER_MASK) : (solid[r] & ~INNER_MASK);
                }
            }
            case FACE_NORTH, FACE_SOUTH -> {
                int z = (face == FACE_NORTH) ? 0 : PADDED - 1;
                for (int a = 0; a < 16; a++) {
                    int r = (a + 1) * PADDED + z;
                    solid[r] = occ ? (solid[r] | INNER_MASK) : (solid[r] & ~INNER_MASK);
                }
            }
            default -> {
                int x = (face == FACE_WEST) ? 0 : PADDED - 1;
                long bit = 1L << x;
                for (int a = 0; a < 16; a++) {
                    int rowBase = (a + 1) * PADDED + 1;
                    for (int b = 0; b < 16; b++) {
                        int r = rowBase + b;
                        solid[r] = occ ? (solid[r] | bit) : (solid[r] & ~bit);
                    }
                }
            }
        }
    }
}
