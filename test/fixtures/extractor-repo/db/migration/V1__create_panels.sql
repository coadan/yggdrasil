create table panels (
  id text primary key,
  owner_id text references users(id)
);

create view active_panels as
select id from panels;
