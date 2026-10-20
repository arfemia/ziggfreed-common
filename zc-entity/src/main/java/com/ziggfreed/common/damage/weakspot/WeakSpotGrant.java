package com.ziggfreed.common.damage.weakspot;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.ziggfreed.common.asset.EditorSchema;
import com.ziggfreed.common.grant.Grant;

/**
 * The {@value #KIND} grant: more of its holder's hits count as weak spots while it is held, each leaf one more
 * way, every leaf optional. What a weak spot pays is still the holder's {@code WeakSpotDamage} under their
 * {@code WeakSpotMax}; this widens only which hits pay it.
 *
 * <pre>{@code
 * { "Kind": "WeakSpot", "TargetHealthBelow": 8 }        // a foe at or under 8% health, per time held
 * { "Kind": "WeakSpot", "TargetEffects": ["Root"] }     // a foe carrying any of these effects
 * }</pre>
 *
 * <p>Its family is {@value #FAMILY}, so a world rule switching the combat bonuses off takes it away too.
 */
public final class WeakSpotGrant extends Grant {

    /** The kind id. */
    public static final String KIND = "WeakSpot";

    /** The broader word a world rule may name for it. */
    public static final String FAMILY = "Combat";

    @Nullable protected Double targetHealthBelow;
    @Nullable protected String[] targetEffects;

    public static final BuilderCodec<WeakSpotGrant> CODEC = BuilderCodec.builder(WeakSpotGrant.class, WeakSpotGrant::new)
            .documentation("More of the holder's hits count as weak spots while the grant is held. What a weak spot "
                    + "pays is still the holder's WeakSpotDamage, up to their WeakSpotMax.")
            .appendInherited(new KeyedCodec<>("TargetHealthBelow", Codec.DOUBLE, false),
                    (o, v) -> o.targetHealthBelow = v, o -> o.targetHealthBelow,
                    (o, p) -> o.targetHealthBelow = p.targetHealthBelow)
            .metadata(EditorSchema.defaultValue(0.0))
            .documentation("A hit on a foe at or under this percent of its health is a weak spot. Each time the "
                    + "grant is held adds it again (a pick taken three times at 8 counts 24), up to 100. 0 when "
                    + "unauthored.").add()
            .appendInherited(new KeyedCodec<>("TargetEffects", Codec.STRING_ARRAY, false),
                    (o, v) -> o.targetEffects = v, o -> o.targetEffects, (o, p) -> o.targetEffects = p.targetEffects)
            .metadata(EditorSchema.assetRef(EntityEffect.class))
            .documentation("Effect ids: a hit on a foe carrying any of them is a weak spot for the holder, "
                    + "whoever put the effect on. An id this server does not have counts nothing.").add()
            .build();

    public WeakSpotGrant() {
    }

    /** Java-side factory (a test, a consumer's own default); sets the same fields the codec fills. */
    @Nonnull
    public static WeakSpotGrant of(@Nullable Double targetHealthBelow, @Nullable String... targetEffects) {
        WeakSpotGrant grant = new WeakSpotGrant();
        grant.targetHealthBelow = targetHealthBelow;
        grant.targetEffects = targetEffects;
        return grant;
    }

    @Nonnull
    @Override
    public String kind() {
        return KIND;
    }

    @Nonnull
    @Override
    public String family() {
        return FAMILY;
    }

    /** The health threshold in percent, 0 or more; 0 when unauthored or not a number. */
    public double targetHealthBelow() {
        return targetHealthBelow == null || !Double.isFinite(targetHealthBelow) ? 0.0 : Math.max(0.0, targetHealthBelow);
    }

    /** The effect ids as written, lower-cased, blanks dropped. */
    @Nonnull
    public Set<String> targetEffects() {
        Set<String> out = new LinkedHashSet<>();
        if (targetEffects != null) {
            for (String effect : targetEffects) {
                if (effect != null && !effect.isBlank()) {
                    out.add(effect.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        return out;
    }
}
