package com.ziggfreed.common.i18n;

import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.text.ContentTextAsset;

/**
 * The argument words an authored text slot may carry beside plain text, read the same way by every surface that
 * fills a content key's {@code {0}, {1}, ...} (a Claim's title, a track pick's name and description): so one lang
 * key serves a whole family of content, each member nesting its own name or number into it, and the family adds one
 * key, never one per member.
 *
 * <ul>
 *   <li>{@value #KEY}{@code <lang key>}: that key's own line, resolved on the reader's client in the reader's
 *       language ({@link ContentKeys#tr}, so an authored key without its file's namespace still resolves), nested as
 *       a {@link Message}: {@code "@key:skill.swords"} names a skill, {@code "@key:yourmod.foe.undead"} a family;</li>
 *   <li>{@value #ITEM}{@code <item id>}: the item's own name ({@link NativeNames#itemNameMsg}), nested the same
 *       way;</li>
 *   <li>{@value #NUM}{@code <number>}: a number bound as a typed param, so the reader's client formats it (a
 *       {@code {0, number}} slot): {@code "@num:5"}, {@code "@num:0.6"}; text that reads as no number stays the
 *       literal it was written as.</li>
 * </ul>
 *
 * <p>Anything else is passed through as written: a consumer's own sentinel (ContentTextAsset's {@code @amount},
 * answered by its {@link ContentTextAsset.ArgLookup}), or plain text.
 */
public final class ContentArgs {

    /** What starts an argument that reads as a lang key's own line. */
    public static final String KEY = "@key:";

    /** What starts an argument that reads as an item's name. */
    public static final String ITEM = "@item:";

    /** What starts an argument that reads as a typed number. */
    public static final String NUM = "@num:";

    private ContentArgs() {
    }

    /**
     * What {@code arg} binds as when it is one of this class's words: a nested {@link Message} for a key or an item,
     * a {@link Long} or {@link Double} for a number; null for anything else (plain text, another sentinel).
     */
    @Nullable
    public static Object nested(@Nullable String arg) {
        if (arg == null || arg.length() < 2 || arg.charAt(0) != '@') {
            return null;
        }
        if (arg.startsWith(KEY) && arg.length() > KEY.length()) {
            return ContentKeys.tr(arg.substring(KEY.length()).trim());
        }
        if (arg.startsWith(ITEM) && arg.length() > ITEM.length()) {
            return NativeNames.itemNameMsg(arg.substring(ITEM.length()).trim());
        }
        if (arg.startsWith(NUM) && arg.length() > NUM.length()) {
            return number(arg.substring(NUM.length()).trim());
        }
        return null;
    }

    /**
     * {@code authored} as a key's arguments: each of this class's words nested ({@link #nested}), each other
     * {@code @} word asked of {@code more} (null for none), everything else passed through as written.
     */
    @Nonnull
    public static Object[] expand(@Nullable List<String> authored, @Nullable ContentTextAsset.ArgLookup more) {
        return ContentTextAsset.expand(authored, sentinel -> {
            Object own = nested(sentinel);
            return own != null || more == null ? own : more.resolve(sentinel);
        });
    }

    /** {@code text} as a typed number (a whole one as a Long), or null when it reads as none. */
    @Nullable
    private static Object number(@Nonnull String text) {
        try {
            double value = Double.parseDouble(text);
            if (!Double.isFinite(value)) {
                return null;
            }
            if (value == Math.rint(value) && Math.abs(value) < 9.0e15) {
                return (long) value;
            }
            return value;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
