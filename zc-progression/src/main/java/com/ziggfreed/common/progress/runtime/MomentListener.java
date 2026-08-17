package com.ziggfreed.common.progress.runtime;

import javax.annotation.Nonnull;

/**
 * "This just happened to this player" - a REACTION to a produced moment, called once for every
 * moment any producer fires, before either engine is asked anything about it.
 *
 * <p>This is where a consumer hangs what it does when a block breaks, a mob dies, an item is
 * crafted, picked up or placed - pay out its own progression currency, count a lifetime total, roll
 * a bonus - so that it reacts to the ONE moment the library produced instead of re-detecting the
 * native event beside the producer and resolving the target, the zone, the placed guard and the
 * owner a second time.
 *
 * <p><b>What it is not.</b> It is not the {@link com.ziggfreed.common.progress.ProgressDispatchTap
 * dispatch tap}: the tap says "an engine considered this", fires inside each engine, after that
 * engine's subject was found and its system gate said yes, and is spent once per action. A listener
 * says "this happened", fires whether or not this player has a quest subject, whether or not an
 * owner has either system switched off, and whether or not anything authored cares. It is not a
 * gate either: nothing a listener does or fails to do can refuse a moment, stand a producer down or
 * keep the engines from seeing it.
 *
 * <p><b>Contributions STACK.</b> Every registered listener is called on every moment, each inside
 * its own guard, so one mod's broken reaction costs its own reaction and nobody else's - and never
 * the dispatch it was reacting to. Registration order is not a precedence: no listener can mark a
 * moment "handled". A listener registered after the runtime was built still fires, because the
 * dispatch calls through a live forwarder over whatever is registered right now.
 *
 * <p>Called on the world thread, inside the producing system's own dispatch, so a listener writes
 * against the {@link Moment#store() store} and {@link Moment#ref() ref} it is handed exactly as the
 * event system it replaces did. Keep it to the reaction; anything slow slows every action in the
 * game.
 */
@FunctionalInterface
public interface MomentListener {

    /** Reacts to nothing: the composed answer on a runtime nobody registered a listener into. */
    MomentListener NONE = moment -> {
    };

    /** One produced moment, exactly as the producer fired it. */
    void react(@Nonnull Moment moment);
}
