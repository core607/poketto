package io.github.core607.poketto.web.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.core607.poketto.auth.AccountFixtures;
import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.auth.SiteGroup;
import io.github.core607.poketto.auth.SitePolicyService;
import io.github.core607.poketto.content.PublicContentSnapshots;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.eclipse.jgit.api.Git;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/** HTTP assertions share the real publication fixture and its already-withdrawn website. */
record ModerationAssertions(MockMvc mvc, ObjectMapper json, AuthService auth, SitePolicyService policies) {
    void verify(
            AuthPrincipal moderator,
            MockHttpSession adminSession,
            MockHttpSession authorSession,
            WorkspaceId workspace,
            Path repository,
            PublicContentSnapshots snapshots)
            throws Exception {
        String endpoint = "/api/auth/site/workspaces/" + workspace + "/review";
        String listing = mvc.perform(get(endpoint).session(adminSession))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(listing).contains("public/note.md").doesNotContain("private/secret.md", "Private sentinel");
        mvc.perform(get(endpoint)).andExpect(status().isUnauthorized());
        mvc.perform(get(endpoint).session(authorSession)).andExpect(status().isForbidden());
        mvc.perform(get(endpoint).session(adminSession).param("limit", "101")).andExpect(status().isBadRequest());
        String body = mvc.perform(
                        get(endpoint + "/document").session(adminSession).param("route", "/note"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.path").value("public/note.md"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(body).doesNotContain("Private sentinel", "private-author-sentinel", "private@example.invalid");
        String image = json.readTree(body)
                .get("media")
                .get("images")
                .get("picture.png")
                .stringValue();
        mvc.perform(get(image).session(adminSession)).andExpect(status().isOk());
        mvc.perform(get(image)).andExpect(status().isUnauthorized());
        mvc.perform(get(image).session(authorSession)).andExpect(status().isForbidden());
        String token = image.substring(image.lastIndexOf('/') + 1);
        mvc.perform(get("/api/public/assets/" + token)).andExpect(status().isNotFound());
        mvc.perform(get(image.replace(workspace.toString(), UUID.randomUUID().toString()))
                        .session(adminSession))
                .andExpect(status().isNotFound());
        for (String route : new String[] {"/secret", "/private/secret.md", "../private/secret.md"}) {
            mvc.perform(get(endpoint + "/document").session(adminSession).param("route", route))
                    .andExpect(status().isNotFound());
        }
        verifyRevocation(moderator, adminSession, endpoint, image);
        withdrawReference(repository, workspace, snapshots);
        mvc.perform(get(image).session(adminSession)).andExpect(status().isNotFound());
        mvc.perform(get(endpoint + "/document").session(adminSession).param("route", "/note"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.media.images").isEmpty());
    }

    private void verifyRevocation(AuthPrincipal moderator, MockHttpSession session, String endpoint, String image)
            throws Exception {
        AuthPrincipal backup =
                AccountFixtures.create(auth, "review-backup", UUID.randomUUID().toString());
        policies.change(moderator, backup.accountId(), SiteGroup.ADMINISTRATOR, "Retain site administration");
        policies.change(backup, moderator.accountId(), SiteGroup.VIEWER, "Revoke review permission");
        mvc.perform(get(endpoint).session(session)).andExpect(status().isForbidden());
        mvc.perform(get(image).session(session)).andExpect(status().isForbidden());
        policies.change(backup, moderator.accountId(), SiteGroup.ADMINISTRATOR, "Restore review permission");
    }

    private static void withdrawReference(Path repository, WorkspaceId workspace, PublicContentSnapshots snapshots)
            throws Exception {
        try (Git git = Git.open(repository.toFile())) {
            Files.writeString(repository.resolve("public/note.md"), "# Visible\n\nCorrected public content.\n");
            git.add().addFilepattern("public/note.md").call();
            git.commit()
                    .setAuthor("Fixture", "fixture@example.invalid")
                    .setMessage("Withdraw image reference")
                    .call();
            git.push().setRemote("origin").setPushAll().call();
        }
        snapshots.refresh(workspace);
    }
}
