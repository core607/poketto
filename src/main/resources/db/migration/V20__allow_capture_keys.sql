-- A capture key only creates notes in the private inbox; see
-- notes/implemented/2026-09-24-capture-inbox.md.
alter table auth_api_keys drop constraint auth_api_keys_capabilities_check;
alter table auth_api_keys add constraint auth_api_keys_capabilities_check check (capabilities <@ array[
    'READ_PRIVATE', 'WRITE_PRIVATE', 'PUBLISH', 'MANAGE_KEYS', 'EXECUTE_REPOSITORY', 'CAPTURE'
]::text[]);
