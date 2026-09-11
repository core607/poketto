package io.github.core607.poketto.auth;

import java.util.UUID;

/** Account identity is independent of membership in any workspace. */
public record AccountIdentity(UUID accountId, String loginName, boolean siteAdministrator) {}
