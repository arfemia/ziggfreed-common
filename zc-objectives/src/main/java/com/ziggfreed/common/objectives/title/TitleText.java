package com.ziggfreed.common.objectives.title;

import java.util.Locale;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.feedback.moment.FeedbackEngine;
import com.ziggfreed.common.i18n.ContentKeys;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.i18n.NativeNames;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.text.ContentTextAsset;
import com.ziggfreed.common.util.SafeLog;

/**
 * What a title is CALLED and where it sits around a player's name: the readings every title
 * surface shares, so the picker, a menu row, the reward chip and the unlock notice cannot disagree.
 *
 * <p><b>The name ladder.</b> The title's {@code Text.TitleKey}, else {@code title.<id>.name} from
 * whichever loaded lang file ships it ({@link ContentKeys}, namespace-agnostic), else its typed
 * {@code Text.DisplayName}, else the id spelled out ({@code example_title} as "Example Title"), a
 * traceable fallback rather than a raw key at a player.
 *
 * <p><b>The display line.</b> {@code title.<id>.display} places the title around a player's name,
 * its {@code {0}} being the name, so a translator chooses the word order per title. A title that
 * ships none follows the name through this library's shared {@code display} line.
 */
public final class TitleText {

    /** The key family the shipped {@code ziggfreedcommon.title.lang} resolves under. */
    public static final String PREFIX = "ziggfreedcommon.title.";

    /** The shared fallback: {0} the player's name, {1} the title's. */
    static final String DISPLAY_KEY = PREFIX + "display";

    /** How a Title reward reads on a chip: {0} the title's name. */
    static final String CHIP_KEY = PREFIX + "chip";

    /** The feedback moment fired for a NEW unlock; the shipped default toasts the title's name. */
    public static final String UNLOCKED_MOMENT = "Title_Unlocked";

    /** The moment value carrying the title's localized name, which the shipped toast line reads. */
    static final String NAME_ARG = "name";

    /** The moment value carrying the title's id, for an override that wants it. */
    static final String TITLE_ARG = "title";

    private TitleText() {
    }

    /** The convention name key, without a namespace: {@code title.<id>.name}. */
    @Nonnull
    public static String nameKey(@Nonnull String titleId) {
        return "title." + lower(titleId) + ".name";
    }

    /** The convention flavor key: {@code title.<id>.flavor}. */
    @Nonnull
    public static String flavorKey(@Nonnull String titleId) {
        return "title." + lower(titleId) + ".flavor";
    }

    /** The convention display key: {@code title.<id>.display}, whose {0} is the player's name. */
    @Nonnull
    public static String displayKey(@Nonnull String titleId) {
        return "title." + lower(titleId) + ".display";
    }

    /** What the title is called, reading its folded file when one is loaded. */
    @Nonnull
    public static Message nameOf(@Nonnull String titleId) {
        return nameOf(titleId, TitleConfig.getInstance().resolve(titleId));
    }

    /** What the title is called, as a client-resolved {@link Message}, by the ladder above. */
    @Nonnull
    public static Message nameOf(@Nonnull String titleId, @Nullable TitleAsset title) {
        ContentTextAsset text = title == null ? null : title.text();
        String key = ContentKeys.pick(text == null ? null : text.getTitleKey(), nameKey(titleId));
        if (key != null) {
            return ContentKeys.tr(key);
        }
        String typed = text == null ? null : text.getDisplayName();
        if (typed != null && !typed.isBlank()) {
            return Msg.raw(typed.trim());
        }
        return Msg.raw(NativeNames.prettify(lower(titleId)));
    }

    /** How the title was earned, reading its folded file; null when nothing describes it. */
    @Nullable
    public static Message flavorOf(@Nonnull String titleId) {
        return flavorOf(titleId, TitleConfig.getInstance().resolve(titleId));
    }

    /** How the title was earned: {@code Text.FlavorKey}, else {@code title.<id>.flavor}, else null. */
    @Nullable
    public static Message flavorOf(@Nonnull String titleId, @Nullable TitleAsset title) {
        ContentTextAsset text = title == null ? null : title.text();
        String key = ContentKeys.pick(text == null ? null : text.getFlavorKey(), flavorKey(titleId));
        return key == null ? null : ContentKeys.tr(key);
    }

    /**
     * A player's name with the title placed around it: the title's own display line when some lang
     * file ships one, else the shared line. The name is raw data, never translated.
     */
    @Nonnull
    public static Message display(@Nonnull String titleId, @Nullable TitleAsset title,
            @Nonnull String playerName) {
        String own = displayKey(titleId);
        if (ContentKeys.known(own)) {
            return ContentKeys.tr(own, playerName);
        }
        return Msg.key(DISPLAY_KEY, playerName, nameOf(titleId, title));
    }

    /** How a Title reward reads on a reward chip. */
    @Nonnull
    public static Message chip(@Nonnull String titleId) {
        return Msg.key(CHIP_KEY, nameOf(titleId));
    }

    /** A line of the title picker, from the shipped player-facing file: {@code picker.<key>}. */
    @Nonnull
    public static Message picker(@Nonnull String key, @Nonnull Object... args) {
        return Msg.key(PREFIX + "picker." + key, args);
    }

    /** Tell {@code who} the title is theirs now. Guarded whole: a notice must never undo the unlock. */
    static void announceUnlocked(@Nonnull Subject who, @Nonnull String titleId) {
        try {
            if (!FeedbackEngine.answers(UNLOCKED_MOMENT)) {
                return;
            }
            FeedbackEngine.fire(UNLOCKED_MOMENT, who, Map.of(TITLE_ARG, titleId, NAME_ARG, nameOf(titleId)));
        } catch (Throwable t) {
            SafeLog.fine("[title] the unlock notice for '" + titleId + "' could not be drawn: " + t.getMessage());
        }
    }

    @Nonnull
    private static String lower(@Nonnull String titleId) {
        return titleId.trim().toLowerCase(Locale.ROOT);
    }
}
