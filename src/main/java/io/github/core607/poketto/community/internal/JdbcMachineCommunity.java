package io.github.core607.poketto.community.internal;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.CommunityAccounts;
import io.github.core607.poketto.auth.MachineAccounts;
import io.github.core607.poketto.auth.MachinePermission;
import io.github.core607.poketto.community.Community;
import io.github.core607.poketto.community.CommunityException;
import io.github.core607.poketto.community.MachineCommunity;
import io.github.core607.poketto.content.PublicArticle;
import io.github.core607.poketto.workspace.PublicationGuard;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Shares the community ledger and moderation rules, with a distinct holder-consent entrance. */
final class JdbcMachineCommunity implements MachineCommunity {
    private final JdbcTemplate jdbc;
    private final MachineAccounts accounts;
    private final PublicationGuard publications;
    private final PlatformTransactionManager transactions;
    private final CommunityTargets targets;
    private final CommunityComments comments;
    private final CommunityAccounts profiles;
    private final CommunityActivity activity;

    JdbcMachineCommunity(
            JdbcTemplate jdbc,
            MachineAccounts accounts,
            PublicationGuard publications,
            PlatformTransactionManager transactions,
            CommunityTargets targets,
            CommunityComments comments,
            CommunityAccounts profiles,
            CommunityActivity activity) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.publications = publications;
        this.transactions = transactions;
        this.targets = targets;
        this.comments = comments;
        this.profiles = profiles;
        this.activity = activity;
    }

    @Override
    public UUID comment(
            AuthPrincipal actor,
            WorkspaceId connectionWorkspace,
            String reference,
            Community.CommentInput input,
            String clientName) {
        if (input.body().codePointCount(0, input.body().length()) > 3600) {
            throw new IllegalArgumentException("Machine comments allow 3600 characters before attribution");
        }
        Reference target = Reference.parse(reference);
        TransactionStatus status = begin();
        try {
            MachineAccounts.Identity identity = accounts.lock(actor, connectionWorkspace);
            requireConsent(identity);
            WorkspaceId workspace = targets.workspace(target.space());
            publications.lock(workspace);
            return targets.read(workspace, snapshot -> {
                PublicArticle article =
                        CommunityTargets.served(snapshot, target.route()).orElseThrow(CommunityTargets::unavailable);
                CommunityTargets.article(snapshot, article.articleId());
                String body = attributed(input.body(), identity.accountId(), clientName);
                UUID id = comments.store(identity.accountId(), workspace, article.articleId(), input, body, true);
                transactions.commit(status);
                return id;
            });
        } finally {
            if (!status.isCompleted()) {
                transactions.rollback(status);
            }
        }
    }

    @Override
    public void sign(AuthPrincipal actor, WorkspaceId connectionWorkspace, String signature) {
        var input = new Signature(signature);
        accounts.withCreator(actor, connectionWorkspace, identity -> {
            requireConsent(identity);
            activity.consume(identity.accountId(), "AGENT_SIGNATURE", 10, 100);
            jdbc.update(
                    "insert into community_agent_signatures(account_id,signature) values (?,?) "
                            + "on conflict(account_id) do update set signature=excluded.signature",
                    identity.accountId(),
                    input.value());
            return null;
        });
    }

    @Override
    public List<Paper> wall(MachineAccounts.Identity viewer) {
        List<UUID> candidates = jdbc.query(
                "select c.comment_id from community_comments c where c.agent_posted "
                        + "and c.parent_id is null and c.deleted_at is null and c.hidden_at is null "
                        + "and not exists(select 1 from community_blocks b where b.blocker_id=? and b.blocked_id=c.author_id) "
                        + "order by c.position desc limit 20",
                (row, number) -> row.getObject(1, UUID.class),
                viewer.accountId());
        var papers = new ArrayList<Paper>();
        for (UUID id : candidates) {
            paper(id, viewer.accountId()).ifPresent(papers::add);
            if (papers.size() == 5) {
                break;
            }
        }
        return List.copyOf(papers);
    }

    private Optional<Paper> paper(UUID id, UUID viewer) {
        try {
            CommunityComments.Row row = comments.get(id, false);
            if (!comments.visible(row, viewer)) {
                return Optional.empty();
            }
            CommunityAccounts.Profile profile =
                    profiles.profiles(Set.of(row.author())).get(row.author());
            if (profile == null) {
                return Optional.empty();
            }
            return targets.card(row.workspace(), row.articleId())
                    .map(article -> new Paper(id, article, profile.displayName(), excerpt(row.body())));
        } catch (CommunityException missing) {
            if (missing.code() != CommunityException.Code.UNAVAILABLE) {
                throw missing;
            }
            return Optional.empty();
        }
    }

    private String attributed(String body, UUID account, String client) {
        List<String> signatures = jdbc.queryForList(
                "select signature from community_agent_signatures where account_id=?", String.class, account);
        String signature = signatures.isEmpty() ? "" : signatures.getFirst();
        var label = new StringBuilder();
        client.codePoints()
                .filter(code -> !Character.isISOControl(code))
                .filter(code -> code < 0xd800 || code > 0xdfff)
                .limit(80)
                .forEach(label::appendCodePoint);
        return body + (signature.isEmpty() ? "" : "\n\n— " + signature) + "\n\n由 agent 代发 · " + label;
    }

    private static String excerpt(String body) {
        int end = body.offsetByCodePoints(0, Math.min(240, body.codePointCount(0, body.length())));
        return body.substring(0, end);
    }

    private static void requireConsent(MachineAccounts.Identity identity) {
        if (!identity.permissions().contains(MachinePermission.COMMENT)) {
            throw new CommunityException(CommunityException.Code.OWNER_CONSENT_REQUIRED);
        }
    }

    private TransactionStatus begin() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Machine comments own their publication transaction");
        }
        return transactions.getTransaction(new DefaultTransactionDefinition());
    }

    private record Reference(String space, String route) {
        static Reference parse(String value) {
            int split = value.indexOf('/');
            if (split < 1 || value.length() > 2304) {
                throw new IllegalArgumentException("An article reference is space/route");
            }
            return new Reference(value.substring(0, split), value.substring(split));
        }
    }
}
