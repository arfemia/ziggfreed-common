package com.ziggfreed.common.encounter.command;

import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;

/**
 * {@code /zigencounter} - the admin surface over live encounters and the content around them.
 *
 * <pre>
 * /zigencounter list                                          every live encounter, with its run and state
 * /zigencounter inspect &lt;ref&gt;                                 one run: subject, members, credit so far
 * /zigencounter spawn &lt;asset&gt; [--world=&lt;name&gt;] [--x= --y= --z=]  stand a script up (at you, or the world spawn)
 * /zigencounter end &lt;ref&gt;                                     remove it, settling an engaged run as a wipe
 * /zigencounter state &lt;ref&gt; &lt;state&gt; [--substate=&lt;name&gt;]        force the script into a state
 * /zigencounter validate                                      audit the scripts, bindings and rules
 * /zigencounter reload                                        re-read the owner files
 * </pre>
 *
 * <p>{@code <ref>} is a prefix of a run id as {@code list} prints it, or a script id when only one
 * live encounter runs it. Optional arguments bind by NAME, never by position; that is the engine's
 * parser.
 *
 * <p>There is no permission check written anywhere in this family, and that is the point: the
 * engine derives one node per command from the plugin and the command name
 * ({@code ziggfreed.ziggfreedcommon.command.zigencounter[.<verb>]}), registers it, and refuses the
 * call before a body runs. The console holds everything, which is what makes every verb usable from
 * a startup script and a headless boot check.
 */
public final class ZigEncounterCommand extends AbstractCommandCollection {

    public ZigEncounterCommand() {
        super(EncounterCommandLine.FAMILY, EncounterAdminMessages.desc("family"));
        addSubCommand(new EncounterListCommand());
        addSubCommand(new EncounterInspectCommand());
        addSubCommand(new EncounterSpawnCommand());
        addSubCommand(new EncounterEndCommand());
        addSubCommand(new EncounterStateCommand());
        addSubCommand(new EncounterValidateCommand());
        addSubCommand(new EncounterReloadCommand());
    }
}
