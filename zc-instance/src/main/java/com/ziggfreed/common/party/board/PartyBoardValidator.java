package com.ziggfreed.common.party.board;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nonnull;

import com.ziggfreed.common.i18n.ContentKeys;
import com.ziggfreed.common.util.SafeLog;
import com.ziggfreed.common.validation.Finding;

/**
 * The content audit over the folded party board settings ({@link PartyBoardAsset}) and set notes
 * ({@link PartyNoteAsset}), filed under the {@code party_board} domain, run once every pack and the owner's
 * {@code party-board.json} have loaded (the boot audit's pass). {@link #audit()} is the engine walk,
 * {@link #audit(PartyBoardAsset)} the pure core over the settings. Every code is a WARNING:
 * <ul>
 *   <li>{@link #BLANK_COMMAND}: an announce line with nothing on it (it is skipped).</li>
 *   <li>{@link #UNKNOWN_PLACEHOLDER}: an announce line naming a placeholder the board does not fill (it runs with the
 *       placeholder standing as written).</li>
 *   <li>{@link #MINUTES_OUT_OF_RANGE}: {@code Notices.Minutes} under 1 or over a day (it is held to that range).</li>
 *   <li>{@link #UNKNOWN_NOTE_TEXT}: a note whose {@code TextKey} no shipped lang file holds (it shows the key).</li>
 * </ul>
 */
public final class PartyBoardValidator {

    public static final String DOMAIN = "party_board";

    /** The label the audit's lines carry when it is logged whole (zc's boot audit). */
    public static final String LOG_LABEL = "[party] board audit";

    public static final String BLANK_COMMAND = "PARTY_BOARD_BLANK_COMMAND";
    public static final String UNKNOWN_PLACEHOLDER = "PARTY_BOARD_UNKNOWN_PLACEHOLDER";
    public static final String MINUTES_OUT_OF_RANGE = "PARTY_BOARD_MINUTES_OUT_OF_RANGE";
    public static final String UNKNOWN_NOTE_TEXT = "PARTY_NOTE_UNKNOWN_TEXT";

    private PartyBoardValidator() {
    }

    /** The engine walk over the folded settings and notes. */
    @Nonnull
    public static List<Finding> audit() {
        try {
            List<Finding> out = audit(PartyBoardConfig.getInstance().settings());
            for (PartyNoteAsset note : PartyNoteConfig.getInstance().offered()) {
                if (!ContentKeys.known(note.textKey())) {
                    out.add(Finding.warning(DOMAIN, UNKNOWN_NOTE_TEXT, "party note '" + note.getId()
                            + "' names the TextKey '" + note.textKey() + "', which no shipped lang file holds, so "
                            + "its tile and every notice carrying it show the key", note.getId()));
                }
            }
            return out;
        } catch (Throwable t) {
            SafeLog.warn(PartyBoard.LOG + "the party board audit failed: " + t.getMessage(), t);
            return List.of();
        }
    }

    /** The pure core over the settings record every board reads. */
    @Nonnull
    public static List<Finding> audit(@Nonnull PartyBoardAsset settings) {
        List<Finding> out = new ArrayList<>();
        String id = settings.getId() == null ? PartyBoardAsset.SHARED_ID : settings.getId();
        List<String> lines = settings.announceCommandsAsWritten();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) {
                out.add(Finding.warning(DOMAIN, BLANK_COMMAND, "party board settings '" + id + "' Announce.Commands["
                        + i + "] is blank; it is skipped", id));
                continue;
            }
            List<String> unknown = PartyBoardAnnounce.unknownPlaceholders(line);
            if (!unknown.isEmpty()) {
                out.add(Finding.warning(DOMAIN, UNKNOWN_PLACEHOLDER, "party board settings '" + id
                        + "' Announce.Commands[" + i + "] names " + unknown + ", which the board does not fill, so "
                        + "it runs with them standing as written; the board fills "
                        + PartyBoardAnnounce.PLACEHOLDERS, id));
            }
        }
        Integer minutes = settings.minutesAsWritten();
        if (minutes != null && (minutes < 1 || minutes > PartyBoardAsset.MAX_MINUTES)) {
            out.add(Finding.warning(DOMAIN, MINUTES_OUT_OF_RANGE, "party board settings '" + id + "' Notices.Minutes "
                    + "is " + minutes + "; a notice stays up " + settings.minutes() + " minutes (1 to "
                    + PartyBoardAsset.MAX_MINUTES + ")", id));
        }
        return out;
    }
}
