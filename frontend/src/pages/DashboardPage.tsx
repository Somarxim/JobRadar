import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from '@/api/client'
import type { DashboardSummary } from '@/api/types'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { STAGE_META } from '@/lib/labels'

/** 仪表盘：漏斗数字卡 + 本周进展 + DDL 倒计时 + 待办（roadmap W1 验收页） */
export default function DashboardPage() {
  const [data, setData] = useState<DashboardSummary | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    api.summary().then(setData).catch((e) => setError(e.message))
  }, [])

  if (error) return <p className="text-destructive">加载失败：{error}</p>
  if (!data) return <p className="text-muted-foreground">加载中…</p>

  const { funnel, this_week: week, upcoming_deadlines: deadlines, next_actions: actions } = data

  return (
    <div className="space-y-6">
      <h1 className="text-xl font-semibold">秋招全景</h1>

      {/* 漏斗数字卡 */}
      <div className="grid grid-cols-4 gap-3 xl:grid-cols-8">
        {Object.entries(funnel).map(([stage, count]) => (
          <Card key={stage} className="py-3 gap-0">
            <CardContent className="px-4">
              <div className="text-2xl font-bold">{count}</div>
              <div className="text-xs text-muted-foreground">
                {STAGE_META[stage as keyof typeof STAGE_META]?.label ?? stage}
              </div>
            </CardContent>
          </Card>
        ))}
      </div>

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-3">
        {/* 本周进展 */}
        <Card>
          <CardHeader>
            <CardTitle className="text-base">本周进展</CardTitle>
          </CardHeader>
          <CardContent className="space-y-2 text-sm">
            <div className="flex justify-between">
              <span className="text-muted-foreground">投递目标</span>
              <span className="font-medium">{week.applied} / {week.goal}</span>
            </div>
            <div className="h-2 rounded-full bg-muted overflow-hidden">
              <div
                className="h-full bg-primary transition-all"
                style={{ width: `${Math.min(100, (week.applied / Math.max(week.goal, 1)) * 100)}%` }}
              />
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
            <CardTitle className="text-base">DDL 倒计时</CardTitle>
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
            <CardTitle className="text-base">待办事项</CardTitle>
          </CardHeader>
          <CardContent className="space-y-2">
            {actions.length === 0 && <p className="text-sm text-muted-foreground">暂无待办，去岗位库收藏几个目标吧</p>}
            {actions.map((a) => (
              <div key={a.application_id} className="flex items-center justify-between gap-2 text-sm">
                <span className="truncate">{a.company} · {a.next_action}</span>
                <span className="text-xs text-muted-foreground shrink-0">
                  {new Date(a.next_action_at).toLocaleString('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' })}
                </span>
              </div>
            ))}
          </CardContent>
        </Card>
      </div>
    </div>
  )
}
