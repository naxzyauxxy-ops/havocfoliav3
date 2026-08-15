package gg.havoc.folia.api;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * Stable surface for plugins that want to cooperate with HavocFolia.
 *
 * <p>Intentionally read-mostly. Plugins should not be able to turn AntiFreecam off for a player —
 * that is a permission decision, and routing it through {@code havocfolia.antifreecam.bypass}
 * keeps one answer to "who can see through walls" instead of two.
 */
public interface HavocFoliaApi {

    /** Snapshot of one region's recent tick behaviour. */
    record RegionInfo(@NotNull String world, double mspt, double tps,
                      int chunkCount, int playerCount, int centerChunkX, int centerChunkZ) {
    }

    /** AntiFreecam counters since the last reset. */
    record AntiFreecamStats(long chunksProcessed, long blocksHidden, double averageMicrosPerChunk) {
    }

    @NotNull String brand();

    @NotNull String version();

    boolean isAntiFreecamEnabled();

    /** Effective hide depth in blocks. */
    int antiFreecamDepth();

    @NotNull AntiFreecamStats antiFreecamStats();

    /** True when region workers are currently pinned to CPUs. */
    boolean isAffinityActive();

    /** Every active region across every world, busiest first. */
    @NotNull List<RegionInfo> regions();

    /** The region that owns a player right now, or null when they are not online. */
    @Nullable RegionInfo regionOf(@NotNull UUID player);

    /** The player's current adaptive view distance. */
    int viewDistanceOf(@NotNull UUID player);

    static @NotNull HavocFoliaApi get() {
        return HavocFoliaApiHolder.require();
    }
}
