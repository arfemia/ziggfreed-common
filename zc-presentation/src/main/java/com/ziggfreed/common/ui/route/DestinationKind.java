package com.ziggfreed.common.ui.route;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * What KIND of screen a destination opens, declared once beside its registration so a surface that
 * offers a way there (a conversation's answer row) can show the player what they are about to open
 * before they press it: a quest list, a shop, a board, their standing, a book, a trophy case, or
 * another conversation.
 *
 * <p>A kind is presentation only: it never changes what opening the destination does. A type that
 * declares none is still a perfectly good destination and reads as a generic "opens a screen".
 *
 * <pre>{@code
 * Destinations.register("mymod", DestinationType.of("Mymod_Shop", Shop.class, Shop.CODEC, MyPages::openShop)
 *         .withKind(DestinationKind.SHOP));
 * }</pre>
 *
 * <p>The vocabulary is closed on purpose: each kind is a glyph the client already holds (a texture
 * path lives in markup, never in Java), so a new kind is a new glyph drawn by
 * {@code tools/ui/make_ui_textures.py} and a new hidden child in the row that shows it.
 */
public enum DestinationKind {

    /** A list of quests, a character's or the player's own log. */
    QUEST("quest"),
    /** A storefront. */
    SHOP("shop"),
    /** A board of contracts. */
    BOARD("board"),
    /** The player's standing with somebody: a reputation. */
    STANDING("standing"),
    /** Something to read: an almanac, a guide. */
    BOOK("book"),
    /** What the player has achieved: achievements, records. */
    TROPHY("trophy"),
    /** Another conversation. */
    TALK("talk");

    private final String key;

    DestinationKind(@Nonnull String key) {
        this.key = key;
    }

    /** The lower-case token a surface maps to its glyph ({@code "shop"}). */
    @Nonnull
    public String key() {
        return key;
    }

    /** The kind whose {@link #key()} equals {@code key}, case-insensitively, or null. */
    @Nullable
    public static DestinationKind byKey(@Nullable String key) {
        if (key == null) {
            return null;
        }
        String wanted = key.trim();
        for (DestinationKind kind : values()) {
            if (kind.key.equalsIgnoreCase(wanted)) {
                return kind;
            }
        }
        return null;
    }
}
