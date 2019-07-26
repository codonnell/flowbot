-- :name insert-botc-game!* :<! :1
-- :doc Insert a single botc-game
insert into botc_game (channel_id, moderator_id)
values (:channel-id, :moderator-id)
returning id, channel_id, moderator_id, created_at

-- :name set-pin-channel!* :<! :1
-- :doc Set the pin channel for a botc-game by id
update botc_game
set pin_channel_id = :pin-channel-id
where id = :id
returning id, channel_id, moderator_id, created_at, pin_channel_id

-- :name get-botc-game-by-id* :? :1
-- :doc Retrieve a single botc-game by id
select id, channel_id, moderator_id, created_at, finished_at, pin_channel_id
from botc_game
where id = :id

-- :name get-latest-botc-game-by-channel-id* :? :1
-- :doc Retrieve the latest botc-game for a given channel-id
select id, channel_id, moderator_id, created_at, finished_at, pin_channel_id
from botc_game
where channel_id = :channel-id
order by created_at desc
limit 1

-- :name get-unfinished-botc-game-by-channel-id* :? :1
-- :doc Retrieve the unfinished botc game for a given channel-id
-- We don't need to limit this query because there is a unique partial index on
-- channel_id where finished_at is null
select id, channel_id, moderator_id, created_at, finished_at, pin_channel_id
from botc_game
where channel_id = :channel-id and finished_at is null

-- :name get-unfinished-botc-games* :? :*
-- :doc Retrieve all botc games which have not been finished
select id, channel_id, moderator_id, created_at, finished_at, pin_channel_id
from botc_game
where finished_at is null

-- :name finish-botc-game-by-id!* :<! :1
-- :doc Set the finished_at for a botc-game by id
update botc_game
set finished_at = now()
where id = :id
returning id, channel_id, moderator_id, created_at, finished_at, pin_channel_id
