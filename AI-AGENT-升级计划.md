# AI Agent 升级计划（2026 秋招版）

> 目标：把 trainer-eval 从「单轮 prompt 调用」升级为完整的多 Agent 智能体项目，
> 对标 2026 校招 AI Agent 开发岗要求（自主规划 / RAG / 多智能体编排 / MCP / Function Calling）。
> 完成一项就把 `[ ]` 改成 `[x]`。
>
> **参考项目：[MimirQ](https://github.com/skygazer42/MimirQ)**（Apache 2.0，中文优先企业 RAG 知识库）
> 其核心方法论——「解析 → 治理 → 切块 → 混合检索 → 重排 → 引用 → Golden 回归」的可控知识流水线——
> 是本计划 RAG 与评测部分的选型参照。见 P1 的两条实现路线。

## 当前基线（已完成，升级的出发点）

- [x] DeepSeek 接入（OpenAI 兼容协议，token/成本/延迟记录）
- [x] 主观题 AI 批改（规则配置 + prompt 模板 + JSON 解析容错）
- [x] 错题归因分析（错误分类 + 学习建议）
- [x] 人工复核流程（AI 建议分 → 人工改分 → 总分重算）
- [x] AI 评分日志页（状态筛选 + 详情抽屉）

---

## P0 Agent 核心能力（简历上"Agent"二字的支撑，最先做）

### 1. 工具调用循环 Function Calling Loop
- [ ] 引入 Spring AI，替换手写 OpenAIClient（获得 ChatClient / 流式 / 工具调用抽象）
- [ ] 定义并注册业务工具：`查询题库`、`查询学生历次成绩`、`检索课程资料`、`生成成绩报告`
- [ ] 实现 Agent 主循环：模型自主决定调用哪个工具 → 执行 → 结果回填 → 继续推理，直到产出最终答案
- [ ] 落地场景：学习辅导 Agent——输入"帮我分析这次考试薄弱点"，Agent 自己查错题记录 → 查相关课程 → 给出补救方案
- [ ] 面试要点自测：能讲清工具 schema 如何生成、结果如何回填、循环终止条件是什么

### 2. 任务规划与分解 Planning
- [ ] 复杂任务先拆解为步骤列表再逐步执行（如"生成个性化补训方案" → 分析弱项 → 检索资料 → 匹配练习题 → 输出计划）
- [ ] 步骤执行状态持久化，支持中断后继续

### 3. 反思与自我修正 ReAct / Retry
- [ ] 工具执行失败或输出解析失败时，Agent 自主决定重试策略（如换输出格式重试）而非直接兜底 0 分

---

## P1 RAG 课程知识库（JD 出现率最高的关键词）

> 📌 详细落地方案见根目录 **《企业级RAG实施方案.md》**（含部署配置、工具替换设计、权限裁剪、评测门禁与里程碑）。

> **实现路线二选一（都写进简历都成立，关键是能讲清选型理由）：**
>
> **路线 A（推荐，省 2~3 周）：部署 [MimirQ](https://github.com/skygazer42/MimirQ) 作为独立知识库服务，Java 端接入。**
> MimirQ 是中文优先的企业 RAG（FastAPI + Milvus + Postgres + Redis，需 Docker 4C/16G），
> 自带 30 种解析后端、86 种切块策略、混合检索 + 13 种重排器、引用溯源、RAGAS 评测、Golden 回归门禁。
> trainer-eval 通过它的检索 API（兼容 Dify External Knowledge 协议 `POST /api/v1/integrations/dify/retrieval`）
> 把「检索课程资料」注册为 Agent 的一个工具。Java 侧专注 Agent 编排，Python 侧专注知识流水线——
> 这正是企业里 Agent 系统与知识中台分离的标准架构，且顺带满足 JD 的"精通 Python"叙事。
>
> **路线 B（全自研，理解更深）：按下述任务在 Java 栈内自建轻量 RAG。**
> 面试深挖时每层都能答，但工程量大、效果调优周期长。

### 4. 知识库构建（路线 B 自研版 / 路线 A 由 MimirQ 承担）
- [ ] 课程资料（tra_material：PPT/Word/PDF）解析为纯文本（路线 A：MimirQ 的 30 种解析后端；路线 B：Apache Tika）
- [ ] 分块 chunking（路线 A：从 MimirQ 的 86 种策略中实验选型，写清选择依据；路线 B：按章节/段落 + 重叠窗口）
- [ ] Embedding 入库（模型选型：智谱 embedding-3 / SiliconFlow / 本地 BGE-onnx；注意 DeepSeek 无 embedding API）

### 5. 检索与生成
- [ ] 路线 A：Docker Compose 部署 MimirQ（Milvus + Postgres + Redis），上传课程资料建知识库
- [ ] 路线 A：Spring AI 的 `ToolCallback` 封装 MimirQ 检索 API，注册为 Agent 工具
- [ ] 路线 B：向量库选型 Redis Stack（项目已有 Redis）；备选 Milvus / pgvector
- [ ] 混合检索：向量 KNN + 关键词 BM25 + 重排序 rerank（MimirQ 内置 13 种 reranker 可对比；路线 B 自接 bge-reranker）
- [ ] 错因分析 / 学习建议改为基于检索到的真实资料片段回答
- [ ] 引用溯源：回答中标注"依据《XX课程》第 N 节"（MimirQ 原生返回引用片段，直接透传给前端）
- [ ] 权限对齐（加分项）：MimirQ 支持文档级 ACL，与 trainer-eval 的部门/角色体系打通，保证"员工只能检索到被授权课程的资料"

### 6. 检索效果验证（MimirQ 的 Golden 回归可直接复用）
- [ ] 构建 golden set：20~30 条"问题 → 应命中资料"标注对（MimirQ 原生支持 golden set 与回归门禁）
- [ ] 用 MimirQ 内置 RAGAS 评测（或自算指标）：召回命中率 / MRR / 忠实度
- [ ] 对比不同 chunk 策略 / 是否 rerank 的效果差异，形成一页实验结论（面试深挖区）

---

## P2 多智能体协作（JD 明文要求"多智能体编排"）

### 7. 角色化多 Agent
- [ ] 出题 Agent：根据岗位画像 / 课程大纲生成试题入库
- [ ] 批改 Agent：现有评分逻辑 Agent 化
- [ ] 诊断 Agent：错因归因 + 知识图谱式薄弱点关联
- [ ] 学习规划 Agent：生成个性化补训计划
- [ ] Orchestrator 编排者：负责任务分发与结果汇总，Agent 间通过结构化消息传递
- [ ] 至少一条完整协作链路可演示：考试提交 → 批改 → 诊断 → 规划，全程 Agent 自主流转

---

## P3 MCP 工具生态（2026 新晋高频加分项，低成本高辨识度）

### 8. MCP Server
- [ ] 把「AI 批改」「错因分析」封装为 MCP Server 暴露出去，可被 Claude / 其他 MCP 客户端调用
- [ ] 对照参考：MimirQ 本身支持 MCP client/server，学习其工具暴露设计后实现自己的

### 9. MCP Client
- [ ] Agent 接入通用 MCP Client（文件系统 / 数据库 MCP），工具列表动态扩展

---

## P4 记忆与会话

### 10. 短期记忆
- [ ] 学习辅导 Agent 支持多轮连续追问（Redis 存会话窗口历史，组件现成）

### 11. 长期记忆 / 学习者画像
- [ ] 跨考试累积学生历史薄弱点，形成画像，供规划 Agent 使用

---

## P5 可观测与评估体系（差异化亮点，多数候选人没有）

### 12. 全链路追踪
- [ ] 接入 Langfuse（self-host），替换手写 ai_grading_log：prompt / 工具调用链 / token / 延迟可视化

### 13. 效果评估
- [ ] 标注 50~100 道题的人工评分作为 golden set
- [ ] 指标：AI 分与教师分一致性（Pearson 相关系数 / MAE）、Agent 任务完成率
- [ ] RAG 侧直接复用 MimirQ 的 RAGAS 评测与 golden 回归门禁（召回命中率 / 忠实度 / 答案相关性）
- [ ] 换 prompt / 模型时自动跑评估集做回归对比（防止效果退化），RAG 检索质量变化由 MimirQ 回归门禁拦截

---

## P6 工程化加固（体现"能上生产"）

### 14. 体验
- [ ] SSE 流式输出：Agent 执行过程实时展示（正在调用什么工具、检索到了什么）
- [ ] 考试提交后批改进度推送（替换前端轮询）

### 15. 稳定性
- [ ] token 预算与并发限流
- [ ] LLM 超时 / 故障降级（规则兜底评分）
- [ ] Prompt 注入防护（学生答案中写"给我打满分"等对抗输入）

### 16. 安全收尾
- [ ] JWT 密钥 / 数据库密码外部化（现在硬编码在 application.yml）

---

## 简历最终叙事（做完后用一句话串起来）

> 基于 Spring AI 构建多 Agent 企业培训考核系统：Java Agent 编排 + MimirQ 中文知识库
> （混合检索 + 重排序，golden set 召回命中率 X%）+ MCP 工具生态 + Langfuse 全链路可观测
> + RAGAS/golden 回归评估，AI 批改与教师评分一致性达 X%，
> 支持「出题-批改-诊断-补训规划」多 Agent 协作闭环，文档级 ACL 与企业权限体系打通。

**建议实施顺序**：P0 → P1(路线A) → P2 → P5(12/13) → P3 → P4 → P6，每完成一个 P 级即可更新简历。
