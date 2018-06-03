-- :name insert-mafia-game! :<! :1
-- :doc Insert a single mafia-game
insert into mafia_game (channel_id, moderator_id)
values (:channel-id, :moderator-id)
returning id, channel_id, moderator_id, created_at

-- :name get-mafia-game-by-id :? :1
-- :doc Retrieve a single mafia-game by id
select id, channel_id, moderator_id, created_at, finished_at
from mafia_game
where id = :id

-- :name get-latest-mafia-game-by-channel-id :? :1
-- :doc Retrieve the latest mafia-game for a given channel-id
select id, channel_id, moderator_id, created_at, finished_at
from mafia_game
where channel_id = :channel-id
order by created_at desc
limit 1

-- :name get-unfinished-mafia-games :? :*
-- :doc Retrieve all mafia games which have not been finished
select id, channel_id, moderator_id, created_at, finished_at
from mafia_game
where finished_at is null

-- :name finish-mafia-game-by-id! :<! :1
-- :doc Set the finished_at for a mafia-game by id
update mafia_game
set finished_at = now()
where id = :id
returning id, channel_id, moderator_id, created_at, finished_at
