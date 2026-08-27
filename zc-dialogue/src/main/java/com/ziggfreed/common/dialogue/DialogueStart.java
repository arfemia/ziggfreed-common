package com.ziggfreed.common.dialogue;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.bson.BsonBoolean;
import org.bson.BsonString;
import org.bson.BsonValue;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.hypixel.hytale.codec.schema.SchemaContext;
import com.hypixel.hytale.codec.schema.config.BooleanSchema;
import com.hypixel.hytale.codec.schema.config.NumberSchema;
import com.hypixel.hytale.codec.schema.config.Schema;
import com.hypixel.hytale.codec.schema.config.StringSchema;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.asset.EditorDataSets;
import com.ziggfreed.common.factor.FactorFormula;
import com.ziggfreed.common.ui.route.Destination;

/**
 * Which screen a conversation opens on, declared as SECTIONS the engine walks in a fixed order.
 *
 * <pre>{@code
 * "Start": {
 *   "First":    [ { "Node": "temple_greet", "When": [ ... ], "Once": true } ],
 *   "Quests":   { "meet_at_the_temple": { "Ready": true, "Active": "temple_brief" } },
 *   "Then":     [ { "Pick": [ { "Node": "menu_a" }, { "Node": "menu_b", "Weight": 3 } ] } ],
 *   "Fallback": "menu"
 * }
 * }</pre>
 *
 * <h2>The ladder, which the engine owns</h2>
 *
 * <p><b>{@code First} (in the order written) &gt; a quest that is READY to hand in &gt; a quest this
 * character can OFFER &gt; a quest that is ACTIVE &gt; {@code Then} (in the order written) &gt;
 * {@code Fallback}.</b> Within one quest band, the order the rows are written settles which quest
 * wins. Nothing an author writes can change that order, which is the point: the ladder used to be
 * simulated by hand-sorting one flat list, and every new beat meant re-reasoning about the whole
 * thing. Write each beat where it belongs and the order follows.
 *
 * <p>Every section is independently optional. A conversation with one screen writes only
 * {@code Fallback}; a giver that says nothing of its own writes only {@code Quests}.
 *
 * <h2>What each section is for</h2>
 *
 * <ul>
 *   <li><b>{@code First}</b> - beats that outrank anything about quests: a first-visit greeting, a
 *       beat for one world, a line gated on a factor another mod owns.</li>
 *   <li><b>{@code Quests}</b> - one row per quest, keyed by quest id, saying what this conversation
 *       does while that quest is ready / offerable / active. The engine reads the quest's own state,
 *       so no condition is written and no "X completed AND Y not started" pair has to be
 *       maintained.</li>
 *   <li><b>{@code Then}</b> - beats tried once no quest row applies: a steady-state greeting, a
 *       seasonal line, a piece of flavour that varies.</li>
 *   <li><b>{@code Fallback}</b> - the screen when nothing else applies. One node id, no conditions:
 *       it is the answer of last resort, so a conversation is never left with nothing to show.</li>
 * </ul>
 *
 * <p>A conversation with no {@code Start} at all opens on the first screen whose own conditions
 * pass, which is what makes a one-screen conversation a file with nothing but {@code Nodes}.
 *
 * <p><b>Under {@code Parent}, a child that writes {@code Start} replaces the whole of it.</b> It is
 * one ladder, so half of a child's and half of a parent's would be a ladder nobody wrote; and
 * replacing is the only way a child can take a parent's opening beat out of the running.
 */
public final class DialogueStart {

    /** The Start of a conversation that authored none: every section empty. */
    public static final DialogueStart EMPTY = new DialogueStart();

    @Nullable Beat[] first;
    @Nullable Map<String, QuestRow> quests;
    @Nullable Beat[] then;
    @Nullable String fallback;

    public DialogueStart() {
    }

    /** Java-side construction (a consumer building a tree in code, a test). */
    @Nonnull
    public static DialogueStart of(@Nullable Beat[] first, @Nullable Map<String, QuestRow> quests,
            @Nullable Beat[] then, @Nullable String fallback) {
        DialogueStart start = new DialogueStart();
        start.first = first == null ? null : first.clone();
        start.quests = quests;
        start.then = then == null ? null : then.clone();
        start.fallback = fallback;
        return start;
    }

    /** The beats tried before anything about quests, in the order written. */
    @Nonnull
    public List<Beat> first() {
        return first == null ? Collections.emptyList() : List.of(first);
    }

    /** What this conversation does about each quest, keyed by quest id, in the order written. */
    @Nonnull
    public Map<String, QuestRow> quests() {
        return quests == null ? Collections.emptyMap() : quests;
    }

    /** The beats tried once no quest row applied, in the order written. */
    @Nonnull
    public List<Beat> then() {
        return then == null ? Collections.emptyList() : List.of(then);
    }

    /** The screen of last resort, or null when none was written. */
    @Nullable
    public String fallback() {
        return fallback;
    }

    /** True when not one section was written, so this Start decides nothing. */
    public boolean isEmpty() {
        return first().isEmpty() && quests().isEmpty() && then().isEmpty()
                && (fallback == null || fallback.isBlank());
    }

    // ==================== Beat ====================

    /**
     * One ordered beat of {@code First} or {@code Then}: a screen (or a choice of screens), when it
     * applies, and whether it retires once played.
     *
     * <p>{@code Node} and {@code Pick} are the two ways to name the screen and an author writes ONE
     * of them; writing both is a validator error rather than a precedence rule, because which one
     * was meant is not something a reader should have to know.
     */
    public static final class Beat {

        @Nullable String node;
        @Nullable Variant[] pick;
        @Nullable DialogueCondition[] when;
        @Nullable DialogueOnce once;

        public Beat() {
        }

        /** Java-side construction: a plain beat on one screen. */
        @Nonnull
        public static Beat ofNode(@Nullable String node) {
            Beat beat = new Beat();
            beat.node = node;
            return beat;
        }

        /** Java-side construction: a beat that draws between screens. */
        @Nonnull
        public static Beat ofPick(@Nullable Variant... variants) {
            Beat beat = new Beat();
            beat.pick = variants == null ? null : variants.clone();
            return beat;
        }

        /** Java-side construction: the same beat, gated. */
        @Nonnull
        public Beat when(@Nullable DialogueCondition... conditions) {
            this.when = conditions == null ? null : conditions.clone();
            return this;
        }

        /** Java-side construction: the same beat, retiring once played through. */
        @Nonnull
        public Beat once(@Nullable DialogueOnce once) {
            this.once = once;
            return this;
        }

        /** The one screen this beat opens on, or null when it draws between several. */
        @Nullable
        public String getNode() {
            return node;
        }

        /** True when a single screen was written (a blank one does not count). */
        public boolean hasNode() {
            return node != null && !node.isBlank();
        }

        /** The screens this beat draws between, empty when it names a single one. */
        @Nonnull
        public List<Variant> getPick() {
            return pick == null ? Collections.emptyList() : List.of(pick);
        }

        /** True when a draw was written at all, even an empty one (which the audit reports). */
        public boolean hasPick() {
            return pick != null;
        }

        /** When this beat applies; an empty list always applies. */
        @Nonnull
        public List<DialogueCondition> getWhen() {
            return when == null ? Collections.emptyList() : List.of(when);
        }

        /**
         * The first-visit knob, or null when this beat may be picked any number of times. It is spent
         * once the player COMPLETES the beat (chooses any option on the screen it opened, the
         * implicit Farewell row included), so leaving with Escape shows it again.
         */
        @Nullable
        public DialogueOnce getOnce() {
            return once;
        }
    }

    // ==================== Variant ====================

    /**
     * One screen a {@code Pick} may draw, and how often it comes up relative to its siblings.
     *
     * <p>{@code Weight} is the shared factor formula, so a variant can be a plain number
     * ({@code "Weight": 3}) or a reading another mod owns ({@code "Weight": {"Base": 1, "Factors":
     * [{"Factor": "yourmod:reputation", "Weight": 0.5}]}}). Omit it for 1.
     */
    public static final class Variant {

        /** What a variant that authors no {@code Weight} is drawn with. */
        public static final double DEFAULT_WEIGHT = 1.0;

        @Nullable String node;
        @Nullable FactorFormula weight;

        /**
         * A weight authored as a bare NUMBER or as the full formula group. The number form is the
         * same value ({@code 3} reads as {@code {"Base": 3}}), so the common case stays one token
         * and there is still one model underneath.
         */
        public static final Codec<FactorFormula> WEIGHT_CODEC = new Codec<>() {

            private final BuilderCodec<FactorFormula> group = FactorFormula.codec(EditorDataSets.FACTORS);

            @Override
            @Nullable
            public FactorFormula decode(BsonValue value, ExtraInfo extraInfo) {
                if (value != null && value.isNumber()) {
                    return FactorFormula.of(value.asNumber().doubleValue(), null, null);
                }
                return group.decode(value, extraInfo);
            }

            @Nonnull
            @Override
            public BsonValue encode(FactorFormula formula, ExtraInfo extraInfo) {
                return group.encode(formula, extraInfo);
            }

            @Override
            @Nullable
            public FactorFormula decodeJson(RawJsonReader reader, ExtraInfo extraInfo) throws IOException {
                int next = reader.peek();
                if (next == '-' || (next >= '0' && next <= '9')) {
                    return FactorFormula.of(reader.readDoubleValue(), null, null);
                }
                return group.decodeJson(reader, extraInfo);
            }

            @Nonnull
            @Override
            public Schema toSchema(@Nonnull SchemaContext context) {
                // The plain-number form decodes too, and the in-game Asset Editor fails a
                // property pane over any authored value shape the exported schema omits, so the
                // schema declares both arms.
                return Schema.anyOf(new NumberSchema(), group.toSchema(context));
            }
        };

        public static final BuilderCodec<Variant> CODEC = BuilderCodec.builder(Variant.class, Variant::new)
                .append(new KeyedCodec<>("Node", Codec.STRING, false),
                        (v, value) -> v.node = value, v -> v.node)
                .documentation("The screen this variant opens on.").add()
                .append(new KeyedCodec<>("Weight", WEIGHT_CODEC, false),
                        (v, value) -> v.weight = value, v -> v.weight)
                .documentation("How often this variant comes up beside its siblings. A plain number is "
                        + "enough; a formula lets another mod's reading decide. Omit for 1, and author 0 to "
                        + "take a variant out of the draw without deleting it.").add()
                .build();

        public Variant() {
        }

        /** Java-side construction; a null weight is the neutral 1. */
        @Nonnull
        public static Variant of(@Nullable String node, @Nullable FactorFormula weight) {
            Variant variant = new Variant();
            variant.node = node;
            variant.weight = weight;
            return variant;
        }

        /** Java-side construction with a plain number weight. */
        @Nonnull
        public static Variant of(@Nullable String node, double weight) {
            return of(node, FactorFormula.of(weight, null, null));
        }

        @Nullable
        public String getNode() {
            return node;
        }

        /** The authored weight, or null when none was written (which reads as 1). */
        @Nullable
        public FactorFormula getWeight() {
            return weight;
        }
    }

    // ==================== Quests ====================

    /**
     * Which of a quest's three moments a row is speaking about. A RESULT of walking the ladder, never
     * an authored value: nothing in any file names one, and a new constant would mean a new moment in
     * a quest's life rather than a new setting.
     */
    public enum Band {

        /** The player can hand this quest in here, right now, carrying what it asks for. */
        READY,

        /** This character has this quest to give and the player could take it on right now. */
        OFFERABLE,

        /** The player is carrying this quest and has not finished it. */
        ACTIVE
    }

    /**
     * What a conversation does about ONE quest, keyed in {@code Quests} by that quest's id.
     *
     * <p>Every leaf is optional and they are read in the engine's own order (ready, then offerable,
     * then active), so a row saying only {@code "Ready": true} is a complete statement.
     *
     * <p><b>A ready quest never diverts the conversation unless its row says so.</b> A giver with a
     * finished quest in the player's log behaves exactly as it did until an author writes the row,
     * which is what keeps "this quest is done" from silently taking over every conversation the
     * player has.
     */
    public static final class QuestRow {

        @Nullable QuestBeat ready;
        @Nullable QuestBeat offerable;
        @Nullable QuestBeat active;

        public static final BuilderCodec<QuestRow> CODEC = BuilderCodec.builder(QuestRow.class, QuestRow::new)
                .append(new KeyedCodec<>("Ready", QuestBeat.CODEC, false),
                        (r, v) -> r.ready = v, r -> r.ready)
                .documentation("What happens when the player can hand this quest in here. Write true to send "
                        + "them to this character's quest list with it highlighted, a screen name for a beat "
                        + "you wrote yourself, or a destination to send them anywhere else. Leave it out and "
                        + "a finished quest changes nothing here.").add()
                .append(new KeyedCodec<>("Offerable", QuestBeat.CODEC, false),
                        (r, v) -> r.offerable = v, r -> r.offerable)
                .documentation("What happens when this character has this quest to give and the player could "
                        + "take it on. The quest's own prerequisites decide that, so no condition is written "
                        + "here.").add()
                .append(new KeyedCodec<>("Active", QuestBeat.CODEC, false),
                        (r, v) -> r.active = v, r -> r.active)
                .documentation("What happens while the player is carrying this quest: usually the screen that "
                        + "reminds them what they are doing.").add()
                .build();

        public QuestRow() {
        }

        /** Java-side construction; every leaf independently optional. */
        @Nonnull
        public static QuestRow of(@Nullable QuestBeat ready, @Nullable QuestBeat offerable,
                @Nullable QuestBeat active) {
            QuestRow row = new QuestRow();
            row.ready = ready;
            row.offerable = offerable;
            row.active = active;
            return row;
        }

        @Nullable
        public QuestBeat getReady() {
            return ready;
        }

        @Nullable
        public QuestBeat getOfferable() {
            return offerable;
        }

        @Nullable
        public QuestBeat getActive() {
            return active;
        }

        /** The beat this row holds for {@code band}, or null when it says nothing about that moment. */
        @Nullable
        public QuestBeat forBand(@Nonnull Band band) {
            switch (band) {
                case READY:
                    return ready;
                case OFFERABLE:
                    return offerable;
                default:
                    return active;
            }
        }
    }

    /**
     * What one moment of a quest row does, in any of the three ways it can be written:
     *
     * <pre>{@code
     * "Ready": true                                    the quest list at this character, this quest highlighted
     * "Active": "temple_brief"                         a screen of this conversation
     * "Offerable": { "Type": "Mmo_Board", "Board": "daily" }   anywhere the routing vocabulary knows
     * }</pre>
     *
     * <p>A bare word is a SCREEN name, never a destination type: a row points into its own
     * conversation far more often than out of it, and an object is how leaving it is written.
     * {@code false} reads as no beat at all, so turning one off is a one-word edit.
     *
     * <p><b>{@code true} routes rather than handing the quest in inline.</b> A conversation that took
     * a quest in on the player's behalf would be handing in a quest the player never chose to hand
     * in; an inline turn-in happens only where an author's own screen writes one.
     */
    public static final class QuestBeat {

        @Nullable String node;
        @Nullable Destination destination;
        boolean questView;

        public static final Codec<QuestBeat> CODEC = new Codec<>() {

            @Override
            @Nullable
            public QuestBeat decode(BsonValue value, ExtraInfo extraInfo) {
                if (value == null || value.isNull()) {
                    return null;
                }
                if (value.isBoolean()) {
                    return value.asBoolean().getValue() ? questView() : null;
                }
                if (value.isString()) {
                    return ofNode(value.asString().getValue());
                }
                return ofDestination(Destination.CODEC.decode(value, extraInfo));
            }

            @Nonnull
            @Override
            public BsonValue encode(QuestBeat beat, ExtraInfo extraInfo) {
                if (beat.node != null) {
                    return new BsonString(beat.node);
                }
                if (beat.destination != null) {
                    return Destination.CODEC.encode(beat.destination, extraInfo);
                }
                return BsonBoolean.valueOf(beat.questView);
            }

            @Override
            @Nullable
            public QuestBeat decodeJson(RawJsonReader reader, ExtraInfo extraInfo) throws IOException {
                int next = reader.peek();
                if (next == 't' || next == 'T' || next == 'f' || next == 'F') {
                    return reader.readBooleanValue() ? questView() : null;
                }
                if (next == '"') {
                    return ofNode(reader.readString());
                }
                return ofDestination(Destination.CODEC.decodeJson(reader, extraInfo));
            }

            @Nonnull
            @Override
            public Schema toSchema(@Nonnull SchemaContext context) {
                // Decode accepts a plain true/false and a bare screen name beside the destination
                // object, and the in-game Asset Editor fails a property pane over any authored
                // value shape the exported schema omits, so the schema declares every arm.
                return Schema.anyOf(new BooleanSchema(), new StringSchema(),
                        Destination.CODEC.toSchema(context));
            }
        };

        public QuestBeat() {
        }

        /** The {@code true} form: this character's quest list, with the row's own quest highlighted. */
        @Nonnull
        public static QuestBeat questView() {
            QuestBeat beat = new QuestBeat();
            beat.questView = true;
            return beat;
        }

        /** The screen form. */
        @Nonnull
        public static QuestBeat ofNode(@Nullable String node) {
            QuestBeat beat = new QuestBeat();
            beat.node = node;
            return beat;
        }

        /** The destination form. */
        @Nonnull
        public static QuestBeat ofDestination(@Nullable Destination destination) {
            QuestBeat beat = new QuestBeat();
            beat.destination = destination;
            return beat;
        }

        /** The screen this beat opens, or null when it routes away instead. */
        @Nullable
        public String getNode() {
            return node;
        }

        /** The destination this beat routes to, or null for the screen form or the default route. */
        @Nullable
        public Destination getDestination() {
            return destination;
        }

        /** True for the {@code true} form: the quest list at this character, this quest highlighted. */
        public boolean isQuestView() {
            return questView;
        }

        /** True when this beat leaves the conversation rather than opening one of its screens. */
        public boolean routes() {
            return questView || destination != null;
        }
    }

    // ==================== helpers ====================

    /** A quest id as the engine compares it: trimmed and lower-cased, or null when unusable. */
    @Nullable
    public static String normalizeQuestId(@Nullable String questId) {
        if (questId == null || questId.isBlank()) {
            return null;
        }
        return questId.trim().toLowerCase(Locale.ROOT);
    }

    /** The {@code Pick} array codec, shared by the beat codec the type table assembles. */
    @Nonnull
    static Codec<Variant[]> pickCodec() {
        return new ArrayCodec<>(Variant.CODEC, Variant[]::new);
    }
}
