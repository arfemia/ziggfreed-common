package com.ziggfreed.common.dialogue;

import java.util.Collections;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

/**
 * One selectable line in a dialogue node: a localized label, an optional list of
 * AND-combined {@link DialogueCondition}s (option hidden while they fail;
 * re-evaluated on every render AND again on click), an ordered list of
 * {@link DialogueAction}s, and an optional {@link Presentation} (per-option
 * colour + icon) that overrides the action-derived {@link DialogueOptionStyle}.
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

    public DialogueOption() {
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

    @Nonnull
    public List<DialogueCondition> getConditions() {
        return conditions == null ? Collections.emptyList() : List.of(conditions);
    }

    /** True when this option authored any conditions (an empty/absent list always passes). */
    public boolean hasConditions() {
        return conditions != null && conditions.length > 0;
    }

    @Nonnull
    public List<DialogueAction> getActions() {
        return actions == null ? Collections.emptyList() : List.of(actions);
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
