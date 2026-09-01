# Consumer Accounts and Personal Workspaces

Date: 2026-09-01
Status: Proposed

## Problem

The implemented [workspace boundary](../implemented/2026-08-27-workspace-tenancy.md) makes a workspace the tenant and allows one account to belong to several workspaces, but the executable topology creates only one default workspace and exposes no self-service creation entrance. The [invitation proposal](2026-08-27-invitation-only-membership.md) explains how an account joins an existing workspace; it does not define the personal workspace created for a new consumer.

Poketto's consumer direction needs each registered person to receive a private workspace without turning the account itself into a storage or authorization scope. That provisioning must work first on the primary single-server deployment and remain independent of any optional serverless infrastructure.

## Proposal

### Account and workspace model

- An `account` is a human identity. A `workspace` remains the tenant, remote repository, authoritative asset namespace, quota, audit, backup, and data-destruction boundary.
- One account may own or join several workspaces. Roles belong to memberships, never globally to the account.
- The consumer account-creation entrance requests one personal workspace and one `OWNER` membership through an idempotent provisioning operation. It does not grant access to an existing workspace; that remains invitation-only.
- Workspace names, public slugs, and custom domains are presentation values. The immutable `WorkspaceId` scopes every repository, blob, row, credential, budget, job, cache, and audit operation.

### Provisioning and visibility

Provisioning is a durable state machine because an account row, workspace catalog row, membership, remote repository, and asset namespace cannot be created in one storage transaction. Every step is idempotent and keyed by a recorded provisioning identifier and `WorkspaceId`. Duplicate delivery resumes the same operation; reuse of an idempotency key for different registration input fails.

A workspace is not routable until its relational state, remote repository authority, and configured asset BlobStore namespace report ready. Failed provisioning remains visible only to the affected account and instance operator. Cleanup removes only resources created for that `WorkspaceId`; it never guesses ownership from provider names or paths. The asset namespace cannot initialize lazily because managed-only images are not rebuildable from Git.

Registration, login identifiers, password handling, sessions, throttling, and account suspension reuse the security contract selected by the invitation-membership implementation. Public enablement must add explicit abuse prevention, recovery, and verification behavior before claiming internet-ready open registration; those controls do not change the account/workspace boundary fixed here.

### Ownership and lifecycle

The personal workspace starts with exactly one active `OWNER`. Ownership transfer, account deletion, workspace deletion, billing cancellation, and retention require later lifecycle decisions and are unavailable until those decisions define recovery and the last-owner invariant. An account may be suspended without silently deleting or transferring its workspaces.

Quotas and usage accounting attach to `WorkspaceId`. Billing may later aggregate several workspaces under one account or subscription, but it cannot weaken workspace isolation or create an account-wide content scope.

## Implementation scope and dependencies

The first implementation depends on the account, session, and membership foundation from [invitation-only membership](2026-08-27-invitation-only-membership.md), the binding contract from [remote repository authority](2026-09-01-remote-repository-authority.md), and the local namespace contract from [asset BlobStore and Git synchronization](2026-09-01-repository-asset-blob-store.md). It adds the provider adapter that creates an isolated private repository, durable provisioning state, personal-workspace creation entrance, asset namespace, owner membership, retry and cleanup behavior, authorization, audit events, and focused failure-injection and isolation tests.

It targets the primary single-server profile first, but every personal workspace receives remote Git authority. Repository provisioning is therefore shared product infrastructure rather than a serverless adapter. Its start gate requires an isolated non-production provider account and narrowly scoped credentials capable of creating private repositories; without them the proposal remains pending rather than substituting local authority. The optional serverless profile later changes the asset BlobStore, database, and SRT deployment without changing consumer identity or repository ownership. Email or SMS delivery, OAuth, social login, passkeys, billing, custom domains, ownership transfer, account recovery, and destructive deletion remain outside this implementation.

## Alternatives considered

**Make the account the tenant.** This works only while each person owns exactly one knowledge space and never collaborates. It would put account identifiers into every storage and authorization key and make later sharing a security-model rewrite.

**Create a workspace only after the first document write.** Lazy creation shortens registration but lets an account exist in a partially authorized state and moves multi-store failure handling into an unrelated content operation.

**Use invitations to create every consumer account and workspace.** Invitations express access to an existing tenant. Reusing them for personal provisioning would confuse joining with ownership and prevent a true registration entrance.

**Wait for the serverless deployment profile.** Remote repository provisioning works on the primary single-server deployment and is part of the consumer product rather than request-host topology. Deferring the product model to optional infrastructure would block independently useful work.

## Acceptance

- Duplicate and concurrent delivery creates exactly one account, personal workspace, `OWNER` membership, private remote repository, and asset namespace for one provisioning operation.
- No route, list, API key, background task, cache, log, metric, or error exposes the workspace before provisioning completes or reveals another consumer's failed provisioning.
- A retry resumes recorded state after failure at every storage boundary. Cleanup touches only resources proven to belong to the recorded `WorkspaceId`.
- One account may own or join several workspaces without an account-wide role or content scope. Joining someone else's workspace still requires an invitation.
- Missing and unauthorized personal workspaces remain indistinguishable to other consumers. Workspace quotas, credentials, audits, and usage records carry `WorkspaceId`.
- The implementation exercises an isolated provider repository fixture, the real local BlobStore adapter, and PostgreSQL under duplicate delivery, injected failure, ambiguous provider response, restart, and two-account isolation tests.
- The relevant automated tests, `./gradlew repoCheck`, and `git diff --check` pass.

## Risks

Public registration creates an anonymous abuse surface. The provisioning mechanism can be implemented locally, but internet exposure must remain disabled until rate limits, verification, recovery, and operational suspension are accepted and tested.

Multi-store provisioning cannot be atomic. Durable progress, idempotent resource creation, and ownership-aware cleanup limit partial failure; they do not remove the need for operator-visible recovery state.

Personal workspaces increase the fleet of repositories. Maintenance and quotas require bounded scheduling before registration volume grows.
