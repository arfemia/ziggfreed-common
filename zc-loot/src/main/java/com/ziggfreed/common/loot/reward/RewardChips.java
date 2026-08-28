package com.ziggfreed.common.loot.reward;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.inventory.ItemStack;

import com.ziggfreed.common.i18n.ContentKeys;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.instance.reward.DeferredRewards;

/**
 * Turns {@link RewardSpec}s into the chips a surface paints, WITHOUT knowing what any one kind
 * means.
 *
 * <p>A reward is a kind plus a bag of strings, and what a kind is called belongs to whoever defined
 * it. So nothing here branches on a kind id: a chip is assembled from the same three sources the
 * deferred-payout layer already reads, in the same order, so a reward previewed on a quest, on a
 * storefront offer and on a results screen cannot disagree about its own name.
 *
 * <ol>
 *   <li>the reward's OWN {@code NameKey} / {@code Icon} parameters, which is one reward saying how it
 *       reads;</li>
 *   <li>the kind FILE's {@code Presentation} group, which is every reward of that kind saying it once
 *       (a kind asset layers defaults &lt; pack &lt; owner, so a pack extending a mapping needs no
 *       Java at all). This rung yields only when the key its template resolves to is one something
 *       on this server actually ships: the file is a DEFAULT, and a default pointing at nothing must
 *       not outrank the rungs below it - which is what lets a per-skill key family cover the skills
 *       it names while a skill it never heard of still reads through a contributed rescue instead of
 *       painting a raw key;</li>
 *   <li>the item form: a spec naming an item is drawn with that item's own engine display name, in
 *       whatever locale the player's client speaks.</li>
 * </ol>
 *
 * <p><b>A reward nothing can name is DROPPED rather than guessed at.</b> Painting a raw kind token
 * at a player reads as a promise of something called {@code Mmo_Boost_Token}, which is worse than
 * showing one fewer chip; the fix is a two-line {@code Presentation} on the kind file, and the
 * validator that reads kind assets is where an author is told so. One rung sits between those two
 * outcomes: a kind's OWNER may {@link #contribute} a reading process-wide, which is how a
 * Java-registered kind whose rewards all read the same way (a wallet kind reading as its wallet's
 * own name) names them without any reward authoring anything.
 *
 * <p>{@link Plan} is the whole decision and touches nothing but strings, so what a chip WILL say is
 * testable with no server; {@link #chipFor} is the half that reaches an item for its name.
 */
public final class RewardChips {

    /** The item id a reward hands over, under either of the two spellings the loot kinds accept. */
    private static final String P_ITEM = "item";

    private static final String P_ID = "id";

    /**
     * A consumer's own reading of one reward, for a mod that knows something about its own kind the
     * generic reading cannot recover. A null answer falls through to the generic reading, never
     * instead of it.
     */
    @FunctionalInterface
    public interface Source {

        @Nullable
        RewardChip chipFor(@Nonnull RewardSpec spec);
    }

    /** No consumer opinion about any reward, so every chip takes the generic reading. */
    public static final Source GENERIC = spec -> null;

    /**
     * Readings a kind's OWNER contributed process-wide, asked only when the generic reading could
     * not name a reward - the rung between "nothing names this" and dropping the chip. A wallet
     * kind reads as its wallet's own name this way, on every surface at once, without a single
     * reward authoring a {@code NameKey}.
     */
    private static final CopyOnWriteArrayList<Source> CONTRIBUTED = new CopyOnWriteArrayList<>();

    private RewardChips() {
    }

    /**
     * Contribute the reading for a kind this caller OWNS, once at setup. It is asked (in
     * contribution order, first answer wins) only for a reward the generic reading could not name,
     * so it can never override a reward's own {@code NameKey}, a kind file's {@code Presentation},
     * or an item's own display name - it only rescues what would otherwise be dropped. A reward's
     * own authored {@code Icon} survives even here: a contributed chip is re-pointed at it, so the
     * "a reward's own words and picture win first" contract holds on every rung.
     */
    public static void contribute(@Nonnull Source source) {
        CONTRIBUTED.add(source);
    }

    /**
     * What a chip will say, decided from strings alone: the loc key to render (with {@link #amount}
     * as its one argument), the item to draw the name of when there is no key, and the picture.
     *
     * <p>A plan with neither {@link #nameKey} nor {@link #itemId} is a reward nothing can name.
     */
    public record Plan(@Nullable String nameKey, @Nullable String itemId, @Nullable String iconItemId,
                       long amount) {
    }

    /**
     * The chips for {@code rewards}, in authored order, skipping every reward nothing could name.
     * {@code source} is asked FIRST for each reward and its answer wins outright; a null answer (or a
     * throwing seam) falls through to the generic reading rather than dropping the reward.
     */
    @Nonnull
    public static List<RewardChip> chipsFor(@Nonnull List<RewardSpec> rewards, @Nullable Source source) {
        List<RewardChip> out = new ArrayList<>();
        for (RewardSpec spec : rewards) {
            if (spec == null) {
                continue;
            }
            RewardChip chip = null;
            if (source != null) {
                try {
                    chip = source.chipFor(spec);
                } catch (Throwable ignored) {
                    // A consumer's own reading failing costs that chip's styling, never the panel.
                    chip = null;
                }
            }
            if (chip == null) {
                chip = chipFor(spec);
            }
            if (chip != null) {
                out.add(chip);
            }
        }
        return out;
    }

    /** The generic reading of one reward, or null when nothing on this server can name it. */
    @Nullable
    public static RewardChip chipFor(@Nonnull RewardSpec spec) {
        Plan plan = plan(spec);
        String nameKey = plan.nameKey();
        // The kind FILE's rung yields only when the key it points at is actually shipped by
        // something on this server: the file's Presentation is a DEFAULT, and a default resolving
        // to nothing must not outrank the item form or a kind owner's contributed reading - that is
        // how a per-skill key family covers the skills it names while everything else still gets a
        // real label instead of a raw key. A reward's OWN NameKey stays as written even when nothing
        // ships it, because an author's explicit word painting as a traceable raw key is how the
        // author finds the typo.
        boolean ownKey = isWritten(spec.param(DeferredRewards.PARAM_NAME_KEY));
        if (nameKey != null && !ownKey && !ContentKeys.known(nameKey)) {
            nameKey = null;
        }
        if (nameKey != null && !nameKey.isBlank()) {
            // Through ContentKeys, never as written: the engine namespaces a key by the .lang FILENAME
            // it was defined in, while a reward's name key is authored without that namespace, so a key
            // handed over verbatim is one the client cannot resolve and the player reads the key itself.
            return RewardChip.of(plan.iconItemId(), ContentKeys.tr(nameKey, labelArgs(spec, plan)));
        }
        if (plan.itemId() != null && !plan.itemId().isBlank()) {
            return RewardChip.of(plan.iconItemId(), itemLabel(plan.itemId(), plan.amount()));
        }
        // The reward's OWN authored Icon survives onto the contributed rung too: "a reward's own
        // words and picture win first" holds on every rung, so a boost token (or any other
        // contributed-named reward) that authored an Icon is drawn with it, not with the
        // contribution's computed one. Only the reward's own parameter re-points the picture -
        // the kind FILE's icon does not, because a contribution may know a better per-value
        // answer than the file's default (a custom skill's own registry icon, say).
        String ownIcon = spec.param(DeferredRewards.PARAM_ICON);
        for (Source contributed : CONTRIBUTED) {
            try {
                RewardChip chip = contributed.chipFor(spec);
                if (chip != null) {
                    return isWritten(ownIcon) ? RewardChip.of(ownIcon, chip.label()) : chip;
                }
            } catch (Throwable ignored) {
                // A contributed reading failing costs its own answer, never the panel.
            }
        }
        return null;
    }

    /**
     * What fills the label key's {@code {0}, {1}, ...} blanks for {@code spec}: the kind file's
     * {@code Presentation.Args} entries in order, or the reward's amount as the one {@code {0}}
     * when none were authored.
     *
     * <p>Each entry naming a declared parameter binds that parameter's effective value - as a
     * NUMBER when it reads as one, so a {@code {0, number}} blank groups its digits in the player's
     * own locale. An entry that looks like a key (it carries a {@code .} or a {@code {}) is a
     * localization-key template filled exactly the way {@code NameKey} is and bound as a NESTED
     * client-translated {@link Message}, which is what renders a {@code Skill} parameter as the
     * translated skill name rather than the literal {@code MINING}. An entry that is NEITHER - a
     * plain word naming no declared parameter, almost always a mis-spelled one - is DROPPED: its
     * blank fills as empty rather than painting a raw token at a player, exactly as
     * {@code FeedbackMomentAsset.Line.Args} refuses an argument the moment does not carry, and the
     * reward-kind validator reports it.
     */
    @Nonnull
    private static Object[] labelArgs(@Nonnull RewardSpec spec, @Nonnull Plan plan) {
        RewardKindAsset kind = kindOf(spec);
        List<String> authored = kind == null || kind.getPresentation() == null
                ? List.of()
                : kind.getPresentation().argsList();
        if (authored.isEmpty()) {
            return new Object[] {plan.amount()};
        }
        Object[] out = new Object[authored.size()];
        for (int i = 0; i < authored.size(); i++) {
            String entry = authored.get(i);
            if (kind.declares(entry)) {
                out[i] = numberOrText(kind.effectiveParam(spec, entry));
            } else if (entry.indexOf('.') >= 0 || entry.indexOf('{') >= 0) {
                out[i] = ContentKeys.tr(kind.fillKeyTemplate(spec, entry));
            } else {
                out[i] = Msg.raw("");
            }
        }
        return out;
    }

    /** A parameter value as the number it spells when it spells one, else the text itself. */
    @Nonnull
    private static Object numberOrText(@Nonnull String value) {
        String trimmed = value.trim();
        try {
            return Long.parseLong(trimmed);
        } catch (NumberFormatException notWhole) {
            try {
                return Double.parseDouble(trimmed);
            } catch (NumberFormatException notNumeric) {
                return value;
            }
        }
    }

    /**
     * The item-form reading as one reusable chip: the item's own picture beside its own engine
     * display name, counted. Public so a contributed reading whose reward turns out to BE an item
     * (a parsed {@code /give} line, say) reads exactly like a declared item grant instead of
     * composing a second item line.
     */
    @Nonnull
    public static RewardChip itemChip(@Nonnull String itemId, long amount) {
        return RewardChip.of(itemId, itemLabel(itemId, amount));
    }

    private static boolean isWritten(@Nullable String value) {
        return value != null && !value.isBlank();
    }

    /**
     * The pure half: which key, which item and which picture one reward resolves to, reading the
     * spec's own parameters first and the authored kind file second.
     */
    @Nonnull
    public static Plan plan(@Nonnull RewardSpec spec) {
        RewardKindAsset kind = kindOf(spec);
        String nameKey = firstWritten(spec.param(DeferredRewards.PARAM_NAME_KEY),
                kind == null ? null : safeNameKey(kind, spec));
        String icon = firstWritten(spec.param(DeferredRewards.PARAM_ICON),
                kind == null ? null : safeIcon(kind, spec));
        String itemId = firstWritten(spec.param(P_ITEM), spec.param(P_ID));
        return new Plan(nameKey, itemId, firstWritten(icon, itemId), amountOf(spec));
    }

    /**
     * The number a chip counts: the reward's own {@code Amount}, else the {@code Count} or
     * {@code Quantity} an item-shaped reward writes instead. The same ladder the deferred-payout
     * layer reads, so one reward cannot preview as five and pay out as one.
     */
    public static long amountOf(@Nonnull RewardSpec spec) {
        long amount = spec.longParam(DeferredRewards.PARAM_AMOUNT,
                spec.longParam("Count", spec.longParam("Quantity", 1L)));
        return amount <= 0 ? 1L : amount;
    }

    /** The kind FILE behind a spec, or null when its kind was registered in Java or not at all. */
    @Nullable
    private static RewardKindAsset kindOf(@Nonnull RewardSpec spec) {
        try {
            return RewardKindConfig.getInstance().resolve(spec.kind());
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Nullable
    private static String safeNameKey(@Nonnull RewardKindAsset kind, @Nonnull RewardSpec spec) {
        try {
            return kind.presentationNameKey(spec);
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Nullable
    private static String safeIcon(@Nonnull RewardKindAsset kind, @Nonnull RewardSpec spec) {
        try {
            return kind.presentationIcon(spec);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * An item's own engine display name, so the chip reads in the player's locale with no key this
     * library had to guess. The quantity prefix is locale-neutral glue, matching how the results
     * screen composes the same line.
     */
    @Nonnull
    private static Message itemLabel(@Nullable String itemId, long amount) {
        int count = (int) Math.max(1L, Math.min(amount, Integer.MAX_VALUE));
        Message name;
        try {
            name = new ItemStack(itemId, count).getDisplayName();
        } catch (Throwable ignored) {
            name = Msg.raw(itemId);
        }
        return count > 1 ? Message.join(Msg.raw("x" + count + " "), name) : name;
    }

    @Nullable
    private static String firstWritten(@Nullable String own, @Nullable String fallback) {
        if (own != null && !own.isBlank()) {
            return own;
        }
        return fallback != null && !fallback.isBlank() ? fallback : null;
    }
}
