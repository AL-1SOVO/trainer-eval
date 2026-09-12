<template>
  <div class="page-container course-detail-page">
    <el-card shadow="never" class="hero-card">
      <div class="hero-content">
        <div class="hero-main">
          <el-tag type="success" effect="plain">在线课程</el-tag>
          <h2>{{ course.courseName || '课程详情' }}</h2>
          <p>{{ course.description || '暂无课程简介' }}</p>
          <div class="hero-meta">
            <span>{{ course.totalChapters || 0 }} 个章节</span>
            <span>{{ materialTotal }} 份资料</span>
            <span>{{ course.enrollCount || 0 }} 人报名</span>
          </div>
        </div>
        <div class="hero-actions">
          <el-button v-if="enrolled" type="primary" size="large" @click="goLearn">开始学习</el-button>
          <el-button v-else type="primary" size="large" :loading="enrolling" @click="handleEnroll">立即报名</el-button>
        </div>
      </div>
    </el-card>

    <el-row :gutter="20">
      <el-col :xs="24" :lg="16">
        <el-card shadow="never" class="section-card">
          <template #header>
            <div class="section-title">课程目录</div>
          </template>

          <div v-if="course.chapters?.length" class="chapter-list">
            <el-card v-for="chapter in course.chapters" :key="chapter.id" shadow="hover" class="chapter-card">
              <template #header>
                <div class="chapter-header">
                  <div>
                    <div class="chapter-title">{{ chapter.title }}</div>
                    <div class="chapter-meta">{{ chapter.materials?.length || 0 }} 份资料</div>
                  </div>
                </div>
              </template>
              <p class="chapter-desc">{{ chapter.description || '暂无章节说明' }}</p>
              <div v-if="chapter.materials?.length" class="material-preview-list">
                <div v-for="material in chapter.materials" :key="material.id" class="material-preview">
                  <el-tag size="small" :type="materialTypeTag(material.materialType)">
                    {{ materialTypeLabel(material.materialType) }}
                  </el-tag>
                  <span>{{ material.title }}</span>
                </div>
              </div>
              <el-empty v-else description="该章节暂无资料" :image-size="72" />
            </el-card>
          </div>
          <el-empty v-else description="课程内容正在准备中" />
        </el-card>
      </el-col>

      <el-col :xs="24" :lg="8">
        <el-card shadow="never" class="section-card">
          <template #header>
            <div class="section-title">学习建议</div>
          </template>
          <div class="tips-panel">
            <div>1. 先报名，再进入课程学习页按章节逐个完成。</div>
            <div>2. 视频、文档、PPT 打开后会自动记录首次学习时间。</div>
            <div>3. 每完成一份资料都可以手动标记，系统会自动累计学习进度。</div>
            <div>4. 课程资料全部完成后，课程状态会自动变为“已完成”。</div>
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { enrollCourse, getCourseById, getMyEnrollments } from '@/api/course'

const route = useRoute()
const router = useRouter()

const course = ref({ chapters: [] })
const myEnrollments = ref([])
const enrolling = ref(false)

const materialTotal = computed(() =>
  (course.value.chapters || []).reduce((total, chapter) => total + (chapter.materials?.length || 0), 0)
)

const enrolled = computed(() => myEnrollments.value.some(item => item.courseId === Number(route.params.id)))

function materialTypeLabel(type) {
  if (type === 'VIDEO') return '视频'
  if (type === 'DOCUMENT') return '文档'
  if (type === 'PPT') return 'PPT'
  if (type === 'TEXT') return '图文'
  return type
}

function materialTypeTag(type) {
  if (type === 'VIDEO') return 'danger'
  if (type === 'DOCUMENT') return 'success'
  if (type === 'PPT') return 'warning'
  return 'info'
}

async function loadPageData() {
  const [courseData, enrollments] = await Promise.all([
    getCourseById(route.params.id),
    getMyEnrollments().catch(() => [])
  ])
  course.value = courseData
  myEnrollments.value = enrollments
}

async function handleEnroll() {
  enrolling.value = true
  try {
    await enrollCourse(course.value.id)
    ElMessage.success('报名成功，现在可以开始学习了')
    myEnrollments.value = await getMyEnrollments()
  } finally {
    enrolling.value = false
  }
}

function goLearn() {
  router.push(`/employee/course-learn/${route.params.id}`)
}

onMounted(loadPageData)
</script>

<style scoped>
.course-detail-page {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.hero-card,
.section-card {
  border-radius: 20px;
}

.hero-content {
  display: flex;
  justify-content: space-between;
  gap: 20px;
  align-items: center;
}

.hero-main h2 {
  margin: 14px 0 10px;
  color: #0f172a;
  font-size: 30px;
}

.hero-main p {
  margin: 0;
  color: #475569;
  line-height: 1.8;
}

.hero-meta {
  display: flex;
  gap: 18px;
  flex-wrap: wrap;
  margin-top: 16px;
  color: #64748b;
  font-size: 14px;
}

.hero-actions {
  flex-shrink: 0;
}

.section-title {
  font-size: 17px;
  font-weight: 600;
  color: #0f172a;
}

.chapter-list {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.chapter-card {
  border-radius: 16px;
}

.chapter-title {
  color: #0f172a;
  font-size: 16px;
  font-weight: 600;
}

.chapter-meta {
  margin-top: 6px;
  color: #64748b;
  font-size: 13px;
}

.chapter-desc {
  margin: 0 0 14px;
  color: #475569;
  line-height: 1.7;
}

.material-preview-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.material-preview {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  background: #f8fafc;
  border-radius: 12px;
  color: #334155;
}

.tips-panel {
  display: flex;
  flex-direction: column;
  gap: 14px;
  line-height: 1.8;
  color: #475569;
}

@media (max-width: 992px) {
  .hero-content {
    flex-direction: column;
    align-items: flex-start;
  }
}
</style>
