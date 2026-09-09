import { Bar, BarChart, CartesianGrid, Cell, ComposedChart, Line, Pie, PieChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import type { DashboardStats } from '@/api/types'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { COMPANY_TYPE_META, STAGE_META } from '@/lib/labels'
import type { Stage } from '@/api/types'
import { BarChart3, LineChart as LineChartIcon, PieChart as PieChartIcon } from 'lucide-react'

/**
 * Dashboard 图表区（W4-3）：漏斗 / 30 天趋势 / 公司类型分布。
 * recharts 的 fill 只认 CSS 颜色值不认 Tailwind 类——色板在此用 hex 镜像
 * STAGE_META / COMPANY_TYPE_META 的 dot 色，改色时两边同步（labels.ts 是文案事实源）。
 */

const STAGE_HEX: Record<Stage, string> = {
  collected: '#94a3b8',
  planned: '#3b82f6',
  applied: '#6366f1',
  written_test: '#f59e0b',
  interview: '#8b5cf6',
  offer: '#10b981',
  rejected: '#ef4444',
  withdrawn: '#a1a1aa',
}

const TYPE_HEX: Record<string, string> = {
  internet: '#0ea5e9',
  soe_central: '#f43f5e',
  soe_local: '#f97316',
  institute: '#6366f1',
  operator: '#3b82f6',
  bank: '#10b981',
  foreign: '#8b5cf6',
  other: '#a1a1aa',
}

/** 漏斗图：横向条形，按主线顺序（终态沉底），直观看出各环节淤积 */
export function FunnelChart({ funnel }: { funnel: Record<string, number> }) {
  const data = Object.entries(STAGE_META).map(([stage, meta]) => ({
    stage,
    label: meta.label,
    count: funnel[stage] ?? 0,
  }))
  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-base flex items-center gap-1.5">
          <BarChart3 className="size-4 text-indigo-500" />投递漏斗
        </CardTitle>
      </CardHeader>
      <CardContent>
        <ResponsiveContainer width="100%" height={240}>
          <BarChart data={data} layout="vertical" margin={{ left: 8, right: 16 }}>
            <XAxis type="number" hide />
            <YAxis type="category" dataKey="label" width={72} tick={{ fontSize: 12 }} axisLine={false} tickLine={false} />
            <Tooltip formatter={(v) => [`${v} 个`, '数量']} />
            <Bar dataKey="count" radius={[0, 4, 4, 0]} barSize={16}>
              {data.map((d) => (
                <Cell key={d.stage} fill={STAGE_HEX[d.stage as Stage]} />
              ))}
            </Bar>
          </BarChart>
        </ResponsiveContainer>
      </CardContent>
    </Card>
  )
}

/** 趋势图：近 30 天每日新收录岗位（柱）+ 每日投递（线），看节奏是否断档 */
export function TrendChart({ daily }: { daily: DashboardStats['daily'] }) {
  const data = daily.map((d) => ({
    ...d,
    day: `${Number(d.date.slice(5, 7))}/${Number(d.date.slice(8, 10))}`,
  }))
  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-base flex items-center gap-1.5">
          <LineChartIcon className="size-4 text-blue-500" />近 30 天趋势
        </CardTitle>
      </CardHeader>
      <CardContent>
        <ResponsiveContainer width="100%" height={240}>
          <ComposedChart data={data} margin={{ left: -16, right: 8 }}>
            <CartesianGrid strokeDasharray="3 3" vertical={false} stroke="#e4e4e7" />
            <XAxis dataKey="day" tick={{ fontSize: 11 }} interval={4} axisLine={false} tickLine={false} />
            <YAxis allowDecimals={false} tick={{ fontSize: 11 }} axisLine={false} tickLine={false} />
            <Tooltip
              formatter={(v, name) => [`${v}`, name === 'applied' ? '投递' : '新收录']}
              labelFormatter={(l) => `日期 ${l}`}
            />
            <Bar dataKey="new_jobs" fill="#7dd3fc" radius={[3, 3, 0, 0]} barSize={8} />
            <Line type="monotone" dataKey="applied" stroke="#6366f1" strokeWidth={2} dot={false} />
          </ComposedChart>
        </ResponsiveContainer>
      </CardContent>
    </Card>
  )
}

/** 类型分布饼图：在架岗位的公司类型构成，检验投递组合是否符合目标方向 */
export function TypePie({ shares }: { shares: DashboardStats['company_types'] }) {
  const data = shares
    .filter((s) => s.count > 0)
    .map((s) => ({
      ...s,
      label: COMPANY_TYPE_META[s.type]?.label ?? s.type,
    }))
  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-base flex items-center gap-1.5">
          <PieChartIcon className="size-4 text-emerald-500" />公司类型分布
        </CardTitle>
      </CardHeader>
      <CardContent>
        {data.length === 0 ? (
          <p className="text-sm text-muted-foreground py-16 text-center">暂无在架岗位</p>
        ) : (
          <ResponsiveContainer width="100%" height={240}>
            <PieChart>
              <Pie data={data} dataKey="count" nameKey="label" innerRadius={52} outerRadius={80} paddingAngle={2}>
                {data.map((d) => (
                  <Cell key={d.type} fill={TYPE_HEX[d.type] ?? TYPE_HEX.other} />
                ))}
              </Pie>
              <Tooltip formatter={(v, name) => [`${v} 个`, name]} />
            </PieChart>
          </ResponsiveContainer>
        )}
        {/* 图例自绘：中文标签 + 计数，比 recharts 默认图例信息密度高 */}
        <div className="flex flex-wrap gap-x-4 gap-y-1 justify-center text-xs text-muted-foreground -mt-2">
          {data.map((d) => (
            <span key={d.type} className="flex items-center gap-1">
              <span className="size-2 rounded-full" style={{ backgroundColor: TYPE_HEX[d.type] ?? TYPE_HEX.other }} />
              {d.label} {d.count}
            </span>
          ))}
        </div>
      </CardContent>
    </Card>
  )
}
