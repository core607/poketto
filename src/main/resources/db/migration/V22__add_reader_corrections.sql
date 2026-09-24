-- Corrections are keyed by route because most articles carry no frontmatter id. The base digest
-- ties a proposal to the exact body the reader started from; bodies are cleared 90 days after
-- resolution while the row keeps credit and notification history.
create table community_corrections (
    correction_id uuid primary key,
    position bigserial not null unique,
    workspace_id uuid not null,
    route text not null check (char_length(route) between 1 and 256),
    author_id uuid not null,
    base_digest varchar(71) not null check (base_digest ~ '^sha256:[a-f0-9]{64}$'),
    proposed_body text not null,
    reason text not null check (char_length(reason) <= 500),
    status text not null default 'OPEN'
        check (status in ('OPEN','ACCEPTED','DECLINED','WITHDRAWN','STALE')),
    created_at timestamptz not null default current_timestamp,
    resolved_at timestamptz,
    resolver_id uuid,
    commit_id varchar(40),
    check ((status = 'OPEN') = (resolved_at is null))
);
create unique index community_open_correction on community_corrections(author_id, workspace_id, route)
    where status = 'OPEN';
create index community_workspace_corrections on community_corrections(workspace_id, status, position desc);
create index community_route_credits on community_corrections(workspace_id, route)
    where status = 'ACCEPTED';

alter table community_notifications alter column comment_id drop not null;
alter table community_notifications
    add column correction_id uuid references community_corrections(correction_id),
    add column event text check (event in ('PROPOSED','ACCEPTED','DECLINED','STALE')),
    add constraint community_notification_subject check (
        (comment_id is not null and correction_id is null and event is null)
        or (comment_id is null and correction_id is not null and event is not null));
create unique index community_correction_notifications
    on community_notifications(recipient_id, correction_id, event) where correction_id is not null;
