package io.github.core607.poketto.auth;

/** Site participation never supplies workspace membership or private-content authority. */
public enum SiteGroup {
    VIEWER,
    COMMUNITY,
    CREATOR,
    ADMINISTRATOR;

    public boolean mayCreateSpace() {
        return this == CREATOR || this == ADMINISTRATOR;
    }

    public boolean administrator() {
        return this == ADMINISTRATOR;
    }
}
