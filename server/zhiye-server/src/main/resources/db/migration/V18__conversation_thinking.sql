-- 会话回答的思考过程（模型的 reasoning_content），刷新后还能展开查看
alter table conversation_message add column thinking text;
