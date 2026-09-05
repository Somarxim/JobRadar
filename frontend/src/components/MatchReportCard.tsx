import { useState } from 'react'
import { toast } from 'sonner'
import { api } from '@/api/client'
import type { MatchReport } from '@/api/types'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { fmtDateTime } from '@/lib/labels'
import { cn } from '@/lib/utils'
import { CheckCircle2, RefreshCw, Sparkles, XCircle } from 'lucide-react'

/** 分数锚点配色（agent-design §3.3）：≥85 强匹配 / 70-84 推荐 / 55-69 可投 / <55 不推荐 */
function scoreClass(score: number): string {
  if (score >= 85) return 'text-emerald-600'
  if (score >= 70) return 'text-blue-600'
  if (score >= 55) return 'text-amber-600'
  return 'text-zinc-500'
}

function scoreLabel(score: number): string {
  if (score >= 85) return '强匹配'
  if (score >= 70) return '推荐'
  if (score >= 55) return '可投'
  return '不推荐'
}

const BREAKDOWN_META: { key: string; label: string; max: number }[] = [
  { key: 'skill', label: '技能重合', max: 40 },
  { key: 'experience', label: '经历契合', max: 40 },
  { key: 'fit', label: '综合契合', max: 20 },
]

/**
 * AI 匹配报告卡（岗位详情页右栏顶部）。
 * report 为 undefined 时显示空态 + 生成按钮；生成/重新评估走同步接口（3-10s loading）。
 */
export default function MatchReportCard({ jobId, report, onGenerated }: {
  jobId: number
  report?: MatchReport
  onGenerated: () => void
}) {
  const [loading, setLoading] = useState(false)

  async function generate() {
    setLoading(true)
    try {
      const r = await api.matchJob(jobId)
      toast.success(`匹配评估完成：${r.score_total} 分（${scoreLabel(r.score_total)}）`)
      onGenerated() // 让父组件重新拉取 job detail（latest_match_report 随之更新）
    } catch (e) {
      toast.error(e instanceof Error ? e.message : String(e))
    } finally {
      setLoading(false)
    }
  }

  const d = report?.detail

  return (
    <Card className={report ? undefined : 'border-dashed border-violet-200 bg-violet-50/40'}>
      <CardHeader>
        <div className="flex items-center justify-between gap-2">
          <CardTitle className="text-base flex items-center gap-1.5">
            <Sparkles className="size-4 text-violet-500" />AI 匹配报告
          </CardTitle>
          <Button variant="outline" size="sm" onClick={generate} disabled={loading}>
            <RefreshCw className={cn('size-3.5', loading && 'animate-spin')} />
            {loading ? '评估中…' : report ? '重新评估' : '生成报告'}
          </Button>
        </div>
      </CardHeader>
      <CardContent className="space-y-4">
        {!report || !d ? (
          <p className="text-sm text-muted-foreground">
            基于默认简历评估与该岗位的匹配度、硬性条件、技能缺口与投递建议（约 5-10 秒）。
          </p>
        ) : (
          <>
            {/* 总分 + 一句话结论 */}
            <div className="flex items-center gap-3">
              <span className={cn('text-3xl font-bold tabular-nums', scoreClass(report.score_total))}>
                {report.score_total}
              </span>
              <div className="space-y-1">
                <div className="flex gap-1.5">
                  <Badge variant="outline" className={scoreClass(report.score_total)}>
                    {scoreLabel(report.score_total)}
                  </Badge>
                  {d.hard_pass
                    ? <Badge variant="outline" className="text-emerald-600 border-emerald-300">硬性条件通过</Badge>
                    : <Badge variant="outline" className="text-red-600 border-red-300">硬性条件未过</Badge>}
                </div>
              </div>
            </div>
            <p className="text-sm font-medium">{d.one_liner}</p>

            {/* 维度分：细条进度 */}
            <div className="space-y-1.5">
              {BREAKDOWN_META.map(({ key, label, max }) => {
                const v = d.score_breakdown?.[key] ?? 0
                return (
                  <div key={key} className="flex items-center gap-2 text-xs">
                    <span className="w-16 text-muted-foreground">{label}</span>
                    <div className="flex-1 h-1.5 rounded-full bg-muted overflow-hidden">
                      <div
                        className="h-full rounded-full bg-violet-400"
                        style={{ width: `${Math.min(100, (v / max) * 100)}%` }}
                      />
                    </div>
                    <span className="w-10 text-right tabular-nums">{v}/{max}</span>
                  </div>
                )
              })}
            </div>

            {/* 硬性条件逐条核对 */}
            {d.hard_checks?.length > 0 && (
              <div className="space-y-1.5">
                <h4 className="text-xs font-semibold text-muted-foreground">硬性条件核对</h4>
                <ul className="space-y-1">
                  {d.hard_checks.map((h, i) => (
                    <li key={i} className="flex items-start gap-1.5 text-xs">
                      {h.passed
                        ? <CheckCircle2 className="size-3.5 mt-0.5 shrink-0 text-emerald-500" />
                        : <XCircle className="size-3.5 mt-0.5 shrink-0 text-red-500" />}
                      <span>
                        <b>{h.item}</b>
                        <span className="text-muted-foreground">：{h.note}</span>
                      </span>
                    </li>
                  ))}
                </ul>
              </div>
            )}

            {/* 技能匹配/缺口 */}
            {(d.matched_skills?.length > 0 || d.missing_skills?.length > 0) && (
              <div className="space-y-1.5">
                <h4 className="text-xs font-semibold text-muted-foreground">技能对照</h4>
                <div className="flex flex-wrap gap-1">
                  {d.matched_skills?.map((s) => (
                    <Badge key={s} className="bg-emerald-100 text-emerald-700 border-emerald-200">{s}</Badge>
                  ))}
                  {d.missing_skills?.map((s) => (
                    <Badge key={s} variant="outline" className="text-zinc-500 border-dashed">缺 {s}</Badge>
                  ))}
                </div>
              </div>
            )}

            {/* 亮点经历 + 投递建议 */}
            {d.highlights?.length > 0 && (
              <div className="space-y-1">
                <h4 className="text-xs font-semibold text-muted-foreground">契合亮点</h4>
                <ul className="list-disc pl-4 text-xs text-muted-foreground space-y-0.5">
                  {d.highlights.map((h, i) => <li key={i}>{h}</li>)}
                </ul>
              </div>
            )}
            {d.suggestion && (
              <p className="text-xs rounded-md bg-violet-50 border border-violet-200 p-2.5 text-violet-900 leading-relaxed">
                {d.suggestion}
              </p>
            )}

            <p className="text-[11px] text-muted-foreground">
              {report.model_used} · {report.prompt_version} · {fmtDateTime(report.created_at)}
            </p>
          </>
        )}
      </CardContent>
    </Card>
  )
}
