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
  latest_match_report?: unknown
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
    next_action_at: string
  }[]
}

export interface CalendarEvent {
  date: string
  type: 'deadline' | 'written_test' | 'interview' | 'planned' | 'next_action'
  title: string
  job_id?: number
  application_id?: number
}
