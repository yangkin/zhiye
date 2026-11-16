-- 创造页多轮会话：按匿名工作空间隔离，软删除会话但保留消息。
create table conversation (
  id           text primary key,
  workspace_id text not null references workspace(id),
  title        text not null default '新对话',
  created_at   timestamptz not null default now(),
  updated_at   timestamptz not null default now(),
  deleted_at   timestamptz
);
create index conversation_ws_idx on conversation(workspace_id, updated_at desc, created_at desc, id desc)
  where deleted_at is null;

create table conversation_message (
  id              text primary key,
  conversation_id text not null references conversation(id) on delete cascade,
  role            text not null check (role in ('user', 'assistant')),
  content         text not null default '',
  created_at      timestamptz not null default clock_timestamp()
);
create index conversation_message_conv_idx on conversation_message(conversation_id, created_at, id);
