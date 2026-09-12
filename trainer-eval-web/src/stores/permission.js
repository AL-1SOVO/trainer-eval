import { defineStore } from 'pinia'

export const usePermissionStore = defineStore('permission', {
  state: () => ({ routes: [] }),
  actions: {
    generateRoutes(roles) {
      const dynamicRoutes = []
      const modules = import.meta.glob('../views/**/*.vue')

      // Employee routes
      if (roles.includes('EMPLOYEE') || roles.includes('TRAINER') || roles.includes('MANAGER')) {
        dynamicRoutes.push({
          path: '/employee',
          redirect: '/employee/courses',
          component: () => import('@/layout/Layout.vue'),
          children: [
            { path: 'dashboard', name: 'EmployeeDashboard', component: () => import('@/views/employee/dashboard/EmployeeDashboard.vue'), meta: { title: '学习仪表盘' } },
            { path: 'courses', name: 'CourseList', component: () => import('@/views/employee/course/CourseList.vue'), meta: { title: '学习中心' } },
            { path: 'courses/:id', name: 'CourseDetail', component: () => import('@/views/employee/course/CourseDetail.vue'), meta: { title: '课程详情' } },
            { path: 'my-courses', name: 'MyCourseList', component: () => import('@/views/employee/mycourse/MyCourseList.vue'), meta: { title: '我的课程' } },
            { path: 'my-exams', name: 'ExamList', component: () => import('@/views/employee/exam/ExamList.vue'), meta: { title: '我的考试' } },
            { path: 'exam/:recordId', name: 'ExamTaking', component: () => import('@/views/employee/exam/ExamTaking.vue'), meta: { title: '考试中' } },
            { path: 'exam-result/:recordId', name: 'ExamResult', component: () => import('@/views/employee/exam/ExamResult.vue'), meta: { title: '考试成绩' } },
            { path: 'wrong-analysis', name: 'WrongAnalysis', component: () => import('@/views/employee/analysis/WrongAnalysis.vue'), meta: { title: '错题分析' } },
            { path: 'ai-tutor', name: 'AiTutor', component: () => import('@/views/employee/agent/AiTutor.vue'), meta: { title: 'AI学习助手' } }
          ]

        })
      }

      // Trainer routes
      if (roles.includes('TRAINER') || roles.includes('MANAGER')) {
        dynamicRoutes.push({
          path: '/trainer',
          redirect: '/trainer/course-manage',
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
            { path: 'grading/:recordId', name: 'GradingDetail', component: () => import('@/views/trainer/grading/GradingDetail.vue'), meta: { title: '阅卷详情' } }
          ]
        })
      }

      // Manager routes
      if (roles.includes('MANAGER')) {
        dynamicRoutes.push({
          path: '/manager',
          redirect: '/manager/dashboard',
          component: () => import('@/layout/Layout.vue'),
          children: [
            { path: 'dashboard', name: 'ManagerDashboard', component: () => import('@/views/manager/dashboard/ManagerDashboard.vue'), meta: { title: '管理仪表盘' } },
            { path: 'users', name: 'UserList', component: () => import('@/views/manager/user/UserList.vue'), meta: { title: '用户管理' } },
            { path: 'depts', name: 'DeptManage', component: () => import('@/views/manager/dept/DeptManage.vue'), meta: { title: '部门管理' } },
            { path: 'roles', name: 'RoleManage', component: () => import('@/views/manager/role/RoleManage.vue'), meta: { title: '角色管理' } },
            { path: 'menus', name: 'MenuManage', component: () => import('@/views/manager/menu/MenuManage.vue'), meta: { title: '菜单管理' } },
            { path: 'training', name: 'AllCourses', component: () => import('@/views/manager/training/AllCourses.vue'), meta: { title: '培训监管' } },
            { path: 'exam-stats', name: 'ExamStats', component: () => import('@/views/manager/exam/ExamStats.vue'), meta: { title: '考试统计' } },
            { path: 'reports', name: 'ReportGenerate', component: () => import('@/views/manager/report/ReportGenerate.vue'), meta: { title: '报表中心' } }
          ]
        })
      }

      this.routes = dynamicRoutes
      return dynamicRoutes
    }
  }
})
