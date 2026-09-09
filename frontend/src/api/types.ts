/**
 * 与后端契约对应的类型（docs/api-design.md，字段为 snake_case——后端 Jackson 全局策略）。
 * 手写对齐而非 codegen：W1 契约小且稳定；若契约膨胀可引入 openapi-typescript 生成。
 */

export type CompanyType =
  | 'internet' | 'soe_central' | 'soe_local' | 'institute' | 'operator' | 'bank' | 'foreign' | 'other'
export type CompanyTier = 'dream' | 'target' | 'backup' | 'none'
export type Stage =
  | 'collected' | 'planned' | 'applied' | 'written_test' | 'interview' | 'offer' | 'rejected' | 'withdrawn'

export interface CompanyBrief {
  id: number
  name: string
  company_type: CompanyType
  tier: CompanyTier
}

export interface JobSummary {
  id: number
  company: CompanyBrief
  title: string
  city?: string
  salary_range?: string
  source_platform: string
  source_url?: string
  deadline?: string
  jd_summary?: string
  match_score?: number
  application_stage?: Stage
  created_at: string
}

export interface ApplicationCard {
  id: number
  job_id: number
  company_name: string
  title: string
  city?: string
  stage: Stage
  priority: number
  planned_at?: string
  next_action?: string
  next_action_at?: string
  updated_at: string
}

export interface JobDetail extends Omit<JobSummary, 'application_stage' | 'match_score'> {
  jd_text: string
  requirements?: string
  publish_date?: string
  active: boolean
  application?: ApplicationCard
  latest_match_report?: MatchReport
}

// ---------- 匹配报告（schema 见 docs/agent-design.md §3.3） ----------

export interface HardCheck {
  item: string
  resume_value: string
  passed: boolean
  note: string
}

export interface MatchDetail {
  hard_checks: HardCheck[]
  hard_pass: boolean
  score_total: number
  /** 键固定为 skill / experience / fit（prompt 约束） */
  score_breakdown: Record<string, number>
  matched_skills: string[]
  missing_skills: string[]
  highlights: string[]
  suggestion: string
  one_liner: string
}

export interface MatchReport {
  id: number
  job_id: number
  resume_id: number
  score_total: number
  detail: MatchDetail
  model_used: string
  prompt_version: string
  /** true = 24h 缓存命中（未新调 LLM） */
  cached?: boolean
  created_at: string
}

export interface PageResponse<T> {
  items: T[]
  total: number
  page: number
  size: number
}

export interface EventItem {
  id: number
  from_stage?: Stage
  to_stage: Stage
  note?: string
  created_at: string
}

export interface BoardResponse {
  groups: Record<Stage, ApplicationCard[]>
}

export interface DashboardSummary {
  funnel: Record<string, number>
  this_week: {
    applied: number
    goal: number
    new_jobs: number
    recommendations_unread: number
  }
  upcoming_deadlines: {
    job_id: number
    company: string
    title: string
    deadline: string
    days_left: number
  }[]
  next_actions: {
    application_id: number
    company: string
    next_action: string
    next_action_at: string | null // null = 系统自动待办，显示"尽快"
  }[]
}

/** W4-3 图表统计：近 N 天逐日数据 + 公司类型分布 */
export interface DashboardStats {
  daily: { date: string; applied: number; new_jobs: number }[]
  company_types: { type: string; count: number }[]
}

/** W4-2 周报：narrative_markdown 为 LLM 叙事（llm_generated=false 时为 null = 纯数据版） */
export interface WeeklyReport {
  week_start: string
  week_end: string
  stats: {
    applied: number
    goal: number
    prev_week_applied: number
    new_jobs: number
    rec_generated: number
    rec_accepted: number
    rec_ignored: number
    stage_inflow: Record<string, number>
  }
  events: { at: string; company: string; title: string; to_stage: string; note: string | null }[]
  narrative_markdown: string | null
  llm_generated: boolean
}

export interface CalendarEvent {
  date: string
  type: 'deadline' | 'written_test' | 'interview' | 'planned' | 'next_action'
  title: string
  job_id?: number
  application_id?: number
}

// ---------- 每日推荐（W3-3） ----------

export type RecommendationStatus = 'pending' | 'accepted' | 'ignored'

export interface Recommendation {
  id: number
  job_id: number
  company: string
  title: string
  city: string | null
  salary_range: string | null
  source_platform: string
  source_url: string | null
  deadline: string | null
  rank: number
  /** 推荐理由：精评时是 LLM 一句话结论；降级时是粗筛命中说明 */
  reason: string
  /** 精评总分（粗筛降级时为规则分） */
  score: number
  /** true = 经过 LLM 精评；false = 规则粗筛降级产出 */
  llm_scored: boolean
  status: RecommendationStatus
  feedback_tag: string | null
}

export interface RecommendRunReport {
  candidates: number
  coarse_passed: number
  llm_scored: number
  recommended: number
  notes: string[]
}

// ---------- 简历（ResumeProfile schema 见 docs/agent-design.md §3.1） ----------

export interface ResumeEducation {
  school: string
  degree: string
  major: string
  period: string
  is985?: boolean
  is211?: boolean
}

export interface ResumeExperience {
  type: 'internship' | 'project' | 'competition' | 'research' | string
  org: string
  role: string
  period: string
  highlights: string[]
}

export interface ParsedResume {
  name?: string
  education: ResumeEducation[]
  skills: string[]
  experiences: ResumeExperience[]
  target_positions: string[]
  target_cities: string[]
  awards: string[]
  summary?: string
}

export interface ResumeSummary {
  id: number
  name: string
  is_default: boolean
  /** parsed=已完成结构化；pending=待解析（扫描件无文字层或 LLM 失败，可 reparse） */
  parse_status: 'parsed' | 'pending'
  summary?: string
  created_at: string
}

export interface ResumeDetail extends Omit<ResumeSummary, 'summary'> {
  parsed?: ParsedResume
}
