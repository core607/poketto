package io.github.core607.poketto.auth;

/**
 * How one account belongs to one workspace. The role decides which capabilities may be
 * granted; it is not itself a capability and is never checked in place of one.
 */
public enum MembershipRole {
    OWNER,
    MEMBER
}
