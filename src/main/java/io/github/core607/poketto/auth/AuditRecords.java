package io.github.core607.poketto.auth;

import io.github.core607.poketto.workspace.WorkspaceId;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Records the changes that decide who can do what.
 *
 * <p>Identity and permission operations produced nothing at all, so an account locked out, a key
 * that stopped working, or a member who gained private access left no account of when it happened
 * or who decided it. These records supply that account. They are not a substitute for the
 * relational state itself, which remains authoritative; they say when it changed and on whose
 * authority.
 *
 * <p>Every record carries an action naming what happened, so a reader filters on the action rather
 * than on a message. They share one logger name, {@code poketto.audit}, so the whole security
 * history can be selected without knowing which class wrote each line.
 *
 * <p>Subjects are named by identifier. Login names, passwords, tokens and invitation codes never
 * appear: a record of a failed sign-in that carried the attempted name would turn the log into a
 * list of account names to try, and a record carrying a code would hand over the credential it
 * describes.
 */
final class AuditRecords {

    private static final Logger log = LoggerFactory.getLogger("poketto.audit");

    private AuditRecords() {}

    /** An operation that changed authority, naming the actor who decided it and the subject. */
    static void changed(String action, AuthPrincipal actor, WorkspaceId workspace, UUID subject) {
        log.atInfo()
                .addKeyValue("action", action)
                .addKeyValue("actor", name(actor))
                .addKeyValue("workspace", name(workspace))
                .addKeyValue("subject", name(subject))
                .setMessage("audit {} by {} in workspace {} on {}")
                .addArgument(action)
                .addArgument(name(actor))
                .addArgument(name(workspace))
                .addArgument(name(subject))
                .log();
    }

    /**
     * A permission change, which also names the capabilities the subject holds afterwards.
     *
     * <p>The sentence states what is held rather than what was given, because the same shape
     * reports a withdrawal. A template with a verb in it would read "granting" on a record whose
     * action says the opposite, and a reader taking the sentence at face value would invert it.
     */
    static void granted(
            String action, AuthPrincipal actor, WorkspaceId workspace, UUID subject, Set<Capability> capabilities) {
        String granted = capabilities.stream().map(Enum::name).sorted().toList().toString();
        log.atInfo()
                .addKeyValue("action", action)
                .addKeyValue("actor", name(actor))
                .addKeyValue("workspace", name(workspace))
                .addKeyValue("subject", name(subject))
                .addKeyValue("capabilities", granted)
                .setMessage("audit {} by {} in workspace {} on {} now holding {}")
                .addArgument(action)
                .addArgument(name(actor))
                .addArgument(name(workspace))
                .addArgument(name(subject))
                .addArgument(granted)
                .log();
    }

    /**
     * A rejected attempt. The reason is this service's own fixed code, never the submitted value:
     * an attempted login name or token belongs to whoever sent it and must not be retained.
     */
    static void refused(String action, String reason) {
        log.atWarn()
                .addKeyValue("action", action)
                .addKeyValue("outcome", reason)
                .setMessage("audit {} refused: {}")
                .addArgument(action)
                .addArgument(reason)
                .log();
    }

    /** An accepted authentication, naming only the identity it resolved to. */
    static void authenticated(String action, AuthPrincipal principal) {
        log.atInfo()
                .addKeyValue("action", action)
                .addKeyValue("actor", name(principal))
                .setMessage("audit {} for {}")
                .addArgument(action)
                .addArgument(name(principal))
                .log();
    }

    private static String name(AuthPrincipal principal) {
        return principal == null ? "anonymous" : principal.toString();
    }

    private static String name(WorkspaceId workspace) {
        return workspace == null ? "" : workspace.toString();
    }

    private static String name(UUID subject) {
        return subject == null ? "" : subject.toString();
    }
}
