package com.ziggfreed.common.npc.placement;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.asset.builder.Builder;
import com.hypixel.hytale.server.npc.asset.builder.BuilderInfo;
import com.hypixel.hytale.server.npc.asset.builder.BuilderManager;
import com.hypixel.hytale.server.npc.asset.builder.BuilderParameters;
import com.hypixel.hytale.server.npc.util.expression.ValueType;

/**
 * Read-only questions about a native NPC role TEMPLATE, asked of the engine's own loaded roles.
 *
 * <p>A generated placement role is a tiny variant: it names a template and overrides a handful of
 * keys. The engine will only accept an override for a key the template explicitly declared in its
 * own {@code Parameters} block; anything else is refused when the variant is built, which shows up
 * as an NPC that simply never appears. That is the exact silent failure this package exists to
 * catch, so both the generator and {@link NpcPlacementValidator} ask these two questions before the
 * mistake can reach a player: does the template exist, and is each key it is about to override one
 * the template actually offers.
 *
 * <p><b>Every answer may be "cannot tell".</b> Outside a running server there is no NPC plugin to
 * ask, and during boot a template may not have loaded yet. A {@code null} answer means exactly that,
 * and a caller reports NOTHING on it rather than guessing - the same rule the model and particle
 * id checks follow. Nothing here mutates engine state: the lookups are the plain read accessors the
 * engine's own spawn guard uses.
 */
public final class RoleTemplates {

    /**
     * Keys the engine reserves and handles itself, whatever the template declares (its combat
     * config, its interaction variables, its exported states and its per-interface overrides). They
     * are always legal overrides, so a parameter check must never call one unknown.
     */
    private static final String RESERVED_PREFIX = "_";

    private RoleTemplates() {
    }

    /**
     * Is a role loaded under {@code templateName}? {@code null} when the question cannot be
     * answered from here (no NPC plugin, i.e. a unit JVM).
     */
    @Nullable
    public static Boolean templateExists(@Nullable String templateName) {
        if (templateName == null || templateName.isBlank()) {
            return null;
        }
        try {
            return builderOf(templateName) != null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Which of {@code keys} the template does NOT offer as an override, in the order given. Empty
     * when every key is fine, when the template declares none of them privately, and equally when
     * the question cannot be answered from here - a caller distinguishes "nothing wrong" from
     * "could not ask" via {@link #templateExists} when it needs to.
     *
     * <p>A reserved key (one starting with an underscore) is never reported: the engine handles
     * those itself rather than looking them up in the template's parameters.
     */
    @Nonnull
    public static List<String> unparameterizedKeys(@Nullable String templateName,
            @Nonnull Collection<String> keys) {
        List<String> out = new ArrayList<>();
        if (templateName == null || templateName.isBlank() || keys.isEmpty()) {
            return out;
        }
        try {
            Builder<?> builder = builderOf(templateName);
            if (builder == null) {
                return out; // Unknown template: reported on its own, not once per key.
            }
            BuilderParameters parameters = builder.getBuilderParameters();
            if (parameters == null) {
                return out;
            }
            for (String key : keys) {
                if (key == null || key.isBlank() || key.startsWith(RESERVED_PREFIX)) {
                    continue;
                }
                if (parameters.getParameterType(key) == ValueType.VOID) {
                    out.add(key);
                }
            }
        } catch (Throwable t) {
            return List.of();
        }
        return out;
    }

    /** The loaded role builder for {@code templateName}, or {@code null} if there is not one. */
    @Nullable
    private static Builder<?> builderOf(@Nonnull String templateName) {
        NPCPlugin npc = NPCPlugin.get();
        if (npc == null) {
            return null;
        }
        BuilderManager manager = npc.getBuilderManager();
        if (manager == null) {
            return null;
        }
        int index = manager.getIndex(templateName);
        if (index < 0) {
            return null;
        }
        BuilderInfo info = npc.getRoleBuilderInfo(index);
        return info == null ? null : info.getBuilder();
    }
}
