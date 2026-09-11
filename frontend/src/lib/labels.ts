import type { Stage } from '@/api/types'

/** 阶段中文名与展示色（看板列/徽章/时间线圆点共用，集中维护） */
export const STAGE_META: Record<Stage, { label: string; className: string; dot: string }> = {
  collected: { label: '已收藏', className: 'bg-slate-100 text-slate-700 border-slate-200', dot: 'bg-slate-400' },
  planned: { label: '计划中', className: 'bg-blue-50 text-blue-700 border-blue-200', dot: 'bg-blue-500' },
  applied: { label: '已投递', className: 'bg-indigo-50 text-indigo-700 border-indigo-200', dot: 'bg-indigo-500' },
  written_test: { label: '笔试', className: 'bg-amber-50 text-amber-700 border-amber-200', dot: 'bg-amber-500' },
  interview: { label: '面试', className: 'bg-violet-50 text-violet-700 border-violet-200', dot: 'bg-violet-500' },
  offer: { label: 'Offer', className: 'bg-emerald-50 text-emerald-700 border-emerald-200', dot: 'bg-emerald-500' },
  rejected: { label: '未通过', className: 'bg-red-50 text-red-600 border-red-200', dot: 'bg-red-500' },
  withdrawn: { label: '已放弃', className: 'bg-zinc-100 text-zinc-500 border-zinc-200', dot: 'bg-zinc-400' },
}

export const STAGES = Object.keys(STAGE_META) as Stage[]

/**
 * 主线阶段的推进顺序（回退检测用）：collected → planned → applied → written_test → interview → offer。
 * rejected/withdrawn 是终态归档动作，从任何阶段流转过去都不算「回退」，故不参与排序。
 */
export const STAGE_ORDER: Partial<Record<Stage, number>> = {
  collected: 0,
  planned: 1,
  applied: 2,
  written_test: 3,
  interview: 4,
  offer: 5,
}

/** 是否为主线上的回退流转（如 面试→笔试）。回退往往意味着流程已结束，前端会弹确认并建议归档 */
export function isRollback(from: Stage, to: Stage): boolean {
  const f = STAGE_ORDER[from]
  const t = STAGE_ORDER[to]
  return f !== undefined && t !== undefined && t < f
}

/**
 * 公司类型：中文名 + 展示色，集中维护（单一事实源）。
 * 面试可讲点：展示层字典与后端枚举解耦——后端只存英文枚举值，
 * 颜色/文案变更不动契约、不迁数据；新增类型只需在此加一行。
 */
export const COMPANY_TYPE_META: Record<string, { label: string; className: string; dot: string }> = {
  internet: { label: '互联网', className: 'bg-sky-50 text-sky-700 border-sky-200', dot: 'bg-sky-500' },
  soe_central: { label: '央企', className: 'bg-rose-50 text-rose-700 border-rose-200', dot: 'bg-rose-500' },
  soe_local: { label: '地方国企', className: 'bg-orange-50 text-orange-700 border-orange-200', dot: 'bg-orange-500' },
  institute: { label: '军工/研究所', className: 'bg-indigo-50 text-indigo-700 border-indigo-200', dot: 'bg-indigo-500' },
  operator: { label: '运营商', className: 'bg-blue-50 text-blue-700 border-blue-200', dot: 'bg-blue-500' },
  bank: { label: '银行', className: 'bg-emerald-50 text-emerald-700 border-emerald-200', dot: 'bg-emerald-500' },
  foreign: { label: '外企', className: 'bg-violet-50 text-violet-700 border-violet-200', dot: 'bg-violet-500' },
  other: { label: '其他', className: 'bg-zinc-100 text-zinc-600 border-zinc-200', dot: 'bg-zinc-400' },
}

/** 供下拉框使用的纯文案映射（由 META 派生，避免两处维护漂移） */
export const COMPANY_TYPE_LABELS: Record<string, string> = Object.fromEntries(
  Object.entries(COMPANY_TYPE_META).map(([k, v]) => [k, v.label]),
)

/**
 * 公司分级（投递规划三层）：dream=冲刺 / target=主攻 / backup=保底 / none=未分级。
 * tier 挂在公司实体上（同公司多岗位共享分级），后端 CompanyTier 枚举的小写形式。
 */
export const TIER_META: Record<string, { label: string; className: string }> = {
  dream: { label: '冲刺', className: 'bg-fuchsia-50 text-fuchsia-700 border-fuchsia-200' },
  target: { label: '主攻', className: 'bg-blue-50 text-blue-700 border-blue-200' },
  backup: { label: '保底', className: 'bg-zinc-100 text-zinc-600 border-zinc-200' },
}

/** 分级下拉框选项（含"未分级"清除项的完整列表在组件内拼，此处只放三级） */
export const TIER_LABELS: Record<string, string> = Object.fromEntries(
  Object.entries(TIER_META).map(([k, v]) => [k, v.label]),
)

/** 投递渠道中文名（后端存英文枚举值，UI 显示中文） */
export const CHANNEL_LABELS: Record<string, string> = {
  official: '官网投递',
  boss: 'BOSS直聘',
  niuke: '牛客',
  email: '邮件',
  referral: '内推',
  campus_talk: '宣讲会',
}

export const CALENDAR_TYPE_META: Record<string, { label: string; className: string }> = {
  deadline: { label: '投递截止', className: 'bg-red-100 text-red-700' },
  written_test: { label: '笔试', className: 'bg-amber-100 text-amber-700' },
  interview: { label: '面试', className: 'bg-violet-100 text-violet-700' },
  planned: { label: '计划投递', className: 'bg-blue-100 text-blue-700' },
  next_action: { label: '待办', className: 'bg-zinc-100 text-zinc-700' },
}

export function fmtDate(s?: string | null): string {
  return s ? s.slice(0, 10) : '—'
}

/** 距今天数（按本地零点计）：负数 = 已过期 */
export function daysUntil(dateStr: string): number {
  const today = new Date()
  today.setHours(0, 0, 0, 0)
  const d = new Date(dateStr.slice(0, 10) + 'T00:00:00')
  return Math.round((d.getTime() - today.getTime()) / 86400000)
}

/**
 * DDL 紧急度配色：已过期删除线灰 / ≤3 天红 / ≤7 天橙 / 其余默认色。
 * 表格、详情页、倒计时共用同一套阈值，保证全站语义一致。
 */
export function deadlineClass(dateStr?: string | null): string {
  if (!dateStr) return 'text-muted-foreground'
  const n = daysUntil(dateStr)
  if (n < 0) return 'text-zinc-400 line-through'
  if (n <= 3) return 'text-red-600 font-medium'
  if (n <= 7) return 'text-amber-600 font-medium'
  return 'text-foreground'
}

/** DDL 倒计时短文案：「已截止 / 今天截止 / 剩 N 天」 */
export function deadlineCountdown(dateStr: string): string {
  const n = daysUntil(dateStr)
  if (n < 0) return '已截止'
  if (n === 0) return '今天截止'
  return `剩 ${n} 天`
}

export function fmtDateTime(s?: string | null): string {
  if (!s) return '—'
  const d = new Date(s)
  return `${d.getMonth() + 1}/${d.getDate()} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}
