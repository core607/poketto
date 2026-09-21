# GitHub App configuration

The App connects a signed-in Poketto account to that person's private repositories.
It does not provide Poketto login or organization-owned spaces.

## Register the App

Use the site's exact public HTTPS origin as the homepage. Set the user
authorization callback to `<POKETTO_OAUTH_ISSUER>/api/auth/workspaces/github/callback`.
Keep user access token expiration enabled. Leave OAuth during installation and
device flow disabled; authorization starts from Poketto with its own browser
state. Request repository Administration: Read and write, Contents: Read and
write, and Metadata: Read-only.
Do not request account email or organization permissions. Allow installation by
other accounts if the site serves users beyond the App owner.
The [GitHub registration reference](https://docs.github.com/en/apps/creating-github-apps/registering-a-github-app/registering-a-github-app)
describes these settings.

Set the webhook URL to `<POKETTO_OAUTH_ISSUER>/api/hooks/github`, keep SSL
verification enabled, and subscribe to Repository events. Authorization and
installation events are delivered automatically. Use a randomly generated
32–256-character webhook secret.

## Protected settings

Set `POKETTO_REPOSITORY_CREDENTIAL_KEY` on the host before enabling the App, as
described in the [usage reference](usage.md#content-and-images). Keep this existing
encryption key when changing App credentials; replacing it makes stored
repository credentials unreadable.

| Setting | Value |
|---|---|
| `POKETTO_GITHUB_APP_ID` | Numeric App ID |
| `POKETTO_GITHUB_CLIENT_ID` | App client ID, also used as the App JWT issuer |
| `POKETTO_GITHUB_CLIENT_SECRET` | Generated App client secret |
| `POKETTO_GITHUB_PRIVATE_KEY` | Single-line Base64 encoding of the unencrypted PKCS#8 RSA signing key |
| `POKETTO_GITHUB_WEBHOOK_SECRET` | The exact webhook secret configured in GitHub |

For CI delivery, configure all five as repository or production-environment
GitHub Secrets. For manual delivery, use the host's protected configuration or
literal `KEY=value` deployment input. CI explicitly sends empty values for
missing secrets, clearing previous host values. Manual updates retain omitted
fields. Both deployment layouts reject incomplete App setting updates before
replacing containers. An existing installation must install the current protected
`deploy/update-existing.py` before it can accept these fields.

Clearing both the client secret and private key disables new authorization and
App-backed repository operations, including synchronization of existing spaces.
Retaining the App ID, client ID and webhook secret keeps revocation processing
available in that state. Workspace contents and memberships are retained;
manual-token connections are unaffected. Clear all five to disable the
integration completely. Restoring settings does not undo a processed revocation:
users must restore GitHub permission and
explicitly reconnect the same repository.

## Convert the downloaded key

From the repository root, use Python 3.10+ and OpenSSL 3+ on PATH. The converter
accepts one unencrypted PKCS#1 or PKCS#8 RSA PEM with a 2048–8192-bit key and writes
one Base64 line. It validates the key before producing output and uses only
process pipes for key material.
It uses OpenSSL's [PKCS#8 conversion](https://docs.openssl.org/3.5/man1/openssl-pkcs8/)
after checking the RSA key.

For example, in Bash or Git Bash with GitHub CLI authenticated to the target
repository, this uploads only after conversion succeeds:

```bash
if github_key="$(python deploy/convert-github-key.py < downloaded-app-key.pem)"; then
    printf '%s' "$github_key" | gh secret set POKETTO_GITHUB_PRIVATE_KEY
    unset github_key
fi
```

Use `gh secret set --env production POKETTO_GITHUB_PRIVATE_KEY` instead when
deployment settings belong to the production environment. Do not paste the raw
multiline PEM into the line-oriented deployment settings. The converter reports
fixed diagnostics without repeating the input or OpenSSL error output.

## Verify the connection

An eligible signed-in user authorizes GitHub from account settings and installs
the App on the same personal account through **Manage GitHub repository access**.
GitHub requires at least one existing repository for a selected-repository
installation; an empty private repository can serve this purpose without granting
access to existing content. The user then confirms an unused repository name and
creates the private repository. GitHub [automatically grants access to repositories
created by the App](https://docs.github.com/en/apps/using-github-apps/installing-your-own-github-app).
If access is missing, select the new repository and continue the original request.
Verify initial content and synchronization, remove access, and confirm
that writes stop. Restore provider permission and use **Verify and restore
connection** on the space's Repository connection tab.

Successful configuration parsing is not provider acceptance. Real verification
must exercise GitHub authorization, repository creation, selected-repository
installation tokens, synchronization, signed revocation and explicit reconnection.
