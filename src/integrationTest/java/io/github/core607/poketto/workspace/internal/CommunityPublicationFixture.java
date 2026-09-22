package io.github.core607.poketto.workspace.internal;

import io.github.core607.poketto.workspace.WorkspacePublications;
import org.springframework.jdbc.core.JdbcTemplate;

/** Exposes the real publication adapter to cross-module database acceptance. */
public final class CommunityPublicationFixture {
    private CommunityPublicationFixture() {}

    public static WorkspacePublications publications(JdbcTemplate jdbc) {
        return new JdbcWorkspacePublications(jdbc);
    }
}
