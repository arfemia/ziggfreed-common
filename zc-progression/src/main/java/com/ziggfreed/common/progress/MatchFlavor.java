package com.ziggfreed.common.progress;

/**
 * Which of the two objective-matching dialects an engine (or a single matching call) runs.
 *
 * <p><b>These cannot be unified, and trying is a data-loss bug.</b> Both dialects have shipped
 * against live authored content, and they disagree on three separate points - target case
 * sensitivity, what an EMPTY authored target means, and what an EMPTY authored qualifier means. A
 * merged rule would either start matching content that deliberately never matched (an empty target
 * suddenly becoming match-all under {@link #STRICT}) or stop matching content that always did (a
 * differently-cased id under {@link #LENIENT}). So both are carried verbatim and the consumer picks
 * per engine or per call.
 *
 * <p>The exact comparisons live in {@link ObjectiveMatch}.
 */
public enum MatchFlavor {

    /**
     * Exact-id matching: targets compare case-SENSITIVELY, an empty authored target matches only an
     * empty identifier, and an empty authored qualifier matches an event whose qualifier is absent
     * OR empty. Pick this when a target is an asset id the author copied verbatim.
     */
    STRICT,

    /**
     * Forgiving matching: targets compare case-INSENSITIVELY, an empty authored target matches
     * EVERYTHING (the match-all shorthand), and an empty authored qualifier matches only an event
     * with no qualifier at all. Pick this for broad "any of this kind of thing" tallies.
     */
    LENIENT
}
