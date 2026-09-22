-- Account and workspace UUIDs are opaque cross-module references. Their existence and authority
-- are checked before writes; avoiding foreign-key locks keeps account recovery and Git publication
-- out of the community lock graph. Future account/workspace deletion must retire these records.
create table community_relations (
    account_id uuid not null,
    workspace_id uuid not null,
    article_id uuid not null,
    kind text not null check (kind in ('LIKE','BOOKMARK')),
    position bigserial not null unique,
    created_at timestamptz not null default current_timestamp,
    primary key (account_id,workspace_id,article_id,kind)
);
create index community_article_relations on community_relations(workspace_id,article_id,kind);
create index community_saved_items on community_relations(account_id,kind,position desc);

create table community_follows (
    position bigserial not null unique,
    account_id uuid not null,
    workspace_id uuid not null,
    created_at timestamptz not null default current_timestamp,
    primary key(account_id,workspace_id)
);

create table community_comments (
    comment_id uuid primary key,
    request_id uuid not null,
    position bigserial not null unique,
    workspace_id uuid not null,
    article_id uuid not null,
    author_id uuid not null,
    parent_id uuid references community_comments(comment_id),
    body text not null check (length(body) <= 4000),
    request_digest varchar(71) not null check(request_digest ~ '^sha256:[a-f0-9]{64}$'),
    created_at timestamptz not null default current_timestamp,
    deleted_at timestamptz,
    hidden_at timestamptz,
    unique(author_id,request_id),
    check (length(body) > 0 or deleted_at is not null)
);
create index community_article_comments on community_comments(workspace_id,article_id,parent_id,position desc);
create index community_replies on community_comments(parent_id,position desc);

create table community_blocks (
    blocker_id uuid not null,
    blocked_id uuid not null,
    position bigserial not null unique,
    primary key(blocker_id,blocked_id),
    check(blocker_id <> blocked_id)
);

create table community_notifications (
    position bigserial primary key,
    recipient_id uuid not null,
    comment_id uuid not null references community_comments(comment_id),
    read_at timestamptz,
    unique(recipient_id,comment_id)
);
create index community_inbox on community_notifications(recipient_id,position desc);

create table community_reports (
    position bigserial primary key,
    comment_id uuid not null references community_comments(comment_id),
    reporter_id uuid not null,
    reason text not null check(length(reason) between 1 and 1000),
    status text not null default 'OPEN' check(status in ('OPEN','DISMISSED','REMOVED')),
    created_at timestamptz not null default current_timestamp,
    reviewed_by uuid,
    reviewed_at timestamptz,
    unique(comment_id,reporter_id)
);
create index community_open_reports on community_reports(status,position desc);

create table community_rate_limits (
    account_id uuid not null,
    action text not null,
    window_start bigint not null,
    used integer not null,
    primary key(account_id,action)
);
