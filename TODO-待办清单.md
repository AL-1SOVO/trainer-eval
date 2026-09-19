# 待办清单（第三轮修复的中断点）

> 记录时间：2026-09-18
> 起因：对项目做功能审计后，按「修复记录-第三轮-功能补全.md」逐项修复。
> 本文是**中断时的准确状态**，供下次接着做。完成一项就把它移到文末的「已完成」区。

---

## 一、当前状态一览

| # | 任务 | 代码 | 验证 | 已提交 |
|---|---|---|---|---|
| 1 | 报表导出（Excel 有数据 / PDF 是真 PDF） | ✅ | ✅ | ✅ `03c1176` |
| 2 | 门户授权补齐 + 三个仪表盘入口 + 按角色落地页 | ✅ | ✅ | ✅ `c293c8a` |
| 3 | 个人信息与修改密码（含会话作废缺口修复） | ✅ | ✅ | ✅ `030f975` |
| 4 | 错因分析接入 RAG | 🟡 **代码写完、编译/构建通过、未端到端验证** | ❌ | ❌ **未提交** |
| 5 | 按钮级权限（v-permission）真正生效 | ❌ 未开始 | ❌ | ❌ |

**未提交的 6 个文件**（都已完成代码改动）：

```
trainer-eval-server/src/main/java/com/trainereval/ai/service/AiPromptBuilder.java
trainer-eval-server/src/main/java/com/trainereval/ai/service/impl/AiErrorAnalysisServiceImpl.java
trainer-eval-server/src/main/java/com/trainereval/ai/service/impl/AiPromptBuilderImpl.java
trainer-eval-server/src/main/java/com/trainereval/exam/service/impl/ExWrongAnswerAnalysisServiceImpl.java
trainer-eval-server/src/main/resources/ai-prompts/error-analysis.txt
trainer-eval-web/src/views/employee/analysis/WrongAnalysis.vue
```

> ⚠️ 这 6 个文件处在"半成品"状态：不影响已有功能（`mvn compile` 与 `npm run build` 都通过），
> 但**第 4 项的端到端验证没做完**，不要直接当成已完成。

---

## 二、下一步（按顺序）

### P0 · 完成第 4 项：错因分析的端到端验证并提交

**改了什么**（已完成）：
1. `ai-prompts/error-analysis.txt` 新增 `{{retrievalContext}}` 段与「资料引用规则」
   （只许引用检索到的资料；没检索到就明说，不许编造标题）。
2. `AiPromptBuilder` 新增 3 参重载 `buildErrorAnalysisPrompt(answer, question, retrievalContext)`；
   2 参版本保留，内部传"本次未检索到相关课程资料"。
3. `AiErrorAnalysisServiceImpl`：
   - 调 `RagRetrievalFacade.search(userId, "error-analysis", 题干+标签, courseId, 4)` 取上下文；
   - 检索失败只记警告并退化为无引用建议，不让整个分析失败；
   - `related_material_ids` 改存**真实资料 ID（数字数组）**——原来存的是模型返回的主题词（文本），
     字段名与语义都是"资料 ID"，存文本属于类型错位。
4. `ExWrongAnswerAnalysisServiceImpl.getUserAnalyses` 新增 `relatedMaterials`（含标题/章节/课程），
   并对历史脏数据容错（非数字元素跳过，不让一条脏数据把列表打挂）。
5. `WrongAnalysis.vue` 把「相关学习资源」从"直接输出原始 JSON"改成可点击的资料标签。

**待做**：
- [ ] 启动后端（需带 `DEEPSEEK_API_KEY`，建议同时开 MimirQ），用**学员账号**触发一次分析：
      `POST /api/v1/exam/analysis/{answerId}`
      可用的错答（都属于 course 1）：answer_id = 6 / 7 / 9 / 10 / 23
- [ ] 断言 `related_material_ids` 是**数字数组**（不是主题词），且确实是本题相关的资料
- [ ] 断言 `recommendation` 里引用的资料标题**真实存在**（不能是编的）
- [ ] 打开「错题分析」页，确认「相关学习资源」渲染成可点击标签、点击能跳到课程学习页
- [ ] **反向验证**：构造一个课程里没有资料对应的问题，确认它**不会编造**资料标题，
      而是说明"该知识点暂无上传资料"
- [ ] 验证历史脏数据不会让列表报错（库里 id=9/10/11/12 四条是旧的 `[1]`/`[2]`/`[3]`/`[1,2,3]`）
- [ ] `mvn compile` + `npm run build` 通过后提交，并补写《修复记录-第三轮-功能补全.md》第四节

### P1 · 第 5 项：按钮级权限（v-permission）

**现状**：`sys_menu` 里有 3 个 F 类型权限项（`sys:user:add/edit/delete`），
`v-permission` 指令已在 `main.js` 注册，但**全项目 0 处使用**。

**注意性质**：这**不是安全漏洞** —— `SysUserController` 的所有写接口都有
`@PreAuthorize("hasRole('MANAGER')")` 兜底。所以这是"权限粒度只到菜单级"的设计缺口，
不是越权风险。修的时候要按这个定位写注释，别夸大成安全修复。

**待做**：
- [ ] 给 `views/manager/user/UserList.vue` 的新增/编辑/删除按钮加 `v-permission="['sys:user:add']"` 等
- [ ] 确认管理员（拥有全部权限）仍能看到按钮；确认无权限角色看不到
- [ ] 判断是否要给其他模块也补 F 类型权限项（当前只有用户管理有），或明确记录"只做用户管理"
- [ ] 更新文档并提交

### P2 · 收尾

- [ ] 停掉 8081 上的测试实例（见下方环境状态）
- [ ] 把《修复记录-第三轮-功能补全.md》的第五、六节补完
- [ ] 全量回归：`/rag/eval`（CHAIN 应仍为 hitRate 1.0000 / MRR 1.0000）
- [ ] 用**非 admin 账号**各跑一遍主要流程（员工 / 内训师）—— 本轮的授权问题就是因为
      一直用 admin 验收才被掩盖的

---

## 三、环境状态（离开时）

| 项 | 状态 |
|---|---|
| 我的测试后端 | **仍在 8081 上运行**（后台任务 pwsh-18，已开 MimirQ） |
| MimirQ | 容器健康，监听 8000 |
| 你的实例 | 8080 未启动 |
| 测试日志 | `trainer-eval-server/logs/backend-*.log` 有多份 |
| 数据库 | `employee1` 密码已还原为 `admin123`；邮箱/手机号被测试改成了 `emp1.new@company.com` / `13900001111`（如需还原请告知） |

---

## 四、已完成（本轮，共 3 个提交）

| 提交 | 内容 |
|---|---|
| `03c1176` | 报表导出：Excel 写入真实数据、PDF 用 iText 生成合法 PDF；顺带修掉 iText 字体跨文档复用、无效 `@Async`、控制器写死 PENDING、异常返回 0 字节文件；并修掉一个报表数据泄露（非管理员可导出全公司数据） |
| `c293c8a` | 补齐门户授权（员工/内训师原来只看得到 2 个菜单）、三个仪表盘菜单入口、登录按角色落地 |
| `030f975` | 接线个人信息与修改密码；顺带修掉"改密后旧 access token 仍有效"（实测修复前 200 → 现在 401） |

详见 `修复记录-第三轮-功能补全.md`（第一~三节已写完，第四~六节待补）。
