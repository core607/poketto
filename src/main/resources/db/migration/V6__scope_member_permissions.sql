alter table auth_memberships add column permissions text[] not null default '{}';
alter table auth_memberships add constraint membership_content_permissions check (
    permissions <@ array['READ_PRIVATE','WRITE_PRIVATE','PUBLISH']::text[]
    and (not ('WRITE_PRIVATE'=any(permissions)) or 'READ_PRIVATE'=any(permissions))
);
alter table auth_invitations add column permissions text[] not null default '{}';
alter table auth_invitations add constraint invitation_content_permissions check (
    permissions <@ array['READ_PRIVATE','WRITE_PRIVATE','PUBLISH']::text[]
    and (not ('WRITE_PRIVATE'=any(permissions)) or 'READ_PRIVATE'=any(permissions))
);

-- Ordinary members no longer inherit private access. Startup discards old execution sessions;
-- revoke retained machine credentials whose authority is no longer granted to their holder.
update auth_api_keys k set revoked_at=current_timestamp
from auth_memberships m
where m.workspace_id=k.workspace_id and m.account_id=k.account_id and m.role='MEMBER'
    and k.revoked_at is null
    and k.capabilities && array['READ_PRIVATE','WRITE_PRIVATE','PUBLISH','MANAGE_KEYS']::text[];
