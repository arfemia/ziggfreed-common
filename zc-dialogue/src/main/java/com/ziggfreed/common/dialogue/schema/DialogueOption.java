package com.ziggfreed.common.dialogue.schema;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

import com.ziggfreed.common.dialogue.DialogueEngine;
import com.ziggfreed.common.dialogue.state.DialogueOnce;
import com.ziggfreed.common.dialogue.style.DialogueOptionStyle;
import com.ziggfreed.common.dialogue.type.DialogueAction;
import com.ziggfreed.common.dialogue.type.DialogueCondition;

/**
 * One selectable line in a dialogue node: a localized label, an optional list of
 * AND-combined {@link DialogueCondition}s (option hidden while they fail;
 * re-evaluated on every render AND again on click), an ordered list of
 * {@link DialogueAction}s, an optional {@link DialogueOnce} ({@code "Once": true} -
 * offered until its actions have run once), and an optional {@link Presentation}
 * (per-option colour + icon) that overrides the action-derived
 * {@link DialogueOptionStyle}.
 * An option with no Goto/Close re-renders its node. A pure data POJO; its codec
 * is assembled by {@link DialogueEngine} (the {@code Presentation} sub-object is
 * self-contained and carries its own {@link Presentation#CODEC}).
 */
public class DialogueOption {

    @Nullable String labelKey;
    @Nullable String label;
    @Nullable DialogueCondition[] conditions;
    @Nullable DialogueAction[] actions;
    @Nullable Presentation presentation;
    @Nullable String styleKind;
    @Nullable DialogueOnce once;
    @Nullable String onceId;
    /** The decoded shorthand keys authored directly on this option; built on demand. */
    @Nullable private DialogueSugarValues sugar;
    /** The {@code Do} atoms, when the author spelled the order out; replaces the bare shorthand. */
    @Nullable DialogueSugarValues[] doAtoms;
    /** The folded {@code Actions} + shorthand list, computed once. */
    @Nullable private volatile List<DialogueAction> folded;

    public DialogueOption() {
    }

    /**
     * The shorthand values authored on this option, created on first touch so the codec always has
     * somewhere to put one.
     */
    @Nonnull
    DialogueSugarValues sugarValues() {
        DialogueSugarValues values = sugar;
        if (values == null) {
            values = new DialogueSugarValues();
            sugar = values;
        }
        return values;
    }

    /** Explicit i18n key for the option label, or null (by-convention key, then raw fallback). */
    @Nullable public String getLabelKey() { return labelKey; }

    /**
     * An explicit {@link DialogueOptionStyle} kind key ({@code accept}/{@code turnin}/
     * {@code continue}/{@code neutral}/{@code farewell}) that OVERRIDES the action-derived style, or
     * null. Lets an option declare its themed look by role - a data-driven, theme-resolved
     * alternative to a hard-coded {@link Presentation} (the colour + glyph then come from the
     * {@code DialogueOptionTheme} asset for that kind). An unknown key is ignored (action-derived).
     */
    @Nullable public String getStyleKind() { return styleKind; }

    /** Deprecated raw label fallback, or null. */
    @Nullable public String getLabel() { return label; }

    /**
     * The one-time knob, or null when this option may be chosen any number of times. With
     * {@code "Once": true} the option is offered until its actions have run once; with
     * {@code "Once": {"World": "<pattern>"}} it is offered once per world family - the leaf takes a
     * world name or a pattern in the ordinary grammar ({@code forgotten_temple}, {@code Temple*},
     * {@code *Temple*}), and the pattern is the exact-versus-family dial.
     */
    @Nullable public DialogueOnce getOnce() { return once; }

    /**
     * An explicit identity for this option's {@code Once}, or null to use its {@code LabelKey}.
     * Author one when the option has no {@code LabelKey}, or when two options in the same node
     * share a label and each should be spendable on its own.
     */
    @Nullable public String getOnceId() { return onceId; }

    /**
     * What this option's {@code Once} is remembered under: the {@code OnceId} when authored, else
     * the {@code LabelKey}, else the raw {@code Label}. Never the option's index, so reordering a
     * node's options cannot resurrect a spent Once or spend a fresh one. Blank when the option
     * offers nothing stable to key on (author an {@code OnceId} in that case).
     */
    @Nonnull
    public String onceDiscriminator() {
        if (onceId != null && !onceId.isBlank()) {
            return onceId;
        }
        if (labelKey != null && !labelKey.isBlank()) {
            return labelKey;
        }
        return label != null && !label.isBlank() ? label : "";
    }

    @Nonnull
    public List<DialogueCondition> getConditions() {
        return conditions == null ? Collections.emptyList() : List.of(conditions);
    }

    /** True when this option authored any conditions (an empty/absent list always passes). */
    public boolean hasConditions() {
        return conditions != null && conditions.length > 0;
    }

    /**
     * Everything this option does when it is chosen: whatever it authored under {@code Actions},
     * followed by the actions its shorthand stands for.
     *
     * <p>The shorthand is folded in HERE rather than by rewriting the file before it is read, which
     * is what lets {@code "Goto": "next"} be a real field with a real type instead of a key the
     * schema never hears about. An option that authored no shorthand skips the fold entirely.
     */
    @Nonnull
    public List<DialogueAction> getActions() {
        if ((sugar == null || sugar.isEmpty()) && (doAtoms == null || doAtoms.length == 0)) {
            return actions == null ? Collections.emptyList() : List.of(actions);
        }
        List<DialogueAction> cached = folded;
        if (cached == null) {
            cached = DialogueTypeTable.get().sugar().fold(actions, sugar, doAtoms);
            folded = cached;
        }
        return cached;
    }

    /** Direct (non-codec) construction: set the canonical actions from Java. */
    public void setActions(@Nullable DialogueAction[] actions) {
        this.actions = actions;
        this.folded = null;
    }

    /** The shorthand keys authored directly on this option (not inside {@code Do}). */
    @Nonnull
    public Set<String> bareSugarKeys() {
        return sugar == null ? Collections.emptySet() : sugar.keys();
    }

    /** True when the author spelled the order out with a {@code Do} array. */
    public boolean hasDoAtoms() {
        return doAtoms != null && doAtoms.length > 0;
    }

    /** How many canonical {@code Actions} the option authored by hand, before any shorthand. */
    public int authoredActionCount() {
        return actions == null ? 0 : actions.length;
    }

    /** True when this option's authored actions include a genuine {@link DialogueAction.Close}. */
    public boolean closesDialogue() {
        for (DialogueAction action : getActions()) {
            if (action instanceof DialogueAction.Close) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when any option in the list {@linkplain #closesDialogue() closes the dialogue}.
     * Tested on the AUTHORED ACTION, never on {@link DialogueOptionStyle#FAREWELL}: an author
     * can force {@code Style: "farewell"} on an option that does not actually close (a themed
     * prompt), and treating a cosmetic closer as a real exit would let a page suppress the only
     * real way out of a node. The page's implicit-farewell policy hangs off this (a rendered
     * node with no closing option gets the implicit "Farewell" row appended).
     */
    public static boolean anyCloses(@Nonnull List<DialogueOption> options) {
        for (DialogueOption option : options) {
            if (option.closesDialogue()) {
                return true;
            }
        }
        return false;
    }

    /** Per-option colour/icon override, or null (fall back to the action-derived style). */
    @Nullable public Presentation getPresentation() { return presentation; }

    /**
     * Optional per-option look: a {@code Color} hex tint for the button states and an
     * {@code Icon} (a game item id OR a fixed glyph token). A cohesive field group, so
     * it is a NESTED sub-object with its own codec + nullable leaves (per the schema
     * mandate), NOT flat prefixed keys. Absent leaves fall back to the action-derived
     * {@link DialogueOptionStyle}. Authored shape:
     * {@code "Presentation": { "Color": "#5ab0ff", "Icon": { "Item": "hytale:iron_sword" } }}.
     */
    public static final class Presentation {
        public static final BuilderCodec<Presentation> CODEC = BuilderCodec.builder(Presentation.class, Presentation::new)
                .append(new KeyedCodec<>("Color", Codec.STRING, false),
                        (p, v) -> p.color = v, p -> p.color).add()
                .append(new KeyedCodec<>("Icon", Icon.CODEC, false),
                        (p, v) -> p.icon = v, p -> p.icon).add()
                .build();

        @Nullable protected String color;
        @Nullable protected Icon icon;

        /** Button-tint hex (e.g. {@code #5ab0ff}) or null. */
        @Nullable public String getColor() { return color; }

        @Nullable public Icon getIcon() { return icon; }
    }

    /**
     * The icon for an option row: an {@code Item} (any game item id, rendered via the
     * item-grid slot mechanism) OR a {@code Glyph} (a fixed token naming a pre-authored
     * glyph in the row {@code .ui}). Both nullable; {@code Item} wins when both are set.
     */
    public static final class Icon {
        public static final BuilderCodec<Icon> CODEC = BuilderCodec.builder(Icon.class, Icon::new)
                .append(new KeyedCodec<>("Item", Codec.STRING, false),
                        (i, v) -> i.item = v, i -> i.item).add()
                .append(new KeyedCodec<>("Glyph", Codec.STRING, false),
                        (i, v) -> i.glyph = v, i -> i.glyph).add()
                .build();

        @Nullable protected String item;
        @Nullable protected String glyph;

        /** A game item id whose icon renders in the row slot, or null. */
        @Nullable public String getItem() { return item; }

        /** A fixed glyph token (e.g. {@code accept}/{@code turnin}) naming a row {@code .ui} glyph, or null. */
        @Nullable public String getGlyph() { return glyph; }
    }
}
