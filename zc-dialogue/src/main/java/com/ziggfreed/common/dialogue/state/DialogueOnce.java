package com.ziggfreed.common.dialogue.state;

import java.io.IOException;
import java.time.DayOfWeek;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.bson.BsonValue;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.schema.SchemaContext;
import com.hypixel.hytale.codec.schema.config.BooleanSchema;
import com.hypixel.hytale.codec.schema.config.Schema;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.asset.EditorSchema;
import com.ziggfreed.common.dialogue.DialogueContext;
import com.ziggfreed.common.dialogue.DialogueEngine;
import com.ziggfreed.common.util.PeriodMath;
import com.ziggfreed.common.world.WorldSelector;

/**
 * The {@code Once} knob: keyless seen-ness, authored on a {@code Start} entry or on an option.
 *
 * <pre>{@code
 * "Once": true                                                    once per character
 * "Once": { "Where": { "Match": ["forgotten_temple"] } }           once per world
 * "Once": { "Where": { "GameplayConfig": ["ForgottenTemple"] } }   once per instance world
 * "Once": { "Period": "Daily" }                                   once per character per day
 * }</pre>
 *
 * <p>Both forms are the same group - {@code true} is shorthand for the empty group {@code {}} and
 * is normalized by the sugar pre-pass, so the codec only ever sees an object. Every leaf is
 * nullable and independent: a future axis is a new leaf, never a mode.
 *
 * <h2>What it does</h2>
 *
 * <p>On a {@code Start} entry, the entry stops matching once the player COMPLETES that beat -
 * chooses any option on the node it routed to, the implicit Farewell row included. Leaving with
 * Escape or the close button does not complete it, so an interrupted first-visit beat shows again.
 *
 * <p>On an option, the option is offered until its actions have run once. Its identity comes from
 * the option's {@code LabelKey} (or an explicit {@code OnceId}), never its position, so reordering
 * a node's options never resurrects a spent Once.
 *
 * <h2>How long it is remembered</h2>
 *
 * <p><b>Without a {@code Period}, a spent Once is remembered for good.</b> Unlike a
 * {@code Memories} entry, there is no declaration behind a {@code Once} to read a lifetime from -
 * the knob IS the whole declaration - so it takes the same answer an undeclared lifetime takes
 * everywhere: persistent. That is also the only answer that means what the word says. A first-visit
 * beat is called that because there is one first visit; one that came back after a restart would be
 * a first visit the player has already had, which is exactly what the knob was written to prevent.
 *
 * <p>The one nearby case that is NOT this is a beat belonging to something that ends - a round, a
 * run. That is state with a name and a scope, so it is a declared {@code Memories} entry carrying
 * {@code "Session": true}, read by a {@code Remembered} condition, rather than a {@code Once}.
 *
 * <h2>{@code Period}</h2>
 *
 * <p>A {@code Once} with a {@code Period} is offered again every window instead of once for good:
 * {@code Daily} turns over at midnight UTC and {@code Weekly} at midnight UTC going into Monday, on
 * the grid every repeating window in the library uses ({@link PeriodMath}). A spend is filed under
 * the window it happened in, after the whole key (its world scope included), and first clears the
 * line's earlier windows, so a player keeps one key per line however many days they come back
 * ({@link Slot}). A word that is neither {@code Daily} nor {@code Weekly} reads as {@code Daily},
 * the way a quest's {@code Reset} reads one, and the content audit names it.
 *
 * <h2>{@code Where}</h2>
 *
 * <p>The shared world selector - the same {@code {Match, GameplayConfig, ExcludeMatch}} group an NPC
 * placement carries - so an author who has written one has already learned this one.
 *
 * <p>Reach for {@code GameplayConfig} for an instance world. One is named
 * {@code instance-KweebecNightmare_Barn-<uuid>} and is destroyed when it empties, so "already seen
 * here" keyed by the literal name would come back on every fresh instance; the config key is
 * authored, carries no uuid, and survives the rebuild. A {@code Match} pattern still works and files
 * the state under the pattern's literal core.
 *
 * <p>In a world the selector does not match, the Once neither reads nor writes: the beat is offered
 * and stays offered. Pair a world-scoped {@code Once} with a {@code World} condition on the same
 * beat so it cannot be reached elsewhere in the first place. A selector matching no world the server
 * has loaded warns once and is a validator finding, because a typo would otherwise re-show a
 * first-visit beat forever.
 */
public final class DialogueOnce {

    /** The canonical "once per character" group, the decoded form of {@code "Once": true}. */
    public static final DialogueOnce GLOBAL = new DialogueOnce();

    /** What the {@code Period} leaf is for, in the editor and in this class's own words. */
    public static final String PERIOD_DOC =
            "Offer this again every Daily or Weekly window instead of once for good. Daily turns over "
                    + "at midnight UTC, Weekly at midnight UTC going into Monday. Leave it out and the Once "
                    + "is spent for good. It works beside Where, which keeps one per world as well.";

    /** The group form, {@code {"Where": {...}, "Period": "..."}}. */
    private static final BuilderCodec<DialogueOnce> GROUP =
            BuilderCodec.builder(DialogueOnce.class, DialogueOnce::new)
                    .append(new KeyedCodec<>("Where", WorldSelector.CODEC, false),
                            (o, v) -> { o.where = v; o.scope = null; }, o -> o.where)
                    .documentation(DialogueFlagScope.WHERE_DOC).add()
                    .append(new KeyedCodec<>("Period", Codec.STRING, false),
                            (o, v) -> o.period = v, o -> o.period)
                    .metadata(EditorSchema.oneOfDocumented(
                            "Daily", "Offered again from midnight UTC every day",
                            "Weekly", "Offered again from midnight UTC every Monday"))
                    .documentation(PERIOD_DOC).add()
                    .append(new KeyedCodec<>("World", DialogueFlagScope.RETIRED_WORLD_LEAF, false),
                            (o, v) -> { /* never decoded: the leaf refuses and says what to write */ },
                            o -> null)
                    .documentation("Retired. Write Where instead.").add()
                    .build();

    /**
     * Accepts BOTH authored forms: the plain {@code true} an author reaches for first, and the group
     * form that names a world family or a window. {@code false} reads as "no Once at all", so turning
     * one off is a one-character edit rather than deleting a block.
     */
    public static final Codec<DialogueOnce> CODEC = new Codec<>() {

        @Override
        @Nullable
        public DialogueOnce decode(BsonValue value, ExtraInfo extraInfo) {
            if (value.isBoolean()) {
                return value.asBoolean().getValue() ? new DialogueOnce() : null;
            }
            return GROUP.decode(value, extraInfo);
        }

        @Nonnull
        @Override
        public BsonValue encode(DialogueOnce once, ExtraInfo extraInfo) {
            return GROUP.encode(once, extraInfo);
        }

        @Override
        @Nullable
        public DialogueOnce decodeJson(RawJsonReader reader, ExtraInfo extraInfo) throws IOException {
            int next = reader.peek();
            if (next == 't' || next == 'T' || next == 'f' || next == 'F') {
                return reader.readBooleanValue() ? new DialogueOnce() : null;
            }
            return GROUP.decodeJson(reader, extraInfo);
        }

        @Nonnull
        @Override
        public Schema toSchema(@Nonnull SchemaContext context) {
            // Decode accepts the plain boolean form too, and the in-game Asset Editor fails a
            // property pane over any authored value shape the exported schema omits, so the
            // schema declares both arms.
            return Schema.anyOf(new BooleanSchema(), GROUP.toSchema(context));
        }
    };

    @Nullable protected WorldSelector where;

    /** The authored window word, exactly as written; parsed on read so a serializer writes the author's words. */
    @Nullable protected String period;

    /** The internal scope carrier; built lazily, dropped by the setter so it cannot go stale. */
    @Nullable private volatile DialogueFlagScope scope;

    public DialogueOnce() {
    }

    /** Java-side construction (tests, a consumer building a tree in code). */
    @Nonnull
    public static DialogueOnce ofWhere(@Nullable WorldSelector where) {
        DialogueOnce once = new DialogueOnce();
        once.where = where;
        return once;
    }

    /** Java-side construction with a window: {@code of(null, "Daily")} is once per character per day. */
    @Nonnull
    public static DialogueOnce of(@Nullable WorldSelector where, @Nullable String period) {
        DialogueOnce once = ofWhere(where);
        once.period = period;
        return once;
    }

    /** The worlds this Once is remembered per, or null for once per character. */
    @Nullable
    public WorldSelector getWhere() {
        return where;
    }

    /** The window word exactly as authored, or null when none was written. */
    @Nullable
    public String getPeriodWord() {
        return period;
    }

    /**
     * The window this Once turns over in, or null for a Once spent for good (unauthored or blank).
     * A word that is neither {@code Daily} nor {@code Weekly} reads as {@code Daily}: the author asked
     * for a window, so the line keeps coming back rather than vanishing, and the audit names the word.
     */
    @Nullable
    public Period getPeriod() {
        if (period == null || period.isBlank()) {
            return null;
        }
        Period parsed = Period.parse(period);
        return parsed == null ? Period.DAILY : parsed;
    }

    /** True when a window word was written that is neither {@code Daily} nor {@code Weekly}. */
    public boolean hasUnknownPeriod() {
        return period != null && !period.isBlank() && Period.parse(period) == null;
    }

    /**
     * The storage key {@code rawKey} resolves to for the player's CURRENT world, or null when this
     * Once's pattern does not match that world (the read is unset and the write a no-op). Warns
     * once per pattern that matches no loaded world; see {@link DialogueFlagScope}. It carries no
     * window; {@link #slotFor} adds one.
     */
    @Nullable
    public String keyFor(@Nonnull String rawKey, @Nonnull DialogueContext ctx) {
        return DialogueFlagScope.keyFor(scope(), rawKey, ctx);
    }

    /**
     * Where this Once is filed for the player right now: {@link #keyFor} plus the window the instant
     * {@code nowMs} falls in, or null when the scope does not match this world. Public for
     * {@link DialogueEngine}, which resolves every entry and option Once through it.
     */
    @Nullable
    public Slot slotFor(@Nonnull String rawKey, @Nonnull DialogueContext ctx, long nowMs) {
        String scoped = keyFor(rawKey, ctx);
        return scoped == null ? null : slotOf(scoped, nowMs);
    }

    /** The PURE half of {@link #slotFor}: the slot for a key already scoped to a world (or unscoped). */
    @Nonnull
    public Slot slotOf(@Nonnull String scopedKey, long nowMs) {
        Period window = getPeriod();
        if (window == null) {
            return new Slot(scopedKey, null);
        }
        return new Slot(DialogueStateKeys.withPeriod(scopedKey, window.code(), window.index(nowMs)),
                DialogueStateKeys.periodFamily(scopedKey));
    }

    /**
     * The PURE resolver behind {@link #keyFor}: the key in the world named {@code worldName}, or
     * null when this Once's selector does not match it.
     */
    @Nullable
    public String resolveKey(@Nonnull String rawKey, @Nullable String worldName) {
        return resolveKey(rawKey, worldName, null);
    }

    /** {@link #resolveKey(String, String)} with the world's gameplay config too. */
    @Nullable
    public String resolveKey(@Nonnull String rawKey, @Nullable String worldName,
                             @Nullable String worldGameplayConfig) {
        return DialogueFlagScope.resolve(scope(), rawKey, worldName, worldGameplayConfig);
    }

    @Nonnull
    private DialogueFlagScope scope() {
        DialogueFlagScope cached = scope;
        if (cached == null) {
            cached = DialogueFlagScope.ofWhere(where);
            scope = cached;
        }
        return cached;
    }

    /**
     * Where a {@code Once} is filed for the player right now: the {@code key} to read and to spend,
     * and, for a {@code Once} with a {@code Period}, the {@code staleFamily} prefix its earlier windows
     * were filed under, which a spend clears first. The family is null for a Once spent for good.
     */
    public record Slot(@Nonnull String key, @Nullable String staleFamily) {
    }

    /**
     * The windows a periodic {@code Once} turns over in: the family's two calendar words, on the UTC
     * grid {@link PeriodMath} keeps. A closed vocabulary, so the editor offers it as a dropdown.
     */
    public enum Period {
        DAILY("Daily", 'D', PeriodMath.DAY_MS, 0L),
        WEEKLY("Weekly", 'W', PeriodMath.WEEK_MS, PeriodMath.weekdayAnchorMs(DayOfWeek.MONDAY));

        private final String word;
        private final char code;
        private final long lengthMs;
        private final long anchorOffsetMs;

        Period(@Nonnull String word, char code, long lengthMs, long anchorOffsetMs) {
            this.word = word;
            this.code = code;
            this.lengthMs = lengthMs;
            this.anchorOffsetMs = anchorOffsetMs;
        }

        /** The word an author writes. */
        @Nonnull
        public String word() {
            return word;
        }

        /** The letter the window segment carries after its {@code P}. */
        public char code() {
            return code;
        }

        /** Which window {@code nowMs} falls in; monotonic, UTC. */
        public long index(long nowMs) {
            return PeriodMath.periodIndex(lengthMs, anchorOffsetMs, nowMs);
        }

        /** The window an authored word names, ignoring case and surrounding space, or null for neither word. */
        @Nullable
        public static Period parse(@Nullable String authored) {
            if (authored == null) {
                return null;
            }
            String value = authored.trim();
            for (Period candidate : values()) {
                if (candidate.word.equalsIgnoreCase(value)) {
                    return candidate;
                }
            }
            return null;
        }
    }
}
