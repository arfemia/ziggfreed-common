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
 * <p>Fail-soft per Type: a throwing registration logs one guarded WARN and returns {@code
 * false}; {@link #registerAll} keeps going and reports how many landed, so one bad Type never
 * takes the whole roster down.
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
     *         null/blank argument or any engine throw (each already logged)
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
        try {
            BuilderCodec<? extends Interaction> codec = spec.codecSupplier().get();
            if (codec == null) {
                SafeLog.warn("[interaction] skipping Type registration for '" + name + "': codec supplier returned null");
                return false;
            }
            plugin.getCodecRegistry(Interaction.CODEC).register(name, spec.type(), codec);
            SafeLog.info("[interaction] registered Type '" + name + "'");
            return true;
        } catch (Throwable t) {
            SafeLog.warn("[interaction] failed to register Type '" + name + "'", t);
            return false;
        }
    }

    /** @return the number of specs that registered successfully; never aborts early */
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
     * inline at setup()). Same fail-soft contract as {@link #register(PluginBase,
     * InteractionTypeSpec)}.
     */
    public static boolean register(@Nullable PluginBase plugin, @Nullable String typeName,
            @Nullable Class<? extends Interaction> type, @Nullable BuilderCodec<? extends Interaction> codec) {
        if (plugin == null || typeName == null || typeName.isBlank() || type == null || codec == null) {
            SafeLog.fine("[interaction] skipping Type registration: missing plugin/typeName/type/codec");
            return false;
        }
        try {
            plugin.getCodecRegistry(Interaction.CODEC).register(typeName, type, codec);
            SafeLog.info("[interaction] registered Type '" + typeName + "'");
            return true;
        } catch (Throwable t) {
            SafeLog.warn("[interaction] failed to register Type '" + typeName + "'", t);
            return false;
        }
    }
}
