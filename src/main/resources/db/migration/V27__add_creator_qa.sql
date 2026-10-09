create table qa_control (singleton boolean primary key check(singleton));
insert into qa_control values (true);

create table qa_budget_days (
    day date primary key,
    spent_micros bigint not null default 0 check (spent_micros >= 0),
    reserved_micros bigint not null default 0 check (reserved_micros >= 0)
);

create table qa_runs (
    account_id uuid not null,
    request_id uuid not null,
    channel varchar(8) not null check (channel in ('WEB','WISH')),
    credential_id uuid,
    requested_provider varchar(32) not null,
    provider varchar(32) not null check (provider in ('anthropic','deepseek')),
    model varchar(128) not null,
    fallback_reason varchar(64),
    input_price numeric(18,8) not null check(input_price > 0),
    output_price numeric(18,8) not null check(output_price > 0),
    budget_day date not null references qa_budget_days(day),
    status varchar(16) not null check (status in ('RUNNING','WAITING','COMPLETED','FAILED')),
    revision integer not null default 0,
    calls integer not null default 0,
    input_tokens bigint not null default 0,
    output_tokens bigint not null default 0,
    cost_micros bigint not null default 0,
    reserved_micros bigint not null check (reserved_micros >= 0),
    call_bound_micros bigint not null,
    in_flight boolean not null default false,
    uncertain boolean not null default false,
    candy_reserved boolean not null default false,
    error_code varchar(64),
    created_at timestamptz not null,
    expires_at timestamptz not null,
    primary key(account_id, request_id)
);
create index qa_account_days on qa_runs(account_id, budget_day, channel);
create index qa_pending on qa_runs(expires_at) where status in ('RUNNING','WAITING');

create table qa_anthropic_months (
    month date primary key check (extract(day from month) = 1),
    spent_micros bigint not null default 0 check(spent_micros >= 0),
    reserved_micros bigint not null default 0 check(reserved_micros >= 0)
);
