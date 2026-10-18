package com.ziggfreed.common.interaction.type;

import java.util.Collection;

import javax.annotation.Nullable;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.ziggfreed.common.util.SafeLog;

/**
 * Registers custom interaction Types on the engine's {@code Interaction.CODEC} type registry -
 * the plumbing every consumer re-rolls today ({@code plugin.getCodecRegistry(Interaction.CODEC)
 * .register(name, class, codec)} inside a per-Type try/catch).
 *
 * <p><b>Call from the consumer plugin's {@code setup()}, before any asset decode.</b> A Type
 * registered after {@code LoadAssetEvent} has decoded the assets that reference it is too late:
 * the referencing asset already failed to decode.
 *
 * <p>A failed registration rethrows, at setup: a throwing registration (or a codec supplier
 * that returns null) logs its own WARN naming the Type, then throws to the caller, and
 * {@link #registerAll} stops at it. Swallowed, the miss would surface later as an unknown Type
 * inside an owner's pack, far from its cause. Each Type that lands logs its own INFO line.
 *
 * <p><b>Argument guards run BEFORE any engine touch.</b> A null plugin/spec or a blank type name
 * is rejected before {@code Interaction.CODEC} is ever referenced, so this class is callable
 * from a log-manager-less unit JVM as long as the arguments are degenerate (see the class
 * javadoc on {@link InteractionTypeSpec} for why touching {@code Interaction.CODEC} outside a
 * live server throws).
 */
public final class InteractionTypes {

    private InteractionTypes() {
    }

    /**
     * @return {@code true} when the Type was handed to the registry, {@code false} on a
     *         null/blank argument (logged, and never reaching the engine)
     * @throws IllegalStateException when the codec supplier returns null (logged first)
     * @throws RuntimeException      or {@link Error}, whatever the engine or the supplier threw,
     *                               rethrown after its WARN line
     */
    public static boolean register(@Nullable PluginBase plugin, @Nullable InteractionTypeSpec spec) {
        if (plugin == null || spec == null) {
            SafeLog.fine("[interaction] skipping Type registration: plugin or spec is null");
            return false;
        }
        String name = spec.typeName();
        if (name == null || name.isBlank()) {
            SafeLog.warn("[interaction] skipping Type registration: blank type name");
            return false;
        }
        BuilderCodec<? extends Interaction> codec;
        try {
            codec = spec.codecSupplier().get();
        } catch (Throwable t) {
            SafeLog.warn("[interaction] failed to register Type '" + name + "'", t);
            throw t;
        }
        if (codec == null) {
            SafeLog.warn("[interaction] failed to register Type '" + name + "': codec supplier returned null");
            throw new IllegalStateException("the codec supplier for interaction Type '" + name + "' returned null");
        }
        registerOrThrow(plugin, name, spec.type(), codec);
        return true;
    }

    /**
     * @return the number of specs that registered: every non-null one, since a failed
     *         registration throws and stops the run there
     */
    public static int registerAll(@Nullable PluginBase plugin, @Nullable Collection<? extends InteractionTypeSpec> specs) {
        if (plugin == null || specs == null) {
            return 0;
        }
        int registered = 0;
        for (InteractionTypeSpec spec : specs) {
            if (register(plugin, spec)) {
                registered++;
            }
        }
        return registered;
    }

    /**
     * Direct form for a caller that already holds a live codec (a Type whose codec is built
     * inline at setup()). Same contract as {@link #register(PluginBase, InteractionTypeSpec)}:
     * {@code false} on a missing argument, and an engine throw rethrown after its WARN line.
     */
    public static boolean register(@Nullable PluginBase plugin, @Nullable String typeName,
            @Nullable Class<? extends Interaction> type, @Nullable BuilderCodec<? extends Interaction> codec) {
        if (plugin == null || typeName == null || typeName.isBlank() || type == null || codec == null) {
            SafeLog.fine("[interaction] skipping Type registration: missing plugin/typeName/type/codec");
            return false;
        }
        registerOrThrow(plugin, typeName, type, codec);
        return true;
    }

    /** The one engine call: logs the Type's INFO line when it lands, its WARN line and rethrows when not. */
    private static void registerOrThrow(PluginBase plugin, String typeName, Class<? extends Interaction> type,
            BuilderCodec<? extends Interaction> codec) {
        try {
            plugin.getCodecRegistry(Interaction.CODEC).register(typeName, type, codec);
        } catch (Throwable t) {
            SafeLog.warn("[interaction] failed to register Type '" + typeName + "'", t);
            throw t;
        }
        SafeLog.info("[interaction] registered Type '" + typeName + "'");
    }
}
