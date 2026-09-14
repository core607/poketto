package io.github.core607.poketto.auth;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.core607.poketto.workspace.WorkspaceId;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * A security record is read after something has already gone wrong, so these fix what it must
 * carry and, more importantly, what it must never carry.
 */
@ExtendWith(OutputCaptureExtension.class)
class AuditRecordTests {

    @Test
    void aRefusalNamesTheActionAndThisServicesOwnReasonOnly(CapturedOutput output) {
        AuditRecords.refused("password.authentication", "INVALID_CREDENTIALS");

        assertThat(output).contains("password.authentication");
        assertThat(output).contains("INVALID_CREDENTIALS");
    }

    @Test
    void aWithdrawalIsNotRecordedAsAGrant(CapturedOutput output) {
        AuditRecords.changed("member.access.revoked", null, WorkspaceId.random(), UUID.randomUUID());

        assertThat(output).contains("member.access.revoked");
        assertThat(output).doesNotContain("granting");
    }

    @Test
    void aPermissionChangeNamesTheActorSubjectAndResultingCapabilities(CapturedOutput output) {
        WorkspaceId workspace = WorkspaceId.random();
        UUID subject = UUID.randomUUID();
        UUID owner = UUID.randomUUID();

        AuditRecords.granted(
                "member.permissions.changed",
                new AuthPrincipal(AuthPrincipal.Kind.ACCOUNT, owner, owner),
                workspace,
                subject,
                Set.of(Capability.READ_PRIVATE, Capability.PUBLISH));

        assertThat(output).contains("member.permissions.changed");
        assertThat(output).contains(owner.toString());
        assertThat(output).contains(workspace.toString());
        assertThat(output).contains(subject.toString());
        assertThat(output).contains("PUBLISH").contains("READ_PRIVATE");
    }

    @Test
    void capabilitiesAreOrderedSoTwoRecordsCanBeCompared(CapturedOutput output) {
        UUID subject = UUID.randomUUID();

        AuditRecords.granted(
                "key.issued", null, WorkspaceId.random(), subject, Set.of(Capability.PUBLISH, Capability.READ_PRIVATE));

        assertThat(output).contains("[PUBLISH, READ_PRIVATE]");
    }

    @Test
    void anAuthenticationRecordNamesOnlyTheResolvedIdentity(CapturedOutput output) {
        UUID key = UUID.randomUUID();

        AuditRecords.authenticated(
                "key.authentication", new AuthPrincipal(AuthPrincipal.Kind.API_KEY, key, UUID.randomUUID()));

        assertThat(output).contains("key.authentication");
        assertThat(output).contains("API_KEY:" + key);
    }

    @Test
    void anUnauthenticatedActorIsNamedRatherThanLeftBlank(CapturedOutput output) {
        AuditRecords.changed("key.revoked", null, WorkspaceId.random(), UUID.randomUUID());

        assertThat(output).contains("anonymous");
    }
}
