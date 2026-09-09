import { useState } from 'react'
import { Link } from 'react-router-dom'
import { toast } from 'sonner'
import { api } from '@/api/client'
import type { Recommendation } from '@/api/types'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Sparkles, Check, X, RefreshCw } from 'lucide-react'

/**
 * Dashboard 今日推荐区（W3-3）：每日 07:45 管线产出 Top 5。
 * 反馈闭环：感兴趣 → accept（自动建看板卡片）；不感兴趣 → ignore（可带原因标签，
 * 标签回流用于评估推荐质量）。已反馈的卡片降透明度展示，不消失（当天仍可回溯）。
 */
export default function RecommendationSection({
  items,
  onItemChanged,
  onRefresh,
}: {
  items: Recommendation[]
  /** 单条反馈成功后回传更新后的条目，父组件局部替换 */
  onItemChanged: (updated: Recommendation) => void
  /** 「立即推荐」跑完后整表刷新 */
  onRefresh: () => void
}) {
  const [busyId, setBusyId] = useState<number | null>(null)
  const [running, setRunning] = useState(false)

  const feedback = async (rec: Recommendation, action: 'accept' | 'ignore') => {
    setBusyId(rec.id)
    try {
      const updated = await api.feedbackRecommendation(rec.id, { action })
      onItemChanged(updated)
      toast.success(action === 'accept' ? '已加入看板（收藏）' : '已忽略')
    } catch (e) {
      toast.error(e instanceof Error ? e.message : '操作失败')
    } finally {
      setBusyId(null)
    }
  }

  const runNow = async () => {
    setRunning(true)
    try {
      const report = await api.runRecommendations()
      toast.success(
        `推荐完成：候选 ${report.candidates} → 推荐 ${report.recommended}` +
          (report.llm_scored === 0 ? '（LLM 不可用，规则粗排）' : ''),
      )
      // 一轮管线可能换掉整个列表，整表刷新
      onRefresh()
    } catch (e) {
      toast.error(e instanceof Error ? e.message : '推荐管线执行失败')
    } finally {
      setRunning(false)
    }
  }

  return (
    <Card>
      <CardHeader>
        <div className="flex items-center justify-between">
          <CardTitle className="text-base flex items-center gap-1.5">
            <Sparkles className="size-4 text-violet-500" />今日推荐
          </CardTitle>
          <Button variant="ghost" size="sm" onClick={runNow} disabled={running}>
            <RefreshCw className={running ? 'size-3.5 animate-spin' : 'size-3.5'} />
            {running ? '推荐中…' : '立即推荐'}
          </Button>
        </div>
      </CardHeader>
      <CardContent className="space-y-2">
        {items.length === 0 && (
          <p className="text-sm text-muted-foreground">
            今天还没有推荐。爬虫每日 07:30 抓取、07:45 生成推荐；也可点右上角「立即推荐」。
          </p>
        )}
        {items.map((r) => {
          const done = r.status !== 'pending'
          return (
            <div
              key={r.id}
              className={
                'flex items-center gap-3 rounded-md border px-3 py-2 text-sm transition-opacity ' +
                (done ? 'opacity-50' : '')
              }
            >
              <span className="w-5 text-center font-mono text-xs text-muted-foreground">{r.rank}</span>
              <div className="min-w-0 flex-1">
                <div className="flex items-center gap-2">
                  <Link to={`/jobs/${r.job_id}`} className="truncate font-medium hover:underline">
                    {r.company} · {r.title}
                  </Link>
                  {r.llm_scored ? (
                    <Badge variant={r.score >= 70 ? 'default' : 'secondary'}>{r.score} 分</Badge>
                  ) : (
                    <Badge variant="outline">规则粗排</Badge>
                  )}
                  {r.deadline && (
                    <span className="shrink-0 text-xs text-muted-foreground">截止 {r.deadline}</span>
                  )}
                </div>
                <p className="mt-0.5 truncate text-xs text-muted-foreground" title={r.reason}>
                  {r.reason}
                </p>
              </div>
              <div className="flex shrink-0 items-center gap-1">
                {r.status === 'accepted' && <Badge variant="default">已入看板</Badge>}
                {r.status === 'ignored' && <Badge variant="secondary">已忽略</Badge>}
                {!done && (
                  <>
                    <Button
                      variant="outline"
                      size="sm"
                      disabled={busyId === r.id}
                      onClick={() => feedback(r, 'accept')}
                    >
                      <Check className="size-3.5" />感兴趣
                    </Button>
                    <Button
                      variant="ghost"
                      size="sm"
                      disabled={busyId === r.id}
                      onClick={() => feedback(r, 'ignore')}
                    >
                      <X className="size-3.5" />忽略
                    </Button>
                  </>
                )}
              </div>
            </div>
          )
        })}
      </CardContent>
    </Card>
  )
}
