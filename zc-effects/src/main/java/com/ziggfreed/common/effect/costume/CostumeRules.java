package com.ziggfreed.common.effect.costume;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * What counts as a costume, and when one may be put on somebody. Pure: every answer is read off two
 * facts about each effect, so the costume Type, the take-off command and a test all decide alike.
 *
 * <p><b>A costume is an effect that changes the wearer's model and is not a debuff.</b> That rule is
 * what lets a wearer always take one off: a transformation a fight puts on somebody is authored a
 * debuff, so it is never a costume, its wearer cannot shrug it off, and no costume goes over it.
 *
 * <p><b>One costume at a time.</b> The engine shows the first model change an entity receives and
 * keeps the model from before it to restore later. Taking one costume off and putting another on in
 * the same tick would record the first costume's model as the one to restore, so a different costume
 * is refused while one is worn. The same costume again is refused as already worn: the costume runs
 * on as its asset says, and a re-dress pays nothing.
 */
public final class CostumeRules {

    /** One effect, active or wanted, reduced to what a costume decision reads. */
    public record Look(@Nonnull String effectId, @Nullable String modelChange, boolean debuff) {

        /** Does this effect change the wearer's model? */
        public boolean changesModel() {
            return modelChange != null && !modelChange.isBlank();
        }

        /** A costume: it changes the model and is not a debuff, so its wearer may take it off. */
        public boolean isCostume() {
            return changesModel() && !debuff;
        }

        /** A transformation its wearer may not take off: it changes the model and is a debuff. */
        public boolean locksModel() {
            return changesModel() && debuff;
        }
    }

    /** What putting a costume on somebody comes to. */
    public enum Verdict {
        /** Put it on. */
        DRESS,
        /** The effect is no costume: it changes no model, or it is a debuff. */
        NOT_A_COSTUME,
        /** The wearer is under a transformation they cannot take off. */
        LOCKED,
        /** The wearer already wears a different costume. */
        WEARING_ANOTHER,
        /** The wearer already wears this very costume, which is refused so that a re-dress pays nothing. */
        ALREADY_WORN
    }

    private CostumeRules() {
    }

    /**
     * Whether {@code wanted} may be put on somebody wearing {@code wearing}. When several refusals hold,
     * the one {@link Verdict} lists first wins, whatever order the effects went on in.
     */
    @Nonnull
    public static Verdict dress(@Nonnull Look wanted, @Nonnull List<Look> wearing) {
        if (!wanted.isCostume()) {
            return Verdict.NOT_A_COSTUME;
        }
        for (Look look : wearing) {
            if (look.locksModel()) {
                return Verdict.LOCKED;
            }
        }
        for (Look look : wearing) {
            if (look.isCostume() && !look.effectId().equalsIgnoreCase(wanted.effectId())) {
                return Verdict.WEARING_ANOTHER;
            }
        }
        for (Look look : wearing) {
            if (look.isCostume() && look.effectId().equalsIgnoreCase(wanted.effectId())) {
                return Verdict.ALREADY_WORN;
            }
        }
        return Verdict.DRESS;
    }

    /** The ids of every costume in {@code wearing}, each once: what taking costumes off removes. */
    @Nonnull
    public static List<String> costumesIn(@Nonnull List<Look> wearing) {
        List<String> out = new ArrayList<>();
        for (Look look : wearing) {
            if (look.isCostume() && out.stream().noneMatch(id -> id.equalsIgnoreCase(look.effectId()))) {
                out.add(look.effectId());
            }
        }
        return List.copyOf(out);
    }

    /** Is the player dressing somebody the very player they would dress? An unknown side is no. */
    public static boolean dressingYourself(@Nullable UUID dresser, @Nullable UUID wearer) {
        return dresser != null && dresser.equals(wearer);
    }
}
