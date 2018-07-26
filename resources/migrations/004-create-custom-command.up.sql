create table custom_command (
  id uuid primary key default gen_random_uuid(),
  name text unique not null,
  format_string text not null
);
