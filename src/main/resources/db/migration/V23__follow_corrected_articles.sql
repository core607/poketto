-- Credit follows the article, not only its route. Each proposal records the article's frontmatter
-- id and creation time; credit shows while the served article matches either, so gaining an id or
-- losing a duplicate one keeps it, while a different article at the same route matches neither.
-- Rows stored before this migration have neither and keep route-only credit.
alter table community_corrections
    add column article_id uuid,
    add column article_created timestamptz;
drop index community_route_credits;
create index community_route_credits on community_corrections(workspace_id, route)
    where status = 'ACCEPTED' and credited;
-- Each new proposal clears text resolved more than 90 days ago; this keeps that off a full scan.
create index community_resolved_text on community_corrections(resolved_at) where proposed_body <> '';
