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
 * Dashboard 今日推荐区（W3-3）：每日 07:45 管线产出一炉 Top 5。
 * 展示模型：只显示当前待处理的一炉——已「感兴趣」（进看板）或「忽略」的条目
 * 立即从列表消失（后端 today 接口也只回 PENDING），下一炉整体替换上一炉。
 */
export default function RecommendationSection({
  items,
  onItemHandled,
  onRefresh,
}: {
  items: Recommendation[]
  /** 反馈成功后父组件把该条移出列表 */
  onItemHandled: (id: number) => void
  /** 「立即推荐」跑完后整表刷新 */
  onRefresh: () => void
}) {
  const [busyId, setBusyId] = useState<number | null>(null)
  const [running, setRunning] = useState(false)

  const feedback = async (rec: Recommendation, action: 'accept' | 'ignore') => {
    setBusyId(rec.id)
    try {
      await api.feedbackRecommendation(rec.id, { action })
      onItemHandled(rec.id)
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
        `推荐完成：从 ${report.candidates} 个新岗位中为你选出 ${report.recommended} 个` +
          (report.llm_scored === 0 ? '（AI 暂不可用，已按方向规则推荐）' : ''),
      )
      // 一炉换一炉：整表刷新
      onRefresh()
    } catch (e) {
      toast.error(e instanceof Error ? e.message : '推荐失败，请稍后重试')
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
        {running && (
          <div className="rounded-md border border-dashed px-3 py-6 text-center text-sm text-muted-foreground">
            正在为你挑选岗位：先按方向初筛，再由 AI 逐个评估，约需 1~2 分钟，完成后这里会自动更新…
          </div>
        )}
        {!running && items.length === 0 && (
          <p className="text-sm text-muted-foreground">
            暂时没有新推荐。点击右上角「立即推荐」马上生成（系统也会每天早上 7:45 自动推荐）；
            之前点过「感兴趣」的岗位已经加入看板。
          </p>
        )}
        {!running &&
          items.map((r) => (
            <div key={r.id} className="flex items-center gap-3 rounded-md border px-3 py-2 text-sm">
              <span className="w-5 text-center font-mono text-xs text-muted-foreground">{r.rank}</span>
              <div className="min-w-0 flex-1">
                <div className="flex items-center gap-2">
                  <Link to={`/jobs/${r.job_id}`} className="truncate font-medium hover:underline">
                    {r.company} · {r.title}
                  </Link>
                  {r.llm_scored ? (
                    <Badge variant={r.score >= 70 ? 'default' : 'secondary'}>{r.score} 分</Badge>
                  ) : (
                    <Badge variant="outline">方向匹配</Badge>
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
              </div>
            </div>
          ))}
      </CardContent>
    </Card>
  )
}
