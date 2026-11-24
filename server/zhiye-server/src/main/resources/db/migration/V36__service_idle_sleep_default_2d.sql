-- 将单体服务默认闲置休眠时长从 20 分钟调整为 2 天（2880 分钟），减少频繁冷启动
alter table app_service alter column idle_sleep_min set default 2880;
update app_service set idle_sleep_min = 2880 where idle_sleep_min = 20;
