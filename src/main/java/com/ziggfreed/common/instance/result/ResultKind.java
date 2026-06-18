package com.ziggfreed.common.instance.result;

/**
 * The outcome banner of a finished instance, game-agnostic so the results screen can tint
 * it without knowing the game's own outcome enum. The consumer maps its outcome (e.g.
 * Kweebec's ESCAPED/CAUGHT/TIMED_OUT) onto one of these.
 */
public enum ResultKind {

    WIN,
    LOSS,
    DRAW,
    ABORT;

    public boolean isWin() {
        return this == WIN;
    }
}
