# Consumer Accounts and Personal Workspaces

Date: 2026-09-01
Status: Rejected

[GitHub-authorized personal spaces](../implemented/2026-09-21-github-authorized-personal-spaces.md)
implements explicit repository creation by an eligible signed-in user. It does not
revive this record's automatic workspace provisioning during registration.

[Consumer identity](../implemented/2026-09-20-consumer-identity-and-site-policy.md) opens verified account registration without creating a workspace, and [multi-user spaces](../implemented/2026-09-11-multiuser-workspaces-and-discovery.md) keep workspace creation an explicit act. Automatically creating a personal repository during registration adds provider-side lifecycle and credential requirements that are not selected. This record retains that alternative's constraints if automatic provisioning is reconsidered.

## Problem

The implemented [workspace boundary](../implemented/2026-08-27-workspace-tenancy.md) makes a workspace the tenant and allows one account to belong to several workspaces, but the executable topology creates only one default workspace and exposes no self-service creation entrance. The [invitation proposal](../implemented/2026-08-27-invitation-only-membership.md) explains how an account joins an existing workspace; it does not define the personal workspace created for a new consumer.

Poketto's consumer direction needs each registered person to receive a private workspace without turning the account itself into a storage or authorization scope. That provisioning must work first on the primary single-server deployment and remain independent of any optional serverless infrastructure.

## Proposal

### Account and workspace model

- An `account` is a human identity. A `workspace` remains the tenant, remote repository, authoritative managed-asset namespace, repository-image cache scope, quota, audit, backup, and data-destruction boundary.
- One account may own or join several workspaces. Roles belong to memberships, never globally to the account.
- The consumer account-creation entrance requests one personal workspace and one `OWNER` membership through an idempotent provisioning operation. It does not grant access to an existing workspace; that remains invitation-only.
- Workspace names, public slugs, and custom domains are presentation values. The immutable `WorkspaceId` scopes every repository, blob, row, credential, budget, job, cache, and audit operation.

### Provisioning and visibility

Provisioning is a durable state machine because an account row, workspace catalog row, membership, remote repository, and managed-object scope cannot be created in one storage transaction. Every step is idempotent and keyed by a recorded provisioning identifier and `WorkspaceId`. Duplicate delivery resumes the same operation; reuse of an idempotency key for different registration input fails.

A workspace is not routable until its relational state, remote repository authority, and configured ManagedBlobStore scope report ready. Failed provisioning remains visible only to the affected account and instance operator. Cleanup removes only resources created for that `WorkspaceId`; it never guesses ownership from provider names or paths. Managed images are not rebuildable from Git, while a repository-image cache remains disposable and never participates in readiness.

Registration, login identifiers, password handling, sessions, throttling, and account suspension reuse the security contract selected by the invitation-membership implementation. Public enablement must add explicit abuse prevention, recovery, and verification behavior before claiming internet-ready open registration; those controls do not change the account/workspace boundary fixed here.

### Ownership and lifecycle

The personal workspace starts with exactly one active `OWNER`. Ownership transfer, account deletion, workspace deletion, billing cancellation, and retention require later lifecycle decisions and are unavailable until those decisions define recovery and the last-owner invariant. An account may be suspended without silently deleting or transferring its workspaces.

Quotas and usage accounting attach to `WorkspaceId`. Billing may later aggregate several workspaces under one account or subscription, but it cannot weaken workspace isolation or create an account-wide content scope.

## Implementation scope and dependencies

The proposal needed a provider adapter that creates an isolated private repository, durable provisioning state, a personal-workspace creation entrance, managed-object scope, owner membership, retry and cleanup behavior, and audit events. Repository provisioning was shared product infrastructure on the single-server deployment rather than a serverless adapter. Its start gate required an isolated non-production provider account and narrowly scoped credentials able to create private repositories. Email or SMS delivery, social login, billing, custom domains, ownership transfer, account recovery, and destructive deletion were outside it.

## Alternatives considered

**Make the account the tenant.** This works only while each person owns exactly one knowledge space and never collaborates. It would put account identifiers into every storage and authorization key and make later sharing a security-model rewrite.

**Create a workspace only after the first document write.** Lazy creation shortens registration but lets an account exist in a partially authorized state and moves multi-store failure handling into an unrelated content operation.

**Use invitations to create every consumer account and workspace.** Invitations express access to an existing tenant. Reusing them for personal provisioning would confuse joining with ownership and prevent a true registration entrance.

**Wait for the serverless deployment profile.** Remote repository provisioning works on the primary single-server deployment and is part of the consumer product rather than request-host topology. Deferring the product model to optional infrastructure would block independently useful work.

## Risks

Public registration creates an anonymous abuse surface. The provisioning mechanism can be implemented locally, but internet exposure must remain disabled until rate limits, verification, recovery, and operational suspension are accepted and tested.

Multi-store provisioning cannot be atomic. Durable progress, idempotent resource creation, and ownership-aware cleanup limit partial failure; they do not remove the need for operator-visible recovery state.

Personal workspaces increase the fleet of repositories. Maintenance and quotas require bounded scheduling before registration volume grows.
