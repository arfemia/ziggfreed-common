package com.ziggfreed.common.validation;

/**
 * How much a {@link Finding} matters. Three tiers, because a content audit answers three different
 * questions and collapsing them costs the reader the one thing a report is for - knowing what to
 * fix first.
 *
 * <ul>
 *   <li>{@link #ERROR} - the authored thing cannot work. A quest that can never be finished, a
 *       formula that can never resolve, an id a save format cannot store. Nothing downstream is
 *       going to rescue it.</li>
 *   <li>{@link #WARNING} - it may well work, but only if something outside this file shows up: a
 *       factor another mod registers, a kind another mod fires. An author who meant "this applies
 *       only where that mod is installed" reads a warning and moves on; an author who typed the id
 *       wrong reads the same line and fixes it.</li>
 *   <li>{@link #INFO} - it works, and the file could still say what it means more plainly. Two
 *       knobs authored where only one is read, a redundant entry. Never a defect.</li>
 * </ul>
 *
 * <p>The split between ERROR and WARNING is deliberate and load-bearing across every validator in
 * this library: <b>an unknown id is a WARNING, never an error</b>, because whichever mod owns it
 * registers it at its own setup, which may run after the audit and may be a mod the author expects
 * some servers not to install.
 */
public enum Severity {

    /** The authored thing cannot work, whatever else is installed. */
    ERROR,

    /** It works only if something outside this file turns up. */
    WARNING,

    /** It works; the file could just say what it means more plainly. */
    INFO;

    /**
     * Is this a tier worth counting as something to fix? {@link #ERROR} and {@link #WARNING} are;
     * {@link #INFO} is not, so a "3 problems" headline never inflates itself with remarks.
     */
    public boolean isProblem() {
        return this != INFO;
    }
}
