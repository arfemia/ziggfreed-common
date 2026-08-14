package com.ziggfreed.common.quest;

import java.util.Locale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * WHERE a quest may be completed and collected, when it may not be collected just anywhere.
 *
 * <p>Its ABSENCE is the default and is spelled as a null on {@link Quest#turnInAt()}, the same shape
 * {@link Quest.Repeat} uses: a quest carrying no site is claimable at any surface a consumer offers -
 * a log, a book, a menu - and there is nothing to enforce. Carrying one means the player has to be
 * SOMEWHERE, and the engine refuses the completion anywhere else.
 *
 * <p>Two forms, and they are a discriminated choice rather than a bundle of switches:
 * <ul>
 *   <li>{@link Kind#CHARACTER} - one character, named by id. The id is already RESOLVED here: an
 *   authoring layer that lets a file say "whoever gave me this" resolves that to the giver's id
 *   before building the quest, so the engine never carries an unresolved sentinel. A null id is
 *   therefore a quest that named a character nobody can be, and it matches nowhere - a content
 *   audit is what tells the author, since silently making it claimable anywhere would hide the
 *   mistake behind working behaviour;</li>
 *   <li>{@link Kind#ACCEPT_SITE} - wherever the player TOOK it, whatever that was: a character, a
 *   notice board, a terminal. Nothing is named because nothing can be; the id is recorded when the
 *   quest is accepted (see {@link QuestEngine#accept(com.ziggfreed.common.subject.Subject, Quest,
 *   String)}) and read back at completion. This is the form for content that is handed out at many
 *   identical places and must come back to the one it came from.</li>
 * </ul>
 *
 * <p><b>Matching is by id, case-insensitively</b>, exactly as the objective-level hand-in lock
 * matches. One character answering to several ids is resolved ABOVE the engine: the caller asks
 * once per id the character answers to, which is what keeps this module free of any identity
 * registry. The accept-site form is matched by plain id equality and nothing else - what a player
 * took a quest from need not be a character at all, so there is no alias set to consult.
 */
public record QuestTurnInSite(@Nonnull Kind kind, @Nullable String id) {

    /** Which question the site asks. Not a mode: neither value carries a knob the other lacks. */
    public enum Kind {

        /** One named character, whoever they are and wherever they stand. */
        CHARACTER,

        /** Wherever this player took the quest from, recorded at the moment they took it. */
        ACCEPT_SITE
    }

    /** Wherever the player took it. There is nothing to name, so there is one instance. */
    public static final QuestTurnInSite ACCEPT_SITE = new QuestTurnInSite(Kind.ACCEPT_SITE, null);

    public QuestTurnInSite {
        id = id == null || id.isBlank() ? null : id.trim();
    }

    /**
     * This quest comes back to {@code npcId}. A null or blank id builds a site nothing can satisfy,
     * which is the honest reading of "return it to the giver" on a quest nobody gives out.
     */
    @Nonnull
    public static QuestTurnInSite character(@Nullable String npcId) {
        return new QuestTurnInSite(Kind.CHARACTER, npcId);
    }

    /** Is this the wherever-you-took-it form? */
    public boolean isAcceptSite() {
        return kind == Kind.ACCEPT_SITE;
    }

    /**
     * Does {@code atId} satisfy this site for a player who accepted the quest at
     * {@code acceptedAtSiteId}?
     *
     * <p>Blank in, false out, on every argument that matters: a completion with nowhere named is a
     * claim from a surface with no place attached (a log, a book), which is exactly what a site-bound
     * quest refuses. A {@link Kind#ACCEPT_SITE} quest whose recorded site is missing therefore
     * matches NOWHERE - progress recorded before the quest carried this form, or an accept from a
     * surface that named no place, both read that way, and re-taking the quest records one.
     */
    public boolean matches(@Nullable String atId, @Nullable String acceptedAtSiteId) {
        String at = normalize(atId);
        if (at == null) {
            return false;
        }
        String required = kind == Kind.ACCEPT_SITE ? normalize(acceptedAtSiteId) : normalize(id);
        return required != null && required.equals(at);
    }

    @Nullable
    private static String normalize(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
