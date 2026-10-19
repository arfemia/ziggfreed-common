package com.ziggfreed.common.usegate;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.Message;

/**
 * Why a use was refused: the gate that refused it, the group it competes in, the axis it was asked on, and the one
 * line a player reads (the gate's most specific unmet requirement, worded by the shared refusal mapping and
 * resolved on the client).
 *
 * @param gateId the refusing gate's id, as its file spells it ({@code ZigLevel:<Item>} for an item's own tag)
 * @param group  the refusing gate's group, folded as {@link UseGateAsset#group()} folds it
 * @param axis   what the gate was about at this moment
 * @param line   what the player is told, a lang-keyed message
 */
public record UseGateRefusal(@Nonnull String gateId, @Nonnull String group, @Nonnull UseGateAxis axis,
        @Nonnull Message line) {
}
