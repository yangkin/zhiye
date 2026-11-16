package com.tcl.csot.zhiye.service.cloud;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** cloud/schema.sql 解析：模型常写的同义类型、修饰、表级约束都能接受；真正不支持的语句给出可修的错误。 */
class CloudSchemaParseTest {

    @Test
    void acceptsCommonModelWritingStyles() {
        String sql = """
                -- 工单
                create table if not exists work_order (
                  id bigserial primary key,
                  title varchar(100) not null,
                  qty integer default 0,
                  cost numeric(10,2),
                  ratio double precision,
                  due date,
                  closed_at timestamp with time zone,
                  done bool default false,
                  extra json,
                  created_at timestamptz default now(),
                  unique (title),
                  primary key (id)
                );
                alter table work_order add column if not exists owner text not null default '';
                create index if not exists idx_wo_due on work_order (due);
                """;
        var defs = CloudService.parse(sql);
        assertThat(defs).hasSize(1);
        var cols = defs.get(0).columns();
        assertThat(cols).containsEntry("title", "text").containsEntry("qty", "int").containsEntry("cost", "numeric")
                .containsEntry("ratio", "double precision").containsEntry("due", "date")
                .containsEntry("closed_at", "timestamptz").containsEntry("done", "boolean")
                .containsEntry("extra", "jsonb").containsEntry("owner", "text");
        assertThat(cols).doesNotContainKeys("id", "created_at", "unique", "primary");
        assertThat(CloudService.checkSchema(sql)).isNull();
    }

    @Test
    void rejectsUnsupportedStatementsWithActionableMessage() {
        assertThat(CloudService.checkSchema("drop table x;")).contains("只允许");
        assertThat(CloudService.checkSchema("create table t (a money);")).contains("不支持的字段类型：money");
        assertThat(CloudService.checkSchema("-- 空的")).isNotNull();
        assertThat(CloudService.checkSchema("create table T-1 (a text);")).isNotNull();
    }

    @Test
    void tableDescriptionComesFromCommentAboveCreate() {
        var d = CloudService.descriptions("""
                -- 设备台账
                create table devices (name text);
                -- 旧说明
                select 1;
                -- 点检记录：每次点检一条
                -- （第二行才算）
                create table if not exists inspections (ok boolean);
                create table plain (x text);
                """);
        assertThat(d).containsEntry("devices", "设备台账").containsEntry("inspections", "（第二行才算）").doesNotContainKey("plain");
    }
}
