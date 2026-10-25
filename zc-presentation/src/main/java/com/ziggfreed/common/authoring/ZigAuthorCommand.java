package com.ziggfreed.common.authoring;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractAsyncCommand;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.i18n.Msg;

/**
 * {@code /zigauthor}: the library's admin way into the in-game editors, beside the authoring tools.
 *
 * <pre>
 * /zigauthor list             the editors and the authoring tools this server has, with each tool's permission
 * /zigauthor open &lt;wizard&gt;    open an editor on yourself, with nothing picked
 * </pre>
 *
 * <p>No permission check is written in this family: the engine derives one node per verb from the plugin and the
 * command name ({@code ziggfreed.ziggfreedcommon.command.zigauthor.<verb>}) and refuses the call before a body runs.
 * Opening a wizard here needs no creative mode; its tool does.
 */
final class ZigAuthorCommand extends AbstractCommandCollection {

    /** The command family. */
    static final String FAMILY = "zigauthor";

    static final String LIST = "list";

    static final String OPEN = "open";

    private static final Color BAD = new Color(0xFF5555);

    ZigAuthorCommand() {
        super(FAMILY, AuthoringText.desc("family"));
        addSubCommand(new ListVerb());
        addSubCommand(new OpenVerb());
    }

    /** {@code list}: every editor and every tool, a tool with the node it needs. */
    private static final class ListVerb extends AbstractAsyncCommand {

        ListVerb() {
            super(LIST, AuthoringText.desc(LIST));
        }

        @Nonnull
        @Override
        protected CompletableFuture<Void> executeAsync(@Nonnull CommandContext ctx) {
            List<String> wizards = AuthoringWizards.ids();
            List<String> tools = AuthoringTools.ids();
            List<Message> lines = new ArrayList<>();
            lines.add(AuthoringText.line("admin.list.wizards", wizards.isEmpty()
                    ? AuthoringText.line("admin.list.none") : Msg.raw(String.join(", ", wizards))));
            lines.add(AuthoringText.line(tools.isEmpty() ? "admin.list.no_tools" : "admin.list.tools"));
            for (String tool : tools) {
                lines.add(AuthoringText.line("admin.list.tool", Msg.raw(tool), Msg.raw(AuthoringTools.permission(tool))));
            }
            ctx.sendMessage(Msg.lines(lines));
            return CompletableFuture.completedFuture(null);
        }
    }

    /** {@code open <wizard>}: the editor on the admin running it, on their world thread. */
    private static final class OpenVerb extends AbstractPlayerCommand {

        private final RequiredArg<String> wizardArg;

        OpenVerb() {
            super(OPEN, AuthoringText.desc(OPEN));
            this.wizardArg = withRequiredArg("wizard", AuthoringText.desc("arg.wizard"), ArgTypes.STRING);
        }

        @Override
        protected void execute(@Nonnull CommandContext ctx, @Nonnull Store<EntityStore> store,
                @Nonnull Ref<EntityStore> ref, @Nonnull PlayerRef player, @Nonnull World world) {
            String id = wizardArg.get(ctx).trim();
            if (!AuthoringWizards.known(id)) {
                ctx.sendMessage(AuthoringText.line("admin.open.unknown", Msg.raw(id)).color(BAD));
                return;
            }
            if (!AuthoringWizards.open(id, ref, store)) {
                ctx.sendMessage(AuthoringText.line("admin.open.failed", Msg.raw(id)).color(BAD));
            }
        }
    }
}
