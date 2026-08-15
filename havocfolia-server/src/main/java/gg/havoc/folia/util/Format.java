package gg.havoc.folia.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;

/** Shared formatting so every HavocFolia command looks like it came from the same server. */
public final class Format {

    public static final TextColor ACCENT = TextColor.color(0xE0245E);
    public static final TextColor MUTED = TextColor.color(0x8A8A8A);

    private Format() {
    }

    public static Component prefix() {
        return Component.text("Havoc", ACCENT).append(Component.text(" | ", MUTED));
    }

    public static Component info(String message) {
        return prefix().append(Component.text(message, NamedTextColor.WHITE));
    }

    public static Component ok(String message) {
        return prefix().append(Component.text(message, NamedTextColor.GREEN));
    }

    public static Component warn(String message) {
        return prefix().append(Component.text(message, NamedTextColor.YELLOW));
    }

    public static Component error(String message) {
        return prefix().append(Component.text(message, NamedTextColor.RED));
    }

    public static Component field(String label, String value) {
        return Component.text("  " + label + ": ", MUTED).append(Component.text(value, NamedTextColor.WHITE));
    }

    /** Colours a tick time: green under 30ms, yellow under 45, red past that. */
    public static Component mspt(double value) {
        NamedTextColor colour = value < 30.0D ? NamedTextColor.GREEN
            : value < 45.0D ? NamedTextColor.YELLOW : NamedTextColor.RED;
        return Component.text(String.format("%.2fms", value), colour);
    }

    public static Component tps(double value) {
        NamedTextColor colour = value > 19.0D ? NamedTextColor.GREEN
            : value > 15.0D ? NamedTextColor.YELLOW : NamedTextColor.RED;
        return Component.text(String.format("%.2f", Math.min(20.0D, value)), colour);
    }

    public static String bytes(long value) {
        if (value < 1024L) return value + " B";
        if (value < 1024L * 1024L) return String.format("%.1f KiB", value / 1024.0D);
        if (value < 1024L * 1024L * 1024L) return String.format("%.1f MiB", value / (1024.0D * 1024.0D));
        return String.format("%.2f GiB", value / (1024.0D * 1024.0D * 1024.0D));
    }
}
