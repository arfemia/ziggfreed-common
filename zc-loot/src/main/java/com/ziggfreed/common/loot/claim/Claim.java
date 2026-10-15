package com.ziggfreed.common.loot.claim;

import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.i18n.ContentKeys;
import com.ziggfreed.common.i18n.NativeNames;
import com.ziggfreed.common.instance.reward.InstanceReward;

/**
 * One payout a player has earned and not taken yet: what they see as a row on the Claims tab and take
 * with Claim. Rewards are collected, never handed over unasked (M428), so whatever pays a player a Claim
 * writes one of these to {@link ClaimStore} and the player presses Claim when their bags have room.
 *
 * @param id         what makes this payout this one: a second write of a Claim the player still holds is a
 *                   no-op (or a top-up, {@link ClaimStore#awardOrTopUp}), so a producer composes it from
 *                   everything that makes the payout unique (the source, the run, the reward, the player);
 *                   matched without regard to case
 * @param source     where it came from, for logs and an owner's tools, conventionally {@code "<site>:<id>"}
 *                   ({@code "admin:<lootable>"})
 * @param titleKey   the lang key the row's title reads ({@code {0}, {1}, ...} bound from {@code titleArgs});
 *                   null reads as the tab's own word for a reward
 * @param titleArgs  the title's arguments, as raw text (data: an id, a number); a name that needs translating
 *                   belongs in the title's own key, or in an argument written through {@link #keyArg} (a lang
 *                   key) or {@link #itemArg} (an item's name), which the reader's client resolves
 * @param difficulty the difficulty it was earned on, a lang key or a plain label; null for none
 * @param rewards    what pressing Claim hands over, block-first through the bag guard
 * @param createdAt  when it was written, epoch milliseconds
 * @param fight      the fight it was paid for (a boss fight's run id, as text), so the chest that fight leaves
 *                   can offer it; null for a Claim no fight paid; matched without regard to case
 */
public record Claim(@Nonnull String id, @Nonnull String source, @Nullable String titleKey,
                    @Nonnull List<String> titleArgs, @Nullable String difficulty,
                    @Nonnull List<InstanceReward> rewards, long createdAt, @Nullable String fight) {

    /** A Claim no fight paid. */
    public Claim(@Nonnull String id, @Nonnull String source, @Nullable String titleKey,
            @Nonnull List<String> titleArgs, @Nullable String difficulty, @Nonnull List<InstanceReward> rewards,
            long createdAt) {
        this(id, source, titleKey, titleArgs, difficulty, rewards, createdAt, null);
    }

    /** What starts a title argument that reads as a lang key's own line ({@link #keyArg}). */
    public static final String KEY_ARG = "@key:";

    /** What starts a title argument that reads as an item's name ({@link #itemArg}). */
    public static final String ITEM_ARG = "@item:";

    public Claim {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("a Claim needs an id");
        }
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("a Claim needs a source");
        }
        id = id.trim();
        source = source.trim();
        titleKey = titleKey == null || titleKey.isBlank() ? null : titleKey.trim();
        titleArgs = titleArgs == null ? List.of() : List.copyOf(titleArgs);
        difficulty = difficulty == null || difficulty.isBlank() ? null : difficulty.trim();
        rewards = rewards == null ? List.of() : List.copyOf(rewards);
        fight = fight == null || fight.isBlank() ? null : fight.trim();
    }

    /** This Claim paying {@code rewards} instead, everything else kept (what did not fit, put back). */
    @Nonnull
    public Claim withRewards(@Nonnull List<InstanceReward> rewards) {
        return new Claim(id, source, titleKey, titleArgs, difficulty, rewards, createdAt, fight);
    }

    /** Whether this Claim is {@code otherId}, without regard to case. */
    public boolean is(@Nullable String otherId) {
        return otherId != null && id.equalsIgnoreCase(otherId.trim());
    }

    /**
     * A title argument that reads as {@code langKey}'s own line, an authored key resolved on the reader's client
     * ({@code ContentKeys}), so a name that needs translating rides in a Claim's saved arguments.
     */
    @Nonnull
    public static String keyArg(@Nonnull String langKey) {
        return KEY_ARG + langKey.trim();
    }

    /** A title argument that reads as the item {@code itemId}'s name, in the reader's own language. */
    @Nonnull
    public static String itemArg(@Nonnull String itemId) {
        return ITEM_ARG + itemId.trim();
    }

    /**
     * What this Claim's title key binds: a {@link #keyArg} argument nested as that key's line, an
     * {@link #itemArg} one as the item's name, every other argument as written.
     */
    @Nonnull
    public Object[] titleParams() {
        Object[] out = new Object[titleArgs.size()];
        for (int i = 0; i < out.length; i++) {
            String arg = titleArgs.get(i);
            if (arg.startsWith(KEY_ARG) && arg.length() > KEY_ARG.length()) {
                out[i] = ContentKeys.tr(arg.substring(KEY_ARG.length()));
            } else if (arg.startsWith(ITEM_ARG) && arg.length() > ITEM_ARG.length()) {
                out[i] = NativeNames.itemNameMsg(arg.substring(ITEM_ARG.length()));
            } else {
                out[i] = arg;
            }
        }
        return out;
    }

    /** Whether the fight {@code fightId} paid this Claim, without regard to case; never for a Claim no fight paid. */
    public boolean fromFight(@Nullable String fightId) {
        return fight != null && fightId != null && fight.equalsIgnoreCase(fightId.trim());
    }
}
