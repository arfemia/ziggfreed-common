package com.ziggfreed.common.loot.reward;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.command.system.CommandManager;
import com.ziggfreed.common.validation.Finding;

/**
 * Reads authored reward kinds looking for the mistakes that produce SILENCE: a kind that runs
 * nothing, a template writing a placeholder nobody fills, a parameter nobody reads, a reward that
 * leaves out something the kind said it needs. None of those throws at load, and a player only finds
 * out by not being paid.
 *
 * <p>It also reports the one thing that is not a mistake at all: a file that has taken over a kind a
 * mod was providing. That is allowed, so it is an INFO - but an owner who never sees it has quietly
 * traded away that kind's engine services (see {@link RewardKindFold}), and the audit is the second
 * place they can find out after the boot log.
 *
 * <p>Severity follows the library rule: an id nobody answers to is a WARNING, never an ERROR,
 * because whichever mod would answer it may simply not be installed on the machine running the check.
 */
public final class RewardKindValidator {

    /** The domain every finding here is stamped with. */
    public static final String DOMAIN = "rewardkind";

    // Codes, stable so an owner can grep a boot log for one.
    public static final String NO_COMMAND = "REWARDKIND_NO_COMMAND";
    public static final String PRESENTATION_ONLY = "REWARDKIND_PRESENTATION_ONLY";
    public static final String UNKNOWN_PARAM = "REWARDKIND_UNKNOWN_PARAM";
    public static final String MISSING_REQUIRED_PARAM = "REWARDKIND_MISSING_REQUIRED_PARAM";
    public static final String UNUSED_PARAM = "REWARDKIND_UNUSED_PARAM";
    public static final String REQUIRED_WITH_DEFAULT = "REWARDKIND_REQUIRED_WITH_DEFAULT";
    public static final String JAVA_BACKED_KIND_SHADOWED = "REWARDKIND_JAVA_BACKED_KIND_SHADOWED";
    public static final String UNKNOWN_COMMAND = "REWARDKIND_UNKNOWN_COMMAND";

    private RewardKindValidator() {
    }

    /**
     * Whether a Java-registered, non-command handler already answers this file's id - the probe
     * that separates decoration from a dud. Reads the process-wide vocabulary directly, like the
     * command-head check reads the engine's command registry: it is the one place the answer
     * exists, and a check that cannot ask answers false rather than guessing.
     */
    private static boolean decoratesJavaKind(@Nonnull RewardKindAsset asset) {
        try {
            RewardHandler handler = RewardKinds.shared().handler(asset.getId());
            return handler != null && !(handler instanceof CommandRewardKind);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Audit every loaded kind asset. */
    @Nonnull
    public static List<Finding> auditAll() {
        return auditAll(RewardKindConfig.getInstance().all().values());
    }

    /** Audit a caller-supplied set of kinds - the form a test drives. */
    @Nonnull
    public static List<Finding> auditAll(@Nullable Collection<RewardKindAsset> assets) {
        List<Finding> findings = new ArrayList<>();
        if (assets == null) {
            return findings;
        }
        Set<String> heads = registeredCommandHeads();
        for (RewardKindAsset asset : assets) {
            findings.addAll(audit(asset, heads));
        }
        return findings;
    }

    /** Audit ONE kind asset, without asking the server which commands exist. */
    @Nonnull
    public static List<Finding> audit(@Nullable RewardKindAsset asset) {
        return audit(asset, null);
    }

    /**
     * Audit ONE kind asset. {@code registeredCommandHeads} is the set of command names this server
     * answers to, or null when nobody asked (or nothing could answer) - in which case the command
     * head is simply not checked.
     */
    @Nonnull
    public static List<Finding> audit(@Nullable RewardKindAsset asset,
            @Nullable Set<String> registeredCommandHeads) {
        List<Finding> findings = new ArrayList<>();
        if (asset == null) {
            return findings;
        }
        String sourceId = asset.authoredId();
        if (asset.isBlank()) {
            if (decoratesJavaKind(asset)) {
                // The one legitimate command-less shape: this file gives an already-working
                // Java-registered kind its authored Presentation, and the payout stays that mod's.
                findings.add(Finding.info(DOMAIN, PRESENTATION_ONLY,
                        "This file decorates a Java-registered kind: its Presentation says how that "
                                + "kind's rewards read, and the payout stays with the mod that "
                                + "registered it.", sourceId));
            } else {
                findings.add(Finding.error(DOMAIN, NO_COMMAND,
                        "This reward kind names no Command, so nothing is registered for it and any reward "
                                + "written for it pays out nothing. Add a Command, or delete the file.", sourceId));
            }
            return findings;
        }

        for (String placeholder : asset.commandPlaceholders()) {
            if (RewardKindAsset.RESERVED_PLACEHOLDERS.contains(placeholder) || asset.declares(placeholder)) {
                continue;
            }
            findings.add(Finding.warning(DOMAIN, UNKNOWN_PARAM,
                    "The command writes {" + placeholder + "}, which is neither a reserved placeholder "
                            + "({player}, {uuid}) nor a parameter this kind declares, so it is left in the "
                            + "command line exactly as written. Declare it under Params, or fix the spelling "
                            + "(placeholders are case-sensitive).", sourceId));
        }

        for (String unused : asset.unusedParams()) {
            findings.add(Finding.info(DOMAIN, UNUSED_PARAM,
                    "Parameter '" + unused + "' is declared but the command never writes {" + unused
                            + "}, so authoring it on a reward changes nothing.", sourceId));
        }

        for (Map.Entry<String, RewardKindAsset.Param> entry : asset.paramsOrEmpty().entrySet()) {
            RewardKindAsset.Param declaration = entry.getValue();
            if (declaration != null && declaration.isRequired() && declaration.hasDefault()) {
                findings.add(Finding.info(DOMAIN, REQUIRED_WITH_DEFAULT,
                        "Parameter '" + entry.getKey() + "' is Required and also has a Default, so the "
                                + "default always answers for it and the requirement can never refuse a "
                                + "reward. Drop one of the two to say which you meant.", sourceId));
            }
        }

        findings.addAll(auditCommandHead(asset, registeredCommandHeads));
        return findings;
    }

    /**
     * Audit one authored REWARD against the kind it names: parameters the kind does not declare, and
     * required ones the reward leaves out. Nothing is reported for a kind nobody authored - a
     * Java-registered kind declares no schema here, so there is nothing to check it against.
     *
     * @param sourceId names the content the reward was written in, so a finding points at the file
     *                 that has to change
     */
    @Nonnull
    public static List<Finding> auditSpec(@Nullable RewardSpec spec, @Nonnull String sourceId) {
        if (spec == null) {
            return List.of();
        }
        return auditSpec(RewardKindConfig.getInstance().resolve(spec.kind()), spec, sourceId);
    }

    /** As {@link #auditSpec(RewardSpec, String)}, against a caller-supplied kind. */
    @Nonnull
    public static List<Finding> auditSpec(@Nullable RewardKindAsset kind, @Nullable RewardSpec spec,
            @Nonnull String sourceId) {
        List<Finding> findings = new ArrayList<>();
        if (kind == null || spec == null) {
            return findings;
        }
        for (String unknown : CommandRewardKind.undeclaredParams(kind, spec)) {
            findings.add(Finding.warning(DOMAIN, UNKNOWN_PARAM,
                    "This reward passes '" + unknown + "' to kind '" + kind.authoredId()
                            + "', which does not declare it, so the value reaches nothing. Check the "
                            + "spelling against that kind's Params.", sourceId));
        }
        for (String missing : CommandRewardKind.missingRequired(kind, spec)) {
            findings.add(Finding.error(DOMAIN, MISSING_REQUIRED_PARAM,
                    "Kind '" + kind.authoredId() + "' requires '" + missing
                            + "' and this reward does not name it, so the payout will refuse rather than "
                            + "run an incomplete command.", sourceId));
        }
        return findings;
    }

    /**
     * Report each kind an authored file took over from a Java registration, as returned by
     * {@link RewardKindFold.Result#shadowed()}. INFO, because it is allowed - the point is that it is
     * never invisible.
     */
    @Nonnull
    public static List<Finding> shadowed(@Nullable Collection<String> shadowedIds) {
        List<Finding> findings = new ArrayList<>();
        if (shadowedIds == null) {
            return findings;
        }
        for (String id : shadowedIds) {
            if (id == null || id.isBlank()) {
                continue;
            }
            findings.add(Finding.info(DOMAIN, JAVA_BACKED_KIND_SHADOWED,
                    RewardKindFold.shadowWarning(id), id));
        }
        return findings;
    }

    // ==================== the command head ====================

    /**
     * Does this server answer to the command the kind starts with? A miss is a WARNING and says so
     * carefully, because the check has one blind spot: the engine lists commands under their
     * canonical NAME, and an alias is not in that list.
     */
    @Nonnull
    private static List<Finding> auditCommandHead(@Nonnull RewardKindAsset asset,
            @Nullable Set<String> registeredCommandHeads) {
        if (registeredCommandHeads == null || registeredCommandHeads.isEmpty()) {
            return List.of();
        }
        String head = asset.commandHead();
        if (head.isEmpty() || registeredCommandHeads.contains(head.toLowerCase(Locale.ROOT))) {
            return List.of();
        }
        return List.of(Finding.warning(DOMAIN, UNKNOWN_COMMAND,
                "Nothing on this server registers a command called '" + head + "' under that name, so "
                        + "this reward would run a line the server rejects. If '" + head + "' is an alias "
                        + "of another command this is a false alarm, since aliases are not listed; "
                        + "otherwise check the spelling, or whether the mod that owns the command is "
                        + "installed.", asset.authoredId()));
    }

    /**
     * Every command name this server answers to, lower-cased, or null when nothing can say.
     *
     * <p>Reachable from here without any new dependency: the command manager is an engine type, not
     * another module. Null is the answer in a unit JVM and on any build where the manager is not up
     * yet, and it means the head is not checked at all rather than reported as unknown - a check that
     * cannot run must never invent a finding.
     */
    @Nullable
    public static Set<String> registeredCommandHeads() {
        try {
            CommandManager manager = CommandManager.get();
            if (manager == null) {
                return null;
            }
            Map<String, ?> registration = manager.getCommandRegistration();
            if (registration == null || registration.isEmpty()) {
                return null;
            }
            Set<String> heads = new LinkedHashSet<>();
            for (String name : registration.keySet()) {
                if (name != null && !name.isBlank()) {
                    heads.add(name.trim().toLowerCase(Locale.ROOT));
                }
            }
            return heads.isEmpty() ? null : heads;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Every finding code this validator can emit, for a test that pins the vocabulary. */
    @Nonnull
    public static List<String> codes() {
        return List.of(NO_COMMAND, PRESENTATION_ONLY, UNKNOWN_PARAM, MISSING_REQUIRED_PARAM,
                UNUSED_PARAM, REQUIRED_WITH_DEFAULT, JAVA_BACKED_KIND_SHADOWED, UNKNOWN_COMMAND);
    }
}
