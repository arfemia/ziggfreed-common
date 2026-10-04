package com.ziggfreed.common.effect.costume;

import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;
import com.hypixel.hytale.server.core.permissions.provider.HytalePermissionsProvider;

/**
 * {@code /zigcostume} - the costumes other players put on you.
 *
 * <pre>
 * /zigcostume off      take off any costume you are wearing
 * </pre>
 *
 * <p>A player's own command: it changes only the caller, so the family sits in the engine's adventurer
 * group and every player holds it. It is what makes a costume the wearer's to end: any effect that
 * changes the model and is not a debuff comes off ({@link CostumeRules}).
 */
public final class CostumeCommand extends AbstractCommandCollection {

    /** The family's name. */
    public static final String FAMILY = "zigcostume";

    /** The verb that takes costumes off. */
    public static final String OFF = "off";

    public CostumeCommand() {
        super(FAMILY, CostumeMessages.desc("family"));
        setPermissionGroups(HytalePermissionsProvider.GROUP_ADVENTURER);
        addSubCommand(new CostumeOffCommand());
    }
}
