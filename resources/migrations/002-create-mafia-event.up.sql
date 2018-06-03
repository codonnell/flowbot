create table mafia_event (
  id serial primary key,
  mafia_game_id int references mafia_game (id),
  payload jsonb not null,
  created_at timestamp not null default now()
);
