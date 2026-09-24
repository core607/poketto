-- Anonymous daily readers per public route. Client identities are never stored; see
-- notes/implemented/2026-09-24-public-view-counts.md.
create table article_views (
    workspace_id uuid not null,
    route text not null check (char_length(route) between 1 and 2048),
    day date not null,
    views integer not null check (views > 0),
    primary key (workspace_id, route, day)
);
