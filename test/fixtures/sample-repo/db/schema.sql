create table panels (
  id uuid primary key,
  name text not null
);

create table panel_events (
  id uuid primary key,
  panel_id uuid references panels(id),
  event_type text not null
);

create view active_panels as
select id, name
from panels;
