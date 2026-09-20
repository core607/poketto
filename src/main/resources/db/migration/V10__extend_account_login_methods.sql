alter table auth_accounts add column verified_email text unique;
alter table auth_accounts add column display_name text not null default '';
update auth_accounts set display_name=login_name;
alter table auth_accounts add constraint account_display_name_length check (length(display_name)<=120);
alter table auth_accounts add column credential_version bigint not null default 0 check (credential_version>=0);
alter table auth_accounts alter column password_hash drop not null;

create table auth_external_identities (
    provider text not null check (provider='GOOGLE'),
    subject text not null check (length(subject) between 1 and 255),
    account_id uuid not null references auth_accounts,
    email text not null,
    linked_at timestamptz not null default current_timestamp,
    primary key(provider,subject),
    unique(provider,account_id)
);
