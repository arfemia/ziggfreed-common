package com.ziggfreed.common.quest;

import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * One thing a character is holding out to a player: a quest they could take on right now, or one they
 * could take on if something changed.
 *
 * <p>A LOCKED offer is still an offer. A giver that simply hides everything the player cannot have
 * yet reads as having nothing to say, while one that shows the locked line with its reason is telling
 * the player what to go and do - so both live in the same list and {@code available} is what separates
 * them.
 *
 * <p>{@code titleKey} and every {@code lockReasonKey} are TRANSLATION KEYS, never rendered text: the
 * player's own client resolves them in its own locale, and nothing on the server ever reads a
 * player's language. A provider with no key to give leaves the title null, and the surface falls back
 * to whatever it knows about the id.
 *
 * <p>The lock reasons are deliberately opaque strings. Whatever decided this offer is unavailable -
 * a level, an item, a prerequisite quest, another mod's gate entirely - is that mod's business, and
 * modelling it here would mean every gate system in every consumer having to agree on one shape.
 */
public record NpcOffer(@Nonnull String id, @Nullable String titleKey, boolean available,
                       @Nonnull List<String> lockReasonKeys) {

    public NpcOffer {
        lockReasonKeys = List.copyOf(lockReasonKeys);
    }

    /** An offer the player can take right now. */
    @Nonnull
    public static NpcOffer available(@Nonnull String id, @Nullable String titleKey) {
        return new NpcOffer(id, titleKey, true, List.of());
    }

    /** An offer the player can see but not take, with the reasons why. */
    @Nonnull
    public static NpcOffer locked(@Nonnull String id, @Nullable String titleKey,
            @Nonnull List<String> lockReasonKeys) {
        return new NpcOffer(id, titleKey, false, lockReasonKeys);
    }
}
