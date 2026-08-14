package com.ziggfreed.common.ui.route;

import javax.annotation.Nonnull;

/**
 * What a registered destination type DOES: put its screen in front of the player.
 *
 * <p>Return true ONLY when the screen was actually taken over. A caller that gets false still owes
 * the player a response and falls back to whatever it would have done anyway, so a handler that
 * declines - a page manager that refused, content that vanished between the click and the open -
 * must say so rather than reporting a screen it did not paint.
 *
 * <p>World thread; the context's handles are live only for the duration of the call.
 *
 * @param <D> the destination type this handler opens
 */
@FunctionalInterface
public interface DestinationHandler<D extends Destination> {

    boolean open(@Nonnull D destination, @Nonnull DestinationContext ctx);
}
