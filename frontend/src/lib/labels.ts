import type { Stage } from '@/api/types'

/** 阶段中文名与展示色（看板列/徽章/时间线共用，集中维护） */
export const STAGE_META: Record<Stage, { label: string; className: string }> = {
  collected: { label: '已收藏', className: 'bg-slate-100 text-slate-700 border-slate-200' },
  planned: { label: '计划中', className: 'bg-blue-50 text-blue-700 border-blue-200' },
  applied: { label: '已投递', className: 'bg-indigo-50 text-indigo-700 border-indigo-200' },
  written_test: { label: '笔试', className: 'bg-amber-50 text-amber-700 border-amber-200' },
  interview: { label: '面试', className: 'bg-violet-50 text-violet-700 border-violet-200' },
  offer: { label: 'Offer', className: 'bg-emerald-50 text-emerald-700 border-emerald-200' },
  rejected: { label: '未通过', className: 'bg-red-50 text-red-600 border-red-200' },
  withdrawn: { label: '已放弃', className: 'bg-zinc-100 text-zinc-500 border-zinc-200' },
}

export const STAGES = Object.keys(STAGE_META) as Stage[]

export const COMPANY_TYPE_LABELS: Record<string, string> = {
  internet: '互联网',
  soe_central: '央企',
  soe_local: '地方国企',
  institute: '军工/研究所',
  operator: '运营商',
  bank: '银行',
  foreign: '外企',
  other: '其他',
}

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

export function fmtDateTime(s?: string | null): string {
  if (!s) return '—'
  const d = new Date(s)
  return `${d.getMonth() + 1}/${d.getDate()} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}
