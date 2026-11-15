package com.ziggfreed.common.progress;

import javax.annotation.Nullable;

/**
 * A {@value #KIND} step's target, parsed: {@code "x,y,z[,radius]"} or {@code "world:x,y,z[,radius]"}, the
 * radius {@value #DEFAULT_RADIUS} blocks when it is left out. A pure string parse, so a reader may cache it;
 * {@link #parse} answers null on anything malformed, which is how a validator says the step can never be met.
 *
 * <p>A generated world is different on every seed, so a step of this kind is a server owner's tool for a
 * place they built, never something a pack ships for an unknown map. A world name holding {@code ':'} is not
 * supported; a structured target can sit beside this string form later without breaking a written one.
 */
public record LocationTarget(@Nullable String world, double x, double y, double z, double radius) {

    /** The objective kind whose target this is: arriving within the sphere. */
    public static final String KIND = "REACH_LOCATION";

    /** The sphere's radius, in blocks, when the target names none. */
    public static final double DEFAULT_RADIUS = 5.0;

    /** The target {@code raw} names, or null when it is blank or malformed. */
    @Nullable
    public static LocationTarget parse(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String world = null;
        String coords = raw.trim();
        int colon = coords.indexOf(':');
        if (colon >= 0) {
            world = coords.substring(0, colon).trim();
            coords = coords.substring(colon + 1).trim();
            if (world.isEmpty()) {
                world = null;
            }
        }
        String[] parts = coords.split(",");
        if (parts.length < 3 || parts.length > 4) {
            return null;
        }
        try {
            double x = Double.parseDouble(parts[0].trim());
            double y = Double.parseDouble(parts[1].trim());
            double z = Double.parseDouble(parts[2].trim());
            double radius = parts.length == 4 ? Double.parseDouble(parts[3].trim()) : DEFAULT_RADIUS;
            if (radius <= 0 || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                    || !Double.isFinite(radius)) {
                return null;
            }
            return new LocationTarget(world, x, y, z, radius);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Is the point inside the sphere (its edge included)? */
    public boolean contains(double px, double py, double pz) {
        double dx = px - x;
        double dy = py - y;
        double dz = pz - z;
        return dx * dx + dy * dy + dz * dz <= radius * radius;
    }

    /** True when this target is bound to a world that is not {@code worldName}, compared without regard to case. */
    public boolean excludesWorld(@Nullable String worldName) {
        return world != null && (worldName == null || !world.equalsIgnoreCase(worldName));
    }
}
