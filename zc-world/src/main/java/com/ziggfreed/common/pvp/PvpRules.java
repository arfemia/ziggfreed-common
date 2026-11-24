package com.ziggfreed.common.pvp;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.server.core.asset.type.gameplay.GameplayConfig;
import com.ziggfreed.common.asset.EditorSchema;
import com.ziggfreed.common.loot.diminish.DiminishSpec;

/**
 * What a world does with fights between players (the PvP ledger, {@link PvpLedger}): whether a kill there is credited
 * at all, how long a hit counts toward a kill and what a kill must not be (an idle victim, one just back from death, the
 * same player again too soon), and the bounty a killing streak earns.
 *
 * <pre>{@code
 * { "Enabled": true,
 *   "Credit": { "AssistSeconds": 15, "EnvironmentSeconds": 10, "IdleSeconds": 60, "FreshSpawnSeconds": 30,
 *               "Repeat": { "Steps": [ 1, 0 ], "Window": "60m" } },
 *   "Bounty": { "Enabled": true, "At": 5, "PerKill": 20, "MaxStreak": 10, "LogoutSeconds": 10, "Announce": true } }
 * }</pre>
 *
 * <p>Native first (R695): a world's own gameplay config carries it, in its {@code Plugin} group under {@value #ID}
 * ({@code GameplayConfig.PLUGIN_CODEC}, merged over a {@code Parent} config's). A world named by a pattern takes the
 * same group from a {@link PvpRuleAsset} file, which is how a pack adds a PvP mode and how the owner retunes every
 * world on the PvP Rules screen ({@code /zigadmin}); a world's own gameplay config wins whole ({@link PvpWorldRules}).
 * Every leaf is nullable and reads its default unwritten, the numbers below the library's own {@code Default} file
 * ships, so a world no rule names runs as that file says.
 *
 * <p>Whether players may hurt each other at all is the engine's own switch ({@code WorldConfig.IsPvpEnabled}), never a
 * rule here: a hit the engine refuses (PvP off, spawn protection, a safe zone) never reaches the ledger.
 */
public final class PvpRules {

    /** The key the rules are written under in a gameplay config's {@code Plugin} group. */
    public static final String ID = "ZigPvp";

    /** A hit counts toward a kill this long, unless written. */
    public static final double DEFAULT_ASSIST_SECONDS = 15.0;

    /** A fall, lava or drowning death this soon after a player's hit is that player's kill, unless written. */
    public static final double DEFAULT_ENVIRONMENT_SECONDS = 10.0;

    /** A victim idle this long pays nothing, unless written. */
    public static final double DEFAULT_IDLE_SECONDS = 60.0;

    /** A victim killed this soon after coming back pays nothing, unless written. */
    public static final double DEFAULT_FRESH_SPAWN_SECONDS = 30.0;

    /** The same player killed again within an hour counts nothing, unless written. */
    public static final DiminishSpec DEFAULT_REPEAT = DiminishSpec.rolling(60L * 60L * 1000L, 1.0, 0.0);

    /** The streak a bounty stands at, unless written. */
    public static final int DEFAULT_BOUNTY_AT = 5;

    /** Whole percent of the fallen player's health a bounty is worth per kill in the streak, unless written. */
    public static final double DEFAULT_BOUNTY_PER_KILL = 20.0;

    /** The most kills of a streak a bounty counts, unless written. */
    public static final int DEFAULT_BOUNTY_MAX_STREAK = 10;

    /** Leaving the server this soon after a player's hit ends the streak, unless written. */
    public static final double DEFAULT_LOGOUT_SECONDS = 10.0;

    /** Rules with every leaf unwritten: every default. */
    public static final PvpRules DEFAULTS = new PvpRules();

    @Nullable protected Boolean enabled;
    @Nullable protected Credit credit;
    @Nullable protected Bounty bounty;

    public static final BuilderCodec<PvpRules> CODEC = BuilderCodec.builder(PvpRules.class, PvpRules::new)
            .documentation("What this world does with fights between players: whether a kill is credited, what a "
                    + "kill must not be, and the bounty a killing streak earns.")
            .appendInherited(new KeyedCodec<>("Enabled", Codec.BOOLEAN, false),
                    (o, v) -> o.enabled = v, o -> o.enabled, (o, p) -> o.enabled = p.enabled)
            .metadata(EditorSchema.defaultValue(true))
            .documentation("Whether a kill of one player by another is credited here at all: the kill, the assists, "
                    + "the streak and the bounty. Unauthored means true. Whether players may hurt each other is the "
                    + "world's own PvP switch, not this.").add()
            .appendInherited(new KeyedCodec<>("Credit", Credit.CODEC, false),
                    (o, v) -> o.credit = v, o -> o.credit, (o, p) -> o.credit = p.credit)
            .documentation("How a kill is credited and what it must not be.").add()
            .appendInherited(new KeyedCodec<>("Bounty", Bounty.CODEC, false),
                    (o, v) -> o.bounty = v, o -> o.bounty, (o, p) -> o.bounty = p.bounty)
            .documentation("The bounty a player's killing streak puts on them, and what ending it is worth.").add()
            .build();

    public PvpRules() {
    }

    /** The rules a gameplay config writes in its {@code Plugin} group, or null when it writes none. */
    @Nullable
    public static PvpRules of(@Nullable GameplayConfig config) {
        if (config == null) {
            return null;
        }
        Object held = config.getPluginConfig().get(PvpRules.class);
        return held instanceof PvpRules rules ? rules : null;
    }

    /** Java-side construction (a test, a consumer building rules in code); nulls stay unwritten. */
    @Nonnull
    public static PvpRules of(@Nullable Boolean enabled, @Nullable Credit credit, @Nullable Bounty bounty) {
        PvpRules rules = new PvpRules();
        rules.enabled = enabled;
        rules.credit = credit;
        rules.bounty = bounty;
        return rules;
    }

    /** Is a kill credited here? True unless written false. */
    public boolean enabled() {
        return enabled == null || enabled;
    }

    /** The {@code Credit} group, every leaf unwritten when none is. */
    @Nonnull
    public Credit credit() {
        return credit == null ? Credit.NONE : credit;
    }

    /** The {@code Bounty} group, every leaf unwritten when none is. */
    @Nonnull
    public Bounty bounty() {
        return bounty == null ? Bounty.NONE : bounty;
    }

    /** A number of seconds as milliseconds, 0 for none or one that is not a number. Pure. */
    static long millis(@Nullable Double seconds, double fallback) {
        double read = seconds == null || !Double.isFinite(seconds) ? fallback : seconds;
        return read <= 0.0 ? 0L : Math.round(read * 1000.0);
    }

    /** The {@code Credit} group: how long a hit counts, and what a kill must not be. */
    public static final class Credit {

        static final Credit NONE = new Credit();

        @Nullable protected Double assistSeconds;
        @Nullable protected Double environmentSeconds;
        @Nullable protected Double idleSeconds;
        @Nullable protected Double freshSpawnSeconds;
        @Nullable protected DiminishSpec repeat;

        public static final BuilderCodec<Credit> CODEC = BuilderCodec.builder(Credit.class, Credit::new)
                .appendInherited(new KeyedCodec<>("AssistSeconds", Codec.DOUBLE, false),
                        (o, v) -> o.assistSeconds = v, o -> o.assistSeconds, (o, p) -> o.assistSeconds = p.assistSeconds)
                .metadata(EditorSchema.defaultValue(DEFAULT_ASSIST_SECONDS))
                .documentation("How long, in seconds, a player's hit on another counts toward that player's death: "
                        + "everyone who hurt them in that time shares the kill as an assist, by the share of the "
                        + "player damage they dealt. 15 when unauthored.").add()
                .appendInherited(new KeyedCodec<>("EnvironmentSeconds", Codec.DOUBLE, false),
                        (o, v) -> o.environmentSeconds = v, o -> o.environmentSeconds,
                        (o, p) -> o.environmentSeconds = p.environmentSeconds)
                .metadata(EditorSchema.defaultValue(DEFAULT_ENVIRONMENT_SECONDS))
                .documentation("A death from a fall, lava, drowning or anything else that is no one's, this many "
                        + "seconds or fewer after a player's hit, is that player's kill. 10 when unauthored; 0 never "
                        + "credits such a death.").add()
                .appendInherited(new KeyedCodec<>("IdleSeconds", Codec.DOUBLE, false),
                        (o, v) -> o.idleSeconds = v, o -> o.idleSeconds, (o, p) -> o.idleSeconds = p.idleSeconds)
                .metadata(EditorSchema.defaultValue(DEFAULT_IDLE_SECONDS))
                .documentation("A victim who has not moved, looked around or used anything for this many seconds "
                        + "is idle, and killing them credits nothing. 60 when unauthored; 0 never calls a victim "
                        + "idle.").add()
                .appendInherited(new KeyedCodec<>("FreshSpawnSeconds", Codec.DOUBLE, false),
                        (o, v) -> o.freshSpawnSeconds = v, o -> o.freshSpawnSeconds,
                        (o, p) -> o.freshSpawnSeconds = p.freshSpawnSeconds)
                .metadata(EditorSchema.defaultValue(DEFAULT_FRESH_SPAWN_SECONDS))
                .documentation("Killing a player this many seconds or fewer after they came back from death "
                        + "credits nothing, against camping a spawn (on top of the game's own few seconds of "
                        + "protection). 30 when unauthored.").add()
                .appendInherited(new KeyedCodec<>("Repeat", DiminishSpec.CODEC, false),
                        (o, v) -> o.repeat = v, o -> o.repeat, (o, p) -> o.repeat = p.repeat)
                .documentation("How killing the same player again pays, counted for each killer and each assist on "
                        + "its own: Steps, what each kill of that player in the Window counts for in order ([1, 0] "
                        + "the first in full and nothing after it), and the Window. A kill counted at 0 credits "
                        + "nothing at all: no kill, no assist, no streak. Steps [1, 0] over 60m when unauthored.")
                .add()
                .build();

        public Credit() {
        }

        /** Java-side construction; nulls stay unwritten. */
        @Nonnull
        public static Credit of(@Nullable Double assistSeconds, @Nullable Double environmentSeconds,
                @Nullable Double idleSeconds, @Nullable Double freshSpawnSeconds, @Nullable DiminishSpec repeat) {
            Credit credit = new Credit();
            credit.assistSeconds = assistSeconds;
            credit.environmentSeconds = environmentSeconds;
            credit.idleSeconds = idleSeconds;
            credit.freshSpawnSeconds = freshSpawnSeconds;
            credit.repeat = repeat;
            return credit;
        }

        public long assistMillis() {
            return millis(assistSeconds, DEFAULT_ASSIST_SECONDS);
        }

        public long environmentMillis() {
            return millis(environmentSeconds, DEFAULT_ENVIRONMENT_SECONDS);
        }

        public long idleMillis() {
            return millis(idleSeconds, DEFAULT_IDLE_SECONDS);
        }

        public long freshSpawnMillis() {
            return millis(freshSpawnSeconds, DEFAULT_FRESH_SPAWN_SECONDS);
        }

        /** The repeat guard: the one written, else {@link #DEFAULT_REPEAT}. */
        @Nonnull
        public DiminishSpec repeat() {
            return repeat == null ? DEFAULT_REPEAT : repeat;
        }
    }

    /** The {@code Bounty} group: when a streak earns a bounty and what ending it is worth. */
    public static final class Bounty {

        static final Bounty NONE = new Bounty();

        @Nullable protected Boolean enabled;
        @Nullable protected Integer at;
        @Nullable protected Double perKill;
        @Nullable protected Integer maxStreak;
        @Nullable protected Double logoutSeconds;
        @Nullable protected Boolean announce;

        public static final BuilderCodec<Bounty> CODEC = BuilderCodec.builder(Bounty.class, Bounty::new)
                .appendInherited(new KeyedCodec<>("Enabled", Codec.BOOLEAN, false),
                        (o, v) -> o.enabled = v, o -> o.enabled, (o, p) -> o.enabled = p.enabled)
                .metadata(EditorSchema.defaultValue(true))
                .documentation("Whether a killing streak puts a bounty on a player here. Unauthored means true.").add()
                .appendInherited(new KeyedCodec<>("At", Codec.INTEGER, false),
                        (o, v) -> o.at = v, o -> o.at, (o, p) -> o.at = p.at)
                .metadata(EditorSchema.defaultValue((long) DEFAULT_BOUNTY_AT))
                .documentation("How many credited kills in a row, without dying, put a bounty on a player. 5 when "
                        + "unauthored.").add()
                .appendInherited(new KeyedCodec<>("PerKill", Codec.DOUBLE, false),
                        (o, v) -> o.perKill = v, o -> o.perKill, (o, p) -> o.perKill = p.perKill)
                .metadata(EditorSchema.defaultValue(DEFAULT_BOUNTY_PER_KILL))
                .documentation("What ending a bounty is worth for each kill in the streak, in whole percent of the "
                        + "fallen player's health (20: a fifth of their health a kill). 20 when unauthored.").add()
                .appendInherited(new KeyedCodec<>("MaxStreak", Codec.INTEGER, false),
                        (o, v) -> o.maxStreak = v, o -> o.maxStreak, (o, p) -> o.maxStreak = p.maxStreak)
                .metadata(EditorSchema.defaultValue((long) DEFAULT_BOUNTY_MAX_STREAK))
                .documentation("The most kills of a streak a bounty counts toward its worth. 10 when unauthored.")
                .add()
                .appendInherited(new KeyedCodec<>("LogoutSeconds", Codec.DOUBLE, false),
                        (o, v) -> o.logoutSeconds = v, o -> o.logoutSeconds, (o, p) -> o.logoutSeconds = p.logoutSeconds)
                .metadata(EditorSchema.defaultValue(DEFAULT_LOGOUT_SECONDS))
                .documentation("Leaving the server this many seconds or fewer after another player's hit ends the "
                        + "streak, and a bounty with it, and pays no one. 10 when unauthored; 0 never ends one.").add()
                .appendInherited(new KeyedCodec<>("Announce", Codec.BOOLEAN, false),
                        (o, v) -> o.announce = v, o -> o.announce, (o, p) -> o.announce = p.announce)
                .metadata(EditorSchema.defaultValue(true))
                .documentation("Whether everyone in the world reads a line when a bounty goes up, grows and ends. "
                        + "Unauthored means true.").add()
                .build();

        public Bounty() {
        }

        /** Java-side construction; nulls stay unwritten. */
        @Nonnull
        public static Bounty of(@Nullable Boolean enabled, @Nullable Integer at, @Nullable Double perKill,
                @Nullable Integer maxStreak, @Nullable Double logoutSeconds, @Nullable Boolean announce) {
            Bounty bounty = new Bounty();
            bounty.enabled = enabled;
            bounty.at = at;
            bounty.perKill = perKill;
            bounty.maxStreak = maxStreak;
            bounty.logoutSeconds = logoutSeconds;
            bounty.announce = announce;
            return bounty;
        }

        public boolean enabled() {
            return enabled == null || enabled;
        }

        /** The streak a bounty stands at, at least 1. */
        public int at() {
            return Math.max(1, at == null ? DEFAULT_BOUNTY_AT : at);
        }

        /** Whole percent of the fallen player's health per kill, 0 or more. */
        public double perKill() {
            double read = perKill == null || !Double.isFinite(perKill) ? DEFAULT_BOUNTY_PER_KILL : perKill;
            return Math.max(0.0, read);
        }

        /** The most kills a bounty counts, at least 1. */
        public int maxStreak() {
            return Math.max(1, maxStreak == null ? DEFAULT_BOUNTY_MAX_STREAK : maxStreak);
        }

        public long logoutMillis() {
            return millis(logoutSeconds, DEFAULT_LOGOUT_SECONDS);
        }

        public boolean announce() {
            return announce == null || announce;
        }
    }
}
