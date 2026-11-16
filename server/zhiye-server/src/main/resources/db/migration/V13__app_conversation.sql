-- 从对话创建的应用：记录来源会话，工作台里把对话和应用放在一起显示
create table app_conversation (
  app_id          text primary key references app(id) on delete cascade,
  conversation_id text not null references conversation(id) on delete cascade,
  created_at      timestamptz not null default now()
);
