alter table workspaces add column public_description text not null default ''
    check (char_length(public_description) <= 280);
