create table botc_event (
  id uuid primary key default gen_random_uuid(),
  botc_game_id uuid references botc_game (id),
  payload jsonb not null,
  created_at timestamp not null default now()
);
