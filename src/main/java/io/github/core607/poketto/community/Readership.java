package io.github.core607.poketto.community;

import java.util.OptionalLong;

/** Anonymous daily readers of public articles, counted without cookies or stored client identities. */
public interface Readership {
    /**
     * Counts one reader of a currently public route for the current UTC day. Repeated reports from one
     * client, crawler user agents, unpublished spaces and routes outside the current snapshot are
     * ignored without telling the caller.
     */
    void record(String space, String route, String client, String userAgent);

    /** All-time readers of a currently public route; empty when the space or the route is not public. */
    OptionalLong total(String space, String route);
}
