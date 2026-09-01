package com.ziggfreed.common.loot.stamp;

import java.util.Locale;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.i18n.LangCatalog;
import com.ziggfreed.common.i18n.Msg;

/**
 * How a stamped stat reads when nobody has claimed its vocabulary - which is meant to be the usual
 * case, not the fallback of last resort.
 *
 * <p><b>A mod that adds a stat should not have to register a name for it.</b> The client already
 * names every stat it can show, under {@code client.itemTooltip.stats.<StatId>}, and that is where
 * this looks first. A stat the base game defines is therefore named correctly with no registration
 * at all, and so is a mod's own stat the moment that mod ships the same key it needs anyway for the
 * ordinary item tooltip. Registering a {@link StatNamer} is for wanting something MORE - a colour,
 * a different wording than the tooltip's - never for wanting a name at all.
 *
 * <p>An authored {@link StatDisplayAsset} beats all of it. A server owner or a pack can name or
 * colour any stat by dropping a file in, without waiting on the mod that invented it - which is the
 * same order everything else here follows: what a server authored outranks what a mod compiled.
 *
 * <p>Behind that this library names exactly one thing itself, {@link #DURABILITY}, because it is not
 * an EntityStat and so the client ships no label for it. Anything still unnamed prints as its own id
 * with its points: honest and debuggable, so an author who misspells a stat id sees the misspelling
 * on the item rather than a blank line or a plausible-looking wrong stat.
 */
public final class DefaultStatNames {

    /**
     * The reserved stat id that raises an item's MAXIMUM DURABILITY instead of storing a stat entry.
     *
     * <p>It is reserved rather than ordinary because durability is not a stat channel at all - it is
     * a property of the stack, so there is nothing for the equip bridge to put a modifier on. The
     * roll math still treats it as any other id (it takes picks, it costs budget, a per-stat ceiling
     * holds it), and only the WRITE knows the difference. That is the whole of the special case, and
     * it lives at the write on purpose: a pool author gets to roll durability against the same
     * budget as everything else, without the cap engine ever learning what a durability is.
     */
    public static final String DURABILITY = "Durability";

    /** The client's own stat-label convention - the first place a name is looked for. */
    private static final String CLIENT_PREFIX = "client.itemTooltip.stats.";

    /** The lang namespace of this library's own backup labels: the shipped file's basename. */
    private static final String PREFIX = "ziggfreedcommon.stamp";

    /**
     * The one id this library has to name itself: durability is not an EntityStat, so the client
     * ships no tooltip label for it. Every other stat the game defines already has one, and shipping
     * a second translation of a string the client already carries would only be a way for the two to
     * disagree.
     */
    private static final Map<String, String> BACKUP_KEYS = Map.of("durability", "stat.durability");

    /**
     * A colour for the few stats this library names itself. Anything else is left uncoloured rather
     * than guessed at - a wrong colour reads as meaning something, and the mod that owns the stat is
     * the one that knows what it should mean.
     */
    private static final Map<String, String> COLORS = Map.of(
            "health", "#e05561",
            "mana", "#5b8dd9",
            "stamina", "#78c46a",
            "oxygen", "#5bc8d9",
            "durability", "#b8b8b8");

    private DefaultStatNames() {
    }

    /** True when this id is the reserved durability one, spelled any way. */
    public static boolean isDurability(@Nonnull String statId) {
        return DURABILITY.equalsIgnoreCase(statId.trim());
    }

    /**
     * The library's own line for {@code statId}: the client's tooltip label if it has one, else this
     * library's backup label, else the id and its points as plain text.
     */
    @Nonnull
    public static Message name(@Nonnull String statId, double points) {
        String id = statId.trim();
        Map<String, Object> value = Map.of("value", Msg.raw(formatPoints(points)));
        StatDisplayAsset authored = authored(id);

        String authoredKey = authored != null ? authored.getKey() : null;
        if (authoredKey != null && !authoredKey.isBlank()) {
            return tint(Msg.keyNamed(authoredKey, value), id, authored);
        }
        String clientKey = CLIENT_PREFIX + id;
        if (LangCatalog.has(clientKey)) {
            return tint(Msg.keyNamed(clientKey, value), id, authored);
        }
        String backup = BACKUP_KEYS.get(id.toLowerCase(Locale.ROOT));
        if (backup != null) {
            return tint(Msg.trNamed(PREFIX, backup, value), id, authored);
        }
        // NUMBER-OK: an unnamed stat id is an operator-facing debugging line, not localized copy.
        return Msg.raw(id + " " + formatPoints(points));
    }

    /** The authored file for {@code statId}, or null when there is none (or none loaded yet). */
    @Nullable
    private static StatDisplayAsset authored(@Nonnull String statId) {
        try {
            return StatDisplayConfig.getInstance().resolve(statId.toLowerCase(Locale.ROOT));
        } catch (Throwable ignored) {
            // Naming runs while a tooltip is composed; a config not yet loaded is a miss, not a fault.
            return null;
        }
    }

    /**
     * {@code line} in the authored colour if a file gave one, else this library's own for the few
     * stats it names, else uncoloured. A colour is never guessed at: a wrong one reads as meaning
     * something, and only the mod that owns a stat knows what it should mean.
     */
    @Nonnull
    private static Message tint(@Nonnull Message line, @Nonnull String statId,
            @Nullable StatDisplayAsset authored) {
        String authoredColor = authored != null ? authored.getColor() : null;
        if (authoredColor != null && !authoredColor.isBlank()) {
            return line.color(authoredColor);
        }
        String color = COLORS.get(statId.toLowerCase(Locale.ROOT));
        return color != null ? line.color(color) : line;
    }

    /**
     * A point value as it reads on a line: a whole number keeps its whole spelling, so {@code 3.0}
     * shows as {@code +3} rather than {@code +3.0}.
     */
    @Nonnull
    // NUMBER-OK: a signed points badge on a stat line, not a locale-formatted quantity.
    public static String formatPoints(double points) {
        if (Double.isFinite(points) && points == Math.rint(points)) {
            return "+" + (long) points;
        }
        return "+" + points;
    }

    /** The key this library would use for {@code statId}, or null when it would print it raw. */
    @Nullable
    static String keyFor(@Nonnull String statId) {
        String id = statId.trim();
        String clientKey = CLIENT_PREFIX + id;
        if (LangCatalog.has(clientKey)) {
            return clientKey;
        }
        String backup = BACKUP_KEYS.get(id.toLowerCase(Locale.ROOT));
        return backup == null ? null : PREFIX + "." + backup;
    }
}
