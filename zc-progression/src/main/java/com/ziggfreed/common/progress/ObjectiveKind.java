package com.ziggfreed.common.progress;

import java.util.Locale;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.icon.IconSpec;

/**
 * One entry in an engine's objective vocabulary: the id content authors write, plus the facts the
 * engine and its validators need about it.
 *
 * <p>The flags are INDEPENDENT knobs, not a category:
 * <ul>
 *   <li>{@code valueBased} decides which arithmetic a dispatch uses - a high-water mark
 *   ({@link ObjectiveProgressState#applyValue}) instead of an accumulating delta
 *   ({@link ObjectiveProgressState#advance}). Set it when producers fire the player's CURRENT
 *   value rather than an increment, or a run of 5 then 4 wrongly tallies 9.
 *   <li>{@code atMost} turns a value-based kind's reading into a CEILING: the objective is met the
 *   first time the fired value is at or under the authored amount (a clear in under five minutes,
 *   a fight with at most two deaths). It composes with {@code valueBased} and means nothing
 *   without it, because only a fired current value can be under a ceiling; see
 *   {@link ObjectiveArithmetic} for the one compare every engine runs.
 *   <li>{@code producible} decides whether authored content may USE the id. An unproducible kind
 *   parses and renders but nothing ever fires it, so a validator rejects authoring one instead of
 *   letting a dead objective ship. Register a kind unproducible when the vocabulary exists before
 *   its producer does.
 *   <li>{@code targetsPlace} says what an objective's TARGET names: a place a player can stand at
 *   (a character, a location) rather than a thing an event carries (a block, an item, an entity).
 *   It is what lets a listing say "this step resolves HERE" for a step with no hand-in of its own,
 *   so set it for a kind whose target is somewhere to go. The comparison it feeds is one whole id
 *   against one whole id: a place is never matched by prefix or substring, because a target written
 *   to catch a family of block ids would otherwise catch character ids too.
 *   <li>{@code targetsItem} says the target names something a player can hold, so a surface may
 *   DRAW the target: an item id is a picture of itself, and a step that asks for a crude pickaxe
 *   can show one. Set it for a kind whose target is an item or a block id.
 *   <li>{@code targetsEntity} says the target names a creature, drawn from that creature's own
 *   generated portrait rather than from an item. Set it for a kind whose target is an NPC role id.
 *   <li>{@code targetsCurrency} says the target names a wallet, drawn with that wallet's own icon.
 *   The engines here define no currency, so the picture is answered by whoever does; the flag is
 *   what tells them a step is about one.
 *   <li>{@code targetsContent} says the target names another quest or achievement, drawn with THAT
 *   content's own icon - the one it is already listed under everywhere else. This module owns both
 *   catalogues, so it answers this one itself.
 *   <li>{@code targetsBoard} says the target names a notice board, answered like a wallet by
 *   whoever defines boards.
 *   <li>{@code targetsEncounter} says the target names a boss fight: an encounter script id, which
 *   is neither an item nor a creature (an in-place role change means the boss's creature id is not
 *   even stable across the fight). The picture is answered by whoever binds encounters, exactly as
 *   a wallet's is.
 * </ul>
 *
 * <p>The target facets are independent of each other and of everything above, and a kind may set
 * several - a target that resolves to none of them simply goes unpictured.
 *
 * <p>{@link Presentation} is the other half of the same description: not what the engine counts, but
 * what a player sees when it is listed. It rides here rather than in a table of its own so that ONE
 * thing describes a kind - the file that states a kind's arithmetic states its wording and its
 * picture in the same breath.
 *
 * <p>{@code id} is normalized to upper case at construction, which is the spelling every surface
 * displays; lookups themselves are case-insensitive.
 */
public record ObjectiveKind(@Nonnull String id, boolean valueBased, boolean producible,
                            boolean targetsPlace, boolean targetsItem, boolean targetsEntity,
                            boolean targetsCurrency, boolean targetsContent, boolean targetsBoard,
                            boolean targetsEncounter, boolean atMost,
                            @Nonnull Presentation presentation) {

    /**
     * How a step of this kind reads and looks: the sentence key it renders through, the picture
     * beside a step whose own target has none, and the targets given a picture of their own.
     *
     * <p>{@code targetIcons} is keyed by target id and exists for the target a derivation cannot
     * answer - most often a FAMILY name whose members each have a portrait but which has none
     * itself, pointed at whichever member represents it.
     */
    public record Presentation(@Nullable String textKey, @Nullable IconSpec icon,
                               @Nonnull Map<String, IconSpec> targetIcons) {

        /** A kind that says nothing about how it reads or looks. */
        public static final Presentation NONE = new Presentation(null, null, Map.of());

        public Presentation {
            targetIcons = Map.copyOf(targetIcons);
        }

        /** The picture authored for one exact target, or null when none was. */
        @Nullable
        public IconSpec iconForTarget(@Nullable String target) {
            if (target == null || target.isBlank() || targetIcons.isEmpty()) {
                return null;
            }
            IconSpec exact = targetIcons.get(target);
            if (exact != null) {
                return exact;
            }
            for (Map.Entry<String, IconSpec> entry : targetIcons.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(target)) {
                    return entry.getValue();
                }
            }
            return null;
        }
    }

    public ObjectiveKind {
        id = id.trim().toUpperCase(Locale.ROOT);
    }

    /** A kind whose target names a thing rather than a place, which is the common case. */
    public ObjectiveKind(@Nonnull String id, boolean valueBased, boolean producible) {
        this(id, valueBased, producible, false);
    }

    /** A kind stating its arithmetic and whether its target is somewhere to go. */
    public ObjectiveKind(@Nonnull String id, boolean valueBased, boolean producible,
                         boolean targetsPlace) {
        this(id, valueBased, producible, targetsPlace, false, false);
    }

    /** A kind whose target names a thing or a place, saying nothing yet about how it reads or looks. */
    public ObjectiveKind(@Nonnull String id, boolean valueBased, boolean producible,
                         boolean targetsPlace, boolean targetsItem, boolean targetsEntity) {
        this(id, valueBased, producible, targetsPlace, targetsItem, targetsEntity, false);
    }

    /** A kind stating every flag, saying nothing yet about how it reads or looks. */
    public ObjectiveKind(@Nonnull String id, boolean valueBased, boolean producible,
                         boolean targetsPlace, boolean targetsItem, boolean targetsEntity,
                         boolean targetsCurrency) {
        this(id, valueBased, producible, targetsPlace, targetsItem, targetsEntity, targetsCurrency,
                false, false, Presentation.NONE);
    }

    /** A kind stating every flag, saying nothing yet about how it reads or looks. */
    public ObjectiveKind(@Nonnull String id, boolean valueBased, boolean producible,
                         boolean targetsPlace, boolean targetsItem, boolean targetsEntity,
                         boolean targetsCurrency, boolean targetsContent, boolean targetsBoard) {
        this(id, valueBased, producible, targetsPlace, targetsItem, targetsEntity, targetsCurrency,
                targetsContent, targetsBoard, Presentation.NONE);
    }

    /**
     * A kind stating the six thing-shaped target facets and its presentation, neither about an
     * encounter nor a ceiling - the shape every registration before those two knobs existed used.
     */
    public ObjectiveKind(@Nonnull String id, boolean valueBased, boolean producible,
                         boolean targetsPlace, boolean targetsItem, boolean targetsEntity,
                         boolean targetsCurrency, boolean targetsContent, boolean targetsBoard,
                         @Nonnull Presentation presentation) {
        this(id, valueBased, producible, targetsPlace, targetsItem, targetsEntity, targetsCurrency,
                targetsContent, targetsBoard, false, false, presentation);
    }

    /** An accumulating, producible kind - the common case. */
    @Nonnull
    public static ObjectiveKind of(@Nonnull String id) {
        return new ObjectiveKind(id, false, true, false);
    }

    /** A producible kind whose producers fire a current value rather than a delta. */
    @Nonnull
    public static ObjectiveKind valueBased(@Nonnull String id) {
        return new ObjectiveKind(id, true, true, false);
    }

    /**
     * A producible kind whose producers fire a current value that must come in AT OR UNDER the
     * authored amount: value-based and a ceiling.
     */
    @Nonnull
    public static ObjectiveKind atMost(@Nonnull String id) {
        return valueBased(id).withAtMost(true);
    }

    /** A producible kind whose target names somewhere to go. */
    @Nonnull
    public static ObjectiveKind placeTargeted(@Nonnull String id) {
        return new ObjectiveKind(id, false, true, true);
    }

    /** A producible kind whose target names an item or block id, so a surface may draw it. */
    @Nonnull
    public static ObjectiveKind itemTargeted(@Nonnull String id) {
        return new ObjectiveKind(id, false, true, false, true, false);
    }

    /** A producible kind whose target names a wallet, drawn with that wallet's own icon. */
    @Nonnull
    public static ObjectiveKind currencyTargeted(@Nonnull String id) {
        return new ObjectiveKind(id, false, true, false, false, false, true);
    }

    /** A producible kind whose target names another quest or achievement, drawn with its own icon. */
    @Nonnull
    public static ObjectiveKind contentTargeted(@Nonnull String id) {
        return new ObjectiveKind(id, false, true, false, false, false, false, true, false);
    }

    /** A producible kind whose target names a creature, drawn from its own portrait. */
    @Nonnull
    public static ObjectiveKind entityTargeted(@Nonnull String id) {
        return new ObjectiveKind(id, false, true, false, false, true);
    }

    /** A producible kind whose target names a boss fight by its encounter script id. */
    @Nonnull
    public static ObjectiveKind encounterTargeted(@Nonnull String id) {
        return of(id).withTargetsEncounter(true);
    }

    /** This kind reading and looking as {@code presentation} says. */
    @Nonnull
    public ObjectiveKind withPresentation(@Nonnull Presentation presentation) {
        return new ObjectiveKind(id, valueBased, producible, targetsPlace, targetsItem, targetsEntity,
                targetsCurrency, targetsContent, targetsBoard, targetsEncounter, atMost, presentation);
    }

    /** This kind with its ceiling knob set as given; everything else kept. */
    @Nonnull
    public ObjectiveKind withAtMost(boolean atMost) {
        return new ObjectiveKind(id, valueBased, producible, targetsPlace, targetsItem, targetsEntity,
                targetsCurrency, targetsContent, targetsBoard, targetsEncounter, atMost, presentation);
    }

    /** This kind with its encounter facet set as given; everything else kept. */
    @Nonnull
    public ObjectiveKind withTargetsEncounter(boolean targetsEncounter) {
        return new ObjectiveKind(id, valueBased, producible, targetsPlace, targetsItem, targetsEntity,
                targetsCurrency, targetsContent, targetsBoard, targetsEncounter, atMost, presentation);
    }
}
