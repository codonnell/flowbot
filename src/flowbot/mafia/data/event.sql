-- :name insert-mafia-event! :<! :1
-- :doc Insert a single mafia-event
insert into mafia_event (mafia_game_id, payload)
values (:mafia-game-id, :payload)
returning id, mafia_game_id, payload, created_at

-- :name get-mafia-event-by-id :? :1
-- :doc Retrieve a single mafia-event by id
select id, mafia_game_id, payload, created_at
from mafia_event
where id = :id

-- :name get-mafia-events-by-mafia-game-id :? :*
-- :doc Retrieve all mafia-events for a mafia-game ordered by created_at
select id, mafia_game_id, payload, created_at
from mafia_event
where mafia_game_id = :mafia-game-id
order by created_at

-- :name get-mafia-events-by-channel-id :? :*
-- :doc Retrieve all mafia-events for a channel ordered by created_at
select e.id, e.mafia_game_id, e.payload, e.created_at
from mafia_event e
join mafia_game g on g.id = e.mafia_game_id
where g.channel_id = :channel-id
order by e.created_at
