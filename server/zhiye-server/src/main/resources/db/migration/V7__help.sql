-- 小织帮助助手：对话、消息、问题上报
create table help_conversation (
  id           text primary key,
  workspace_id text not null,
  title        text not null default '新对话',
  created_at   timestamptz not null default now(),
  updated_at   timestamptz not null default now(),
  -- 删除对话只做软删除：消息保留用于每日次数统计
  deleted_at   timestamptz
);
create index help_conversation_ws_idx on help_conversation(workspace_id, updated_at desc) where deleted_at is null;

create table help_message (
  id              text primary key,
  conversation_id text not null references help_conversation(id),
  role            text not null,              -- user / assistant
  content         text not null default '',
  asset_ids       jsonb not null default '[]',
  created_at      timestamptz not null default now(),
  status          text not null default 'done' -- done / stopped / error / feedback
);
create index help_message_conv_idx on help_message(conversation_id, created_at);
create index help_message_day_idx on help_message(created_at) where role = 'user';

create table help_feedback (
  id              text primary key,
  workspace_id    text not null,
  conversation_id text,
  content         text not null,
  asset_ids       jsonb not null default '[]',
  created_at      timestamptz not null default now()
);
create index help_feedback_ws_idx on help_feedback(workspace_id, created_at desc);
