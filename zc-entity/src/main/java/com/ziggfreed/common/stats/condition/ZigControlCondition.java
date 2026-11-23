package com.ziggfreed.common.stats.condition;

import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.modules.entity.condition.Condition;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.asset.EditorSchema;
import com.ziggfreed.common.util.SafeLog;

/**
 * An entity condition: does a control of {@code Kind} land on the entity now ({@link BossRules#lands}), with
 * {@code ByTalent} saying a talent or a proc added it to the move? So data asks the same place code does: a proc's
 * {@code Area} keeps bosses out of a talent's push, and an effect's own {@code ApplyConditions} drop it the tick its
 * wearer's rules stop letting it land. Registered into the engine's entity condition map as {@value #TYPE_NAME}.
 *
 * <pre>{@code
 * { "Id": "ZigControl", "Kind": "Push", "ByTalent": true }
 * }</pre>
 *
 * <p>A {@code Kind} that names no kind never holds, warned once per value.
 */
public final class ZigControlCondition extends Condition {

    /** The authored {@code "Id"} name. */
    public static final String TYPE_NAME = "ZigControl";

    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    @Nullable
    private String kind;

    @Nullable
    private Boolean byTalent;

    private static final class Holder {
        static final BuilderCodec<ZigControlCondition> CODEC = BuilderCodec.builder(
                        ZigControlCondition.class, ZigControlCondition::new, Condition.BASE_CODEC)
                .documentation("Holds while a control of the kind lands on the entity now, by its rules (a boss's "
                        + "group tags, a phase's or a window's effect, the family default for bosses and elites).")
                .append(new KeyedCodec<>("Kind", Codec.STRING, false), (c, v) -> c.kind = v, c -> c.kind)
                .metadata(EditorSchema.assetRef(ControlKindAsset.class))
                .documentation("The kind of crowd control (a ControlKinds file: the library's own, or a pack's), "
                        + "matched without regard to case. A name that is no kind never holds.")
                .add()
                .append(new KeyedCodec<>("ByTalent", Codec.BOOLEAN, false), (c, v) -> c.byTalent = v,
                        c -> c.byTalent)
                .metadata(EditorSchema.defaultValue(false))
                .documentation("True: a talent or a proc adds this control to the move, which a boss or an elite "
                        + "takes only where its rules let every one land. False when unauthored: an ability's own, "
                        + "which lands on one at the cast's own strength unless its rules shrug the kind.")
                .add()
                .build();
    }

    /** The codec, built on first call. Only registration and a codec test invoke this. */
    @Nonnull
    public static BuilderCodec<ZigControlCondition> codec() {
        return Holder.CODEC;
    }

    /** The kind as written, or null when it names none. */
    @Nullable
    public ControlKind kind() {
        return ControlKind.parse(kind);
    }

    /** Does a talent or a proc add it? False unless written true. */
    public boolean byTalent() {
        return Boolean.TRUE.equals(byTalent);
    }

    @Override
    public boolean eval0(@Nonnull ComponentAccessor<EntityStore> componentAccessor, @Nonnull Ref<EntityStore> ref,
            @Nonnull Instant currentTime) {
        ControlKind named = kind();
        if (named == null) {
            String written = kind == null ? "" : kind.trim();
            if (WARNED.add(written.toLowerCase(Locale.ROOT))) {
                SafeLog.warn("[condition] a ZigControl condition names the Kind '" + written + "', which is no kind "
                        + "of control (" + String.join(", ", ControlKind.ids()) + "): it never holds");
            }
            return false;
        }
        return BossRules.lands(componentAccessor, ref, named, byTalent());
    }
}
