package gg.havoc.folia.antifreecam;

import io.papermc.paper.antixray.ChunkPacketInfo;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PalettedContainer;

/**
 * Bridges the NMS palette representation and the flat bit world the kernel works on.
 *
 * <p>Deliberately the only file in this package that touches NMS internals — {@link DepthObfuscator}
 * stays pure so it can be unit tested, and when upstream reshuffles palette internals this is the
 * single file that has to move.
 */
final class SectionCodec {

    /** Palettes wider than this fall back to the resolved-state path. */
    private static final int MAX_PALETTE_BITS = 6;

    private SectionCodec() {
    }

    /**
     * Builds the "does palette entry i occlude" mask for a section.
     *
     * @return the mask, or -1 when the palette is too wide to encode in a long
     */
    static long paletteOcclusionMask(LevelChunkSection section, long[] occludingBits) {
        Palette<BlockState> palette = section.getStates().data().palette();
        int size = palette.getSize();
        if (size > 64) {
            return -1L;
        }
        long mask = 0L;
        for (int i = 0; i < size; i++) {
            int id = Block.getId(palette.valueFor(i));
            if ((occludingBits[id >>> 6] >>> id & 1L) != 0L) {
                mask |= 1L << i;
            }
        }
        return mask;
    }

    /** Runs the kernel over one section, choosing the packed or resolved path automatically. */
    static int compute(LevelChunkSection section, int[][] border, long[] occludingBits,
                       int maxDepth, DepthObfuscator.Scratch scratch) {
        PalettedContainer.Data<BlockState> data = section.getStates().data();
        int bits = data.storage().getBits();
        long paletteMask = bits <= MAX_PALETTE_BITS ? paletteOcclusionMask(section, occludingBits) : -1L;

        if (paletteMask >= 0L) {
            long[] raw = bits == 0 ? null : data.storage().getRaw();
            return DepthObfuscator.computeHiddenPacked(
                raw, bits, paletteMask, border, occludingBits, maxDepth, scratch);
        }
        readStateIds(section, scratch.states);
        return DepthObfuscator.computeHidden(scratch.states, border, occludingBits, maxDepth, scratch);
    }

    /** Flattens a section into 4096 global state ids in YZX order. */
    static void readStateIds(LevelChunkSection section, int[] out) {
        PalettedContainer<BlockState> states = section.getStates();
        int i = 0;
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    out[i++] = Block.getId(states.get(x, y, z));
                }
            }
        }
    }

    /**
     * Collects the six neighbouring faces around section {@code index}.
     *
     * <p>Encoding matches {@link DepthObfuscator}: {@code null} means "neighbour absent, treat as
     * open", a length-1 array means "uniformly this block state" (the common case for the solid
     * stone and air sections that dominate a world), and length-256 is per-cell.
     */
    static int[][] borderOf(LevelChunk chunk, int index) {
        int[][] faces = new int[6][];
        LevelChunkSection[] sections = chunk.getSections();

        faces[DepthObfuscator.FACE_DOWN] = index > 0 ? faceY(sections[index - 1], 15) : null;
        faces[DepthObfuscator.FACE_UP] = index < sections.length - 1 ? faceY(sections[index + 1], 0) : null;
        faces[DepthObfuscator.FACE_NORTH] = faceZ(chunk, index, -1);
        faces[DepthObfuscator.FACE_SOUTH] = faceZ(chunk, index, +1);
        faces[DepthObfuscator.FACE_WEST] = faceX(chunk, index, -1);
        faces[DepthObfuscator.FACE_EAST] = faceX(chunk, index, +1);
        return faces;
    }

    private static int[] uniformOf(LevelChunkSection section) {
        if (section == null) {
            return null;
        }
        Palette<BlockState> palette = section.getStates().data().palette();
        if (palette.getSize() == 1) {
            return new int[] { Block.getId(palette.valueFor(0)) };
        }
        return null;
    }

    private static int[] faceY(LevelChunkSection section, int y) {
        if (section == null) {
            return null;
        }
        int[] uniform = uniformOf(section);
        if (uniform != null) {
            return uniform;
        }
        int[] face = new int[256];
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                face[(z << 4) | x] = Block.getId(section.getBlockState(x, y, z));
            }
        }
        return face;
    }

    private static int[] faceZ(LevelChunk chunk, int index, int dir) {
        LevelChunk neighbour = neighbour(chunk, 0, dir);
        LevelChunkSection section = sectionAt(neighbour, index);
        if (section == null) {
            return null;
        }
        int[] uniform = uniformOf(section);
        if (uniform != null) {
            return uniform;
        }
        int z = dir < 0 ? 15 : 0;
        int[] face = new int[256];
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                face[(y << 4) | x] = Block.getId(section.getBlockState(x, y, z));
            }
        }
        return face;
    }

    private static int[] faceX(LevelChunk chunk, int index, int dir) {
        LevelChunk neighbour = neighbour(chunk, dir, 0);
        LevelChunkSection section = sectionAt(neighbour, index);
        if (section == null) {
            return null;
        }
        int[] uniform = uniformOf(section);
        if (uniform != null) {
            return uniform;
        }
        int x = dir < 0 ? 15 : 0;
        int[] face = new int[256];
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                face[(y << 4) | z] = Block.getId(section.getBlockState(x, y, z));
            }
        }
        return face;
    }

    /**
     * Neighbour lookup that never blocks and never generates. Under Folia the caller owns this
     * chunk's region; {@code getChunkNow} returns null rather than reaching into a region we do
     * not own, and the kernel treats null as open.
     */
    private static LevelChunk neighbour(LevelChunk chunk, int dx, int dz) {
        return chunk.getLevel().getChunkSource()
            .getChunkNow(chunk.getPos().x + dx, chunk.getPos().z + dz);
    }

    private static LevelChunkSection sectionAt(LevelChunk chunk, int index) {
        if (chunk == null) {
            return null;
        }
        LevelChunkSection[] sections = chunk.getSections();
        return index >= 0 && index < sections.length ? sections[index] : null;
    }

    /**
     * Writes the filler palette index into every masked position of the packet's packed data.
     * Paper pre-seeds the filler into each section's palette via {@code getPresetBlockStates},
     * so this is an in-place bit twiddle with no palette resize and no re-serialisation.
     */
    static void writeFiller(ChunkPacketInfo<BlockState> info, int sectionIndex,
                            long[] hiddenMask, int fillerId) {
        final long[] data = info.getBuffer(sectionIndex);
        final int bits = info.getBits(sectionIndex);
        if (data == null || bits <= 0) {
            return;
        }
        final int presetIndex = info.getPresetValuesIndex(sectionIndex, fillerId);
        if (presetIndex < 0) {
            return; // Filler missing from this section's palette; leave the section untouched.
        }
        final int entriesPerWord = 64 / bits;
        final long mask = (1L << bits) - 1L;
        final long replacement = presetIndex & mask;

        for (int word = 0; word < DepthObfuscator.MASK_WORDS; word++) {
            long bitsSet = hiddenMask[word];
            if (bitsSet == 0L) {
                continue; // Skips 64 blocks at a time — most sections are mostly visible or mostly hidden.
            }
            int base = word << 6;
            while (bitsSet != 0L) {
                int bit = Long.numberOfTrailingZeros(bitsSet);
                bitsSet &= bitsSet - 1;
                int cell = base + bit;
                int longIndex = cell / entriesPerWord;
                int shift = (cell % entriesPerWord) * bits;
                data[longIndex] = (data[longIndex] & ~(mask << shift)) | (replacement << shift);
            }
        }
    }
}
