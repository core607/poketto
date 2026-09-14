alter table workspaces add column public_author_name text not null default ''
    check (char_length(public_author_name) <= 120);
