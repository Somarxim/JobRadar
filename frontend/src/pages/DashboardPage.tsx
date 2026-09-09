import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from '@/api/client'
import type { DashboardStats, DashboardSummary, Recommendation } from '@/api/types'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import RecommendationSection from '@/components/RecommendationSection'
import { FunnelChart, TrendChart, TypePie } from '@/components/StatsCharts'
import WeeklyReportCard from '@/components/WeeklyReportCard'
import { STAGE_META } from '@/lib/labels'
import { cn } from '@/lib/utils'
import { AlarmClock, ListTodo, TrendingUp } from 'lucide-react'

/** 仪表盘：漏斗数字卡 + 图表区 + 今日推荐 + 本周进展 + DDL 倒计时 + 待办 + 每周复盘 */
export default function DashboardPage() {
  const [data, setData] = useState<DashboardSummary | null>(null)
  const [recs, setRecs] = useState<Recommendation[] | null>(null)
  const [stats, setStats] = useState<DashboardStats | null>(null)
  const [error, setError] = useState<string | null>(null)

  const loadRecs = useCallback(() => {
    // 推荐区独立加载：即使推荐接口失败也不拖垮整个仪表盘
    api.todayRecommendations().then(setRecs).catch(() => setRecs([]))
  }, [])

  useEffect(() => {
    api.summary().then(setData).catch((e) => setError(e.message))
    loadRecs()
    // 图表区独立加载：stats 失败只隐藏图表，不影响主视图
    api.dashboardStats(30).then(setStats).catch(() => setStats(null))
  }, [loadRecs])

  if (error) return <p className="text-destructive">加载失败：{error}</p>
  if (!data) return <p className="text-muted-foreground">加载中…</p>

  const { funnel, this_week: week, upcoming_deadlines: deadlines, next_actions: actions } = data

  return (
    <div className="space-y-6">
      <h1 className="text-xl font-semibold">秋招全景</h1>

      {/* 漏斗数字卡：阶段徽章复用 STAGE_META 配色，与看板/表格语义一致 */}
      <div className="grid grid-cols-4 gap-3 xl:grid-cols-8">
        {Object.entries(funnel).map(([stage, count]) => {
          const meta = STAGE_META[stage as keyof typeof STAGE_META]
          return (
            <Card key={stage} className="py-3 gap-0">
              <CardContent className="px-4 space-y-1.5">
                <div className="flex items-center gap-1.5">
                  <span className={cn('size-2 rounded-full', meta?.dot ?? 'bg-zinc-300')} />
                  <span className="text-2xl font-bold">{count}</span>
                </div>
                <div className="text-xs text-muted-foreground">{meta?.label ?? stage}</div>
              </CardContent>
            </Card>
          )
        })}
      </div>

      {/* 今日推荐：W3-3 每日管线产出，只展示待处理的一炉（反馈后即移出） */}
      <RecommendationSection
        items={recs ?? []}
        onItemHandled={(id) => setRecs((prev) => prev?.filter((r) => r.id !== id) ?? null)}
        onRefresh={loadRecs}
      />

      {/* 图表区（W4-3）：漏斗构成 + 30 天节奏 + 公司类型分布 */}
      {stats && (
        <div className="grid grid-cols-1 gap-4 lg:grid-cols-3">
          <FunnelChart funnel={funnel} />
          <TrendChart daily={stats.daily} />
          <TypePie shares={stats.company_types} />
        </div>
      )}

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-3">
        {/* 投递进展：累计投递 = 当前处于「已投递/笔试/面试/Offer」阶段的申请数（在途有效投递）。
            不再显示拍脑袋的默认周目标——数字直接来自状态统计，与用户实际操作一一对应。
            rejected/withdrawn 不计入：它们是已归档的终态，不代表有效投递。 */}
        <Card>
          <CardHeader>
            <CardTitle className="text-base flex items-center gap-1.5">
              <TrendingUp className="size-4 text-blue-500" />投递进展
            </CardTitle>
          </CardHeader>
          <CardContent className="space-y-2 text-sm">
            <div className="flex justify-between items-baseline">
              <span className="text-muted-foreground">累计投递（流程中）</span>
              <span className="text-xl font-bold text-blue-600">
                {(funnel.applied ?? 0) + (funnel.written_test ?? 0) + (funnel.interview ?? 0) + (funnel.offer ?? 0)}
              </span>
            </div>
            <div className="flex justify-between">
              <span className="text-muted-foreground">本周新投递</span>
              <span className="font-medium">{week.applied}</span>
            </div>
            <div className="flex justify-between">
              <span className="text-muted-foreground">本周新增岗位</span>
              <span className="font-medium">{week.new_jobs}</span>
            </div>
            <div className="flex justify-between">
              <span className="text-muted-foreground">未读推荐</span>
              <span className="font-medium">{week.recommendations_unread}</span>
            </div>
          </CardContent>
        </Card>

        {/* DDL 倒计时 */}
        <Card>
          <CardHeader>
            <CardTitle className="text-base flex items-center gap-1.5">
              <AlarmClock className="size-4 text-red-500" />DDL 倒计时
            </CardTitle>
          </CardHeader>
          <CardContent className="space-y-2">
            {deadlines.length === 0 && <p className="text-sm text-muted-foreground">暂无临近截止的岗位</p>}
            {deadlines.map((d) => (
              <Link key={d.job_id} to={`/jobs/${d.job_id}`} className="flex items-center justify-between gap-2 text-sm hover:bg-accent rounded-md px-1 py-0.5">
                <span className="truncate">
                  {d.company} · {d.title}
                </span>
                <Badge variant={d.days_left <= 3 ? 'destructive' : 'secondary'}>
                  {d.days_left === 0 ? '今天截止' : `剩 ${d.days_left} 天`}
                </Badge>
              </Link>
            ))}
          </CardContent>
        </Card>

        {/* 待办 */}
        <Card>
          <CardHeader>
            <CardTitle className="text-base flex items-center gap-1.5">
              <ListTodo className="size-4 text-amber-500" />待办事项
            </CardTitle>
          </CardHeader>
          <CardContent className="space-y-2">
            {actions.length === 0 && <p className="text-sm text-muted-foreground">暂无待办，去岗位库收藏几个目标吧</p>}
            {actions.map((a) => (
              <div key={a.application_id} className="flex items-center justify-between gap-2 text-sm">
                <span className="truncate">{a.company} · {a.next_action}</span>
                <span className="text-xs text-muted-foreground shrink-0">
                  {/* 无时间 = 系统按阶段生成的待办，提示尽快处理 */}
                  {a.next_action_at
                    ? new Date(a.next_action_at).toLocaleString('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' })
                    : <span className="text-amber-600">尽快</span>}
                </span>
              </div>
            ))}
          </CardContent>
        </Card>
      </div>

      {/* 每周复盘（W4-2）：纯数据版即时出，AI 叙事按需生成 */}
      <WeeklyReportCard />
    </div>
  )
}
