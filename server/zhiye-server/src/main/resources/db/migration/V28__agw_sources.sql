alter table data_source add column if not exists provider text not null default 'manual';
alter table data_source add column if not exists external_id text;

create unique index if not exists data_source_external_uq on data_source(workspace_id, provider, external_id)
  where external_id is not null and deleted_at is null;
create index if not exists data_source_provider_idx on data_source(provider, external_id);
