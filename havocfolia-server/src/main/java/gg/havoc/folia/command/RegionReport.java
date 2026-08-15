package gg.havoc.folia.command;

import io.papermc.paper.threadedregions.TickRegions;
import io.papermc.paper.threadedregions.ThreadedRegionizer;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * A point-in-time snapshot of every active Folia region.
 *
 * <p>Snapshot, not a live view, and deliberately so: iterating regions while they tick would
 * either need a lock the tick loop also wants, or produce a report that contradicts itself
 * halfway down. Each region's tick report is read through Folia's own already-published
 * accessors, which are safe to read off-thread.
 */
public record RegionReport(List<Row> rows, int playerCount) {

    public record Row(String world, double mspt, double tps, int chunkCount,
                      int playerCount, int centerChunkX, int centerChunkZ) {
    }

    public static RegionReport capture(MinecraftServer server) {
        List<Row> rows = new ArrayList<>();
        int players = 0;

        for (ServerLevel level : server.getAllLevels()) {
            ThreadedRegionizer<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> regionizer =
                level.regioniser;
            if (regionizer == null) {
                continue;
            }
            regionizer.computeForAllRegions(region -> {
                TickRegions.TickRegionData data = region.getData();
                var handle = data.getRegionSchedulingHandle();
                var report = handle.getTickReport15s(System.nanoTime());

                double mspt = report == null ? 0.0D
                    : report.timePerTickData().segmentAll().average() / 1.0E6D;
                double tps = report == null ? 20.0D
                    : report.tpsData().segmentAll().average();

                rows.add(new Row(
                    level.dimension().location().getPath(),
                    mspt,
                    tps,
                    region.getOwnedSections().size() * 16,
                    data.world.getLocalPlayers().size(),
                    region.getCenterChunk() == null ? 0 : region.getCenterChunk().x,
                    region.getCenterChunk() == null ? 0 : region.getCenterChunk().z));
            });
            players += level.players().size();
        }
        rows.sort(Comparator.comparingDouble(Row::mspt).reversed());
        return new RegionReport(List.copyOf(rows), players);
    }

    public int regionCount() {
        return rows.size();
    }

    public List<Row> busiest(int limit) {
        return rows.subList(0, Math.min(limit, rows.size()));
    }

    public double worstMspt() {
        return rows.isEmpty() ? 0.0D : rows.get(0).mspt();
    }

    public double worstTps() {
        return rows.stream().mapToDouble(Row::tps).min().orElse(20.0D);
    }

    /** Median rather than mean: one pathological region should not hide a healthy server. */
    public double medianMspt() {
        if (rows.isEmpty()) {
            return 0.0D;
        }
        List<Double> sorted = rows.stream().map(Row::mspt).sorted().toList();
        int mid = sorted.size() / 2;
        return sorted.size() % 2 == 1
            ? sorted.get(mid)
            : (sorted.get(mid - 1) + sorted.get(mid)) / 2.0D;
    }
}
