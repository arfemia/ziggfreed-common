package com.ziggfreed.common.loot.reward;

import java.util.Locale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.schema.config.BooleanSchema;
import com.hypixel.hytale.codec.schema.config.IntegerSchema;
import com.hypixel.hytale.codec.schema.config.NumberSchema;
import com.hypixel.hytale.codec.schema.config.Schema;
import com.hypixel.hytale.codec.schema.config.StringSchema;
import com.ziggfreed.common.asset.EditorPick;

/**
 * One parameter a reward kind reads, described for the in-game Asset Editor: its name as a reward's
 * {@code Params} writes it, what shape of value it takes, what it means, whether a reward must name it,
 * what stands in when one leaves it out, and the picker that offers its value. A kind's parameters
 * together ({@link RewardParams}) are the form a reward row of that kind shows in the editor.
 *
 * <p><b>Description only.</b> A reward's {@code Params} still decodes as the open map it always was (a
 * number or true/false written bare or in quotes), and the kind's handler still reads what it reads;
 * nothing here refuses a value.
 */
public record RewardParam(@Nonnull String name, @Nonnull Type type, @Nullable String documentation,
                          boolean required, @Nullable String defaultValue, @Nullable EditorPick pick) {

    /** The shape a parameter's value takes, as the editor's control for it. */
    public enum Type {
        /** Any text: an id, a pattern, a line. The default. */
        TEXT("Text"),
        /** A whole number. */
        INTEGER("Integer"),
        /** A number that may have a fraction. */
        NUMBER("Number"),
        /** True or false. */
        BOOLEAN("Boolean");

        private final String authored;

        Type(@Nonnull String authored) {
            this.authored = authored;
        }

        /** The word a kind file writes for this shape. */
        @Nonnull
        public String authored() {
            return authored;
        }

        /** The shape {@code written} names, matched without regard to case; text for anything else. */
        @Nonnull
        public static Type fromAuthored(@Nullable String written) {
            if (written != null) {
                String folded = written.trim().toLowerCase(Locale.ROOT);
                for (Type type : values()) {
                    if (type.authored.toLowerCase(Locale.ROOT).equals(folded)) {
                        return type;
                    }
                }
            }
            return TEXT;
        }
    }

    public RewardParam {
        name = name.trim();
    }

    /** A text parameter. */
    @Nonnull
    public static RewardParam text(@Nonnull String name, @Nullable String documentation) {
        return new RewardParam(name, Type.TEXT, documentation, false, null, null);
    }

    /** A whole-number parameter. */
    @Nonnull
    public static RewardParam integer(@Nonnull String name, @Nullable String documentation) {
        return new RewardParam(name, Type.INTEGER, documentation, false, null, null);
    }

    /** A parameter taking a number that may have a fraction. */
    @Nonnull
    public static RewardParam number(@Nonnull String name, @Nullable String documentation) {
        return new RewardParam(name, Type.NUMBER, documentation, false, null, null);
    }

    /** A true-or-false parameter. */
    @Nonnull
    public static RewardParam bool(@Nonnull String name, @Nullable String documentation) {
        return new RewardParam(name, Type.BOOLEAN, documentation, false, null, null);
    }

    /** This parameter, which a reward must name. */
    @Nonnull
    public RewardParam asRequired() {
        return new RewardParam(name, type, documentation, true, defaultValue, pick);
    }

    /** This parameter, with {@code value} standing in when a reward leaves it out. */
    @Nonnull
    public RewardParam withDefault(@Nullable String value) {
        return new RewardParam(name, type, documentation, required, value, pick);
    }

    /** This parameter, its value offered through {@code offered}. */
    @Nonnull
    public RewardParam withPick(@Nullable EditorPick offered) {
        return new RewardParam(name, type, documentation, required, defaultValue, offered);
    }

    /** A whole number in quotes, as {@code RewardSpec.longParam} reads one. */
    static final String QUOTED_INTEGER = "^[-+]?[0-9]+$";

    /** A number in quotes, as {@code RewardSpec.doubleParam} reads one. */
    static final String QUOTED_NUMBER = "^[-+]?([0-9]+(\\.[0-9]*)?|\\.[0-9]+)([eE][-+]?[0-9]+)?$";

    /** True or false in quotes, in any case, as {@code RewardSpec.flagParam} reads one. */
    static final String QUOTED_BOOLEAN = "^([Tt][Rr][Uu][Ee]|[Ff][Aa][Ll][Ss][Ee])$";

    /**
     * The editor's control for this parameter: its shape, its sentence, its default as that shape (a
     * default that does not read as one is left off), and its picker on a text value. A number or a
     * true/false also takes the same value in quotes, since a reward's {@code Params} decode the two alike
     * and most shipped rewards quote theirs ({@code "Count": "1"}).
     */
    @Nonnull
    public Schema toSchema() {
        Schema schema = switch (type) {
            case INTEGER -> {
                IntegerSchema s = new IntegerSchema();
                Long value = parseLong(defaultValue);
                if (value != null && value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE) {
                    s.setDefault(value.intValue());
                }
                yield orQuoted(s, QUOTED_INTEGER, true);
            }
            case NUMBER -> {
                NumberSchema s = new NumberSchema();
                Double value = parseDouble(defaultValue);
                if (value != null) {
                    s.setDefault(value);
                }
                yield orQuoted(s, QUOTED_NUMBER, true);
            }
            case BOOLEAN -> {
                BooleanSchema s = new BooleanSchema();
                if (defaultValue != null && !defaultValue.isBlank()) {
                    s.setDefault(Boolean.parseBoolean(defaultValue.trim()));
                }
                yield orQuoted(s, QUOTED_BOOLEAN, false);
            }
            case TEXT -> {
                StringSchema s = new StringSchema();
                if (defaultValue != null) {
                    s.setDefault(defaultValue);
                }
                if (pick != null) {
                    pick.applyTo(s);
                }
                yield s;
            }
        };
        String sentence = documentation == null || documentation.isBlank() ? null : documentation.trim();
        if (required) {
            sentence = sentence == null ? "A reward must name this." : sentence + " A reward must name this.";
        }
        if (sentence != null) {
            schema.setMarkdownDescription(sentence);
        }
        return schema;
    }

    /**
     * {@code typed}, or the same value written in quotes as {@code pattern} spells it: the engine's own shape
     * for a value its codec takes either way ({@code FloatCodec}: an {@code anyOf} of the number and a
     * patterned string, the editor's number control named on it), the default kept on the typed arm.
     */
    @Nonnull
    private static Schema orQuoted(@Nonnull Schema typed, @Nonnull String pattern, boolean numeric) {
        StringSchema quoted = new StringSchema();
        quoted.setPattern(pattern);
        Schema either = Schema.anyOf(typed, quoted);
        if (numeric) {
            either.getHytale().setType("Number");
        }
        return either;
    }

    @Nullable
    private static Long parseLong(@Nullable String written) {
        if (written == null || written.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(written.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Nullable
    private static Double parseDouble(@Nullable String written) {
        if (written == null || written.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(written.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
