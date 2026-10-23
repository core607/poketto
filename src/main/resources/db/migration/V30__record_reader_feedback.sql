alter table creator_applications add column decision_read_at timestamp with time zone;
create index creator_applications_unread on creator_applications(account_id, created_at desc)
    where status <> 'OPEN' and decision_read_at is null;
alter table community_reports add column review_note text check (length(review_note) between 1 and 500);
