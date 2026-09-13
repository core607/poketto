# Cross-space discovery: browser evidence

Demonstrated application and frontend revision: `465447a8944303526972e7cc0cce47503428f2a5`. Captured on 2026-09-14 (Asia/Hong_Kong); subsequent product edits only update documentation.

Started with `./gradlew stageAcceptanceRuntime` and `docker compose --env-file <ignored disposable environment> -p poketto-discovery -f acceptance/compose.yaml up -d --build`. Real Spring, PostgreSQL 17.11, production Next and Caddy used two independent synthetic Git repositories and a disposable owner. The multiple-workspace fixture was enabled. No production data or provider credentials were used.

Browser: Codex in-app browser. The [mixed-space page](mixed-spaces.jpg) uses the default 1280 by 720 desktop viewport, scrolled to show cards from both spaces. The [mobile homepage](mobile-home.jpg) uses a 390 by 844 override; its content scroll width was 375, with no horizontal overflow. Captures at 03:44:17 and 03:45:33 are separate unchanged screenshots from one run. Their saved image bytes were reopened and inspected.

## Observed flow

1. With the second website disabled, create a batch containing only the default space. Enable the second website through its owner panel. Returning to the existing batch keeps its four cards in the same order.
2. Open an article and use browser Back. The batch URL and card order are unchanged.
3. After the second repository snapshot becomes available, choose New batch. The resulting eight cards span both repositories: six on page one and two on page two, with eight unique article links. Previous page restores the same six-card order.
4. Disable the second website through its owner panel. Reload the same batch page: withdrawn cards disappear and the surviving default-space cards keep their original positions relative to one another.
5. Open an unknown batch ID. The explicit expired-batch page appears, and its new-batch link restores browsing.

The disposable containers, volumes and network were removed after acceptance. This run establishes local integration behavior, not production deployment, large-catalog capacity, complete site search, author metadata, album thumbnails or collection navigation. Do not merge this artifact branch into product history.
