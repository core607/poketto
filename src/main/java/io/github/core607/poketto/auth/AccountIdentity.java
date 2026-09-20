package io.github.core607.poketto.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

/** Account identity is independent of membership in any workspace. */
public record AccountIdentity(UUID accountId, String loginName, String displayName, SiteGroup group) {
    @JsonProperty
    public boolean siteAdministrator() {
        return group.administrator();
    }
}
