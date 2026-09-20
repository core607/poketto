alter table content_repository_bindings alter column sealed_credentials drop not null;
alter table content_repository_bindings add column credential_kind text not null default 'TOKEN';
alter table content_repository_bindings add column github_account_id uuid references content_github_grants(account_id);
alter table content_repository_bindings add column github_owner_id bigint;
alter table content_repository_bindings add column github_installation_id bigint;

alter table content_repository_bindings add constraint repository_credential_kind check (
    (credential_kind = 'TOKEN' and sealed_credentials is not null
        and github_account_id is null and github_owner_id is null and github_installation_id is null)
    or
    (credential_kind = 'GITHUB_APP' and sealed_credentials is null and github_account_id is not null
        and github_owner_id is not null and github_owner_id > 0
        and github_installation_id is not null and github_installation_id > 0
        and provider_identity ~ '^github:[1-9][0-9]{0,18}$')
);
