package io.github.core607.poketto.auth;

/**
 * How one account belongs to one workspace. The role decides which capabilities may be granted to
 * a key, and for membership work it is the admission test itself: inviting, revoking and listing
 * members require an owning human account and check no named capability. Someone adding such an
 * operation should not assume a capability check stands behind the role.
 */
public enum MembershipRole {
    OWNER,
    MEMBER
}
