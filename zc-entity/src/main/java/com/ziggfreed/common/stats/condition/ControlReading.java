package com.ziggfreed.common.stats.condition;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * What a foe's rules say of one kind of crowd control ({@link BossRules#reading}): it lands from anyone, a talent's
 * included; it lands as usual (an ability's own control at the cast's own strength, never a talent's); or it lands
 * from nothing.
 */
public enum ControlReading {

    /** It lands from anyone, a talent's included. */
    LANDS,
    /** An ability's own lands, at the cast's own strength; a talent's or a proc's added one does not. */
    USUAL,
    /** It lands from nothing, the game's own controls included. */
    SHRUGS;

    /** Does a control of this reading land, {@code byTalent} saying a talent or a proc added it to the move? */
    public boolean lands(boolean byTalent) {
        return this == LANDS || (this == USUAL && !byTalent);
    }

    /**
     * The layer order, top first: a timed rule effect the foe wears ({@code window}), an infinite one
     * ({@code standing}), the tags on its own NPC groups ({@code own}), and for a boss or an elite the family default
     * ({@code family}, the tags on the library's boss group); the first layer naming {@code kind} answers, its
     * {@code Shrugs} over its {@code Lands}. Below every layer, structural: a boss or an elite reads {@link #USUAL},
     * anything else {@link #LANDS}. A null {@code kind} (a control of no kind) reads structural alone; a null layer
     * names nothing. Pure.
     */
    @Nonnull
    public static ControlReading resolve(@Nullable ControlKind kind, boolean bossOrElite, @Nullable ControlRule window,
            @Nullable ControlRule standing, @Nullable ControlRule own, @Nullable ControlRule family) {
        if (kind != null) {
            ControlReading named = first(kind, window, standing, own, bossOrElite ? family : null);
            if (named != null) {
                return named;
            }
        }
        return bossOrElite ? USUAL : LANDS;
    }

    @Nullable
    private static ControlReading first(@Nonnull ControlKind kind, @Nullable ControlRule... layers) {
        for (ControlRule layer : layers) {
            ControlReading answer = layer == null ? null : layer.answer(kind);
            if (answer != null) {
                return answer;
            }
        }
        return null;
    }
}
