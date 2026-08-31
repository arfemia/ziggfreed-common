package com.ziggfreed.common.npc.placement.command;

import javax.annotation.Nonnull;

/**
 * What this family is CALLED, and the one line anything else writes to drive it.
 *
 * <p>A leaf on purpose: it imports nothing, so a caller that needs to name a verb can do so without
 * dragging a command implementation - or anything the commands read - along with it. The format has
 * exactly one owner, which is the command that parses it, and every producer of that line asks here
 * rather than spelling a second copy that drifts.
 *
 * <p><b>The named-arg form is not a style choice.</b> The engine's parser binds arguments by NAME,
 * so a positional line silently binds nothing.
 */
public final class NpcCommandLine {

    /** The command family every NPC placement admin verb hangs off. */
    public static final String FAMILY = "zignpc";

    /** Put a new placement where the caller is standing. */
    public static final String PLACE = "place";

    private NpcCommandLine() {
    }

    /**
     * The console line that stands {@code role} where the caller is, under {@code id}.
     *
     * @param role the NPC role to place
     * @param id   the placement id to write, or null to let the command derive one from the role
     */
    @Nonnull
    public static String place(@Nonnull String role, String id) {
        StringBuilder out = new StringBuilder("/").append(FAMILY).append(' ').append(PLACE)
                .append(" --role=").append(role.trim());
        if (id != null && !id.isBlank()) {
            out.append(" --id=").append(id.trim());
        }
        return out.toString();
    }
}
