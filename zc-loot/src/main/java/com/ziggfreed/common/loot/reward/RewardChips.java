package com.ziggfreed.common.loot.reward;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.inventory.ItemStack;

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
 *       Java at all);</li>
 *   <li>the item form: a spec naming an item is drawn with that item's own engine display name, in
 *       whatever locale the player's client speaks.</li>
 * </ol>
 *
 * <p><b>A reward nothing can name is DROPPED rather than guessed at.</b> Painting a raw kind token
 * at a player reads as a promise of something called {@code Mmo_Boost_Token}, which is worse than
 * showing one fewer chip; the fix is a two-line {@code Presentation} on the kind file, and the
 * validator that reads kind assets is where an author is told so.
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

    private RewardChips() {
    }

    /**
     * What a chip will say, decided from strings alone: the loc key to render (with {@link #amount}
     * as its one argument), the item to draw the name of when there is no key, and the picture.
     *
     * <p>A plan with neither {@link #nameKey} nor {@link #itemId} is a reward nothing can name.
     */
    public record Plan(@Nullable String nameKey, @Nullable String itemId, @Nullable String iconItemId,
                       long amount) {

        /** Can this be painted at all? */
        public boolean isRenderable() {
            return (nameKey != null && !nameKey.isBlank()) || (itemId != null && !itemId.isBlank());
        }
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
        if (!plan.isRenderable()) {
            return null;
        }
        String nameKey = plan.nameKey();
        if (nameKey != null && !nameKey.isBlank()) {
            return RewardChip.of(plan.iconItemId(), Msg.key(nameKey, plan.amount()));
        }
        return RewardChip.of(plan.iconItemId(), itemLabel(plan.itemId(), plan.amount()));
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
