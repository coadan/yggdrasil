create table panels (
  id text primary key,
  owner_id text references users(id)
);

create view active_panels as
select id from panels;

create index idx_panels_owner_id on panels(owner_id);

alter table panels
  add constraint fk_panels_owner
  foreign key (owner_id) references users(id);
