import { createRouter, createWebHistory } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { useAppStore } from '@/stores/app'
import { homePath } from '@/utils/home'

const routes = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/login/LoginView.vue'),
    meta: { noAuth: true }
  },
  {
    path: '/',
    redirect: '/login'
  },
  {
    path: '/employee',
    component: () => import('@/layout/Layout.vue'),
    children: [
      { path: 'dashboard', name: 'EmployeeDashboard', component: () => import('@/views/employee/dashboard/EmployeeDashboard.vue'), meta: { title: '学习仪表盘' } },
      { path: 'courses', name: 'CourseList', component: () => import('@/views/employee/course/CourseList.vue'), meta: { title: '学习中心' } },
      { path: 'courses/:id', name: 'CourseDetail', component: () => import('@/views/employee/course/CourseDetail.vue'), meta: { title: '课程详情' } },
      { path: 'course-learn/:id', name: 'CourseLearn', component: () => import('@/views/employee/course/CourseLearn.vue'), meta: { title: '课程学习' } },
      { path: 'my-courses', name: 'MyCourseList', component: () => import('@/views/employee/mycourse/MyCourseList.vue'), meta: { title: '我的课程' } },
      { path: 'my-exams', name: 'ExamList', component: () => import('@/views/employee/exam/ExamList.vue'), meta: { title: '我的考试' } },
      { path: 'exam/:recordId', name: 'ExamTaking', component: () => import('@/views/employee/exam/ExamTaking.vue'), meta: { title: '考试中' } },
      { path: 'exam-result/:recordId', name: 'ExamResult', component: () => import('@/views/employee/exam/ExamResult.vue'), meta: { title: '考试成绩' } },
      { path: 'wrong-analysis', name: 'WrongAnalysis', component: () => import('@/views/employee/analysis/WrongAnalysis.vue'), meta: { title: '错题分析' } },
      { path: 'ai-tutor', name: 'AiTutor', component: () => import('@/views/employee/agent/AiTutor.vue'), meta: { title: 'AI学习助手' } },
      { path: 'knowledge-search', name: 'KnowledgeSearch', component: () => import('@/views/employee/knowledge/KnowledgeSearch.vue'), meta: { title: '知识搜索' } }
    ]
  },
  {
    path: '/trainer',
    component: () => import('@/layout/Layout.vue'),
    children: [
      { path: 'dashboard', name: 'TrainerDashboard', component: () => import('@/views/trainer/dashboard/TrainerDashboard.vue'), meta: { title: '培训概览' } },
      { path: 'course-manage', name: 'CourseManage', component: () => import('@/views/trainer/course/CourseManage.vue'), meta: { title: '课程管理' } },
      { path: 'course-editor/:id?', name: 'CourseEditor', component: () => import('@/views/trainer/course/CourseEditor.vue'), meta: { title: '课程编辑' } },
      { path: 'question-bank', name: 'QuestionList', component: () => import('@/views/trainer/question/QuestionList.vue'), meta: { title: '题库管理' } },
      { path: 'question-editor/:id?', name: 'QuestionEditor', component: () => import('@/views/trainer/question/QuestionEditor.vue'), meta: { title: '题目编辑' } },
      { path: 'paper-manage', name: 'PaperList', component: () => import('@/views/trainer/paper/PaperList.vue'), meta: { title: '试卷管理' } },
      { path: 'paper-editor/:id?', name: 'PaperEditor', component: () => import('@/views/trainer/paper/PaperEditor.vue'), meta: { title: '试卷编辑' } },
      { path: 'grading', name: 'GradingQueue', component: () => import('@/views/trainer/grading/GradingQueue.vue'), meta: { title: '阅卷中心' } },
      { path: 'grading/:recordId', name: 'GradingDetail', component: () => import('@/views/trainer/grading/GradingDetail.vue'), meta: { title: '阅卷详情' } },
      { path: 'ai-config', name: 'AiRuleConfig', component: () => import('@/views/trainer/aiconfig/AiRuleConfig.vue'), meta: { title: 'AI评分配置' } },
      // 内训师也要能管自己课程的知识库：文档本来就是内训师上传的，
      // 出了"传了但员工搜不到"的问题时，最该能自查的就是他。
      // 与管理员门户共用同一个组件，避免两份实现各自漂移。
      { path: 'knowledge', name: 'TrainerKnowledgeBase', component: () => import('@/views/manager/knowledge/KnowledgeBase.vue'), meta: { title: '知识库管理' } }
    ]
  },
  {
    path: '/manager',
    component: () => import('@/layout/Layout.vue'),
    children: [
      { path: 'dashboard', name: 'ManagerDashboard', component: () => import('@/views/manager/dashboard/ManagerDashboard.vue'), meta: { title: '管理仪表盘' } },
      { path: 'users', name: 'UserList', component: () => import('@/views/manager/user/UserList.vue'), meta: { title: '用户管理' } },
      { path: 'depts', name: 'DeptManage', component: () => import('@/views/manager/dept/DeptManage.vue'), meta: { title: '部门管理' } },
      { path: 'roles', name: 'RoleManage', component: () => import('@/views/manager/role/RoleManage.vue'), meta: { title: '角色管理' } },
      { path: 'menus', name: 'MenuManage', component: () => import('@/views/manager/menu/MenuManage.vue'), meta: { title: '菜单管理' } },
      // alias: sys_menu 里“培训监管”的 path 是 all-courses，而这里定义的是 training。
      // 之前侧边栏点它会直接进 404。用 alias 对齐，不需要动数据库里的菜单数据。
      { path: 'training', alias: 'all-courses', name: 'AllCourses', component: () => import('@/views/manager/training/AllCourses.vue'), meta: { title: '培训监管' } },
      { path: 'exam-stats', name: 'ExamStats', component: () => import('@/views/manager/exam/ExamStats.vue'), meta: { title: '考试统计' } },
      { path: 'reports', name: 'ReportGenerate', component: () => import('@/views/manager/report/ReportGenerate.vue'), meta: { title: '报表中心' } },
      { path: 'ai-logs', name: 'AiGradingLog', component: () => import('@/views/manager/log/AiGradingLog.vue'), meta: { title: 'AI评分日志' } },
      // RAG 运维：知识库里到底有什么、有没有漏灌、检索为什么没召回
      { path: 'knowledge', name: 'KnowledgeBaseManage', component: () => import('@/views/manager/knowledge/KnowledgeBase.vue'), meta: { title: '知识库管理' } },
      // 可观测性：一次请求内部的完整链路（模型调用 / 检索 / 工具各自耗时与 token）
      { path: 'traces', name: 'TraceList', component: () => import('@/views/manager/observability/TraceList.vue'), meta: { title: '链路追踪' } },
      // MCP 外部工具接入
      { path: 'mcp', name: 'McpManage', component: () => import('@/views/manager/mcp/McpManage.vue'), meta: { title: 'MCP 工具接入' } }
    ]
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'NotFound',
    component: () => import('@/views/NotFound.vue')
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 })
})

router.beforeEach(async (to, from, next) => {
  const userStore = useUserStore()
  const appStore = useAppStore()

  if (to.path === '/login') {
    if (userStore.token) {
      // 已登录时访问 /login：回到该角色自己的仪表盘，而不是固定去员工学习中心
      next(homePath(userStore.roles))
      return
    }
    next()
    return
  }

  if (!userStore.token) {
    next(`/login?redirect=${to.path}`)
    return
  }

  if (!userStore.userId) {
    try {
      await userStore.getUserInfo()
      await appStore.loadMenus()
      next()
    } catch (error) {
      userStore.resetState()
      next('/login')
    }
    return
  }

  // 菜单由管理员在后台维护：改完菜单不该要求每个用户重新登录/整页刷新才能看到。
  // 这里做 60s 节流的补偿刷新，顺带兜住"首次加载失败导致侧边栏一直空着"的情况。
  // 不 await：菜单只是导航的附属信息，不值得拖慢每一次跳转。
  appStore.refreshMenusIfStale(60000)

  next()
})

export default router
