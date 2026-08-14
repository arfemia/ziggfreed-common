package com.ziggfreed.common.commerce.page;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Two clicks for anything that charges: the first ARMS and says what it will cost, the second inside
 * the window goes through.
 *
 * <p>It exists for one specific accident. A reroll button sits where a player is already clicking
 * fast, its price leaves the wallet with no further prompt, and the thing it buys is a random swap -
 * so a mis-click costs real currency for a result nobody asked for. Arming turns that into a
 * question with the price in it.
 *
 * <p>Page-instance state, and deliberately not persisted: an arm that survived a relog would fire on
 * a click the player made in a different session about a different thing. Pure and clock-injected,
 * so the window is assertable without waiting for one.
 */
public final class ConfirmArm {

    /** How long an armed press stays armed. Long enough to read the price, short enough to forget. */
    public static final long DEFAULT_WINDOW_MS = 5_000L;

    private final long windowMs;

    private final Map<String, Long> armedAt = new ConcurrentHashMap<>();

    /** An arm with the default window. */
    public ConfirmArm() {
        this(DEFAULT_WINDOW_MS);
    }

    /** An arm whose window is {@code windowMs}; anything non-positive means the default. */
    public ConfirmArm(long windowMs) {
        this.windowMs = windowMs > 0 ? windowMs : DEFAULT_WINDOW_MS;
    }

    /**
     * Press {@code key}. False means this press ARMED it and the caller should say what it costs;
     * true means it was already armed and the action may go ahead.
     *
     * <p>A confirmed press clears the arm, so the next one starts over rather than letting a second
     * charge through on a stray click.
     */
    public boolean confirm(@Nullable String key, long nowMs) {
        String id = keyOf(key);
        Long armed = armedAt.get(id);
        if (armed != null && nowMs - armed.longValue() <= windowMs) {
            armedAt.remove(id);
            return true;
        }
        armedAt.put(id, Long.valueOf(nowMs));
        return false;
    }

    /** Is {@code key} armed right now? What a render asks, to label the button Confirm. */
    public boolean isArmed(@Nullable String key, long nowMs) {
        Long armed = armedAt.get(keyOf(key));
        return armed != null && nowMs - armed.longValue() <= windowMs;
    }

    /**
     * Forget every arm. Called whenever the player does something ELSE - selects another row, buys
     * something - because an arm left standing behind an unrelated action is a charge waiting to
     * happen on a click that was never about it.
     */
    public void reset() {
        armedAt.clear();
    }

    @Nonnull
    private static String keyOf(@Nullable String key) {
        return key == null ? "" : key;
    }
}
