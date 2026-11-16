package com.tcl.csot.zhiye.service.llm;

import com.tcl.csot.zhiye.util.Ids;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

/** 按任务发放的网关临时令牌：只存哈希，只在任务进行中有效。 */
@Service
public class TaskTokenService {
    private final JdbcClient jdbc;

    public TaskTokenService(JdbcClient jdbc) { this.jdbc = jdbc; }

    public String issue(String taskId, Duration ttl) {
        String token = "zt_" + Ids.token();
        jdbc.sql("insert into task_token (token_hash, task_id, expires_at) values (?, ?, now() + make_interval(secs => ?))")
                .params(Ids.sha256(token), taskId, (double) ttl.toSeconds()).update();
        return token;
    }

    /** 有效时返回 taskId */
    public Optional<String> validate(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        return jdbc.sql("""
                select t.task_id from task_token t join task k on k.id = t.task_id
                where t.token_hash = ? and not t.revoked and t.expires_at > now()
                  and k.status in ('PREPARING','RUNNING','VERIFYING','COMMITTING')
                """).param(Ids.sha256(token)).query(String.class).optional();
    }

    public void revoke(String taskId) {
        jdbc.sql("update task_token set revoked = true where task_id = ?").param(taskId).update();
    }
}
