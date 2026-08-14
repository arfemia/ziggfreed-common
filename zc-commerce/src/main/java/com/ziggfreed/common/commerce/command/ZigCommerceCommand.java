package com.ziggfreed.common.commerce.command;

import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;

/**
 * {@code /zigcommerce} - the admin surface for this server's economy: what is authored, what a
 * player has, and the few things an admin puts right by hand.
 *
 * <h2>The verbs</h2>
 *
 * <pre>
 * /zigcommerce validate                                          audit every wallet, storefront and board
 * /zigcommerce wallets                                           which wallets exist, and what backs each
 * /zigcommerce shops [--shop=&lt;id&gt;]                               storefronts, shelves, rotation state
 * /zigcommerce boards [--board=&lt;id&gt;]                             boards, slots, rotation state
 * /zigcommerce show [--player=&lt;name&gt;]                            one player's balances, purchases, rerolls
 * /zigcommerce give --player=&lt;name&gt; --currency=&lt;id&gt; --amount=&lt;n&gt;
 * /zigcommerce take --player=&lt;name&gt; --currency=&lt;id&gt; --amount=&lt;n&gt;
 * /zigcommerce set  --player=&lt;name&gt; --currency=&lt;id&gt; --amount=&lt;n&gt;
 * /zigcommerce resetlimits [--player=&lt;name&gt;]                     clear their purchase counts
 * /zigcommerce resetrerolls [--player=&lt;name&gt;]                    clear their rerolls this period
 * </pre>
 *
 * <p>Arguments bind by NAME, never by position - that is the engine's parser, not a house style.
 *
 * <h2>Permissions</h2>
 *
 * <p>There is no permission check written anywhere in this family, and that is the point: the engine
 * derives one node per command from the plugin and the command name, registers it, and refuses the
 * call before a body runs. So the nodes are
 * {@code ziggfreed.ziggfreedcommon.command.zigcommerce} for the family and
 * {@code ziggfreed.ziggfreedcommon.command.zigcommerce.<verb>} for each verb, a sub-command needs
 * BOTH, and nobody holds either until a server grants it. The console holds everything, which is
 * what makes this usable from a startup script and from a wrapper with no permissions plugin
 * installed at all.
 *
 * <p>One permission question, one answer: a second check inside these bodies would be a second
 * vocabulary a server owner has to discover, and the first one to drift.
 *
 * <h2>Why this family belongs to the library</h2>
 *
 * <p>The module that owns an engine owns the commands that drive it. Everything here reads or writes
 * through the same catalogues, the same currency engine and the same state store every page and
 * every payout uses - so a consumer mod wanting {@code /myshop} registers an alias that calls
 * straight through, rather than a second implementation that can disagree with this one.
 */
public final class ZigCommerceCommand extends AbstractCommandCollection {

    public ZigCommerceCommand() {
        super(CommerceCommandLine.FAMILY, CommerceAdminMessages.desc("family"));
        addSubCommand(new CommerceValidateCommand());
        addSubCommand(new CommerceWalletsCommand());
        addSubCommand(new CommerceShopsCommand());
        addSubCommand(new CommerceBoardsCommand());
        addSubCommand(new CommerceShowCommand());
        addSubCommand(new CommerceWalletCommand(CommerceWalletCommand.Op.GIVE));
        addSubCommand(new CommerceWalletCommand(CommerceWalletCommand.Op.TAKE));
        addSubCommand(new CommerceWalletCommand(CommerceWalletCommand.Op.SET));
        addSubCommand(new CommerceResetCommand(CommerceResetCommand.Scope.LIMITS));
        addSubCommand(new CommerceResetCommand(CommerceResetCommand.Scope.REROLLS));
    }
}
