package com.ziggfreed.common.ui.kit;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * What a row, pill, tile or page is saying about its state, as one meaning with one colour pair: an accent fill (the
 * row's bar, a pill's dot) and a text colour (the state word). One accent per row: a row's only colour is its tone.
 * Tones are semantic, never themed; {@code ZigTokensContrastTest} holds their colours readable.
 */
public enum Tone {

    /** No state: no accent, the state word in body ink. */
    NEUTRAL(null, ZigTokens.INK_BODY, ZigStyles.Name.STATE_NEUTRAL),
    /** In progress. */
    ACTIVE(ZigTokens.TONE_ACTIVE_FILL, ZigTokens.TONE_ACTIVE_TEXT, ZigStyles.Name.STATE_ACTIVE),
    /** Rewards waiting to be collected. */
    COLLECT(ZigTokens.TONE_COLLECT_FILL, ZigTokens.TONE_COLLECT_TEXT, ZigStyles.Name.STATE_COLLECT),
    /** Finished, earned. */
    DONE(ZigTokens.TONE_DONE_FILL, ZigTokens.TONE_DONE_TEXT, ZigStyles.Name.STATE_DONE),
    /** A season on now (green, a different word from Done). */
    LIVE(ZigTokens.TONE_LIVE_FILL, ZigTokens.TONE_LIVE_TEXT, ZigStyles.Name.STATE_LIVE),
    /** Can be taken; a season returning soon. */
    AVAILABLE(ZigTokens.TONE_AVAILABLE_FILL, ZigTokens.TONE_AVAILABLE_TEXT, ZigStyles.Name.STATE_AVAILABLE),
    /** Waiting out a cooldown. */
    WAITING(ZigTokens.TONE_WAITING_FILL, ZigTokens.TONE_WAITING_TEXT, ZigStyles.Name.STATE_WAITING),
    /** Locked, not yet, between seasons. */
    BLOCKED(ZigTokens.TONE_BLOCKED_FILL, ZigTokens.TONE_BLOCKED_TEXT, ZigStyles.Name.STATE_BLOCKED),
    /** Abandon, refused. */
    DANGER(ZigTokens.TONE_DANGER_FILL, ZigTokens.TONE_DANGER_TEXT, ZigStyles.Name.STATE_DANGER);

    private final String fillHex;
    private final String textHex;
    private final ZigStyles.Name stateStyle;

    Tone(@Nullable String fillHex, @Nonnull String textHex, @Nonnull ZigStyles.Name stateStyle) {
        this.fillHex = fillHex;
        this.textHex = textHex;
        this.stateStyle = stateStyle;
    }

    /** The accent fill ({@code #rrggbb}), or null for {@link #NEUTRAL}, which has no accent. */
    @Nullable
    public String fillHex() {
        return fillHex;
    }

    /** The state word's colour ({@code #rrggbb}). */
    @Nonnull
    public String textHex() {
        return textHex;
    }

    /** The state-word style a painter swaps onto a {@code #State} label. */
    @Nonnull
    public ZigStyles.Name stateStyle() {
        return stateStyle;
    }
}
