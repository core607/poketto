alter table auth_accounts add column suspended_at timestamp with time zone;
alter table auth_accounts add column suspension_reason text check (length(suspension_reason) between 1 and 500);
alter table auth_accounts add constraint account_suspension_reason
    check ((suspended_at is null) = (suspension_reason is null));

create table auth_suspension_changes (
    change_id uuid primary key,
    actor_id uuid not null references auth_accounts,
    account_id uuid not null references auth_accounts,
    suspended boolean not null,
    reason text not null check (length(reason) between 1 and 500),
    changed_at timestamp with time zone not null default current_timestamp
);
create index auth_suspension_changes_account on auth_suspension_changes(account_id, changed_at desc);

-- A suspended owner cannot keep a website public, exactly like an owner without creator eligibility.
create or replace view website_owner_eligibility as
select w.workspace_id,
    exists(select 1 from auth_memberships m where m.workspace_id=w.workspace_id and m.role='OWNER')
    and not exists (
        select 1 from auth_memberships m join auth_accounts a using(account_id)
        where m.workspace_id=w.workspace_id and m.role='OWNER'
        and (a.site_group not in ('CREATOR','ADMINISTRATOR') or a.suspended_at is not null)
    ) as eligible
from workspaces w;

create table creator_applications (
    application_id uuid primary key,
    account_id uuid not null references auth_accounts,
    statement text not null check (length(statement) between 1 and 1000),
    status text not null default 'OPEN' check (status in ('OPEN','APPROVED','DECLINED')),
    created_at timestamp with time zone not null default current_timestamp,
    decided_by uuid references auth_accounts,
    decided_at timestamp with time zone,
    decision_note text check (length(decision_note) <= 500),
    check ((status = 'OPEN') = (decided_at is null))
);
create unique index creator_applications_one_open on creator_applications(account_id) where status='OPEN';
create index creator_applications_queue on creator_applications(status, created_at);
create index creator_applications_account on creator_applications(account_id, created_at desc);
