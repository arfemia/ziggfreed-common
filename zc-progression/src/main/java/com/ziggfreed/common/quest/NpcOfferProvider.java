package com.ziggfreed.common.quest;

import java.util.Collection;
import java.util.List;

import javax.annotation.Nonnull;

import com.ziggfreed.common.subject.Subject;

/**
 * Answers "what is this character holding out to this player right now".
 *
 * <p>The quest RUNTIME cannot answer that on its own, and says so: which quests a place hands out is
 * an authoring-layer association, and whether the player may take one is a gate pass. Neither is
 * quest state, so a generic engine asking the question would have to guess. A provider is where the
 * catalogue and the gates actually are.
 *
 * <p>Registered on {@link NpcOfferProviders}, one per mod with quests to give. Several may answer at
 * once, which is exactly what makes an NPC surface work on a server running two content mods: the
 * player sees both mods' offers at one character, and neither mod had to know about the other.
 */
@FunctionalInterface
public interface NpcOfferProvider {

    /**
     * Everything this provider offers {@code subject} at a character answering to {@code answersTo} -
     * available and locked alike, in whatever order the provider considers most useful.
     *
     * <p>{@code answersTo} is the character's whole answer set, primary first, so a provider matches
     * against all of them rather than resolving aliases itself. Return an empty list rather than null,
     * and never throw for "nothing here": an empty answer IS the answer.
     */
    @Nonnull
    List<NpcOffer> offersAt(@Nonnull Subject subject, @Nonnull Collection<String> answersTo);

    /**
     * Is anything at all AVAILABLE here? Override when the cheap answer is genuinely cheaper than the
     * full list: this runs on render paths that ask once per character on screen, while
     * {@link #offersAt} may build titles and evaluate every gate.
     */
    default boolean hasOffersAt(@Nonnull Subject subject, @Nonnull Collection<String> answersTo) {
        for (NpcOffer offer : offersAt(subject, answersTo)) {
            if (offer.available()) {
                return true;
            }
        }
        return false;
    }
}
