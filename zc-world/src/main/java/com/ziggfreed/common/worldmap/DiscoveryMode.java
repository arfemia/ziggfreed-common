package com.ziggfreed.common.worldmap;

/**
 * How a {@link MapDiscovery.DiscoverablePoi} becomes visible on the world map - the TRIGGER axis,
 * orthogonal to {@link MapDiscovery.Visibility} (which is WHO sees a discovered POI). A consumer
 * stores this as a per-context policy knob and drives the matching {@link MapDiscovery} call.
 *
 * <ul>
 *   <li>{@link #OFF} - discovery disabled; the consumer skips creating a tracker, so nothing renders.</li>
 *   <li>{@link #ON_INTERACT} - a POI is revealed when the consumer calls {@link MapDiscovery#discover}
 *       (e.g. from a block / NPC interaction handler).</li>
 *   <li>{@link #PROXIMITY} - a POI is revealed when a player comes within a radius; the consumer drives
 *       it from its own tick via {@link MapDiscovery#revealWithin}.</li>
 * </ul>
 */
public enum DiscoveryMode {
    OFF,
    ON_INTERACT,
    PROXIMITY
}
