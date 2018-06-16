create table mafia_game (
  id uuid primary key default gen_random_uuid(),
  channel_id bigint not null,
  moderator_id bigint not null,
  created_at timestamp not null default now(),
  finished_at timestamp
);

create index on mafia_game (channel_id, created_at);
