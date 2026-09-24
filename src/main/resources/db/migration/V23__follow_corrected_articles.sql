-- Credit follows the article, not only its route: the article's id, or its creation time when it
-- has none, is recorded with each proposal and must match the article served now.
alter table community_corrections add column article_key text check (char_length(article_key) <= 80);
drop index community_route_credits;
create index community_route_credits on community_corrections(workspace_id, route, article_key)
    where status = 'ACCEPTED' and credited;
-- Each new proposal clears text resolved more than 90 days ago; this keeps that off a full scan.
create index community_resolved_text on community_corrections(resolved_at) where proposed_body <> '';
