package gg.havoc.folia.antifreecam;

import gg.havoc.folia.config.HavocConfig;

import io.papermc.paper.antixray.ChunkPacketBlockController;
import io.papermc.paper.antixray.ChunkPacketInfo;

import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.concurrent.atomic.LongAdder;

/**
 * Native AntiFreecam. Extends Paper's anti-xray hook point, so it runs inside the existing
 * chunk-packet pipeline with no PacketEvents, no ProtocolLib, and no extra packet copy.
 *
 * <p>Why native matters here: a packet-library implementation has to deserialise the chunk
 * blob, rewrite it and re-serialise it, once per player per chunk, on a listener thread that
 * does not own the region. On Folia that is both a throughput problem and a thread-safety
 * problem. Sitting on {@link ChunkPacketBlockController} instead means we mutate the palette
 * data <em>while it is being written</em>, on the region thread that already owns the chunk.
 *
 * <p>Interaction with anti-xray: run one or the other. AntiFreecam is strictly stronger —
 * hidden blocks are replaced with filler, so ores behind depth are gone rather than disguised.
 * If Paper's anti-xray is enabled at the same time you pay for both passes for no benefit;
 * {@code HavocFoliaBootstrap} logs a warning when it detects that.
 */
public final class AntiFreecamController extends ChunkPacketBlockController {

    private final Level level;
    private final PresetBlocks presets;

    private final LongAdder chunksProcessed = new LongAdder();
    private final LongAdder blocksHidden = new LongAdder();
    private final LongAdder nanosSpent = new LongAdder();

    public AntiFreecamController(Level level) {
        this.level = level;
        this.presets = PresetBlocks.forLevel(level);
    }

    /**
     * Filler palette. Paper pre-seeds every section's palette with these so the replacement
     * never forces a palette resize mid-write (which would be the expensive path).
     */
    @Override
    public BlockState[] getPresetBlockStates(Level world, ChunkPos chunkPos, int bottomBlockY) {
        HavocConfig.AntiFreecam cfg = HavocConfig.get().antiFreecam;
        if (!cfg.enabled || bottomBlockY >= cfg.surfaceY) {
            return null; // Surface sections stream untouched — zero cost above ground.
        }
        return presets.paletteFor(bottomBlockY);
    }

    /**
     * Per-player gate. Runs before any work is done for that player's copy of the chunk.
     */
    @Override
    public boolean shouldModify(ServerPlayer player, LevelChunk chunk) {
        HavocConfig.AntiFreecam cfg = HavocConfig.get().antiFreecam;
        if (!cfg.enabled) {
            return false;
        }
        if (player.getBukkitEntity().hasPermission(cfg.bypassPermission)) {
            return false;
        }
        // Chunks far from the viewer are cheap to hide and expensive to compute honestly —
        // but distance alone is not a security boundary, so we only use it to skip the
        // *neighbour-aware* path, never to skip hiding entirely.
        return true;
    }

    @Override
    public ChunkPacketInfo<BlockState> getChunkPacketInfo(ClientboundLevelChunkPacketData packet, LevelChunk chunk) {
        HavocConfig.AntiFreecam cfg = HavocConfig.get().antiFreecam;
        if (!cfg.enabled) {
            return null;
        }
        ChunkPacketInfo<BlockState> info = new ChunkPacketInfo<>(packet, chunk);
        info.setEdgesLoaded(areNeighboursLoaded(chunk));
        return info;
    }

    /**
     * The hot path: called once per chunk packet, on the owning region thread, with the
     * section palettes already laid out. We compute the hidden mask per section and write the
     * filler id straight into the packed data.
     */
    @Override
    public void modifyBlocks(ClientboundLevelChunkPacketData packet, ChunkPacketInfo<BlockState> chunkPacketInfo) {
        if (chunkPacketInfo == null) {
            return;
        }
        HavocConfig.AntiFreecam cfg = HavocConfig.get().antiFreecam;
        if (!cfg.enabled) {
            return;
        }

        final long start = System.nanoTime();
        final LevelChunk chunk = chunkPacketInfo.getChunk();
        final LevelChunkSection[] sections = chunk.getSections();
        final DepthObfuscator.Scratch scratch = DepthObfuscator.scratch();
        final long[] occluding = PresetBlocks.occlusionWords();

        final int minSection = chunk.getMinSectionY();
        long hiddenHere = 0L;

        for (int i = 0; i < sections.length; i++) {
            final int bottomY = (minSection + i) << 4;
            if (bottomY >= cfg.surfaceY) {
                continue; // Surface and above stream untouched — zero cost where it would be visible anyway.
            }
            LevelChunkSection section = sections[i];
            if (section == null || section.hasOnlyAir()) {
                continue;
            }
            if (!chunkPacketInfo.isWritten(i)) {
                continue;
            }

            int[][] border = chunkPacketInfo.isEdgesLoaded()
                ? SectionCodec.borderOf(chunk, i)
                : null;

            int count = SectionCodec.compute(section, border, occluding, cfg.maxDepth, scratch);
            if (count > 0) {
                SectionCodec.writeFiller(chunkPacketInfo, i, scratch.hidden, presets.fillerIdFor(bottomY));
                hiddenHere += count;
            }
        }

        chunksProcessed.increment();
        blocksHidden.add(hiddenHere);
        nanosSpent.add(System.nanoTime() - start);
    }

    /**
     * A block change can open a cavity that was previously interior — e.g. a player mines
     * through to a hidden cave. Re-send the small neighbourhood so the newly reachable blocks
     * arrive. Scheduled onto the owning region so it is safe under Folia.
     */
    @Override
    public void onBlockChange(Level world, BlockPos position, BlockState newState, BlockState oldState,
                              int flags, int maxUpdateDepth) {
        HavocConfig.AntiFreecam cfg = HavocConfig.get().antiFreecam;
        if (!cfg.enabled || !cfg.updateOnBlockChange || cfg.updateRadius <= 0) {
            return;
        }
        if (position.getY() >= cfg.surfaceY) {
            return;
        }
        // Only a transition from occluding -> non-occluding can reveal anything.
        if (newState == null || oldState == null) {
            return;
        }
        if (!oldState.canOcclude() || newState.canOcclude()) {
            return;
        }
        RefreshQueue.forLevel(world).enqueue(position, cfg.updateRadius);
    }

    private boolean areNeighboursLoaded(LevelChunk chunk) {
        ChunkPos pos = chunk.getPos();
        return level.getChunkSource().hasChunk(pos.x - 1, pos.z)
            && level.getChunkSource().hasChunk(pos.x + 1, pos.z)
            && level.getChunkSource().hasChunk(pos.x, pos.z - 1)
            && level.getChunkSource().hasChunk(pos.x, pos.z + 1);
    }

    // ------------------------------------------------------------------ telemetry (/havoc freecam status)

    public long chunksProcessed() {
        return chunksProcessed.sum();
    }

    public long blocksHidden() {
        return blocksHidden.sum();
    }

    public double averageMicrosPerChunk() {
        long chunks = chunksProcessed.sum();
        return chunks == 0 ? 0.0D : (nanosSpent.sum() / (double) chunks) / 1000.0D;
    }

    public void resetStats() {
        chunksProcessed.reset();
        blocksHidden.reset();
        nanosSpent.reset();
    }
}
