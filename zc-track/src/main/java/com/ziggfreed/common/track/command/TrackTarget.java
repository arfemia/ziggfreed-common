package com.ziggfreed.common.track.command;

import java.util.UUID;

import javax.annotation.Nonnull;

/**
 * The player a {@code /zigtrack} verb was pointed at: whose climb, and the name the answers speak (data,
 * never translated).
 */
record TrackTarget(@Nonnull UUID id, @Nonnull String name) {
}
