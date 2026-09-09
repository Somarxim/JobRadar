import { useCallback, useEffect, useState } from 'react'
import { api } from '@/api/client'
import type { WeeklyReport } from '@/api/types'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { FileText, Loader2, Sparkles } from 'lucide-react'

/**
 * 本周复盘卡片（W4-2 周报 Agent 的前端出口）：
 * 默认加载纯数据版（零 LLM 成本）；「AI 复盘」按需触发叙事生成（3-10s）。
 * LLM 未启用/失败时后端降级 llm_generated=false，此处提示但数据照常展示。
 */
export default function WeeklyReportCard() {
  const [offset, setOffset] = useState(0)
  const [report, setReport] = useState<WeeklyReport | null>(null)
  const [loading, setLoading] = useState(true)
  const [narrating, setNarrating] = useState(false)
  const [hint, setHint] = useState<string | null>(null)

  const load = useCallback((weekOffset: number) => {
    api.weeklyReport(weekOffset, false)
      .then(setReport)
      .catch(() => setReport(null))
      .finally(() => setLoading(false))
  }, [])

  useEffect(() => load(offset), [offset, load])

  /** 切换周区间：在事件里置 loading/清提示（lint: 不在 effect 里同步 setState） */
  const switchWeek = (weekOffset: number) => {
    setLoading(true)
    setHint(null)
    setOffset(weekOffset)
  }

  const narrate = () => {
    setNarrating(true)
    setHint(null)
    api.weeklyReport(offset, true)
      .then((r) => {
        setReport(r)
        if (!r.llm_generated) setHint('AI 复盘暂不可用（未配置模型或今日额度已用完），以上为纯数据版')
      })
      .catch(() => setHint('AI 复盘生成失败，请稍后重试'))
      .finally(() => setNarrating(false))
  }

  const s = report?.stats
  return (
    <Card>
      <CardHeader className="flex-row items-center justify-between space-y-0">
        <CardTitle className="text-base flex items-center gap-1.5">
          <FileText className="size-4 text-violet-500" />每周复盘
        </CardTitle>
        <div className="flex items-center gap-2">
          {/* 本周/上周切换（历史周回看走 week_offset） */}
          <div className="flex rounded-md border text-xs">
            {['本周', '上周'].map((label, i) => (
              <button
                key={label}
                onClick={() => switchWeek(-i)}
                className={`px-2.5 py-1 ${offset === -i ? 'bg-accent font-medium' : 'text-muted-foreground'}`}
              >
                {label}
              </button>
            ))}
          </div>
          <Button size="sm" variant="outline" onClick={narrate} disabled={narrating || loading}>
            {narrating ? <Loader2 className="size-3.5 animate-spin" /> : <Sparkles className="size-3.5" />}
            {narrating ? '生成中…' : 'AI 复盘'}
          </Button>
        </div>
      </CardHeader>
      <CardContent className="space-y-3">
        {loading && <p className="text-sm text-muted-foreground">加载中…</p>}
        {!loading && !report && <p className="text-sm text-muted-foreground">周报加载失败</p>}
        {report && s && (
          <>
            <p className="text-xs text-muted-foreground">{report.week_start} ~ {report.week_end}</p>
            <div className="grid grid-cols-2 gap-x-6 gap-y-1.5 text-sm sm:grid-cols-4">
              <Stat label="投递" value={`${s.applied} / ${s.goal}`} sub={`上周 ${s.prev_week_applied}`} />
              <Stat label="新收录岗位" value={String(s.new_jobs)} />
              <Stat label="推荐采纳" value={`${s.rec_accepted} / ${s.rec_generated}`} sub={`忽略 ${s.rec_ignored}`} />
              <Stat
                label="阶段流转"
                value={String(Object.values(s.stage_inflow).reduce((a, b) => a + b, 0))}
                sub={Object.entries(s.stage_inflow).map(([k, v]) => `${k}×${v}`).join(' ') || undefined}
              />
            </div>
            {hint && <p className="text-xs text-amber-600">{hint}</p>}
            {report.narrative_markdown && (
              <div className="rounded-md bg-muted/50 p-3 text-sm">
                <Narrative md={report.narrative_markdown} />
              </div>
            )}
          </>
        )}
      </CardContent>
    </Card>
  )
}

function Stat({ label, value, sub }: { label: string; value: string; sub?: string }) {
  return (
    <div>
      <div className="text-xs text-muted-foreground">{label}</div>
      <div className="font-semibold">{value}</div>
      {sub && <div className="text-xs text-muted-foreground truncate" title={sub}>{sub}</div>}
    </div>
  )
}

/**
 * 极简 Markdown 渲染：周报叙事是后端 prompt 约束的固定格式（## 小节 / - 列表 / **加粗**），
 * 只渲染这三种结构即可，不引入 react-markdown 这类通用解析器（依赖体积换不到收益）。
 */
function Narrative({ md }: { md: string }) {
  return (
    <div className="space-y-1.5">
      {md.split('\n').map((line, i) => {
        const t = line.trim()
        if (!t) return null
        if (t.startsWith('## ')) {
          return <h3 key={i} className="font-semibold pt-1.5">{renderInline(t.slice(3))}</h3>
        }
        if (t.startsWith('- ')) {
          return <p key={i} className="pl-3 -indent-3">· {renderInline(t.slice(2))}</p>
        }
        return <p key={i}>{renderInline(t.replace(/^#+\s*/, ''))}</p>
      })}
    </div>
  )
}

/** 行内 **加粗** → <strong>（prompt 约定的唯一行内语法） */
function renderInline(text: string) {
  return text.split(/\*\*(.+?)\*\*/g).map((part, i) =>
    i % 2 === 1 ? <strong key={i}>{part}</strong> : part,
  )
}
