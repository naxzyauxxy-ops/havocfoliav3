package gg.havoc.folia.antifreecam;

import gg.havoc.folia.config.HavocConfig;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Arrays;
import java.util.BitSet;
import java.util.logging.Logger;

/**
 * Resolves the per-dimension filler block and builds the occlusion lookup table.
 *
 * <p>The occlusion table is a {@link BitSet} indexed by global block state id. Resolving
 * "does this state block vision" through the registry on every one of 4096 blocks per section
 * would dominate the profile; a bitset lookup is one array read and a mask.
 */
public final class PresetBlocks {

    private static final Logger LOGGER = Logger.getLogger("HavocFolia");
    private static volatile long[] OCCLUDING_WORDS = buildOcclusionTable();

    private final BlockState filler;
    private final int fillerId;
    private final BlockState[] palette;

    private PresetBlocks(BlockState filler) {
        this.filler = filler;
        this.fillerId = Block.getId(filler);
        this.palette = new BlockState[] { filler };
    }

    public static PresetBlocks forLevel(Level level) {
        HavocConfig.AntiFreecam cfg = HavocConfig.get().antiFreecam;
        String dim = level.dimension().location().toString();
        String id = cfg.presetBlocks.getOrDefault(dim, "minecraft:stone");
        BlockState state = resolve(id);
        return new PresetBlocks(state);
    }

    private static BlockState resolve(String id) {
        ResourceLocation key = ResourceLocation.tryParse(id);
        if (key == null) {
            LOGGER.warning("[HavocFolia] Invalid preset block '" + id + "', falling back to stone.");
            return Blocks.STONE.defaultBlockState();
        }
        Block block = BuiltInRegistries.BLOCK.getOptional(key).orElse(null);
        if (block == null) {
            LOGGER.warning("[HavocFolia] Unknown preset block '" + id + "', falling back to stone.");
            return Blocks.STONE.defaultBlockState();
        }
        BlockState state = block.defaultBlockState();
        if (!state.canOcclude()) {
            LOGGER.warning("[HavocFolia] Preset block '" + id + "' is not a full cube; clients would see"
                + " through the filler. Falling back to stone.");
            return Blocks.STONE.defaultBlockState();
        }
        return state;
    }

    /** Palette Paper pre-seeds into each section so replacement never resizes the palette. */
    public BlockState[] paletteFor(int bottomBlockY) {
        return Arrays.copyOf(palette, palette.length);
    }

    public int fillerIdFor(int bottomBlockY) {
        return fillerId;
    }

    public BlockState filler() {
        return filler;
    }

    /**
     * The occlusion table as raw bitset words, indexed by global block state id.
     * The kernel indexes this directly — a lambda here would put an interface call on a path
     * that runs 4096 times per section.
     */
    public static long[] occlusionWords() {
        return OCCLUDING_WORDS;
    }

    /** Rebuilt on reload so ignored-blocks changes take effect without a restart. */
    public static void rebuildOcclusionTable() {
        OCCLUDING_WORDS = buildOcclusionTable();
    }

    private static long[] buildOcclusionTable() {
        HavocConfig.AntiFreecam cfg = HavocConfig.get().antiFreecam;
        BitSet set = new BitSet(Block.BLOCK_STATE_REGISTRY.size());
        for (BlockState state : Block.BLOCK_STATE_REGISTRY) {
            int id = Block.getId(state);
            if (!state.canOcclude()) {
                continue;
            }
            // A block that is not a full cube leaves a sight line even if it "occludes".
            if (!state.isCollisionShapeFullBlock(net.minecraft.world.level.EmptyBlockGetter.INSTANCE,
                    net.minecraft.core.BlockPos.ZERO)) {
                continue;
            }
            ResourceLocation key = BuiltInRegistries.BLOCK.getKey(state.getBlock());
            if (key != null && cfg.ignoredBlocks.contains(key.toString())) {
                continue;
            }
            set.set(id);
        }
        // Pad so `id >>> 6` is always in range for any registered state id.
        long[] words = set.toLongArray();
        int needed = (Block.BLOCK_STATE_REGISTRY.size() >>> 6) + 2;
        return words.length >= needed ? words : java.util.Arrays.copyOf(words, needed);
    }
}
