alter table auth_accounts add column site_group text not null default 'VIEWER'
    check (site_group in ('VIEWER','COMMUNITY','CREATOR','ADMINISTRATOR'));

update auth_accounts a set site_group = case
    when a.instance_admin then 'ADMINISTRATOR'
    when exists (
        select 1 from auth_memberships m where m.account_id=a.account_id
        and (m.role='OWNER' or m.permissions && array['WRITE_PRIVATE','PUBLISH']::text[])
    ) then 'CREATOR'
    else 'VIEWER'
end;
alter table auth_accounts drop column instance_admin;

create table auth_group_changes (
    change_id uuid primary key,
    actor_id uuid not null references auth_accounts,
    account_id uuid not null references auth_accounts,
    previous_group text not null,
    next_group text not null,
    reason text not null check (length(reason) between 1 and 500),
    changed_at timestamptz not null default current_timestamp
);
create index auth_group_changes_account on auth_group_changes(account_id,changed_at,change_id);

-- A derived relation, not a second publication switch. Member grants are unaffected.
create view website_owner_eligibility as
select w.workspace_id,
    exists(select 1 from auth_memberships m where m.workspace_id=w.workspace_id and m.role='OWNER')
    and not exists (
        select 1 from auth_memberships m join auth_accounts a using(account_id)
        where m.workspace_id=w.workspace_id and m.role='OWNER'
        and a.site_group not in ('CREATOR','ADMINISTRATOR')
    ) as eligible
from workspaces w;
