-- 会话里 AI 写出的文件（[[file:名称]] … [[/file]]）：同名再次生成记为新版本，右侧「文件」面板按名称显示最新版本
create table conversation_file (
  id              text primary key,
  conversation_id text not null references conversation(id) on delete cascade,
  message_id      text references conversation_message(id) on delete set null,
  name            text not null,
  version         int not null,
  content         text not null,
  created_at      timestamptz not null default clock_timestamp(),
  deleted_at      timestamptz,
  unique (conversation_id, name, version)
);
create index conversation_file_conv_idx on conversation_file(conversation_id, name, version desc);
