# 织页质量基准评测集（Evals）

## 文件说明
- `cases.json`：12 个涵盖日常开发 CRUD、数据看板、HashRouter 多页、云权限、云函数、应用内 AI、Vue 3、Python 脚本、Skill 包、沙箱存储安全、移动端 390px 适配及增量修改的基准案例。
- `score.mjs`：确定性评分器，校验文件结构、禁/必用接口和冒烟结果。
- `run-eval.mjs`：
  - `node evals/run-eval.mjs --check`（默认 / CI 用）：零模型成本校验评测集与官方 React/Vue 基线模板能否通过最新版 `verify` 与 `smokeTest`。
  - `node evals/run-eval.mjs --live`（或 `ZHIYE_EVAL_LIVE=1`）：对本地/测试环境 `http://127.0.0.1:8080` 实跑真实模型端到端通过率、耗时与 token 消耗。
