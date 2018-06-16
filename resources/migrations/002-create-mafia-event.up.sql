create table mafia_event (
  id uuid primary key default gen_random_uuid(),
  mafia_game_id uuid references mafia_game (id),
  payload jsonb not null,
  created_at timestamp not null default now()
);
