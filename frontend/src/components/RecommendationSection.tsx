import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { toast } from 'sonner'
import { api } from '@/api/client'
import type { Recommendation } from '@/api/types'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { CloudDownload, Sparkles, Check, X, RefreshCw } from 'lucide-react'

/**
 * Dashboard 今日推荐区（W3-3）：每日 07:45 管线产出一炉 Top 5。
 * 展示模型：只显示当前待处理的一炉——已「感兴趣」（进看板）或「忽略」的条目
 * 立即从列表消失（后端 today 接口也只回 PENDING），下一炉整体替换上一炉。
 *
 * 手动爬取入口也在这里：本地部署的后端不是 7×24，07:30 的定时爬虫错过窗口
 * 不会补跑，所以「每天上线点一下立即爬取」才是可靠的 baseline（定时器是 bonus）。
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
  const [crawling, setCrawling] = useState(false)
  const [lastCrawledAt, setLastCrawledAt] = useState<string | null>(null)

  const loadCrawlState = useCallback(() => {
    // 最近爬取时间 = 启用源里 last_crawled_at 的最大值；接口失败就静默不显示
    api.listCrawlSources()
      .then((sources) => {
        const latest = sources.filter((s) => s.enabled && s.last_crawled_at)
          .map((s) => s.last_crawled_at as string)
          .sort()
          .at(-1) ?? null
        setLastCrawledAt(latest)
      })
      .catch(() => setLastCrawledAt(null))
  }, [])

  useEffect(loadCrawlState, [loadCrawlState])

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

  const crawlNow = async () => {
    setCrawling(true)
    try {
      const summary = await api.runCrawl()
      const created = summary.results.reduce((n, r) => n + r.created, 0)
      const fetched = summary.results.reduce((n, r) => n + r.fetched, 0)
      toast.success(
        created > 0
          ? `爬取完成：抓到 ${fetched} 条，新入库 ${created} 个岗位，可以点「立即推荐」了`
          : `爬取完成：抓到 ${fetched} 条，没有新岗位（都是已收录的）`,
      )
      loadCrawlState()
    } catch (e) {
      toast.error(e instanceof Error ? e.message : '爬取失败，请稍后重试')
    } finally {
      setCrawling(false)
    }
  }

  const runNow = async () => {
    setRunning(true)
    try {
      const report = await api.runRecommendations()
      toast.success(
        `推荐完成：从 ${report.candidates} 个候选岗位中为你选出 ${report.recommended} 个` +
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

  /** 上次爬取时间的轻量展示：当天只显示时分，跨天带日期 */
  const fmtLastCrawled = (iso: string) => {
    const d = new Date(iso)
    const sameDay = d.toDateString() === new Date().toDateString()
    return d.toLocaleString('zh-CN', sameDay
      ? { hour: '2-digit', minute: '2-digit' }
      : { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' })
  }
  const crawledToday = lastCrawledAt != null
    && new Date(lastCrawledAt).toDateString() === new Date().toDateString()

  return (
    <Card>
      <CardHeader>
        <div className="flex items-center justify-between gap-2">
          <CardTitle className="text-base flex items-center gap-1.5">
            <Sparkles className="size-4 text-violet-500" />今日推荐
          </CardTitle>
          <div className="flex items-center gap-2">
            <span
              className={crawledToday ? 'text-xs text-muted-foreground' : 'text-xs text-amber-600'}
              title="爬虫定时任务（每天 07:30）只有后端在线才会跑，手动点更稳"
            >
              {lastCrawledAt ? `上次爬取 ${fmtLastCrawled(lastCrawledAt)}` : '今天还没爬过'}
            </span>
            <Button variant="ghost" size="sm" onClick={crawlNow} disabled={crawling}>
              <CloudDownload className={crawling ? 'size-3.5 animate-pulse' : 'size-3.5'} />
              {crawling ? '爬取中…' : '立即爬取'}
            </Button>
            <Button variant="ghost" size="sm" onClick={runNow} disabled={running}>
              <RefreshCw className={running ? 'size-3.5 animate-spin' : 'size-3.5'} />
              {running ? '推荐中…' : '立即推荐'}
            </Button>
          </div>
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
            暂时没有新推荐。先点「立即爬取」抓一批新岗位，再点「立即推荐」生成；
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
