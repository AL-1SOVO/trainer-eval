<template>
  <div class="page-container course-editor-page">
    <el-page-header @back="router.back()">
      <template #content>
        <div class="header-title">
          <span>{{ isEdit ? '编辑课程' : '新建课程' }}</span>
          <el-tag size="small" :type="courseId ? 'success' : 'warning'">
            {{ courseId ? '可继续维护课程内容' : '请先保存课程基础信息' }}
          </el-tag>
        </div>
      </template>
    </el-page-header>

    <el-row :gutter="20" class="editor-grid">
      <el-col :xs="24" :lg="10">
        <el-card shadow="never" class="editor-card">
          <template #header>
            <div class="card-header">
              <span>课程基础信息</span>
              <el-button type="primary" :loading="savingCourse" @click="handleSaveCourse">
                {{ courseId ? '保存修改' : '创建课程' }}
              </el-button>
            </div>
          </template>

          <el-form ref="courseFormRef" :model="courseForm" :rules="courseRules" label-width="90px">
            <el-form-item label="课程封面">
              <div class="cover-upload-section">
                <div class="cover-preview">
                  <img v-if="courseForm.coverImage" :src="resolveFileUrl(courseForm.coverImage)" alt="课程封面" />
                  <div v-else class="cover-placeholder">暂无封面</div>
                </div>
                <div class="cover-actions">
                  <el-upload
                    action="#"
                    :auto-upload="false"
                    :show-file-list="false"
                    :before-upload="() => false"
                    :on-change="handleCoverChange"
                  >
                    <el-button :loading="uploadingCover">选择封面</el-button>
                  </el-upload>
                  <div class="cover-tip">{{ uploadedCoverName || '建议上传 16:9 的 JPG/PNG 图片' }}</div>
                  <el-button
                    v-if="pendingCoverFile"
                    type="primary"
                    plain
                    size="small"
                    :loading="uploadingCover"
                    @click="submitCoverUpload"
                  >
                    上传封面
                  </el-button>
                </div>
              </div>
            </el-form-item>

            <el-form-item label="课程名称" prop="courseName">
              <el-input v-model="courseForm.courseName" maxlength="60" show-word-limit placeholder="请输入课程名称" />
            </el-form-item>
            <el-form-item label="所属部门">
              <el-select v-model="courseForm.deptId" clearable placeholder="不限部门" style="width: 100%">
                <el-option v-for="dept in depts" :key="dept.id" :label="dept.deptName" :value="dept.id" />
              </el-select>
            </el-form-item>
            <el-form-item label="课程简介">
              <el-input
                v-model="courseForm.description"
                type="textarea"
                :rows="6"
                maxlength="500"
                show-word-limit
                placeholder="请输入课程简介、适用对象和学习目标"
              />
            </el-form-item>
          </el-form>

          <div class="tips-box">
            <div>1. 先创建课程，再维护章节与资料。</div>
            <div>2. 学习资料支持视频、文档、PPT 和图文内容。</div>
            <div>3. 建议先设置封面，课程列表和学习中心会直接展示。</div>
          </div>
        </el-card>
      </el-col>

      <el-col :xs="24" :lg="14">
        <el-card shadow="never" class="editor-card">
          <template #header>
            <div class="card-header">
              <span>课程内容管理</span>
              <el-button type="primary" plain :disabled="!courseId" @click="openChapterDialog()">
                新增章节
              </el-button>
            </div>
          </template>

          <div v-if="!courseId" class="empty-hint">
            <el-empty description="保存课程后即可新增章节、上传资料并配置学习路径" />
          </div>

          <div v-else-if="!chapters.length" class="empty-hint">
            <el-empty description="当前还没有章节，点击右上角开始搭建课程内容" />
          </div>

          <div v-else class="chapter-list">
            <el-card v-for="chapter in chapters" :key="chapter.id" shadow="hover" class="chapter-card">
              <template #header>
                <div class="card-header">
                  <div>
                    <div class="chapter-title">{{ chapter.title }}</div>
                    <div class="chapter-meta">
                      排序 {{ chapter.sortOrder || '-' }} · {{ chapter.materials?.length || 0 }} 份资料
                    </div>
                  </div>
                  <div class="header-actions">
                    <el-button link type="primary" @click="openChapterDialog(chapter)">编辑章节</el-button>
                    <el-button link type="success" @click="openIngestDialog(chapter)">上传并建索引</el-button>
                    <el-button link type="primary" @click="openMaterialDialog(chapter)">新增资料</el-button>
                    <el-popconfirm title="删除章节会同时删除其下所有资料，确认继续吗？" @confirm="handleDeleteChapter(chapter.id)">
                      <template #reference>
                        <el-button link type="danger">删除章节</el-button>
                      </template>
                    </el-popconfirm>
                  </div>
                </div>
              </template>

              <p class="chapter-desc">{{ chapter.description || '暂无章节说明' }}</p>

              <div v-if="chapter.materials?.length" class="material-list">
                <div v-for="material in chapter.materials" :key="material.id" class="material-item">
                  <div class="material-main">
                    <el-tag size="small" :type="materialTypeTag(material.materialType)">
                      {{ materialTypeLabel(material.materialType) }}
                    </el-tag>
                    <div class="material-info">
                      <div class="material-title">{{ material.title }}</div>
                      <div class="material-meta">
                        排序 {{ material.sortOrder || '-' }}
                        <span v-if="material.fileUrl"> · 已上传文件</span>
                        <span v-if="material.duration"> · {{ material.duration }} 分钟</span>
                      </div>
                    </div>
                  </div>
                  <div class="header-actions">
                    <el-button link type="primary" @click="openMaterialDialog(chapter, material)">编辑</el-button>
                    <el-button v-if="material.fileUrl" link type="primary" @click="openFile(material.fileUrl)">
                      预览
                    </el-button>
                    <el-popconfirm title="确认删除这份资料吗？" @confirm="handleDeleteMaterial(material.id)">
                      <template #reference>
                        <el-button link type="danger">删除</el-button>
                      </template>
                    </el-popconfirm>
                  </div>
                </div>
              </div>
              <el-empty v-else description="该章节还没有资料" :image-size="80" />
            </el-card>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-dialog v-model="chapterDialogVisible" :title="chapterDialogTitle" width="520px">
      <el-form ref="chapterFormRef" :model="chapterForm" :rules="chapterRules" label-width="90px">
        <el-form-item label="章节标题" prop="title">
          <el-input v-model="chapterForm.title" maxlength="60" show-word-limit />
        </el-form-item>
        <el-form-item label="章节排序">
          <el-input-number v-model="chapterForm.sortOrder" :min="1" :max="9999" style="width: 100%" />
        </el-form-item>
        <el-form-item label="章节说明">
          <el-input v-model="chapterForm.description" type="textarea" :rows="4" maxlength="300" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="chapterDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="savingChapter" @click="handleSaveChapter">保存章节</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="materialDialogVisible" :title="materialDialogTitle" width="640px">
      <el-form ref="materialFormRef" :model="materialForm" :rules="materialRules" label-width="90px">
        <el-form-item label="所属章节">
          <el-input :model-value="currentChapter?.title || '-'" disabled />
        </el-form-item>
        <el-form-item label="资料标题" prop="title">
          <el-input v-model="materialForm.title" maxlength="80" show-word-limit />
        </el-form-item>
        <el-form-item label="资料类型" prop="materialType">
          <el-select v-model="materialForm.materialType" style="width: 100%">
            <el-option v-for="item in materialTypeOptions" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="显示顺序">
          <el-input-number v-model="materialForm.sortOrder" :min="1" :max="9999" style="width: 100%" />
        </el-form-item>
        <el-form-item label="学习时长">
          <el-input-number v-model="materialForm.duration" :min="0" :max="9999" style="width: 100%" />
        </el-form-item>
        <el-form-item v-if="needsFile(materialForm.materialType)" label="上传文件">
          <el-upload
            class="upload-block"
            action="#"
            :auto-upload="false"
            :show-file-list="false"
            :on-change="handleFileChange"
            :before-upload="() => false"
          >
            <el-button :loading="uploadingFile">选择文件</el-button>
          </el-upload>
          <div class="upload-hint">
            <span>{{ uploadedFileName || materialForm.fileUrl || '未上传文件' }}</span>
            <el-button v-if="pendingFile" link type="primary" :loading="uploadingFile" @click="submitUpload">
              开始上传
            </el-button>
          </div>
        </el-form-item>
        <el-form-item v-if="materialForm.materialType === 'TEXT'" label="图文内容">
          <el-input
            v-model="materialForm.content"
            type="textarea"
            :rows="8"
            maxlength="5000"
            show-word-limit
            placeholder="请输入学习说明、知识点或图文内容"
          />
        </el-form-item>
        <el-form-item v-else label="资料说明">
          <el-input
            v-model="materialForm.content"
            type="textarea"
            :rows="4"
            maxlength="1000"
            show-word-limit
            placeholder="可填写资料摘要、学习要求或观看说明"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="materialDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="savingMaterial" @click="handleSaveMaterial">保存资料</el-button>
      </template>
    </el-dialog>

    <!-- 上传并建索引：一步把文件变成"能被搜到"的资料 -->
    <el-dialog v-model="ingestDialogVisible" title="上传文件并建立检索索引" width="640px">
      <el-alert type="info" :closable="false" show-icon class="ingest-tip"
                title="上传后系统会自动抽取正文、切块，并同步到知识库，员工即可在「知识搜索」中查到。" />
      <el-form label-width="90px" class="ingest-form">
        <el-form-item label="目标章节">
          <el-input :model-value="ingestChapterTitle" disabled />
        </el-form-item>
        <el-form-item label="选择文件">
          <el-upload
            :auto-upload="false"
            :limit="1"
            :on-change="handleIngestFileChange"
            :on-remove="() => { ingestFile = null }"
            :file-list="ingestFileList"
          >
            <el-button>选择文件</el-button>
            <template #tip>
              <div class="upload-tip">
                支持 {{ (ingestCapabilities.formats || []).join(' / ') }}，单个文件不超过 50MB
              </div>
            </template>
          </el-upload>
        </el-form-item>
        <el-form-item label="资料标题">
          <el-input v-model="ingestForm.title" clearable placeholder="留空则用文件名（不含扩展名）" />
        </el-form-item>
        <el-form-item label="资料类型">
          <el-select v-model="ingestForm.materialType" clearable placeholder="留空则按扩展名自动判断" style="width:100%">
            <el-option label="文档" value="DOCUMENT" />
            <el-option label="课件" value="PPT" />
            <el-option label="视频" value="VIDEO" />
          </el-select>
        </el-form-item>
      </el-form>

      <!-- 结果如实回显：抽了多少字、切了几块、知识库有没有同步成功 -->
      <el-card v-if="ingestResult" shadow="never" class="ingest-result">
        <div class="ingest-result-row">
          <el-tag :type="ingestResult.searchable ? 'success' : 'danger'" size="small">
            {{ ingestResult.searchable ? '已可被检索' : '未被索引' }}
          </el-tag>
          <span class="ingest-result-text">
            格式 {{ ingestResult.format }} · 抽取 {{ ingestResult.extractedChars }} 字 ·
            解析 {{ ingestResult.parsedBlocks }} 段 · 切块 {{ ingestResult.chunkCount }} 块 ·
            知识库同步 {{ knowledgeSyncLabel(ingestResult.knowledgeSync) }}
          </span>
        </div>
        <el-alert
          v-if="ingestResult.warnings && ingestResult.warnings.length"
          type="warning"
          :closable="false"
          class="ingest-warning"
          :title="ingestResult.warnings.join('；')"
        />
        <div v-if="ingestResult.knowledgeSync === 'SKIPPED_NOT_CONFIGURED'" class="ingest-hint">
          未配置知识库写入账号，本次只建立了本地索引：员工仍能搜到，但拿不到混合召回与重排。
        </div>
        <div v-else-if="ingestResult.knowledgeSync === 'FAILED'" class="ingest-hint">
          知识库同步失败：该资料当前只能通过本地检索搜到。可稍后重试，或让管理员执行知识库重建。
        </div>
      </el-card>

      <template #footer>
        <el-button @click="ingestDialogVisible = false">关闭</el-button>
        <el-button type="primary" :loading="ingesting" @click="handleSubmitIngest">上传并建立索引</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getDeptTree } from '@/api/dept'
import {
  createChapter,
  createCourse,
  createMaterial,
  deleteChapter,
  deleteMaterial,
  getCourseById,
  getIngestCapabilities,
  ingestMaterial,
  updateChapter,
  updateCourse,
  updateMaterial,
  uploadCourseCover,
  uploadMaterial
} from '@/api/course'

const route = useRoute()
const router = useRouter()

const courseFormRef = ref()
const chapterFormRef = ref()
const materialFormRef = ref()

const isEdit = computed(() => Boolean(route.params.id))
const courseId = ref(route.params.id ? Number(route.params.id) : null)
const savingCourse = ref(false)
const savingChapter = ref(false)
const savingMaterial = ref(false)
const uploadingFile = ref(false)
const uploadingCover = ref(false)
const depts = ref([])
const chapters = ref([])

const chapterDialogVisible = ref(false)
const materialDialogVisible = ref(false)
const currentChapter = ref(null)
const editingChapterId = ref(null)
const editingMaterialId = ref(null)
const pendingFile = ref(null)
const pendingCoverFile = ref(null)
const uploadedFileName = ref('')
const uploadedCoverName = ref('')

// 「上传并建索引」的状态
const ingestDialogVisible = ref(false)
const ingesting = ref(false)
const ingestChapterId = ref(null)
const ingestChapterTitle = ref('')
const ingestFile = ref(null)
const ingestFileList = ref([])
const ingestResult = ref(null)
const ingestCapabilities = ref({ formats: [], maxFileBytes: 0 })
const ingestForm = reactive({ title: '', materialType: '' })

const courseForm = reactive({
  courseName: '',
  description: '',
  deptId: null,
  coverImage: ''
})

const chapterForm = reactive({
  title: '',
  description: '',
  sortOrder: 1
})

const materialForm = reactive({
  title: '',
  materialType: 'VIDEO',
  fileUrl: '',
  fileSize: null,
  content: '',
  duration: 0,
  sortOrder: 1
})

const materialTypeOptions = [
  { label: '视频', value: 'VIDEO' },
  { label: '文档', value: 'DOCUMENT' },
  { label: 'PPT', value: 'PPT' },
  { label: '图文', value: 'TEXT' }
]

const courseRules = {
  courseName: [{ required: true, message: '请输入课程名称', trigger: 'blur' }]
}

const chapterRules = {
  title: [{ required: true, message: '请输入章节标题', trigger: 'blur' }]
}

const materialRules = {
  title: [{ required: true, message: '请输入资料标题', trigger: 'blur' }],
  materialType: [{ required: true, message: '请选择资料类型', trigger: 'change' }]
}

const chapterDialogTitle = computed(() => (editingChapterId.value ? '编辑章节' : '新增章节'))
const materialDialogTitle = computed(() => (editingMaterialId.value ? '编辑资料' : '新增资料'))

function materialTypeLabel(type) {
  return materialTypeOptions.find(item => item.value === type)?.label || type
}

function materialTypeTag(type) {
  if (type === 'VIDEO') return 'danger'
  if (type === 'DOCUMENT') return 'success'
  if (type === 'PPT') return 'warning'
  return 'info'
}

function needsFile(type) {
  return ['VIDEO', 'DOCUMENT', 'PPT'].includes(type)
}

function resolveFileUrl(fileUrl) {
  if (!fileUrl) return ''
  if (fileUrl.startsWith('http')) return fileUrl
  return `${window.location.origin}${fileUrl}`
}

function openFile(fileUrl) {
  const target = resolveFileUrl(fileUrl)
  if (target) {
    window.open(target, '_blank')
  }
}

async function loadBaseData() {
  try {
    depts.value = await getDeptTree()
  } catch (error) {
    depts.value = []
  }
}

async function loadCourseDetail() {
  if (!courseId.value) {
    chapters.value = []
    return
  }

  const course = await getCourseById(courseId.value)
  courseForm.courseName = course.courseName || ''
  courseForm.description = course.description || ''
  courseForm.deptId = course.deptId ?? null
  courseForm.coverImage = course.coverImage || ''
  uploadedCoverName.value = course.coverImage ? '当前课程封面已设置' : ''
  chapters.value = course.chapters || []
}

async function handleSaveCourse() {
  const valid = await courseFormRef.value.validate().catch(() => false)
  if (!valid) return

  savingCourse.value = true
  try {
    if (courseId.value) {
      await updateCourse(courseId.value, courseForm)
      ElMessage.success('课程信息已保存')
    } else {
      const created = await createCourse(courseForm)
      courseId.value = created.id
      ElMessage.success('课程创建成功，请继续维护课程内容')
      await router.replace(`/trainer/course-editor/${created.id}`)
    }
    await loadCourseDetail()
  } finally {
    savingCourse.value = false
  }
}

function openChapterDialog(chapter = null) {
  editingChapterId.value = chapter?.id || null
  chapterForm.title = chapter?.title || ''
  chapterForm.description = chapter?.description || ''
  chapterForm.sortOrder = chapter?.sortOrder || (chapters.value.length + 1)
  chapterDialogVisible.value = true
}

async function handleSaveChapter() {
  const valid = await chapterFormRef.value.validate().catch(() => false)
  if (!valid || !courseId.value) return

  savingChapter.value = true
  try {
    if (editingChapterId.value) {
      await updateChapter(editingChapterId.value, chapterForm)
      ElMessage.success('章节已更新')
    } else {
      await createChapter(courseId.value, chapterForm)
      ElMessage.success('章节已创建')
    }
    chapterDialogVisible.value = false
    await loadCourseDetail()
  } finally {
    savingChapter.value = false
  }
}

async function handleDeleteChapter(chapterId) {
  await deleteChapter(chapterId)
  ElMessage.success('章节已删除')
  await loadCourseDetail()
}

function resetMaterialForm() {
  materialForm.title = ''
  materialForm.materialType = 'VIDEO'
  materialForm.fileUrl = ''
  materialForm.fileSize = null
  materialForm.content = ''
  materialForm.duration = 0
  materialForm.sortOrder = 1
  pendingFile.value = null
  uploadedFileName.value = ''
}

function openMaterialDialog(chapter, material = null) {
  currentChapter.value = chapter
  editingMaterialId.value = material?.id || null
  resetMaterialForm()
  materialForm.title = material?.title || ''
  materialForm.materialType = material?.materialType || 'VIDEO'
  materialForm.fileUrl = material?.fileUrl || ''
  materialForm.fileSize = material?.fileSize || null
  materialForm.content = material?.content || ''
  materialForm.duration = material?.duration || 0
  materialForm.sortOrder = material?.sortOrder || ((chapter.materials?.length || 0) + 1)
  materialDialogVisible.value = true
}

function handleFileChange(uploadFile) {
  pendingFile.value = uploadFile.raw
  uploadedFileName.value = uploadFile.name
}

// ------------------------------------------------------------------ 上传并建索引

function openIngestDialog(chapter) {
  ingestChapterId.value = chapter.id
  ingestChapterTitle.value = chapter.title || ''
  ingestFile.value = null
  ingestFileList.value = []
  ingestResult.value = null
  ingestForm.title = ''
  ingestForm.materialType = ''
  ingestDialogVisible.value = true
  loadIngestCapabilities()
}

async function loadIngestCapabilities() {
  // 只为提示文案；失败不影响上传，所以静默处理
  if (ingestCapabilities.value.formats?.length) return
  try {
    const data = await getIngestCapabilities()
    if (data) ingestCapabilities.value = data
  } catch (e) { /* ignore */ }
}

function handleIngestFileChange(uploadFile) {
  ingestFile.value = uploadFile.raw
  ingestFileList.value = uploadFile.raw ? [uploadFile] : []
}

function knowledgeSyncLabel(status) {
  if (status === 'SYNCED') return '已同步'
  if (status === 'SKIPPED_NOT_CONFIGURED') return '未配置，已跳过'
  if (status === 'FAILED') return '失败'
  if (status === 'DELETED_EMPTY') return '正文为空，已清理'
  return '未知'
}

async function handleSubmitIngest() {
  if (!ingestFile.value) {
    ElMessage.warning('请先选择要上传的文件')
    return
  }
  const chapterId = ingestChapterId.value
  const formData = new FormData()
  formData.append('file', ingestFile.value)
  if (ingestForm.title) formData.append('title', ingestForm.title)
  if (ingestForm.materialType) formData.append('materialType', ingestForm.materialType)

  ingesting.value = true
  try {
    const result = await ingestMaterial(chapterId, formData)
    ingestResult.value = result
    if (result.searchable) {
      ElMessage.success(`已建立索引：抽取 ${result.extractedChars} 字、切块 ${result.chunkCount} 块`)
    } else {
      ElMessage.warning('资料已创建，但未产生索引，员工暂时搜不到')
    }
  } catch (e) {
    // 后端对"抽不到正文"等情况会带明确原因返回，request.js 已统一弹出 message
    ingestResult.value = null
  } finally {
    ingesting.value = false
  }
  // 刷新章节列表放在结果处理之外：刷新失败不该把刚拿到的结果清掉
  loadCourseDetail().catch(() => {})
}

function handleCoverChange(uploadFile) {
  pendingCoverFile.value = uploadFile.raw
  uploadedCoverName.value = uploadFile.name
}

async function submitUpload() {
  if (!pendingFile.value) return
  const formData = new FormData()
  formData.append('file', pendingFile.value)

  uploadingFile.value = true
  try {
    const result = await uploadMaterial(formData)
    materialForm.fileUrl = result.fileUrl
    materialForm.fileSize = result.fileSize
    uploadedFileName.value = result.originalName || uploadedFileName.value
    pendingFile.value = null
    ElMessage.success('资料上传成功')
  } finally {
    uploadingFile.value = false
  }
}

async function submitCoverUpload() {
  if (!pendingCoverFile.value) return
  const formData = new FormData()
  formData.append('file', pendingCoverFile.value)

  uploadingCover.value = true
  try {
    const result = await uploadCourseCover(formData)
    courseForm.coverImage = result.fileUrl
    uploadedCoverName.value = result.originalName || uploadedCoverName.value
    pendingCoverFile.value = null
    ElMessage.success('课程封面上传成功')
  } finally {
    uploadingCover.value = false
  }
}

async function handleSaveMaterial() {
  const valid = await materialFormRef.value.validate().catch(() => false)
  if (!valid || !currentChapter.value) return

  if (needsFile(materialForm.materialType) && !materialForm.fileUrl) {
    ElMessage.warning('请先上传资料文件')
    return
  }

  savingMaterial.value = true
  try {
    if (editingMaterialId.value) {
      await updateMaterial(editingMaterialId.value, materialForm)
      ElMessage.success('资料已更新')
    } else {
      await createMaterial(currentChapter.value.id, materialForm)
      ElMessage.success('资料已添加')
    }
    materialDialogVisible.value = false
    await loadCourseDetail()
  } finally {
    savingMaterial.value = false
  }
}

async function handleDeleteMaterial(materialId) {
  await deleteMaterial(materialId)
  ElMessage.success('资料已删除')
  await loadCourseDetail()
}

onMounted(async () => {
  await loadBaseData()
  await loadCourseDetail()
})
</script>

<style scoped>
.course-editor-page {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.editor-grid {
  margin-top: 4px;
}

.editor-card {
  border-radius: 18px;
}

.header-title {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 18px;
  font-weight: 600;
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-wrap: wrap;
}

.cover-upload-section {
  display: flex;
  align-items: center;
  gap: 18px;
  width: 100%;
}

.cover-preview {
  width: 220px;
  height: 124px;
  border-radius: 16px;
  overflow: hidden;
  background: #f8fafc;
  border: 1px dashed #cbd5e1;
  flex-shrink: 0;
}

.cover-preview img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.cover-placeholder {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #94a3b8;
  font-size: 14px;
}

.cover-actions {
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-width: 0;
}

.cover-tip {
  color: #64748b;
  font-size: 13px;
  line-height: 1.6;
}

.tips-box {
  padding: 14px 16px;
  border-radius: 14px;
  background: linear-gradient(135deg, #eff6ff, #f0fdf4);
  color: #475569;
  line-height: 1.8;
}

.empty-hint {
  padding: 24px 0;
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
  font-size: 16px;
  font-weight: 600;
  color: #0f172a;
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

.material-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.material-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 14px;
  border-radius: 14px;
  background: #f8fafc;
}

.material-main {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  min-width: 0;
}

.material-info {
  min-width: 0;
}

.material-title {
  color: #0f172a;
  font-weight: 600;
}

.material-meta {
  margin-top: 6px;
  color: #64748b;
  font-size: 13px;
}

.upload-block {
  width: 100%;
}

.upload-hint {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 8px;
  color: #64748b;
  font-size: 13px;
  flex-wrap: wrap;
}

@media (max-width: 992px) {
  .material-item,
  .card-header,
  .cover-upload-section {
    flex-direction: column;
    align-items: flex-start;
  }

  .cover-preview {
    width: 100%;
    height: 180px;
  }
}

.ingest-tip {
  margin-bottom: 16px;
}

.ingest-form {
  margin-top: 4px;
}

.ingest-result {
  border-radius: 12px;
  background: #f8fafc;
}

.ingest-result-row {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.ingest-result-text {
  color: #334155;
  font-size: 13px;
  line-height: 1.7;
}

.ingest-warning {
  margin-top: 10px;
}

.ingest-hint {
  margin-top: 10px;
  color: #b45309;
  font-size: 13px;
  line-height: 1.7;
}
</style>
