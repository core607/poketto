create table plaza_wallets (
    account_id uuid primary key,
    balance bigint not null default 0 check (balance >= 0),
    claimed_day date not null
);

create table community_agent_signatures (
    account_id uuid primary key,
    signature text not null check (char_length(signature) <= 80)
);

alter table community_comments add column agent_posted boolean not null default false;
create index community_machine_wall on community_comments(position desc)
    where agent_posted and parent_id is null and hidden_at is null and deleted_at is null;
