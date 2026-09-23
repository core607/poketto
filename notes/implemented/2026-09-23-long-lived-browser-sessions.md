# Long-Lived Browser Sessions

Date: 2026-09-23
Status: Implemented

## Problem

[Invitation-only membership](2026-08-27-invitation-only-membership.md) and
[workspace identity](2026-09-06-workspace-identity-http.md) record browser sessions that
expire after 30 minutes of inactivity and live in the servlet container's memory. Poketto is a
notebook and a website people return to over days, and every merge to `main` redeploys
production. The consequences:

- A reader signs in again whenever they leave a tab alone for half an hour.
- Every deploy signs everyone out.

That is stricter than comparable writing and code-hosting sites. It also buys no protection the
design lacks otherwise: every request already revalidates the account.

## Decision

Browser sessions are stored in PostgreSQL through Spring Session JDBC, in the tables created by
migration `V18`, so a restart or deploy keeps them.

A session starts with the 30-minute anonymous idle timeout. `AccountSessionLifetime` raises
it to `poketto.security.account-session-idle-days`, which defaults to 90 and slides with every
request, at the moment a route stores an account in the session:
- password login in its success handler;
- Google login right after it saves the security context.

Sign-up creates no session; the browser signs in with the new password afterwards.
`WorkspaceIdentityFilter`, which validates the stored account on each browser request, applies
the same lifetime again, which covers sessions stored before this change. Sessions created only
for a CSRF token keep the short timeout, so anonymous traffic cannot fill the table with
long-lived rows.

The `POKETTO_SESSION` cookie carries a 400-day `Max-Age`, the longest lifetime current browsers
keep, so closing the browser does not end the session. The server-side idle expiry is the bound
that matters. The existing controls still revoke a session early:
- `HttpOnly`, `Secure` and `SameSite=Lax` stay on the cookie;
- the session ID rotates at login;
- logout deletes the stored row;
- a password change or suspension changes the credential version that each request checks.

Session attributes are Java-serialized. The stored types are:
- the security context with its `AuthPrincipal`;
- the CSRF token;
- the pending OAuth consent requests;
- the Google and GitHub authorization flows.

Each of these types is `Serializable`. Reading applies an `ObjectInputFilter` that admits only
application, Spring Security and JDK classes, within depth, reference and size limits. An
attribute that does not read back, for example one written by a release whose classes changed,
counts as absent: the visitor is signed out and signs in again, and does not see an error. A
value that cannot be serialized is logged as an error and stored empty, which also reads as
absent, so a new session type that misses `Serializable` does not fail the request that stores
it. The PostgreSQL customizer writes attributes with `ON CONFLICT` upserts.

## Alternatives

- **Keep container sessions and lengthen the timeout.** Memory then holds weeks of sessions,
  and deploys still sign everyone out.
- **Spring Security remember-me tokens with short container sessions.** This survives
  restarts, but every sign-in route (password, Google, sign-up) would have to issue and rotate
  its own token, and flows held in the session, such as OAuth consent, would still vanish on a
  deploy. Spring Session covers every route with one store.
- **Stateless signed tokens (JWT) in cookies.** These cannot be revoked at logout without a
  server-side list, and they duplicate the CSRF and fixation protections that sessions already
  provide.
- **Require recent re-authentication for sensitive actions ("sudo mode").** This is
  compatible with this decision but is a separate change. Today every action relies on the
  per-request credential check.

## Consequences

- Every browser request that carries a session reads it from PostgreSQL, and a changed
  attribute or the last-access time is written back. For one instance's traffic this is small next to the
  content work the same requests do. Spring Session's cleanup job removes expired rows every
  minute.
- Session writes use Spring Session's immediate flush mode, so an attribute removal reaches
  PostgreSQL when it happens rather than when its request ends. A running request still holds
  its own copy, so a flow that must notice a change made by another request of the same browser
  reads the saved session. The GitHub connection callback does this before storing a grant, so a
  disconnect from another tab while the callback exchanges its code stops the connection, as it
  did when both requests shared one in-memory session.
- A value that a request reads from the session and then mutates in place is no longer saved.
  Such code must write the attribute back. OAuth consent now does this when it removes the
  answered request, so a later consent for the same request fails.
- `synchronized (session)` no longer serializes concurrent requests, because each request gets
  its own session object.
  - Two simultaneous consents for one pending request could each return a code. Both come from
    the same signed-in visitor for the same client, and each code is still single-use and bound
    to its PKCE challenge.
  - Two simultaneous Google or GitHub callbacks for one stored state could both pass the state
    check before either removes it. The provider's authorization code is single-use, so only one
    exchange succeeds; the other ends as a failed sign-in or connection.
  - Moving these one-time values to atomic database rows would close the window. It is not done
    here, because the providers already reject the replay.
- Most integration tests keep MockMvc container sessions: the `integrationTest` task excludes
  the session auto-configuration. `BrowserSessionStoreIntegrationIT` opts back in and exercises
  the production store over real HTTP.

## Verification

- `BrowserSessionConfigurationTests` round-trips the following through the attribute
  conversion:
  - the signed-in context and the CSRF token;
  - pending OAuth requests;
  - the GitHub and Google flows.

  It also shows that classes outside the allow-list, damaged bytes and a value that cannot be
  serialized all read as absent.
- `BrowserSessionStoreIntegrationIT` checks the store over real HTTP:
  - an anonymous CSRF session has a 30-minute idle timeout and a 400-day cookie;
  - the login response alone leaves the row with the 90-day timeout;
  - the next request authenticates from the stored row, which names the account;
  - logout deletes the row;
  - an unreadable stored context answers 401, not an error.
- `GoogleIdentityHttpIntegrationIT` checks that a Google login leaves its session with the
  90-day timeout.
