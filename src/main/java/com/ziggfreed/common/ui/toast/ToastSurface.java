package com.ziggfreed.common.ui.toast;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;

/**
 * The transport seam between {@link ToastController} (engine) and wherever the
 * {@code #ZigToast} block lives. The controller builds the show / hide command stream;
 * the surface pushes it to the active UI.
 *
 * <p>A page binds this to a {@code sendUpdate} (so the toast appears and clears without a
 * page reopen, preserving scroll); a future {@code CustomUIHud} binds it to
 * {@code update(false, ...)}. Both show and hide ride this one method, because
 * {@link ToastRenderer} drives visibility through the {@code Anchor} (not {@code Visible}),
 * which a {@code sendUpdate} can set. Invoked on the world thread (event path) or the
 * scheduler thread (auto-dismiss); implementations must marshal to the world thread - the
 * page's {@code sendUpdate} already does.
 */
@FunctionalInterface
public interface ToastSurface {
    void push(@Nonnull UICommandBuilder cmd);
}
