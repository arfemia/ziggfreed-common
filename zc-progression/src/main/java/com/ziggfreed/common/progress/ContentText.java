package com.ziggfreed.common.progress;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.i18n.ContentKeys;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.progress.asset.ContentTextAsset;
import com.ziggfreed.common.util.NumberFormatter;

/**
 * What a piece of content is CALLED, carried on the runtime object the engines run.
 *
 * <p>The engines carry mechanics, not words, and that was fine for exactly as long as one mod owned
 * every catalogue. The moment two mods publish into one runtime, a shared surface walking the merged
 * catalogue has to be able to name every row - and whoever folded a row is the only one holding its
 * words. Carrying the words HERE, on the object every fold already builds, is what lets one text
 * source answer for content authored in any format rather than one source per format.
 *
 * <p><b>Keys, not sentences.</b> Every leaf is a localization key the player's own client resolves
 * in the player's own language; nothing on the server ever reads a locale. {@code displayName} and
 * {@code description} are the plain fallbacks for content whose key is not written yet - they reach
 * every player in the one language they were typed in, so anything shipping to players carries a key.
 * The one composed leaf is a STEP's line, which a fold may hand over already built from its own keys
 * (an authored step key included, resolved with the arguments its slots need); see
 * {@link #objective(String)} for why that is not a sentence and why it outranks the bare key.
 *
 * <p><b>Two keys per line, because most content has a convention.</b> An EXPLICIT key is what the
 * file literally says; a CONVENTION key is what a mod's own naming rule would call it
 * ({@code quest.<id>.title} and friends), so a pack ships localized titles through {@code .lang}
 * alone with no per-file edit. The explicit key wins when it resolves, the convention key next, the
 * plain fallback after that.
 *
 * <p>Arguments are bound ONCE, where the content is folded, because everything they can say is fixed
 * per row: the amount a step asks for, the thing it names. A caller that wants a localized argument
 * passes a nested {@link Message} and it stays one.
 */
public final class ContentText {

    /**
     * The empty argument list every unset args leaf points at.
     *
     * <p>It is declared BEFORE {@link #EMPTY} on purpose: static fields initialize in source order,
     * and {@link #EMPTY} builds an instance whose args leaves read this one. Declared after, it is
     * still null while that instance is being built, and the shared constant ends up carrying null
     * args whose two {@code @Nonnull} readers then throw.
     */
    private static final Object[] NO_ARGS = new Object[0];

    /** Nothing at all: every read answers null, which is the honest answer for content with no words. */
    public static final ContentText EMPTY = builder().build();

    @Nullable private final String titleKey;
    @Nullable private final String titleConventionKey;
    @Nullable private final String displayName;
    private final Object[] titleArgs;
    @Nullable private final String flavorKey;
    @Nullable private final String flavorConventionKey;
    @Nullable private final String description;
    private final Object[] flavorArgs;
    private final Map<String, String> objectiveKeys;
    private final Map<String, Supplier<Message>> objectiveLines;
    private final Map<String, String> lore;

    private ContentText(@Nonnull Builder b) {
        this.titleKey = blankToNull(b.titleKey);
        this.titleConventionKey = blankToNull(b.titleConventionKey);
        this.displayName = blankToNull(b.displayName);
        this.titleArgs = b.titleArgs;
        this.flavorKey = blankToNull(b.flavorKey);
        this.flavorConventionKey = blankToNull(b.flavorConventionKey);
        this.description = blankToNull(b.description);
        this.flavorArgs = b.flavorArgs;
        this.objectiveKeys = Map.copyOf(b.objectiveKeys);
        this.objectiveLines = Map.copyOf(b.objectiveLines);
        this.lore = Map.copyOf(b.lore);
    }

    /** True when nothing was carried at all, so a source can skip this row without resolving it. */
    public boolean isEmpty() {
        return titleKey == null && titleConventionKey == null && displayName == null
                && flavorKey == null && flavorConventionKey == null && description == null
                && objectiveKeys.isEmpty() && objectiveLines.isEmpty() && lore.isEmpty();
    }

    /** What this content is called, or null when it carries no name at all. */
    @Nullable
    public Message title() {
        return resolve(titleKey, titleConventionKey, displayName, titleArgs);
    }

    /**
     * What this content is called, falling back to its own id written out.
     *
     * <p>For a reader that has to say SOMETHING: a moment announcing an achievement or a quest reads
     * far better naming an id than going silent, which is what a null title would cost it.
     */
    @Nonnull
    public Message titleOr(@Nonnull String id) {
        Message title = title();
        return title != null ? title : Msg.raw(id);
    }

    /** The line under the title, or null. */
    @Nullable
    public Message flavor() {
        return resolve(flavorKey, flavorConventionKey, description, flavorArgs);
    }

    /**
     * What one step is called: the line the fold composed for it when there is one, else the
     * authored key, else null.
     *
     * <p>Nothing is INVENTED here - this carries words, it does not write them. But a fold that
     * already knows how its own steps read is the one layer that can say so, and a step with no line
     * at all renders as a generic "unnamed step" on every shared surface at once, which is what the
     * player sees while the content file still reads as perfectly correct. So a fold may hand over a
     * composed line, and it hands over a SUPPLIER because whatever composes it is often installed
     * later in a boot than the catalogue is folded. A supplier that throws, or that answers null
     * because it has nothing to say yet, costs its own line and nothing else: the authored key takes
     * over exactly as if no line had been offered.
     *
     * <p>The composed line is asked FIRST, unlike the title ladder, because it is not a rival to
     * the authored key - it is the fold's fuller reading of the same authorship. A fold's composer
     * resolves the step's own key itself, WITH the arguments the key's slots need (a hand-in's
     * character name, a kill or collect count) and any wrapping the step declares; this seam holds
     * no per-step arguments, so resolving a parameterized key here paints its {@code {0}} literally
     * on every shared surface at once. The bare key resolution is the fallback, for the step whose
     * fold composed nothing.
     */
    @Nullable
    public Message objective(@Nonnull String objectiveId) {
        Supplier<Message> line = objectiveLines.get(objectiveId);
        if (line != null) {
            try {
                Message composed = line.get();
                if (composed != null) {
                    return composed;
                }
            } catch (Throwable composerFailed) {
                // Costs its own line, never the screen.
            }
        }
        String key = objectiveKeys.get(objectiveId);
        return key == null ? null : ContentKeys.tr(key);
    }

    /** The authored narrative for a lifecycle state, or null when this content wrote none. */
    @Nullable
    public Message lore(@Nonnull String state) {
        String raw = lore.get(state);
        return raw == null || raw.isEmpty() ? null : Msg.raw(raw);
    }

    /**
     * What binds the title key's numbered slots, already expanded - for a surface that resolves the
     * key itself rather than taking the {@link #title()} message. A copy, so nothing can rewrite
     * what a fold decided.
     */
    @Nonnull
    public Object[] titleArgs() {
        return titleArgs.clone();
    }

    /** What binds the flavor key's numbered slots, on the same terms. */
    @Nonnull
    public Object[] flavorArgs() {
        return flavorArgs.clone();
    }

    /** The explicit title key exactly as authored, for a surface that wants the key rather than the text. */
    @Nullable
    public String titleKey() {
        return titleKey;
    }

    /**
     * The key a surface should ask a client to resolve for this content's NAME, or null when there
     * is none: the explicit key when it resolves, else the convention key when it does. Handing a
     * key rather than a {@link Message} is what an offer listing wants, since it carries keys across
     * to whatever paints it.
     */
    @Nullable
    public String resolvableTitleKey() {
        if (titleKey != null && ContentKeys.known(titleKey)) {
            return titleKey;
        }
        if (titleConventionKey != null && ContentKeys.known(titleConventionKey)) {
            return titleConventionKey;
        }
        return titleKey != null ? titleKey : titleConventionKey;
    }

    /**
     * The ladder, in one place: an explicit key that resolves, then a convention key that resolves,
     * then the plain fallback, and finally whichever key was written at all, handed over as it
     * stands.
     *
     * <p>That last rung is the same answer {@link #resolvableTitleKey()} gives, on purpose. A row on
     * a server whose translation has not landed yet then paints the key rather than nothing at all,
     * which is a thing somebody reading a screenshot or a support log can trace back to the file it
     * came from; a blank is not. Only content that wrote no key of either sort answers null, which
     * is the honest "this carries no name".
     */
    @Nullable
    private static Message resolve(@Nullable String explicitKey, @Nullable String conventionKey,
            @Nullable String raw, @Nonnull Object[] args) {
        if (explicitKey != null && ContentKeys.known(explicitKey)) {
            return ContentKeys.tr(explicitKey, args);
        }
        if (conventionKey != null && ContentKeys.known(conventionKey)) {
            return ContentKeys.tr(conventionKey, args);
        }
        if (raw != null) {
            return Msg.raw(raw);
        }
        String written = explicitKey != null ? explicitKey : conventionKey;
        return written == null ? null : ContentKeys.tr(written, args);
    }

    @Nullable
    private static String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * An authored {@code TextArgs} list bound to concrete values, with the shared schema's one
     * sentinel answered.
     *
     * <p>A whole ladder of content is usually ONE translated line with each rung supplying its own
     * number, so a key resolved WITHOUT its args renders that line with an empty slot on every rung
     * at once while the content file still reads as perfectly correct. {@code @amount} is grouped
     * for readability and passed raw, since a digit needs no translating; anything else an author
     * wrote is passed through exactly as typed, so a sentinel nothing answers shows up in the line
     * instead of leaving a blank nobody can diagnose.
     */
    @Nonnull
    public static Object[] amountArgs(@Nullable List<String> authored, long amount) {
        return ContentTextAsset.expand(authored, sentinel ->
                ContentTextAsset.ARG_AMOUNT.equals(sentinel)
                        ? NumberFormatter.grouped(amount) : null);
    }

    @Nonnull
    public static Builder builder() {
        return new Builder();
    }

    /** Assembles a {@link ContentText}; every leaf is optional and an unset one simply reads null. */
    public static final class Builder {

        @Nullable private String titleKey;
        @Nullable private String titleConventionKey;
        @Nullable private String displayName;
        private Object[] titleArgs = NO_ARGS;
        @Nullable private String flavorKey;
        @Nullable private String flavorConventionKey;
        @Nullable private String description;
        private Object[] flavorArgs = NO_ARGS;
        private final Map<String, String> objectiveKeys = new LinkedHashMap<>();
        private final Map<String, Supplier<Message>> objectiveLines = new LinkedHashMap<>();
        private final Map<String, String> lore = new LinkedHashMap<>();

        private Builder() {
        }

        /** The key the content file literally names for the title. */
        @Nonnull
        public Builder titleKey(@Nullable String titleKey) {
            this.titleKey = titleKey;
            return this;
        }

        /** The key this content's own naming rule would use for the title. */
        @Nonnull
        public Builder titleConventionKey(@Nullable String conventionKey) {
            this.titleConventionKey = conventionKey;
            return this;
        }

        /** The plain name to fall back on while a key is being written. */
        @Nonnull
        public Builder displayName(@Nullable String displayName) {
            this.displayName = displayName;
            return this;
        }

        /** What binds the title key's {@code {0}/{1}/...} slots, already expanded. */
        @Nonnull
        public Builder titleArgs(@Nullable Object[] titleArgs) {
            this.titleArgs = titleArgs == null ? NO_ARGS : titleArgs.clone();
            return this;
        }

        @Nonnull
        public Builder flavorKey(@Nullable String flavorKey) {
            this.flavorKey = flavorKey;
            return this;
        }

        @Nonnull
        public Builder flavorConventionKey(@Nullable String conventionKey) {
            this.flavorConventionKey = conventionKey;
            return this;
        }

        /** The plain description to fall back on while a key is being written. */
        @Nonnull
        public Builder description(@Nullable String description) {
            this.description = description;
            return this;
        }

        /** What binds the flavor key's {@code {0}/{1}/...} slots, already expanded. */
        @Nonnull
        public Builder flavorArgs(@Nullable Object[] flavorArgs) {
            this.flavorArgs = flavorArgs == null ? NO_ARGS : flavorArgs.clone();
            return this;
        }

        /** The key one step is named by. A blank id or key is ignored rather than stored. */
        @Nonnull
        public Builder objectiveKey(@Nullable String objectiveId, @Nullable String key) {
            if (objectiveId != null && !objectiveId.isBlank() && key != null && !key.isBlank()) {
                objectiveKeys.put(objectiveId.trim(), key.trim());
            }
            return this;
        }

        /**
         * The line one step reads with, composed by the fold (its authored key resolved with its
         * arguments, or a generated sentence for a step that authored none) and asked for on
         * demand. A blank id or a null supplier is ignored rather than stored.
         */
        @Nonnull
        public Builder objectiveLine(@Nullable String objectiveId, @Nullable Supplier<Message> line) {
            if (objectiveId != null && !objectiveId.isBlank() && line != null) {
                objectiveLines.put(objectiveId.trim(), line);
            }
            return this;
        }

        /** The narrative this content reads with in one lifecycle state, as authored. */
        @Nonnull
        public Builder lore(@Nullable String state, @Nullable String text) {
            if (state != null && !state.isBlank() && text != null && !text.isEmpty()) {
                lore.put(state.trim(), text);
            }
            return this;
        }

        @Nonnull
        public ContentText build() {
            return new ContentText(this);
        }
    }
}
