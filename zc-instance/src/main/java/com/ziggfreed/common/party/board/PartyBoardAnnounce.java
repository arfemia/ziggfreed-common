package com.ziggfreed.common.party.board;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.ziggfreed.common.command.CommandRunner;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.i18n.PlainText;
import com.ziggfreed.common.instance.dungeon.DungeonAsset;
import com.ziggfreed.common.instance.dungeon.DungeonConfig;
import com.ziggfreed.common.instance.dungeon.tier.TierNames;
import com.ziggfreed.common.ui.name.PlayerDisplayNames;
import com.ziggfreed.common.util.SafeLog;

/**
 * A notice's announcement, run only when its poster ticked the box ({@link PartyBoard#post}): the board's own line to
 * every player online when the owner leaves {@code Announce.Message} on (the default), then the owner's
 * {@code Announce.Commands}, each run as the server console through the library's one runner of authored lines
 * ({@link CommandRunner}); no runner of its own.
 *
 * <p><b>The placeholders</b> ({@link #PLACEHOLDERS}) are filled from the notice's own fields, never from anything a
 * player typed (a notice has no free text), and each is made safe for a command line before it goes in, since the
 * engine splits a line into words at spaces and reads quotes, a backslash and the list brackets specially:
 * <ul>
 *   <li>{@code {player}}: the poster's login name, only when it is a plain one ({@link #loginToken}: letters, digits
 *       and underscores, 1 to 32); any other name runs NO line, since changing it could name someone else.</li>
 *   <li>{@code {uuid}}: the poster's id, a UUID the server made. {@code {wants}}: a whole number from 1 to 4.</li>
 *   <li>{@code {dungeon}}, {@code {tier}}: the board's dungeon and the notice's depth or daily, file ids
 *       ({@link #idToken}: letters, digits, underscores, dots and inner dashes, else empty).</li>
 *   <li>{@code {dungeon_name}}, {@code {for}}, {@code {place}}, {@code {notice}}: words in the server's language,
 *       from pack files and the poster's plain name ({@link #words}): letters, digits, single spaces and plain
 *       punctuation only (no comma), an apostrophe turned typographic and a dash never opening a word, so none of them
 *       can close a quote, escape a character, open or split a list or start a {@code --flag}. Each may hold spaces,
 *       so an owner puts it where a line takes the rest of its words (a message), never in a one-word slot.</li>
 * </ul>
 * The runner substitutes in one pass and never reads a value again, so a value cannot name a second placeholder.
 */
public final class PartyBoardAnnounce {

    /** Every placeholder an announce line may name. */
    public static final List<String> PLACEHOLDERS = List.of("player", "uuid", "wants", "dungeon", "tier",
            "dungeon_name", "for", "place", "notice");

    /** The longest text a words placeholder carries. */
    static final int MAX_WORDS = 200;

    /** The longest login name a line takes. */
    static final int MAX_LOGIN = 32;

    private static final String LOG = "[party] ";

    /** Logs the first refused name once, never one line per notice. */
    private static final AtomicBoolean NAME_REFUSAL_LOGGED = new AtomicBoolean(false);

    private PartyBoardAnnounce() {
    }

    /** Announce {@code notice}: the line to everyone online, then the owner's commands. Never throws. */
    static void announce(@Nonnull PartyNotice notice) {
        PartyBoardAsset settings = PartyBoardConfig.getInstance().settings();
        try {
            if (settings.announceMessage()) {
                Universe universe = Universe.get();
                if (universe != null) {
                    universe.sendMessage(message(notice));
                }
            }
        } catch (Throwable t) {
            SafeLog.warn(LOG + "a notice's announcement could not be sent", t);
        }
        List<String> lines = settings.announceCommands();
        if (lines.isEmpty()) {
            return;
        }
        try {
            PlayerRef poster = Universe.get().getPlayer(notice.poster());
            // NAME-DATA-OK: a command line names the poster by the login name the server's commands read
            String login = loginToken(poster == null ? null : poster.getUsername());
            if (login == null) {
                if (NAME_REFUSAL_LOGGED.compareAndSet(false, true)) {
                    SafeLog.warn(LOG + "a notice's announce commands did not run: its poster's name is not a plain "
                            + "login name (letters, digits and underscores), so it was kept out of the command line "
                            + "(logged once)");
                }
                return;
            }
            CommandRunner.runAll(lines, placeholders(notice, login), failure -> SafeLog.warn(LOG + failure));
        } catch (Throwable t) {
            SafeLog.warn(LOG + "a notice's announce commands could not run", t);
        }
    }

    /** The line everyone online reads: who wants how many more, for what, and where to answer. */
    @Nonnull
    static Message message(@Nonnull PartyNotice notice) {
        String name = PlayerDisplayNames.plainName(notice.poster(), null);
        Message dungeonName = PartyBoardText.dungeonName(notice.dungeon());
        DungeonAsset aim = notice.aim() == null ? null : DungeonConfig.getInstance().resolve(notice.aim());
        Message wants;
        if (dungeonName == null) {
            wants = PartyBoardText.line("announce.any", name, notice.wants());
        } else if (aim == null) {
            wants = PartyBoardText.line("announce.dungeon", name, notice.wants(), dungeonName);
        } else if (aim.seedDaily()) {
            wants = PartyBoardText.line("announce.daily", name, notice.wants(), dungeonName);
        } else {
            wants = PartyBoardText.line("announce.tier", name, notice.wants(), dungeonName,
                    TierNames.word(aim));
        }
        Message place = PartyBoardText.place(notice.place());
        Message where = place == null ? PartyBoardText.line("announce.board") : PartyBoardText.line("announce.at", place);
        return PartyBoardText.line("announce.line", wants, where);
    }

    /** The placeholders a notice fills, each made safe; {@code login} already passed {@link #loginToken}. */
    @Nonnull
    static Map<String, String> placeholders(@Nonnull PartyNotice notice, @Nonnull String login) {
        Message dungeonName = PartyBoardText.dungeonName(notice.dungeon());
        Message place = PartyBoardText.place(notice.place());
        String plainName = PlayerDisplayNames.plainName(notice.poster(), null);
        return fill(login, notice.poster(), notice.wants(), notice.dungeon(), notice.aim(),
                dungeonName == null ? "" : PlainText.of(dungeonName),
                PlainText.of(PartyBoardText.aim(notice.aim())),
                place == null ? "" : PlainText.of(place),
                PlainText.of(PartyBoardText.title(Msg.raw(plainName), notice.aim(), notice.wants())));
    }

    /**
     * The pure core: every placeholder from already-read values, each made safe. {@code login} must already be a
     * {@link #loginToken}; the rest are made safe here.
     */
    @Nonnull
    static Map<String, String> fill(@Nonnull String login, @Nonnull UUID uuid, int wants, @Nullable String dungeon,
            @Nullable String tier, @Nullable String dungeonName, @Nullable String forWords, @Nullable String place,
            @Nullable String notice) {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("player", login);
        out.put("uuid", uuid.toString());
        out.put("wants", Integer.toString(Math.max(1, Math.min(PartyNotice.MAX_WANTS, wants))));
        out.put("dungeon", idToken(dungeon));
        out.put("tier", idToken(tier));
        out.put("dungeon_name", words(dungeonName));
        out.put("for", words(forWords));
        out.put("place", words(place));
        out.put("notice", words(notice));
        return out;
    }

    /** A login name as one command word: letters, digits and underscores, 1 to {@value #MAX_LOGIN}; else null. */
    @Nullable
    static String loginToken(@Nullable String name) {
        if (name == null || name.isEmpty() || name.length() > MAX_LOGIN) {
            return null;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean plain = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_';
            if (!plain) {
                return null;
            }
        }
        return name;
    }

    /**
     * A file id as one command word: letters, digits, underscores, dots and dashes, no dash first (so it never reads
     * as a flag); anything else, or none, is empty.
     */
    @Nonnull
    static String idToken(@Nullable String id) {
        if (id == null || id.isBlank()) {
            return "";
        }
        String trimmed = id.trim();
        if (trimmed.length() > MAX_WORDS || trimmed.charAt(0) == '-') {
            return "";
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            boolean plain = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_'
                    || c == '.' || c == '-';
            if (!plain) {
                return "";
            }
        }
        return trimmed;
    }

    /**
     * Words for a command line: letters and digits of any script, single spaces, and {@code . : ! ? ( ) -} only; an
     * apostrophe becomes the typographic one (the engine reads a plain one opening a word as a quote), a dash is kept
     * only inside a word (never opening one, so never a flag), every other character (quotes, a backslash, the list
     * brackets and the comma the engine splits a list on, braces, control characters and line breaks) is left out,
     * and the whole is cut to {@value #MAX_WORDS} characters.
     */
    @Nonnull
    static String words(@Nullable String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(Math.min(text.length(), MAX_WORDS));
        boolean wordStart = true;
        for (int i = 0; i < text.length() && out.length() < MAX_WORDS; i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c) || Character.isISOControl(c)) {
                if (!wordStart) {
                    out.append(' ');
                    wordStart = true;
                }
                continue;
            }
            char kept;
            if (Character.isLetterOrDigit(c) || ".:!?()".indexOf(c) >= 0) {
                kept = c;
            } else if (c == '\'' || c == '’') {
                kept = '’';
            } else if (c == '-' && !wordStart) {
                kept = '-';
            } else {
                continue;
            }
            out.append(kept);
            wordStart = false;
        }
        int end = out.length();
        while (end > 0 && out.charAt(end - 1) == ' ') {
            end--;
        }
        return out.substring(0, end);
    }

    /** The placeholders {@code line} names that the board does not fill, lower-cased, in the order written. */
    @Nonnull
    static List<String> unknownPlaceholders(@Nullable String line) {
        List<String> out = new ArrayList<>();
        if (line == null) {
            return out;
        }
        int at = 0;
        while (true) {
            int open = line.indexOf('{', at);
            if (open < 0) {
                return out;
            }
            int close = line.indexOf('}', open + 1);
            if (close < 0) {
                return out;
            }
            String key = line.substring(open + 1, close);
            if (!key.isBlank() && !PLACEHOLDERS.contains(key)) {
                String folded = key.toLowerCase(Locale.ROOT);
                if (!out.contains(folded)) {
                    out.add(folded);
                }
            }
            at = close + 1;
        }
    }
}
