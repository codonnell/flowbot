-- :name insert-botc-event!* :<! :1
-- :doc Insert a single botc-event
insert into botc_event (botc_game_id, payload)
values (:botc-game-id, :payload)
returning id, botc_game_id, payload, created_at

-- :name get-botc-event-by-id* :? :1
-- :doc Retrieve a single botc-event by id
select id, botc_game_id, payload, created_at
from botc_event
where id = :id

-- :name get-botc-events-by-botc-game-id* :? :*
-- :doc Retrieve all botc-events for a botc-game ordered by created_at
select id, botc_game_id, payload, created_at
from botc_event
where botc_game_id = :botc-game-id
order by created_at

-- :name get-botc-events-by-channel-id* :? :*
-- :doc Retrieve all botc-events for a channel ordered by created_at
select e.id, e.botc_game_id, e.payload, e.created_at
from botc_event e
join botc_game g on g.id = e.botc_game_id
where g.channel_id = :channel-id
order by e.created_at
