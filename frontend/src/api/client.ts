/**
 * API client：fetch 薄封装。
 * - 开发环境经 vite proxy 同源访问 /api（Origin=localhost:5173，后端受信源放行，无需 token）
 * - 错误体约定 {"detail": "..."}，解析后抛出带中文信息的 Error
 */
import type {
  ApplicationCard,
  BoardResponse,
  CalendarEvent,
  DashboardSummary,
  EventItem,
  JobDetail,
  JobSummary,
  MatchReport,
  PageResponse,
  Recommendation,
  RecommendRunReport,
  ResumeDetail,
  ResumeSummary,
  Stage,
} from './types'

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(path, {
    headers: init?.body ? { 'Content-Type': 'application/json' } : undefined,
    ...init,
  })
  if (!res.ok) {
    let detail = `${res.status} ${res.statusText}`
    try {
      const body = await res.json()
      if (body?.detail) detail = body.detail
    } catch {
      /* 非 JSON 错误体，用状态行 */
    }
    throw new Error(detail)
  }
  return res.json() as Promise<T>
}

export interface JobQuery {
  q?: string
  company_type?: string
  city?: string
  stage?: string
  tier?: string
  deadline_before?: string
  sort?: string
  page?: number
  size?: number
}

function qs(params: Record<string, string | number | undefined>): string {
  const sp = new URLSearchParams()
  for (const [k, v] of Object.entries(params)) {
    if (v !== undefined && v !== '') sp.set(k, String(v))
  }
  const s = sp.toString()
  return s ? `?${s}` : ''
}

export const api = {
  // Jobs
  listJobs: (q: JobQuery) => request<PageResponse<JobSummary>>(`/api/jobs${qs({ ...q })}`),
  getJob: (id: number) => request<JobDetail>(`/api/jobs/${id}`),
  createJob: (body: Record<string, unknown>) =>
    request<JobDetail>('/api/jobs', { method: 'POST', body: JSON.stringify(body) }),
  // PATCH 语义：不传/传 null 的字段后端保持原值（dirty checking），故空字符串需先转 null
  updateJob: (id: number, body: Record<string, unknown>) =>
    request<JobDetail>(`/api/jobs/${id}`, { method: 'PATCH', body: JSON.stringify(body) }),
  ingestJob: (body: Record<string, unknown>) =>
    request<{ job_id: number; already_exists: boolean; warnings: string[] }>(
      '/api/jobs/ingest', { method: 'POST', body: JSON.stringify(body) }),
  // 批量删除：无关联数据的物理删除，有投递/匹配/推荐记录的归档保留
  batchDeleteJobs: (ids: number[]) =>
    request<{ deleted: number; archived: number; missing: number }>(
      '/api/jobs/batch-delete', { method: 'POST', body: JSON.stringify({ ids }) }),

  // Applications
  board: () => request<BoardResponse>('/api/applications'),
  createApplication: (body: { job_id: number; stage?: Stage; priority?: number; notes?: string }) =>
    request<ApplicationCard>('/api/applications', { method: 'POST', body: JSON.stringify(body) }),
  transition: (id: number, body: { to_stage: Stage; note?: string; channel?: string }) =>
    request<{ application: unknown; events: EventItem[] }>(
      `/api/applications/${id}/stage`, { method: 'POST', body: JSON.stringify(body) }),
  events: (id: number) => request<EventItem[]>(`/api/applications/${id}/events`),

  // Dashboard
  summary: () => request<DashboardSummary>('/api/dashboard/summary'),
  calendar: (month: string) =>
    request<{ events: CalendarEvent[] }>(`/api/dashboard/calendar?month=${month}`),

  // Recommendations（每日推荐：今日列表 / 反馈闭环 / 手动触发）
  todayRecommendations: () => request<Recommendation[]>('/api/recommendations/today'),
  feedbackRecommendation: (id: number, body: { action: 'accept' | 'ignore'; tag?: string }) =>
    request<Recommendation>(`/api/recommendations/${id}/feedback`, {
      method: 'POST',
      body: JSON.stringify(body),
    }),
  runRecommendations: () =>
    request<RecommendRunReport>('/api/recommendations/run', { method: 'POST' }),

  // Match（同步接口，一次 LLM 调用 3-10s，调用方需 loading 态）
  matchJob: (jobId: number, resumeId?: number) =>
    request<MatchReport>(`/api/match/jobs/${jobId}`, {
      method: 'POST',
      body: JSON.stringify(resumeId ? { resume_id: resumeId } : {}),
    }),

  // Resumes
  listResumes: () => request<{ items: ResumeSummary[] }>('/api/resumes'),  getResume: (id: number) => request<ResumeDetail>(`/api/resumes/${id}`),
  // multipart 上传不能走 request()——它会强设 Content-Type: application/json，
  // 而 FormData 必须由浏览器自动生成带 boundary 的 multipart 头
  uploadResume: async (file: File): Promise<ResumeDetail> => {
    const fd = new FormData()
    fd.append('file', file)
    const res = await fetch('/api/resumes', { method: 'POST', body: fd })
    if (!res.ok) {
      let detail = `${res.status} ${res.statusText}`
      try {
        const body = await res.json()
        if (body?.detail) detail = body.detail
      } catch { /* 非 JSON 错误体 */ }
      throw new Error(detail)
    }
    return res.json() as Promise<ResumeDetail>
  },
  reparseResume: (id: number) =>
    request<ResumeDetail>(`/api/resumes/${id}/reparse`, { method: 'POST' }),
  setDefaultResume: (id: number) =>
    request<ResumeDetail>(`/api/resumes/${id}/default`, { method: 'PATCH' }),
}
