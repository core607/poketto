# Signed in without workspace membership

Source: `6a5bdd2d410432282acd0782f75308e7c83a2d35`, clean working tree. Screenshot: `signed-in-without-space.jpg`.

The image shows a registered ordinary account signed in without a workspace membership. Account management offers the separate workspace-joining form; it does not show repository content, administrator setup, or empty invitation-issuance controls for an ineligible account.

The final isolated stack was rebuilt with `./gradlew stageAcceptanceRuntime` and `docker compose -p poketto-account-20260911 --env-file .gradle/account/fixture.env -f acceptance/compose.yaml up --build -d`, after removing that fixture's earlier containers and named volumes. Backend sources did not change after runtime staging. Dependencies are real Spring services, PostgreSQL 17, production Next.js frontend, and Caddy; only account and repository data are synthetic. No production content repository is accessed.

An operator fixture account issued a registration invitation through the real API; the guest redeemed it without accepting a workspace invitation. A fresh Chrome tab opened the actual login page, signed in as that account, and waited for both the no-workspace message and absence of ineligible invitation controls before capture. Viewport: 1920 x 945. All credential fields were empty in the captured state.

This screenshot proves the account-management state, not the multi-step registration interaction. A separate manual run of the same registration/join behavior successfully copied two independent invitation types from their browser controls, registered a guest, confirmed its no-space state, and then joined a space explicitly. HTTP and browser tests cover those transitions, consumed-code login retry, suspended membership, and retained invitation controls after issuance is disabled. Full workspace creation, multi-workspace selection, and the new member-permission defaults belong to later changes.
