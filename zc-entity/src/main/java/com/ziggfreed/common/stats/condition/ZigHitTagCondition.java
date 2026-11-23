package com.ziggfreed.common.stats.condition;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.modules.entity.condition.Condition;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * An entity condition that holds only on a hit whose interaction carries one of its {@code Tags} in its native
 * {@code Tags} {@code Hit} list ({@link HitReads#carriesTag}: the damage node's own tags, merged with its {@code Parent}'s
 * and every asset containing it), and never outside a hit, whatever its {@code Inverse} says. Registered into the
 * engine's entity condition map as {@value #TYPE_NAME} (R700). It is hit-aware ({@link HitAwareCondition}), so a bound
 * grant naming it never sits on its stat: the bound hit term pays the grant on each hit that carries the tag where its
 * other conditions hold, and a proc whose {@code Holder} names it fires only on such a hit. {@code Inverse} turns the
 * hit's answer around: a hit carrying none of them. So a mod tells one attack from another by a tag on the attack's own
 * damage file (or on the file it takes as its {@code Parent}), with no damage type or stat of its own.
 *
 * <pre>{@code
 * // the attack's damage file:  "Tags": { "Hit": [ "Full_Draw" ] }
 * { "Kind": "Stat", "Stat": "DamageBonus", "Add": 8,
 *   "While": [ { "Id": "ZigHitTag", "Tags": [ "Full_Draw" ] } ] }
 * }</pre>
 */
public final class ZigHitTagCondition extends Condition implements HitAwareCondition {

    /** The authored {@code "Id"} name. */
    public static final String TYPE_NAME = "ZigHitTag";

    @Nullable
    private String[] tags;

    /** {@link #tags} trimmed, blanks dropped, on the first read. */
    @Nullable
    private volatile List<String> asked;

    private static final class Holder {
        static final BuilderCodec<ZigHitTagCondition> CODEC = BuilderCodec.builder(
                        ZigHitTagCondition.class, ZigHitTagCondition::new, Condition.BASE_CODEC)
                .documentation("Holds only on a hit whose interaction carries one of these tags in its Tags Hit list, "
                        + "never outside a hit: a bound grant naming it counts hit by hit and never sits on its stat. "
                        + "Inverse: a hit carrying none of them.")
                .append(new KeyedCodec<>("Tags", Codec.STRING_ARRAY, false), (c, v) -> c.tags = v, c -> c.tags)
                .documentation("The hit tags, any one of which counts, matched without regard to case: the values of "
                        + "\"Tags\": { \"Hit\": [ ... ] } on the damage file that dealt the hit, or on a file it takes "
                        + "as its Parent. No hit counts when unauthored.")
                .add()
                .build();
    }

    /** The codec, built on first call. Only registration and a codec test invoke this. */
    @Nonnull
    public static BuilderCodec<ZigHitTagCondition> codec() {
        return Holder.CODEC;
    }

    /**
     * Make the engine's entity conditions know this kind when nothing has registered it yet: a consumer's file read
     * before setup (a unit-test JVM, which never runs setup) still decodes. On a server the library's setup has
     * registered it first, so this does nothing. Idempotent.
     */
    public static void ensureKnown() {
        if (Condition.CODEC.getCodecFor(TYPE_NAME) == null) {
            Condition.CODEC.register(TYPE_NAME, ZigHitTagCondition.class, codec());
        }
    }

    /** Outside a hit it never holds, its {@code Inverse} included: there is no hit to carry a tag. */
    @Override
    public boolean eval(@Nonnull ComponentAccessor<EntityStore> componentAccessor, @Nonnull Ref<EntityStore> ref,
            @Nonnull Instant currentTime) {
        return false;
    }

    @Override
    public boolean eval0(@Nonnull ComponentAccessor<EntityStore> componentAccessor, @Nonnull Ref<EntityStore> ref,
            @Nonnull Instant currentTime) {
        return false;
    }

    @Override
    public boolean evalOnHit(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> attacker,
            @Nonnull Damage damage, @Nonnull Instant now) {
        return inverse != HitReads.carriesTag(damage, asked());
    }

    /** The tags as written, trimmed, blanks dropped. */
    @Nonnull
    List<String> asked() {
        List<String> cached = asked;
        if (cached != null) {
            return cached;
        }
        List<String> out = new ArrayList<>();
        if (tags != null) {
            for (String tag : tags) {
                if (tag != null && !tag.isBlank()) {
                    out.add(tag.trim());
                }
            }
        }
        asked = List.copyOf(out);
        return asked;
    }
}
