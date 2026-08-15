package gg.havoc.folia.view;

import io.papermc.paper.threadedregions.TickRegionScheduler;

/**
 * Reads the tick time of the region the calling thread is currently ticking.
 *
 * <p>Deliberately caller-thread-local. Under Folia there is no single "server MSPT" worth acting
 * on — one region can be melting while the rest of the world is idle. Any adaptive behaviour has
 * to key off the region that actually owns the player, and the cheapest way to get that is to ask
 * while running on its thread.
 *
 * <p>Returns 0 when called off a region thread, which callers read as "no pressure" — failing
 * open is right here, because the worst case of a wrong answer is a view distance that does not
 * shrink, not a broken world.
 */
public final class RegionMspt {

    private RegionMspt() {
    }

    /** Mean tick time in milliseconds over the last 15 seconds for the current region. */
    public static double current() {
        TickRegionScheduler.RegionScheduleHandle handle = TickRegionScheduler.getCurrentRegionizedWorldData() == null
            ? null
            : TickRegionScheduler.getCurrentTickingRegion();
        if (handle == null) {
            return 0.0D;
        }
        var report = handle.getTickReport15s(System.nanoTime());
        if (report == null) {
            return 0.0D;
        }
        return report.timePerTickData().segmentAll().average() / 1.0E6D;
    }
}
