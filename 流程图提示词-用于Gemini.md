# 企业培训与智能考核系统 — 流程图提示词

以下每个提示词可单独复制到 Gemini 中使用，生成对应流程图。

---

## 1. 系统整体架构与角色流程

```
请生成一张系统架构流程图(mermaid flowchart)，描述"企业培训与智能考核系统"的整体架构。
系统有3种角色：员工(EMPLOYEE)、内训师(TRAINER)、管理员(MANAGER)。

员工功能模块：
  学习仪表盘 → 学习中心(浏览课程) → 报名课程 → 学习章节/资料 → 我的课程(查看进度)
  → 我的考试(查看可用试卷) → 开始考试 → 作答 → 提交试卷
  → 自动批改客观题(单选/多选/判断) → AI批改主观题(简答/论述)
  → 查看成绩 → 错题分析(AI诊断错误原因+改进建议)

内训师功能模块：
  培训概览 → 课程管理(创建/编辑/发布/归档课程) → 添加章节和资料
  → 题库管理(创建/编辑/删除/导入Excel试题) → 试卷管理(组卷：选择试题+设置分值)
  → 阅卷中心(查看学员答卷→人工调分→确认成绩) → AI评分配置(配置评分规则模板)

管理员功能模块：
  管理仪表盘 → 用户管理 → 部门管理 → 角色管理 → 菜单权限管理
  → 培训监管(查看所有课程) → 考试统计 → 报表中心(导出Excel/PDF) → AI评分日志

通用：JWT登录认证 → 根据角色显示不同菜单 → Redis存储Token → 退出登录

请用不同颜色区分三个角色的模块，标注数据库表名(如sys_user, tra_course, qb_question, ex_exam_record等)。
```

---

## 2. 考试完整流程（含AI评分）

```
请生成一张详细的时序图(mermaid sequenceDiagram)，描述"企业培训与智能考核系统"的完整考试流程。

参与者：学员、前端Vue、后端SpringBoot、MySQL、Redis、OpenAI

流程步骤：
1. 学员登录 → 前端POST /api/v1/auth/login → 后端验证用户名密码(查询sys_user表) → 生成JWT Token → 存入Redis(token:access:xxx) → 返回token和角色信息
2. 前端根据角色(EMPLOYEE/TRAINER/MANAGER)动态显示菜单
3. 学员进入"我的考试" → GET /api/v1/exam/papers/available → 后端查询qb_exam_paper表返回可用试卷列表
4. 学员点击"开始考试" → POST /api/v1/exam/start → 后端创建ex_exam_record(状态IN_PROGRESS) → 随机抽题(如设置随机) → 创建ex_exam_answer记录 → 返回试题列表
5. 学员逐题作答 → POST /api/v1/exam/session/{id}/save → 实时保存答案到ex_exam_answer
6. 学员点击"提交" → POST /api/v1/exam/submit → 后端执行：
   a) 自动批改客观题(对比用户答案和qb_question.reference_answer) → 计算客观分
   b) 更新ex_exam_record状态为SUBMITTED
   c) 异步调用OpenAI批改主观题 → 解析AI返回的评分和评语 → 更新主观分
   d) 汇总总分 → 判断是否及格(对比qb_exam_paper.pass_score) → 状态改为GRADED
7. 异步触发错题分析：对is_correct=0的题目 → 调用OpenAI → 生成error_category/error_analysis/recommendation → 存入ex_wrong_answer_analysis
8. 学员查看成绩 → GET /api/v1/exam/records/{id}/result → 展示每题得分/AI评语/错题分析

请标注每个步骤涉及的API路径和数据库表。
```

---

## 3. 试题Excel导入流程

```
请生成一张流程图(mermaid flowchart)，描述"企业培训与智能考核系统"的试题Excel导入功能。

流程：
1. 内训师进入题库管理页面 → GET /api/v1/questions?courseId=X → 显示该课程已有试题
2. 点击"导入试题"按钮 → 弹出三步向导对话框
3. 步骤一：下载模板 → GET /api/v1/questions/template → 后端用Apache POI生成xlsx文件
   模板包含：题目内容|题型|选项A|选项B|选项C|选项D|正确答案|分值|难度|解析|标签|评分标准
   含示例数据行和"填写说明"Sheet
4. 步骤二：内训师按模板填写数据 → 选择目标课程 → 上传文件(拖拽或点击)
   前端校验：文件格式(.xlsx/.xls)、文件大小
5. 步骤三：POST /api/v1/questions/import (multipart/form-data) → 后端处理：
   a) WorkbookFactory.create解析Excel → 逐行读取
   b) 跳过空行，校验必填字段(题目内容、题型、正确答案)
   c) 自动识别题型：含"单选"→SINGLE_CHOICE, "多选"→MULTI_CHOICE, "判断"→TRUE_FALSE, "简答"→SHORT_ANSWER, "论述"→ESSAY
   d) 选择题自动构建options JSON数组
   e) 逐行INSERT INTO qb_question → 返回成功导入数量
6. 刷新列表 → 显示新增的试题

异常处理：
- 文件格式错误 → "given workbook OOXML has a XXXX extension" → 提示仅支持xlsx/xls
- 题型无法识别 → 跳过该行，记录warning日志
- 必填字段为空 → 跳过该行
- 课程不存在 → 返回错误

请用蓝色标注正常流程，红色标注异常分支，绿色标注成功结果。
```

---

## 4. 错题分析流程

```
请生成一张流程图(mermaid flowchart)，描述"企业培训与智能考核系统"的AI错题分析功能。

流程：
1. 学员提交考试后 → 系统自动批改客观题 → 标记is_correct=0的答案为错题
2. 异步调用AI错题分析服务(AiErrorAnalysisService)：
   a) 获取错题信息(ex_exam_answer)和题目详情(qb_question)
   b) 构建Prompt：包含题目内容、学员答案、参考答案、题目解析
   c) 调用OpenAI(gpt-4o) → 要求返回JSON格式
   d) 解析返回的JSON：
      - errorCategory: 错误分类(MISUNDERSTANDING概念误解/KNOWLEDGE_GAP知识盲区/CALCULATION_ERROR计算错误/OTHER其他)
      - errorAnalysis: 详细错误分析
      - recommendation: 改进建议
      - suggestedTopics: 建议复习的知识点ID列表
   e) 存入ex_wrong_answer_analysis表(upsert逻辑：同answer_id则更新)
3. 学员进入"错题分析"页面 → GET /api/v1/exam/analysis/stats → 展示：
   - 统计卡片：错题总数、错误类型数、涉及课程数、高频错题TOP5
   - 饼图：错误类型分布(MISUNDERSTANDING vs KNOWLEDGE_GAP vs OTHER)
   - 饼图：题型错误分布(单选题/多选题/判断题/简答题/论述题)
4. 错题列表 → GET /api/v1/exam/analysis/user → 分页查询
   每道错题展示：
   - 题目内容、题型标签、所属课程、难度星级
   - 学员答案 vs 参考答案
   - 已分析：显示errorCategory标签 + errorAnalysis + recommendation + 建议复习章节
   - 未分析：显示"AI智能分析"按钮 → POST /api/v1/exam/analysis/{answerId} → 触发单题分析
5. 筛选功能：按课程筛选、按题型筛选
6. 展开/收起详情（点击题目行展开）

如果AI分析失败：返回默认分析(errorCategory=OTHER, "未能生成错误分析,请咨询教师")

请区分"已分析"和"未分析"两种状态，标注每个节点对应的API。
```

---

## 5. 用户认证与权限流程

```
请生成一张时序图(mermaid sequenceDiagram)，描述系统的JWT认证和RBAC权限控制流程。

参与者：浏览器、Vue前端、SpringBoot后端、Spring Security过滤器链、MySQL、Redis

流程：
1. 用户访问页面 → Vue Router beforeEach守卫检查token
2. 无token → 重定向到/login → 显示登录表单
3. 用户输入用户名密码 → POST /api/v1/auth/login
4. 后端AuthController：
   a) AuthenticationManager.authenticate()
   b) UserDetailsServiceImpl.loadUserByUsername() → 查询sys_user表 → 查询sys_user_role获取角色ID → 查询sys_role获取role_code(ROLE_EMPLOYEE/ROLE_TRAINER/ROLE_MANAGER) → 查询sys_menu获取权限列表
   c) 构建LoginUser对象(包含user + roles + permissions)
   d) JwtUtils.generateAccessToken() → 生成JWT(含userId, username, jti)
   e) 存入Redis: token:access:{jti} → userId (TTL 7200秒)
   f) 返回token + refreshToken + userInfo(含roles数组和permissions数组)
5. 前端：token存入localStorage(access_token) + pinia store
6. 后续请求 → axios拦截器自动附加 Authorization: Bearer {token}
7. 后端JwtAuthenticationFilter(OncePerRequestFilter)：
   a) 从Header提取Bearer token
   b) JwtUtils.validateToken() → 验证签名和过期时间
   c) 查询Redis: token:access:{jti} → 存在则有效
   d) 查询黑名单: token:blacklist:{jti} → 存在则已登出
   e) 从DB加载用户信息 → 构建Authentication → 设置SecurityContext
8. 授权检查(@PreAuthorize)：
   - hasRole('MANAGER') → 检查authority是否为ROLE_MANAGER
   - hasAnyRole('TRAINER','MANAGER') → 检查是否包含任一角色
   - 方法级权限: @PreAuthorize on Controller方法
9. 退出登录 → POST /api/v1/auth/logout → 将jti加入Redis黑名单 → 清除token

异常场景：
- 401(未认证)：token为空/无效/过期 → JwtAuthenticationEntryPoint返回401 → 前端跳转登录页
- 403(无权限)：角色不匹配 → AccessDeniedHandler返回403 → 前端提示"无权限访问"
- Token过期但前端未清理 → 前端响应拦截器检测401 → 清除token → 跳转/login

数据库表：sys_user, sys_role, sys_user_role, sys_menu, sys_role_menu
Redis键：token:access:{jti}, token:blacklist:{jti}
JWT结构：{sub: userId, username, jti, iat, exp}
```

---

## 6. 课程管理与组卷流程

```
请生成一张流程图(mermaid flowchart)，描述内训师创建课程到发布试卷的完整流程。

流程：
1. 内训师登录 → 进入培训门户(/trainer)
2. 课程管理：
   a) 创建课程 → POST /api/v1/training/courses → 填写课程名称、描述、封面 → INSERT tra_course
   b) 添加章节 → 填写章节标题、排序 → INSERT tra_chapter
   c) 为章节添加资料(视频/文档/文本) → INSERT tra_material
   d) 发布课程 → PUT /api/v1/training/courses/{id}/status {status:1}
3. 题库管理：
   a) 手动创建试题 → POST /api/v1/questions → 选择题型/填写内容/选项/答案/分值/难度 → INSERT qb_question
   b) 批量导入试题 → POST /api/v1/questions/import → 上传Excel → 解析插入
   c) 试题列表查询 → GET /api/v1/questions?courseId=X → 支持题型/难度筛选
4. 试卷管理：
   a) 创建试卷 → 填写试卷名称、描述、总分、及格分、时长(分钟)
   b) 选择试题 → 从题库中勾选题目 → 设置每题分值 → INSERT qb_exam_paper + 批量INSERT qb_paper_question
   c) 配置选项：是否随机抽题(shuffle_questions)、是否随机选项(shuffle_options)、允许重考次数(max_attempts)
   d) 发布试卷 → 状态设为1
5. 学员端：
   a) 进入"我的考试" → 看到已发布试卷列表
   b) 已报名对应课程的学员可参加考试
   c) 系统检查重考次数 → 未超限可开始考试

请展示从课程创建到试卷发布的数据流，标注每个步骤操作的数据表。
```

---

## 7. 阅卷与成绩管理流程

```
请生成一张流程图，描述内训师阅卷和成绩管理的完整流程。

流程：
1. 学员提交试卷后 → 系统自动批改客观题 → 异步AI批改主观题 → 状态变为GRADED
2. 内训师进入"阅卷中心" → GET /api/v1/exam/grading/queue → 查看所有已提交的考试记录
3. 内训师点击某条记录 → GET /api/v1/exam/grading/{recordId} → 查看该学员的完整答卷：
   - 客观题：显示学员答案、正确答案、是否正确、得分(系统自动判定，不可修改)
   - 主观题：显示学员答案、参考答案、AI评分、AI评语
4. 内训师可对主观题进行人工调分：
   a) PUT /api/v1/exam/grading/answers/{answerId} → 修改manual_score
   b) 系统重新计算主观题总分
5. 内训师确认成绩 → PUT /api/v1/exam/grading/{recordId}/finalize → 状态改为REVIEWED
6. 成绩统计(管理员)：
   a) GET /api/v1/exam/stats/overview → 总考试数、及格率、平均分
   b) 按部门统计、按课程统计、分数分布
7. 报表导出(管理员)：
   a) POST /api/v1/reports/generate → 选择报表类型(培训统计/考试统计/部门统计)和格式(XLSX/PDF)
   b) 异步生成 → 存入rpt_export_log → 完成后可下载

涉及表：ex_exam_record, ex_exam_answer, qb_question, ai_grading_log, rpt_export_log
```

---

## 使用方法

1. 把上面任意一个提示词完整复制到 Gemini
2. Gemini 会生成 mermaid 代码块
3. 把 mermaid 代码复制到 https://mermaid.live 或支持 mermaid 的 Markdown 编辑器中，即可看到流程图
4. 也可以让 Gemini 直接输出 PlantUML 格式（把提示词中"mermaid"改为"PlantUML"）
