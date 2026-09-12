<template>
  <div class="page-container learn-page">
    <el-row :gutter="20">
      <el-col :xs="24" :lg="7">
        <el-card shadow="never" class="sidebar-card">
          <template #header>
            <div class="learn-header">
              <div>
                <div class="course-title">{{ course.courseName || '课程学习' }}</div>
                <div class="course-progress">当前进度 {{ Math.round(Number(course.progress || 0)) }}%</div>
              </div>
            </div>
          </template>

          <el-progress :percentage="Math.round(Number(course.progress || 0))" :status="course.enrollmentStatus === 1 ? 'success' : ''" />

          <div class="chapter-nav">
            <div v-for="chapter in course.chapters || []" :key="chapter.id" class="chapter-nav-block">
              <div class="chapter-nav-title">{{ chapter.title }}</div>
              <div
                v-for="material in chapter.materials || []"
                :key="material.id"
                class="material-nav-item"
                :class="{ active: activeMaterial?.id === material.id }"
                @click="selectMaterial(chapter, material)"
              >
                <div class="material-nav-main">
                  <el-tag size="small" :type="material.completed ? 'success' : materialTypeTag(material.materialType)">
                    {{ material.completed ? '已学' : materialTypeLabel(material.materialType) }}
                  </el-tag>
                  <span class="material-nav-text">{{ material.title }}</span>
                </div>
                <el-icon v-if="material.completed" color="#16a34a"><Check /></el-icon>
              </div>
            </div>
          </div>
        </el-card>
      </el-col>

      <el-col :xs="24" :lg="17">
        <el-card shadow="never" class="content-card">
          <template #header>
            <div class="content-header">
              <div>
                <div class="material-title">{{ activeMaterial?.title || '请选择学习资料' }}</div>
                <div class="material-subtitle">
                  {{ activeChapter?.title || '章节内容' }}
                  <span v-if="activeMaterial?.duration"> · 建议学习 {{ activeMaterial.duration }} 分钟</span>
                </div>
              </div>
              <div class="content-actions" v-if="activeMaterial">
                <el-tag :type="activeMaterial.completed ? 'success' : 'warning'">
                  {{ activeMaterial.completed ? '已完成' : '未完成' }}
                </el-tag>
                <el-button type="primary" plain :disabled="Boolean(activeMaterial.completed)" @click="handleCompleteMaterial">
                  标记为已完成
                </el-button>
              </div>
            </div>
          </template>

          <div v-if="activeMaterial" class="material-content">
            <div class="viewer-shell">
              <div class="viewer-topbar">
                <div class="viewer-meta">
                  <span class="viewer-badge">{{ currentPreviewLabel }}</span>
                  <span class="viewer-file-name">{{ activeMaterial.title }}</span>
                </div>
                <el-button v-if="activeMaterial.fileUrl" text type="primary" @click="openSourceInNewTab">
                  新窗口打开
                </el-button>
              </div>

              <div v-if="activeMaterial.materialType === 'VIDEO'" class="viewer-body">
                <video :src="resolveFileUrl(activeMaterial.fileUrl)" controls class="video-player" />
              </div>

              <div v-else-if="isEmbeddedFilePreview" class="viewer-body">
                <iframe v-if="activeMaterial.fileUrl" :src="embeddedPreviewUrl" class="doc-frame" />
                <el-empty v-else description="未上传资料文件" />
              </div>

              <div v-else-if="isStructuredPreview" class="viewer-body">
                <div v-if="previewLoading" class="preview-loading">
                  <el-skeleton :rows="10" animated />
                </div>

                <div v-else-if="previewMode === 'slides' && previewPages.length" class="slide-preview-list">
                  <div v-for="(page, index) in previewPages" :key="index" class="slide-preview-item">
                    <div class="slide-index">第 {{ index + 1 }} 页</div>
                    <img :src="page" :alt="`slide-${index + 1}`" class="slide-preview-image" />
                  </div>
                </div>

                <div v-else-if="previewMode === 'docx'" class="docx-preview-wrap">
                  <div ref="docxPreviewRef" class="docx-preview-body"></div>
                </div>

                <div v-else-if="previewMode === 'text'" class="docx-preview-wrap">
                  <div class="docx-preview-body text-preview-body" v-html="previewHtml"></div>
                </div>

                <div v-else-if="legacyPreviewBlocks.length" class="legacy-preview-list">
                  <div v-for="(block, index) in legacyPreviewBlocks" :key="index" class="legacy-preview-item">
                    <div class="slide-index">第 {{ index + 1 }} 页</div>
                    <pre class="legacy-preview-text">{{ block }}</pre>
                  </div>
                </div>

                <el-empty v-else description="当前资料暂时无法解析预览" />
              </div>

              <div v-else class="viewer-body text-panel">
                <div class="text-content">{{ activeMaterial.content || '暂无图文内容' }}</div>
              </div>
            </div>

            <div class="study-note">
              <h4>学习说明</h4>
              <p>{{ activeMaterial.content || '暂无补充说明' }}</p>
            </div>
          </div>

          <el-empty v-else description="该课程还没有可学习的资料" />
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { Check } from '@element-plus/icons-vue'
import { computed, nextTick, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { renderAsync } from 'docx-preview'
import { completeMaterial, getLearningCourseDetail, previewMaterial, recordMaterialAccess } from '@/api/course'

const route = useRoute()

const course = ref({ chapters: [], progress: 0, enrollmentStatus: 0 })
const activeChapter = ref(null)
const activeMaterial = ref(null)
const previewPages = ref([])
const previewHtml = ref('')
const previewMode = ref('none')
const previewLoading = ref(false)
const legacyPreviewBlocks = ref([])
const docxPreviewRef = ref(null)

const embeddedPreviewUrl = computed(() => {
  if (!activeMaterial.value?.fileUrl) return ''
  return resolveFileUrl(activeMaterial.value.fileUrl)
})

const fileExtension = computed(() => {
  const fileUrl = activeMaterial.value?.fileUrl || ''
  const pureUrl = fileUrl.split('?')[0]
  const parts = pureUrl.split('.')
  return parts.length > 1 ? parts.pop().toLowerCase() : ''
})

const isEmbeddedFilePreview = computed(() =>
  ['pdf'].includes(fileExtension.value) ||
  ['jpg', 'jpeg', 'png', 'gif', 'webp'].includes(fileExtension.value)
)

const isStructuredPreview = computed(() =>
  ['docx', 'pptx', 'txt', 'md'].includes(fileExtension.value)
)

const currentPreviewLabel = computed(() => {
  if (activeMaterial.value?.materialType === 'VIDEO') return '视频学习'
  if (previewMode.value === 'slides') return 'PPT 在线预览'
  if (previewMode.value === 'docx') return '文档在线预览'
  if (isEmbeddedFilePreview.value) return '资料在线预览'
  if (previewMode.value === 'text') return '文本在线预览'
  return '资料学习'
})

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

function resolveFileUrl(fileUrl) {
  if (!fileUrl) return ''
  if (fileUrl.startsWith('http')) return fileUrl
  return `${window.location.origin}${fileUrl}`
}

function openSourceInNewTab() {
  const url = embeddedPreviewUrl.value
  if (!url) return
  window.open(url, '_blank', 'noopener,noreferrer')
}

function normalizePreviewResult(result) {
  if (Array.isArray(result)) {
    return {
      mode: 'legacy',
      pages: [],
      html: '',
      blocks: result.filter(item => typeof item === 'string' && item.trim())
    }
  }

  const mode = typeof result?.mode === 'string' ? result.mode : 'none'
  const pages = Array.isArray(result?.pages) ? result.pages : []
  const html = typeof result?.html === 'string' ? result.html : ''

  if (mode === 'slides' && pages.length) {
    return { mode, pages, html: '', blocks: [] }
  }

  if ((mode === 'docx' || mode === 'text') && html) {
    return { mode, pages: [], html, blocks: [] }
  }

  if (pages.length && pages.every(item => typeof item === 'string' && item.startsWith('data:image'))) {
    return { mode: 'slides', pages, html: '', blocks: [] }
  }

  if (pages.length && pages.every(item => typeof item === 'string')) {
    return { mode: 'legacy', pages: [], html: '', blocks: pages }
  }

  return { mode: 'none', pages: [], html: '', blocks: [] }
}

async function renderDocxPreview() {
  if (previewMode.value !== 'docx' || fileExtension.value !== 'docx' || !activeMaterial.value?.fileUrl || !docxPreviewRef.value) {
    return
  }

  try {
    const response = await fetch(resolveFileUrl(activeMaterial.value.fileUrl))
    const blob = await response.blob()
    const container = docxPreviewRef.value
    container.innerHTML = ''
    await renderAsync(blob, container, undefined, {
      className: 'docx-rendered',
      inWrapper: false,
      ignoreWidth: false,
      ignoreHeight: false,
      breakPages: true,
      ignoreFonts: false
    })
  } catch (error) {
    if (previewHtml.value) {
      docxPreviewRef.value.innerHTML = previewHtml.value
    } else {
      previewMode.value = 'none'
    }
  }
}

async function loadMaterialPreview() {
  previewPages.value = []
  previewHtml.value = ''
  previewMode.value = 'none'
  legacyPreviewBlocks.value = []
  if (docxPreviewRef.value) {
    docxPreviewRef.value.innerHTML = ''
  }
  if (!activeMaterial.value || !isStructuredPreview.value) return

  previewLoading.value = true
  try {
    const result = await previewMaterial(activeMaterial.value.id)
    const normalized = normalizePreviewResult(result)
    previewMode.value = normalized.mode
    previewPages.value = normalized.pages
    previewHtml.value = normalized.html
    legacyPreviewBlocks.value = normalized.blocks
    await nextTick()
    await renderDocxPreview()
  } catch (error) {
    previewPages.value = []
    previewHtml.value = ''
    previewMode.value = 'none'
    legacyPreviewBlocks.value = []
  } finally {
    previewLoading.value = false
  }
}

async function loadLearningDetail(preferredMaterialId = null) {
  const data = await getLearningCourseDetail(route.params.id)
  course.value = data

  let targetChapter = null
  let targetMaterial = null

  for (const chapter of data.chapters || []) {
    const found = (chapter.materials || []).find(item => item.id === preferredMaterialId)
    if (found) {
      targetChapter = chapter
      targetMaterial = found
      break
    }
  }

  if (!targetMaterial) {
    for (const chapter of data.chapters || []) {
      const firstIncomplete = (chapter.materials || []).find(item => !item.completed)
      if (firstIncomplete) {
        targetChapter = chapter
        targetMaterial = firstIncomplete
        break
      }
    }
  }

  if (!targetMaterial) {
    targetChapter = data.chapters?.[0] || null
    targetMaterial = targetChapter?.materials?.[0] || null
  }

  activeChapter.value = targetChapter
  activeMaterial.value = targetMaterial

  if (targetMaterial) {
    await recordMaterialAccess(targetMaterial.id)
    await loadMaterialPreview()
  }
}

async function selectMaterial(chapter, material) {
  activeChapter.value = chapter
  activeMaterial.value = material
  await recordMaterialAccess(material.id)
  await loadMaterialPreview()
}

async function handleCompleteMaterial() {
  if (!activeMaterial.value) return
  await completeMaterial(activeMaterial.value.id)
  ElMessage.success('学习进度已记录')
  await loadLearningDetail(activeMaterial.value.id)
}

onMounted(() => {
  loadLearningDetail()
})
</script>

<style scoped>
.learn-page {
  min-height: calc(100vh - 120px);
}

.sidebar-card,
.content-card {
  border-radius: 20px;
  height: 100%;
}

.course-title {
  color: #0f172a;
  font-size: 18px;
  font-weight: 600;
}

.course-progress {
  margin-top: 6px;
  color: #64748b;
  font-size: 13px;
}

.chapter-nav {
  margin-top: 18px;
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.chapter-nav-title {
  margin-bottom: 10px;
  color: #0f172a;
  font-weight: 600;
}

.material-nav-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 10px 12px;
  border-radius: 14px;
  cursor: pointer;
  transition: 0.2s ease;
}

.material-nav-item:hover,
.material-nav-item.active {
  background: #eff6ff;
}

.material-nav-main {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
  color: #334155;
}

.material-nav-text {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.content-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
}

.material-title {
  color: #0f172a;
  font-size: 22px;
  font-weight: 600;
}

.material-subtitle {
  margin-top: 8px;
  color: #64748b;
}

.content-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.material-content {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.viewer-shell,
.study-note {
  border-radius: 18px;
  background: #f8fafc;
}

.viewer-topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 16px 18px;
  border-bottom: 1px solid rgba(148, 163, 184, 0.18);
}

.viewer-meta {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}

.viewer-badge {
  flex-shrink: 0;
  padding: 6px 10px;
  border-radius: 999px;
  background: #dbeafe;
  color: #1d4ed8;
  font-size: 12px;
  font-weight: 600;
}

.viewer-file-name {
  color: #0f172a;
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.viewer-body {
  padding: 18px;
  min-height: 520px;
}

.video-player,
.doc-frame {
  width: 100%;
  min-height: 520px;
  border: 0;
  border-radius: 14px;
  background: #fff;
}

.slide-preview-list {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.slide-preview-item,
.docx-preview-wrap,
.legacy-preview-item,
.text-panel {
  background: #fff;
  border-radius: 16px;
  padding: 18px;
  box-shadow: inset 0 0 0 1px rgba(148, 163, 184, 0.16);
}

.slide-index {
  margin-bottom: 12px;
  color: #64748b;
  font-size: 13px;
}

.slide-preview-image {
  width: 100%;
  display: block;
  border-radius: 12px;
  background: #fff;
}

.docx-preview-body {
  min-height: 420px;
}

.docx-preview-body :deep(.docx-wrapper) {
  background: transparent;
  padding: 0;
}

.docx-preview-body :deep(.docx) {
  margin: 0 auto 24px;
  box-shadow: 0 12px 24px rgba(15, 23, 42, 0.08);
}

.docx-preview-body :deep(p) {
  margin: 0 0 14px;
  color: #334155;
  line-height: 1.9;
}

.docx-preview-body :deep(table) {
  width: 100%;
  border-collapse: collapse;
  margin: 18px 0;
}

.docx-preview-body :deep(td) {
  border: 1px solid #dbe2ea;
  padding: 10px 12px;
  color: #334155;
  vertical-align: top;
}

.text-preview-body {
  color: #334155;
  line-height: 1.9;
  word-break: break-word;
}

.legacy-preview-list {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.legacy-preview-text,
.text-content {
  margin: 0;
  white-space: pre-wrap;
  line-height: 1.9;
  color: #334155;
  font-family: inherit;
}

.preview-loading {
  background: #fff;
  border-radius: 14px;
  padding: 16px;
}

.study-note {
  padding: 18px;
}

.study-note h4 {
  margin: 0 0 10px;
  color: #0f172a;
}

.study-note p {
  margin: 0;
  color: #475569;
  line-height: 1.8;
  white-space: pre-wrap;
}

@media (max-width: 992px) {
  .content-header,
  .viewer-topbar {
    flex-direction: column;
    align-items: flex-start;
  }

  .viewer-body,
  .video-player,
  .doc-frame {
    min-height: 360px;
  }
}
</style>
