-- 任务进行中的用户补充消息：当前任务结束后，按创建顺序转成新的 modify 任务
create table task_message_queue (
    id            text primary key,
    task_id       text not null references task(id),
    app_id        text not null references app(id),
    workspace_id  text not null references workspace(id),
    content       text not null check (char_length(content) between 1 and 2000),
    status        text not null default 'pending' check (status in ('pending', 'sent', 'cancelled')),
    created_at    timestamptz not null default clock_timestamp(),
    sent_task_id  text references task(id)
);

create index task_message_queue_pending_idx
    on task_message_queue (app_id, created_at, id)
    where status = 'pending';

create index task_message_queue_workspace_idx
    on task_message_queue (workspace_id, app_id, created_at, id);
