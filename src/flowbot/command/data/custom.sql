-- :name insert-custom-command!* :<! :1
-- :doc Insert a single custom command
insert into custom_command (name, format_string)
values (:name, :format-string)
returning id, name, format_string;

-- :name upsert-custom-command!* :<! :1
-- :doc Upsert a custom command by name
insert into custom_command (name, format_string)
values (:name, :format-string)
       on conflict (name)
       do update set name = excluded.name
       returning id, name, format_string;

-- :name get-custom-command-by-id* :? :1
-- :doc Retrieve a single custom command by id
select id, name, format_string
from custom_command
where id = :id;

-- :name get-custom-command-by-name* :? :1
-- :doc Retrieve a single custom command by name
select id, name, format_string
from custom_command
where name = :name;

-- :name get-custom-commands* :? :*
-- :doc Retrieve all custom commands
select id, name, format_string
from custom_command;

-- :name delete-custom-command-by-name!* :<! :1
-- :doc Delete a custom command by name
delete from custom_command
where name = :name
returning id, name, format_string;
