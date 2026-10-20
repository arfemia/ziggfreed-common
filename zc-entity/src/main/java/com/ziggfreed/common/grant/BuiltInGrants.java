package com.ziggfreed.common.grant;

/**
 * The library's own grant kinds ({@code Stat}) in {@link Grant#CODEC} before any setup: a codec that reads grants (a
 * track's {@code Points.Grants}, a pick's {@code Grants}) calls {@link #ensure} from its own initializer, so a file
 * naming them decodes in any JVM, a unit test or a consumer reading a file before setup included. Setup still
 * registers each through the plugin's codec registry with its writer ({@code EntityBootstrap}), the same put again.
 *
 * <p>A holder of its own on purpose: neither {@link Grant} nor {@link StatGrant} names it, so no thread first touching
 * one of the two can wait on the other's initializer (a subclass initializing its superclass while the superclass's
 * initializer reaches back to it is a deadlock between two threads).
 */
public final class BuiltInGrants {

    static {
        Grant.CODEC.register(StatGrant.KIND, StatGrant.class, StatGrant.CODEC);
    }

    private BuiltInGrants() {
    }

    /** Make sure the library's kinds are in {@link Grant#CODEC}: this class's initializer puts them there, once. */
    public static void ensure() {
        // Nothing beyond loading the class.
    }
}
