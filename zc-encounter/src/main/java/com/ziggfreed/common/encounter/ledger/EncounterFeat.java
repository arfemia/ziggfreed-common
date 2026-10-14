package com.ziggfreed.common.encounter.ledger;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * One named FEAT a fight can be won with: a condition over the fight's own hits, held or broken by
 * everyone in it together, that every participant the win credits earns at once. A binding row
 * authors it under {@code Progression.Feats.<Name>}; {@link FeatTally} keeps it for one run; the win
 * carries the feats still held on {@code EncounterDefeatedEvent.feats()}, and an
 * {@code ENCOUNTER_FEAT} step names one by its name as the {@code Qualifier}.
 *
 * <p>The one condition so far is on the SUBJECT's hits: every hit a player lands on the boss (the
 * subject the row binds, never one of the fight's adds) must carry one of {@link #subjectHitTags}, the
 * native {@code Tags.Hit} values of the interaction or entity effect that dealt it (zc-core's
 * {@code HitTags}). A further condition joins as one more nullable group beside it, never as a mode.
 *
 * <p>Pure: no engine type, so a test builds one from strings.
 *
 * @param name           the feat's name as authored, the {@code Qualifier} an {@code ENCOUNTER_FEAT}
 *                       step names (matched ignoring case)
 * @param subjectHitTags the Hit tags a player's hit on the subject must carry one of, folded to lower
 *                       case; empty when the feat sets no condition on those hits
 */
public record EncounterFeat(@Nonnull String name, @Nonnull Set<String> subjectHitTags) {

    public EncounterFeat {
        name = name.trim();
        subjectHitTags = Set.copyOf(subjectHitTags);
    }

    /** A feat named {@code name} whose subject hits must carry one of {@code subjectHitTags} (any case). */
    @Nonnull
    public static EncounterFeat of(@Nonnull String name, @Nullable Collection<String> subjectHitTags) {
        Set<String> folded = new LinkedHashSet<>();
        if (subjectHitTags != null) {
            for (String tag : subjectHitTags) {
                if (tag != null && !tag.isBlank()) {
                    folded.add(fold(tag));
                }
            }
        }
        return new EncounterFeat(name, folded);
    }

    /** Whether the feat sets any condition at all; one that sets none is never held. */
    public boolean hasCondition() {
        return judgesSubjectHits();
    }

    /** Whether the feat judges the hits players land on the subject. */
    public boolean judgesSubjectHits() {
        return !subjectHitTags.isEmpty();
    }

    /** Whether a hit carrying {@code hitTags} (any case) meets the subject condition. */
    public boolean acceptsSubjectHit(@Nonnull Collection<String> hitTags) {
        for (String tag : hitTags) {
            if (tag != null && subjectHitTags.contains(fold(tag))) {
                return true;
            }
        }
        return false;
    }

    /** The case-blind form a feat name or a tag is compared in. */
    @Nonnull
    static String fold(@Nonnull String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
