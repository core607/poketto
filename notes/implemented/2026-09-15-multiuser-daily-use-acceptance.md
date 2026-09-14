# Multi-user Daily-use Acceptance

Date: 2026-09-15

## Delivery boundary

The phase-one and multi-user contracts are delivered on the authorized HTTPS installation.
Application revision `9474a8121c3aadb9cacd236f005a77b54a0eafd1` contains the account-copy,
mutable-baseline, workspace synchronization, search and editor changes. Its frontend
tree is identical to the demonstrated daily-use UI revision
`577384c12a1c569bfbeb9cdb86ae016af5a7c9e5`. Subsequent evidence-only changes do not change
runtime behavior. A release tag is a separate operator action.

## Evidence and limits

| Contract | Evidence |
|---|---|
| Registration, separate invitations, no-space accounts and owner boundaries | `RegistrationIntegrationIT` covers policy defaults, distinct credential kinds, expiry, rollback and concurrent single redemption against PostgreSQL. Existing account-entry and administrator-CLI browser evidence verifies login without anonymous initialization and no-space management. |
| Existing repository connection and retry | [Real GitHub acceptance](../../acceptance/evidence/2026-09-15-provider-connection.json) verifies credential rejection, same-request retry, authoritative lookup, idempotent replay, duplicate rollback, encrypted storage and private defaults. `SpaceCreationIntegrationIT` additionally covers overlapping requests, abandoned leases and service-restart replay. No remote ref changes; CNB interoperability is not claimed by the GitHub run. |
| Account/workspace isolation and current grants | `WorkspaceEntrancesIntegrationIT` writes independent Git authorities through separate HTTP routes. Member-permission browser evidence verifies public-only save/move and private denial. [Native HTTP acceptance](../../acceptance/clients/evidence/2026-09-15-baseline-state.json) covers separate reading scopes, changing grants, revocation and disposal. |
| Durable copies and resource bounds | [Account working copies](2026-09-14-account-working-copies.md) owns account/workspace/scope identity, XFS quotas, serialization, expiry and reattachment. The deployment has a 32 GiB pool, 4 GiB per-copy limits and four execution slots. The [real-corpus sample](../../acceptance/evidence/2026-09-15-real-corpus-worker-timing.json) measures initial native copying and twenty reuses on its recorded earlier worker revision, not provider-network or current end-to-end latency. |
| Timeout retention, save, synchronization and uncertain writes | Native HTTP acceptance observes timeout-preserved bytes, consecutive saves advancing Git HEAD/index, independent authoritative readback, remote additions/deletions and conflicts. Retained-journal and native worker checks cover interrupted local installation. The production connector retained its copy and file fingerprint across worker/application replacement; synchronization and remote/base/Git equality were independently checked. |
| Public delivery, images and withdrawal | The workspace-public-delivery, native media/storage and browser records verify private defaults, member/public separation and invalidation of issued grants. Production HTTPS, robots and sitemap return successfully; this does not replace the broader synthetic authorization scenarios. Managed originals were not deleted by execution or acceptance cleanup. |
| Discovery, albums, collections and authors | Existing discovery-card, album-thumbnail and collection-navigation browser records cover mixed cards, public signatures, original/thumbnail separation, authored sequence, keyboard navigation and reading returns. These owning frontend components are retained by the final delivery. |
| Search, administration and saved/public state | [Daily-use UI acceptance](../../acceptance/evidence/2026-09-14-daily-use-ui.json) verifies two spaces, 158 search results, filename pagination, dirty navigation, independent Git readback, metadata failures, website withdrawal and mobile layouts. |
| Reading without application scripts | [Public reading acceptance](../../acceptance/evidence/2026-09-15-public-reading.json) verifies initial HTML, article links, native search/pagination and valid images with page scripts prohibited by CSP. It does not claim a global browser setting or script-dependent modal behavior. |
| Verification and installation | Required CI and main deployment succeeded for the runtime revision. Application/frontend revisions agree; eleven installed worker source hashes match the delivered tree. PostgreSQL, Linux storage, native executor, frontend, repository and deployment checks cover their owning surfaces. |

The callable connector provides real model-driven execution evidence. Its cached catalog
may retain an older description or omit `repo_discard`; fresh authenticated HTTP/native
integration verifies the current server catalog and explicit disposal. External chat
clients unavailable to the operator are not acceptance conditions and are not reported
as tested. Browser fixtures and real-provider validation remain distinct from production
user content. All disposable acceptance stacks, volumes and native pools were removed.

## Record lifecycle

The phase-one and multi-user records now describe implemented contracts. Their subsystem
records remain authoritative for ownership, permissions, storage, publication and conflict
semantics. This acceptance record consolidates evidence; it does not replace those decisions
or broaden the delivery. Backups, off-host recovery, visitor Q&A, automatic provider-side
repository creation and other stated exclusions remain outside this release preparation.
