package com.ziggfreed.common.grant;

import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.hypixel.hytale.server.core.modules.entity.condition.Condition;
import com.hypixel.hytale.server.core.modules.entitystats.asset.EntityStatType;
import com.ziggfreed.common.asset.EditorSchema;

/**
 * The {@value #KIND} grant: a native stat raised by a flat amount, a percent of its base, or both, written as one
 * native {@code StaticModifier} on the stat's maximum ({@link StatGrantWriter}). The stat is any native entity stat
 * (an {@code EntityStatType} id), vanilla's or a pack's.
 *
 * <pre>{@code
 * { "Kind": "Stat", "Stat": "Health", "Add": 4 }
 * { "Kind": "Stat", "Stat": "Stamina", "Percent": 5 }
 * }</pre>
 *
 * <p>{@code Percent} is a percent of the stat's own base maximum (its asset's {@code Max}), written as a flat native
 * amount: the engine sums every multiplying modifier on a stat into one factor (two times 1.5 make times 3), so a
 * second multiplier beside gear's or an effect's would multiply wrong, while flat amounts always add up.
 *
 * <p>{@code While} binds the grant to its holder's state (R933): a list of the engine's own entity conditions (an
 * effect's {@code ApplyConditions} kind, the library's {@code ZigHolding} or {@code ZigFoesNear}, or one a mod
 * registers), every one of which must hold for the grant to count, so a weapon talent raises the shared
 * {@code CritChance} only while its weapon is in hand. Such a grant is a bound grant ({@link #bound()}): its writer
 * hands it to {@code stats.condition.BoundGrants}, which puts or takes off its modifier as the conditions start or
 * stop holding, read every tick for a player (a creature on its carrier cadence).
 *
 * <pre>{@code
 * { "Kind": "Stat", "Stat": "CritChance", "Add": 2,
 *   "While": [ { "Id": "ZigHolding", "Items": [ "ItemTag:Family=Sword", "ItemTag:Family=Longsword" ] } ] }
 * { "Kind": "Stat", "Stat": "CritChance", "Add": 1, "While": [ { "Id": "ZigHolding", "Inverse": true } ] }
 * }</pre>
 *
 * <p>The second holds while the hand is empty: {@code ZigHolding} with no knob holds while anything is held, and the
 * engine's {@code Inverse} turns any condition around.
 *
 * <p>{@code HoldSeconds} asks that the conditions have held without a break that long before the grant counts, so a
 * "while still" talent waits for the holder to have stood still a moment; it is the same knob, with the same meaning,
 * as a conditional stat file's {@code HoldSeconds}. A grant with no {@code While} always counts, so a hold alone does
 * nothing.
 *
 * <pre>{@code
 * { "Kind": "Stat", "Stat": "DamageResist", "Add": 3,
 *   "While": [ { "Id": "ZigMoving", "Inverse": true } ], "HoldSeconds": 2 }
 * }</pre>
 *
 * <p>A world rule switches it off by its kind, {@value #KIND}, or by any family its stat's own
 * {@value GrantFamilies#TAG} tag names ({@link #families()}; the library's damage and resistance stats say
 * {@value GrantFamilies#COMBAT}).
 */
public final class StatGrant extends Grant {

    /** The kind id. */
    public static final String KIND = "Stat";

    @Nullable protected String stat;
    @Nullable protected Double add;
    @Nullable protected Double percent;
    @Nullable protected Condition[] whileHolds;
    @Nullable protected Double holdSeconds;

    public static final BuilderCodec<StatGrant> CODEC = BuilderCodec.builder(StatGrant.class, StatGrant::new)
            .documentation("A native stat raised while the grant is held: a flat amount, a percent of the stat's "
                    + "base, or both, on its maximum.")
            .appendInherited(new KeyedCodec<>("Stat", Codec.STRING, false),
                    (o, v) -> o.stat = v, o -> o.stat, (o, p) -> o.stat = p.stat)
            .metadata(EditorSchema.assetRef(EntityStatType.class))
            .documentation("The native stat raised, by its id (an EntityStatType: Health, Stamina, Mana, or one a "
                    + "pack ships). A stat this server does not have raises nothing. The stat's own ZigGrantFamily "
                    + "tag names the families a world rule may switch this grant off by (Combat on the damage and "
                    + "resistance stats), beside its kind, Stat.").add()
            .appendInherited(new KeyedCodec<>("Add", Codec.DOUBLE, false),
                    (o, v) -> o.add = v, o -> o.add, (o, p) -> o.add = p.add)
            .metadata(EditorSchema.defaultValue(0.0))
            .documentation("A flat amount added to the stat's maximum each time the grant is held (a pick taken "
                    + "three times adds it three times). 0 when unauthored.").add()
            .appendInherited(new KeyedCodec<>("Percent", Codec.DOUBLE, false),
                    (o, v) -> o.percent = v, o -> o.percent, (o, p) -> o.percent = p.percent)
            .metadata(EditorSchema.defaultValue(0.0))
            .documentation("A percent of the stat's base maximum (its asset's Max) added each time the grant is "
                    + "held: 5 on Health with a base of 100 adds 5. It adds as a flat amount, so it never multiplies "
                    + "what gear or an effect adds. 0 when unauthored.").add()
            .appendInherited(new KeyedCodec<>("While", new ArrayCodec<>(Condition.CODEC, Condition[]::new), false),
                    (o, v) -> o.whileHolds = v, o -> o.whileHolds, (o, p) -> o.whileHolds = p.whileHolds)
            .documentation("Entity conditions every one of which must hold on the grant's holder for it to count "
                    + "(the engine's own, or ZigHolding, ZigFoesNear or one a mod registers), read every tick for a "
                    + "player: a weapon talent counts only while its weapon is in hand. Unauthored, the grant always "
                    + "counts.").add()
            .appendInherited(new KeyedCodec<>("HoldSeconds", Codec.DOUBLE, false),
                    (o, v) -> o.holdSeconds = v, o -> o.holdSeconds, (o, p) -> o.holdSeconds = p.holdSeconds)
            .metadata(EditorSchema.defaultValue(0.0))
            .documentation("How long, in seconds, the While conditions must have held without a break before the "
                    + "grant counts (standing still for 2 seconds). Asks nothing of a grant with no While. 0 when "
                    + "unauthored.").add()
            .build();

    public StatGrant() {
    }

    /** Java-side factory (a test, a consumer's own default); sets the same fields the codec fills. */
    @Nonnull
    public static StatGrant of(@Nullable String stat, @Nullable Double add, @Nullable Double percent) {
        StatGrant grant = new StatGrant();
        grant.stat = stat;
        grant.add = add;
        grant.percent = percent;
        return grant;
    }

    @Nonnull
    @Override
    public String kind() {
        return KIND;
    }

    /** The families the raised stat's {@value GrantFamilies#TAG} tag names, read off the loaded stat. */
    @Nonnull
    @Override
    public List<String> families() {
        return GrantFamilies.ofStat(stat());
    }

    /** The stat id as written, trimmed, or null. */
    @Nullable
    public String stat() {
        return stat == null || stat.isBlank() ? null : stat.trim();
    }

    /** The flat amount, 0 when unauthored or not a number. */
    public double add() {
        return add == null || !Double.isFinite(add) ? 0.0 : add;
    }

    /** The percent of the base, 0 when unauthored or not a number. */
    public double percent() {
        return percent == null || !Double.isFinite(percent) ? 0.0 : percent;
    }

    /** The conditions the grant counts under, never null: empty for a grant that always counts. */
    @Nonnull
    public Condition[] whileConditions() {
        return whileHolds == null ? new Condition[0] : whileHolds;
    }

    /** Whether the grant counts only while its {@code While} conditions hold. */
    public boolean bound() {
        return whileHolds != null && whileHolds.length > 0;
    }

    /** The seconds the {@code While} conditions must have held before the grant counts, 0 or more. */
    public double holdSeconds() {
        return holdSeconds == null || !Double.isFinite(holdSeconds) ? 0.0 : Math.max(0.0, holdSeconds);
    }

    /** What one hold of this grant adds to a stat whose base maximum is {@code baseMax}. */
    public double amountOn(double baseMax) {
        double base = Double.isFinite(baseMax) ? baseMax : 0.0;
        return add() + percent() / 100.0 * base;
    }
}
