# AI Agent 升级计划（2026 秋招版）

> 目标：把 trainer-eval 从「单轮 prompt 调用」升级为完整的多 Agent 智能体项目，
> 对标 2026 校招 AI Agent 开发岗要求（自主规划 / RAG / 多智能体编排 / MCP / Function Calling）。
>
> **参考项目：[MimirQ](https://github.com/skygazer42/MimirQ)**（Apache 2.0，中文优先企业 RAG 知识库）
>
> ⚠️ **本文档已于 2026-09-18 逐条对照代码重新核对。**
> 此前所有复选框都是 `[ ]`，但其中一大批其实早已实现 —— 这个偏差直接导致过一次误判
> （"Agent 不会调用工具"）。**勾选状态现在只代表"代码里确实有、且我核对过"**，
> 每条已完成的都注明了证据位置。核对方法与历次偏差见文末§附录。

## 当前基线（已完成，升级的出发点）

- [x] DeepSeek 接入（OpenAI 兼容协议，token/成本/延迟记录）
- [x] 主观题 AI 批改（规则配置 + prompt 模板 + JSON 解析容错）
- [x] 错题归因分析（错误分类 + 学习建议）
- [x] 人工复核流程（AI 建议分 → 人工改分 → 总分重算）
- [x] AI 评分日志页（状态筛选 + 详情抽屉）

> ⚠️ **已知配置隐患**：`application.yml` 默认模型写的是 `deepseek-v4-flash`，
> 但 DeepSeek 实际只提供 `deepseek-flash` / `deepseek-v4-pro`。
> 实测请求 `deepseek-v4-flash` 会被宽容地路由到 `deepseek-flash`，属于"碰巧能用"，
> 建议改为 `deepseek-flash` 以免某天对方收紧校验后全线 400。

---

## P0 Agent 核心能力（简历上"Agent"二字的支撑）

### 1. 工具调用循环 Function Calling Loop — ✅ 已完成

- [x] 引入 Spring AI，替换手写 OpenAIClient
      （证据：`AgentChatService` 用 `ChatModel` + `OpenAiChatOptions`；
      旧的 `OpenAIClient` 仍服务于批改链路，两条并存）
- [x] 定义并注册业务工具：`myExamRecords`（成绩）、`myWrongAnswers`（错题）、
      `myEnrolledCourses`（课程）、`searchCourseMaterials`（检索课程资料）、
      `getMaterialDetail`（展开资料全文）
      （证据：`agent/tools/TutorTools.java`，全部带 `@Tool` 注解）
- [x] 实现 Agent 主循环：模型决定调用 → 执行 → 结果回填 → 继续推理
      （证据：`internalToolExecutionEnabled(false)` + `ToolCallingManager.executeToolCalls`，
      默认最多 6 轮：`agent.tutor.max-iterations`）
- [x] 落地场景：学习辅导 Agent —— 实测输入"我最近考试考得怎么样"，
      第 1 轮并行调用 `myExamRecords` + `myWrongAnswers`，
      第 2 轮调用 `searchCourseMaterials` ×2，随后产出带 `M4#0` 引用的答案
- [x] 面试要点自测：工具 schema 由 `ToolCallbacks.from(tools)` 从注解生成；
      结果以 `ToolResponseMessage` 回填；终止条件是"本轮响应不含 tool_calls"
      或达到 `maxIterations`（超限时明确报错而不是静默截断）

### 2. 任务规划与分解 Planning — ✅ 已完成（2026-09-18）

- [x] 复杂任务先拆解为步骤列表再逐步执行
      （证据：`agent/service/AgentPlanner.java` 产出结构化步骤表；
      `AgentChatService` 通过 SSE `plan` 事件下发，`step_status` 逐个回报状态；
      前端渲染成带状态的执行计划面板）
- [x] 步骤执行状态持久化，支持中断后继续
      （证据：计划与状态写入 Redis `agent:plan:{userId}:{sessionId}`，TTL 同会话历史；
      下一轮若存在未完成步骤，会作为上下文交给模型继续）

> 设计取舍：规划**不是每次都做**。`agent.tutor.planning-mode` 默认 `auto` 走启发式门
> （短消息/闲聊不规划），因为对"我考了多少分"这类单步问题规划纯属为展示而增延迟。
> 门只是粗筛，模型仍可返回空计划来否决。规划失败会自动退化为原来的"走一步看一步"，
> 不影响对话本身。

### 3. 反思与自我修正 ReAct / Retry — ✅ 已完成（2026-09-18）

- [x] 工具执行失败或输出解析失败时，Agent 自主决定重试策略而非直接兜底
      （证据：`agent/service/AgentReflection.java` 诊断工具返回，
      命中"空结果/报错"时以 SystemMessage 追加**具体**的重试指引
      —— 换更宽泛的词 / 换同义表达 / 拆词，并把 `reflection` 事件推给前端；
      单工具重试上限 `agent.tutor.max-tool-retries`=2，用尽后转为"如实告知"指引）

> 关键设计：**区分"该重试"与"重试无意义"**。
> 检索类工具（参数是关键词）空结果值得换词重试；
> 成绩/错题类工具空结果是"确实没数据"，重试只会白烧 token，直接如实说明。
>
> 实测（问语料里不存在的 Kubernetes）：
> ```
> event:reflection x2  {"tool":"searchCourseMaterials","reason":"资料检索无结果","retrying":true,"attempt":1}
>                      {"tool":"searchCourseMaterials","reason":"资料检索无结果","retrying":true,"attempt":2}
> ```
> 最终回答明确指出"仅命中一份无关资料 M4#0《常见网络安全威胁与防范指南》…未涉及 Helm 或容器编排，
> 非你想要的知识点"，并说明该知识点尚未上传 —— 而不是拿网络安全资料硬答 Kubernetes。

---

## P1 RAG 课程知识库

> 📌 详细落地方案见 **《企业级RAG实施方案.md》**；实施记录与 40 个坑见 **《RAG实施进度与踩坑.md》**；
> 后续优化路线见 **《RAG与Agent优化方案.md》**。

> **实现路线**：已选**路线 A** —— 部署 MimirQ 作为独立知识库服务，Java 端接入，源码已 vendored 进 `mimirq/`。

### 4. 知识库构建

- [x] 课程资料解析为纯文本
      （证据：`training/service/DocumentTextExtractor.java` —— txt/md/csv/json/xml/html（自动探测 UTF-8/GBK）、
      docx（段落+表格）、pptx（逐页+表格）、xlsx（逐 sheet）、pdf（文本层）；
      **扫描件需 OCR，当前会明确拒绝建资料而不是静默产生无正文条目**）
- [x] 分块 chunking（证据：`rag/service/ChunkingService.java` —— 段落优先、句子兜底、80 字重叠、剥 HTML；
      **不走 MimirQ 的 86 种策略**，是用自研切块结果作为 manual chunks 灌入）
- [ ] Embedding 入库 —— ⚠️ **当前是 `deterministic_test` 哈希向量，排序无语义**。
      要换真模型见《RAG与Agent优化方案.md》P0-2（需 embedding 服务 key；DeepSeek 无 embedding API）

### 5. 检索与生成

- [x] Docker Compose 部署 MimirQ（证据：`mimirq/docker/docker-compose.lite.yml`，
      由 `start.ps1` 托管生命周期，实测 15s 就绪）
- [x] Spring AI 的 `ToolCallback` 封装 MimirQ 检索 API，注册为 Agent 工具
      （证据：`searchCourseMaterials` → `RagRetrievalFacade`）
- [x] 混合检索：向量 KNN + 关键词 + 重排
      （证据：链路 `MimirQ → 本地 ngram 全文 + 子串 + RRF → 子串兜底`；
      重排由 MimirQ 承担，但**13 种 reranker 尚未做对比实验**）
- [ ] 错因分析 / 学习建议改为基于检索到的真实资料片段回答
      （**已核实：`AiErrorAnalysisServiceImpl` 未引用 `RagRetrievalFacade`，仍是单轮 LLM 调用**）
- [x] 引用溯源：回答中标注引用编号
      （证据：SSE `citation` 事件 → 前端「参考来源」卡片；`M12#0` 格式可跳回课程）
- [x] 权限对齐（部分）：`KnowledgeAccessService` 实现课程级裁剪，
      **降级路径同样裁剪**（避免 fail-open），支持 `PUBLISHED` / `ENROLLED` 两档
- [ ] 部门级 ACL（MimirQ 支持文档级 ACL，尚未与部门/角色体系打通）

### 6. 检索效果验证

- [x] 构建 golden set：20 条"问题 → 应命中资料"标注对
      （证据：`resources/rag/golden-rag.jsonl`，其中 11 条与原文无字面重叠）
- [x] 召回命中率 / MRR 指标 + 回归门禁
      （证据：`/api/v1/rag/eval`，CHAIN 与 LIKE_BASELINE 双策略留档在 `rag_eval_run.strategy`；
      **2026-09-18 复测：CHAIN 1.0000 vs LIKE 0.4500（+0.55）**）
- [ ] 对比不同 chunk 策略 / 是否 rerank 的效果差异，形成一页实验结论

---

## P2 多智能体协作 — ⬜ 全部未完成

### 7. 角色化多 Agent

- [ ] 出题 Agent：根据岗位画像 / 课程大纲生成试题入库
- [ ] 批改 Agent：现有评分逻辑 Agent 化
- [ ] 诊断 Agent：错因归因 + 知识图谱式薄弱点关联
- [ ] 学习规划 Agent：生成个性化补训计划
- [ ] Orchestrator 编排者：负责任务分发与结果汇总
- [ ] 至少一条完整协作链路可演示：考试提交 → 批改 → 诊断 → 规划

> 已核实：代码中不存在任何多 Agent 编排实现（grep 无 orchestrator/planner 相关）。

---

## P3 MCP 工具生态 — ⬜ 未完成

- [ ] 把「AI 批改」「错因分析」封装为 MCP Server 暴露出去
- [ ] Agent 接入通用 MCP Client，工具列表动态扩展

---

## P4 记忆与会话

### 10. 短期记忆 — ✅ 已完成

- [x] 学习辅导 Agent 支持多轮连续追问
      （证据：`AgentChatService.loadHistory/appendHistory`，Redis key `agent:hist:{userId}:{sessionId}`，
      窗口 `agent.tutor.history-window`=20 条，TTL 168 小时）

### 11. 长期记忆 / 学习者画像 — ⬜ 未完成

- [ ] 跨考试累积学生历史薄弱点，形成画像，供规划 Agent 使用

---

## P5 可观测与评估体系

### 12. 全链路追踪 — ⬜ 未完成

- [ ] 接入 Langfuse（self-host），替换手写 `ai_grading_log`

> 已核实：代码中无 langfuse 集成。现有的可观测性来自手写日志 +
> `rag_retrieval_trace`（检索轨迹，含 `strategy` 实际服务方）+ `agent_message`（会话留痕）。

### 13. 效果评估 — 🟡 部分完成

- [x] RAG 侧 golden 回归门禁（同 P1 §6）
- [ ] 标注 50~100 道题的人工评分作为 golden set
- [ ] 指标：AI 分与教师分一致性（Pearson / MAE）、Agent 任务完成率
- [ ] 换 prompt / 模型时自动跑评估集做回归对比

---

## P6 工程化加固

### 14. 体验 — ✅ 已完成

- [x] SSE 流式输出：Agent 执行过程实时展示
      （证据：`tool_call` / `tool_result` / `citation` / `delta` / `done` / `error` 六类事件；
      前端渲染「正在调用 xxx…」→「xxx 完成」）
- [ ] 考试提交后批改进度推送（替换前端轮询）

### 15. 稳定性 — 🟡 部分完成

- [ ] token 预算与并发限流（已核实：无 RateLimiter；仅有单条规则的 `max_tokens` 上限）
- [x] LLM 输出格式容错（批改链路的 JSON 解析容错 + 失败不写 0 分）
- [x] Prompt 注入防护（证据：`AgentGuard.checkInput` 在进入模型前拦截；
      工具结果统一用 `<data>` 包裹形成可依赖的边界；`maskOutput` 做输出脱敏）

### 16. 安全收尾 — 🟡 部分完成

- [x] AI/知识库密钥外部化（`application.yml` 用 `${DEEPSEEK_API_KEY:...}` 等占位，
      实际值走 `.env.local` 或系统环境变量，`.env.local` 已在 .gitignore）
- [ ] JWT 密钥 / 数据库密码外部化（仍硬编码在 `application.yml`）
- [ ] 轮换历史泄露的 DeepSeek key（明文曾出现在 `target/` 构建产物里）

---

## 简历最终叙事（做完后用一句话串起来）

> 基于 Spring AI 构建多 Agent 企业培训考核系统：Java Agent 编排 + MimirQ 中文知识库
> （混合检索 + 重排序，golden set 召回命中率 X%）+ MCP 工具生态 + Langfuse 全链路可观测
> + RAGAS/golden 回归评估，AI 批改与教师评分一致性达 X%，
> 支持「出题-批改-诊断-补训规划」多 Agent 协作闭环，文档级 ACL 与企业权限体系打通。

**当前缺口**：P2 多智能体、P3 MCP、P5.12 Langfuse、P1.4 embedding。
其中 **embedding 是质量短板**（其余是能力广度）。

---

## 附录：文档偏差记录与核对方法

本次核对发现"文档说未完成、代码已完成"的项（**这是曾经误判 Agent 能力的原因**）：

| 项 | 文档原状 | 实际 |
|---|---|---|
| P0 §1 工具调用循环 | `[ ]` | ✅ 完整实现，实测 4 次工具调用 |
| P1 §4 切块 / §5 混合检索 / 引用 / 权限 | `[ ]` | ✅ 已实现 |
| P1 §6 golden set 与指标 | `[ ]` | ✅ 20 题 + 双策略留档 |
| P4 §10 短期记忆 | `[ ]` | ✅ Redis 会话窗口 |
| P6 §14 SSE | `[ ]` | ✅ 六类事件 |
| P6 §15 注入防护 | `[ ]` | ✅ `AgentGuard` |

**核对方法**（可复现）：对每条复选框，找"能证伪的代码位置"——
`@Tool` 注解、`@Transactional` 传播行为、Mapper 方法、Controller 路由、
以及**实际跑一次并看事件流/日志**。只靠读文档或读一个类名都会误判。

**教训**：文档里的复选框也是代码资产。它过期一次，就会让下一个人（包括作者自己）
在错误的前提下做判断。此后的规则是：**改代码的同时改文档，同一提交内完成。**
