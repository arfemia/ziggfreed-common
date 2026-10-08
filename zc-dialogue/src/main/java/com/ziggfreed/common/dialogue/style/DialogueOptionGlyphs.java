package com.ziggfreed.common.dialogue.style;

import java.util.Locale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.dialogue.schema.DialogueOption;
import com.ziggfreed.common.ui.route.DestinationKind;

/**
 * Which picture leads an answer row: what the answer DOES, so a player can tell talking on from
 * opening a shop from saying goodbye before they press anything. An answer that talks shows a speech
 * bubble, one that ends the conversation an X, an accept or a hand-in its check, and one that opens a
 * screen the kind of screen it opens (a quest scroll, a coin sack, a notice board, a star for standing,
 * a book, a trophy), as its destination declared it ({@code DestinationType.withKind}).
 *
 * <p>An authored {@code Style} plays no part here: it only colours the row. The order, first answer
 * wins:
 * <ol>
 *   <li>{@code Presentation.Icon.Item}: that item's own picture (drawn plain, with no item tooltip);</li>
 *   <li>{@code Presentation.Icon.Glyph}: a glyph token the author named;</li>
 *   <li>for an answer that opens a screen, the kind its destination declared;</li>
 *   <li>the {@code Glyph} of the theme for what the answer does ({@code DialogueOptionTheme/<Kind>.json});</li>
 *   <li>that kind's own glyph ({@link DialogueOptionStyle#iconElementId}).</li>
 * </ol>
 * A token nothing knows falls through to the next rung rather than leaving the row bare.
 *
 * <p>Pure: it names an element id of {@code Pages/ZigDialogueOptionRow.ui}, whose glyph textures live
 * in the markup (a texture path set from Java does not resolve), and the page reveals it.
 */
public final class DialogueOptionGlyphs {

    /**
     * What leads one row: an item whose picture to draw, and the glyph element to reveal instead when
     * there is no item or the item has no picture. Either may be null.
     */
    public record Glyph(@Nullable String itemId, @Nullable String elementId) {
    }

    private DialogueOptionGlyphs() {
    }

    /**
     * The glyph for an answer.
     *
     * @param actionStyle      what the answer does ({@code DialogueEngine.actionStyle}), never its
     *                         authored {@code Style}
     * @param destinationKind  the kind of screen the answer opens, or null when it opens none or its
     *                         destination declared no kind
     * @param actionTheme      the theme for {@code actionStyle}, or null when no layer authored one
     * @param presentation     the answer's own {@code Presentation}, or null
     */
    @Nonnull
    public static Glyph resolve(@Nonnull DialogueOptionStyle actionStyle, @Nullable DestinationKind destinationKind,
            @Nullable DialogueOptionTheme actionTheme, @Nullable DialogueOption.Presentation presentation) {
        DialogueOption.Icon icon = presentation == null ? null : presentation.getIcon();
        String item = icon == null ? null : trimToNull(icon.getItem());
        String element = elementFor(icon == null ? null : icon.getGlyph());
        if (element == null && destinationKind != null) {
            element = elementFor(destinationKind.key());
        }
        if (element == null) {
            element = elementFor(actionTheme == null ? null : actionTheme.glyphToken());
        }
        if (element == null) {
            element = actionStyle.iconElementId();
        }
        return new Glyph(item, element);
    }

    /**
     * The row element a glyph token names, case-insensitively, or null for a blank or unknown token.
     * The tokens are the five answer kinds ({@code accept}, {@code turnin}, {@code continue},
     * {@code open}, {@code farewell}), {@code talk}, and every {@link DestinationKind} key.
     */
    @Nullable
    public static String elementFor(@Nullable String token) {
        String key = trimToNull(token);
        if (key == null) {
            return null;
        }
        switch (key.toLowerCase(Locale.ROOT)) {
            case "accept": return "#IcoAccept";
            case "turnin":
            case "turn_in": return "#IcoTurnIn";
            case "continue": return "#IcoContinue";
            case "open": return "#IcoOpen";
            case "farewell":
            case "close": return "#IcoFarewell";
            case "talk": return "#IcoTalk";
            case "quest": return "#IcoQuest";
            case "shop": return "#IcoShop";
            case "board": return "#IcoBoard";
            case "standing": return "#IcoStanding";
            case "book": return "#IcoBook";
            case "trophy": return "#IcoTrophy";
            default: return null;
        }
    }

    @Nullable
    private static String trimToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
