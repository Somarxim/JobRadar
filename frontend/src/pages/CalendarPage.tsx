import { useCallback, useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from '@/api/client'
import type { CalendarEvent } from '@/api/types'
import { Button } from '@/components/ui/button'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover'
import { CALENDAR_TYPE_META, CALENDAR_TYPE_PRIORITY } from '@/lib/labels'
import { cn } from '@/lib/utils'
import { ErrorState } from '@/components/StatusStates'
import { ChevronLeft, ChevronRight } from 'lucide-react'

/** 单格最多直显的事件数，超出收进「+N」胶囊（防单日事件顶开格子、整行变形） */
const MAX_VISIBLE = 3

/** 日历页：月视图，固定等高格子 + 溢出 Popover（deadline/笔试/面试/计划/待办） */
export default function CalendarPage() {
  const [month, setMonth] = useState(() => {
    const d = new Date()
    return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`
  })
  const [events, setEvents] = useState<CalendarEvent[]>([])
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(() => {
    api.calendar(month).then((r) => setEvents(r.events)).catch((e) => setError(e.message))
  }, [month])

  useEffect(load, [load])

  const shiftMonth = (delta: number) => {
    const [y, m] = month.split('-').map(Number)
    const d = new Date(y, m - 1 + delta, 1)
    setMonth(`${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`)
  }

  // 当月日历网格：周一开头，补齐前后月灰格
  const cells = useMemo(() => buildMonthCells(month), [month])
  const byDate = useMemo(() => {
    const map = new Map<string, CalendarEvent[]>()
    for (const e of events) {
      const list = map.get(e.date) ?? []
      list.push(e)
      map.set(e.date, list)
    }
    // 格内按优先级排序：deadline/面试永远占据直显位，不被 +N 折叠
    for (const list of map.values()) {
      list.sort((a, b) => (CALENDAR_TYPE_PRIORITY[a.type] ?? 9) - (CALENDAR_TYPE_PRIORITY[b.type] ?? 9))
    }
    return map
  }, [events])

  const today = localToday()

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h1 className="text-xl font-semibold">日历</h1>
        <div className="flex items-center gap-2">
          <Button variant="outline" size="icon" aria-label="上个月" onClick={() => shiftMonth(-1)}><ChevronLeft className="size-4" /></Button>
          <span className="text-sm font-medium w-28 text-center tabular-nums" aria-live="polite">{month}</span>
          <Button variant="outline" size="icon" aria-label="下个月" onClick={() => shiftMonth(1)}><ChevronRight className="size-4" /></Button>
        </div>
      </div>

      <div className="flex gap-3 text-xs text-muted-foreground">
        {Object.entries(CALENDAR_TYPE_META).map(([k, v]) => (
          <span key={k} className="inline-flex items-center gap-1">
            <span className={cn('inline-block size-2 rounded-sm', v.className.split(' ')[0])} />{v.label}
          </span>
        ))}
      </div>

      {error && <ErrorState message={error} onRetry={load} />}

      {/* key=month：切换月份触发整格 fade-in，给「已切换」的明确反馈 */}
      <div key={month} className="grid grid-cols-7 gap-px rounded-xl border bg-border overflow-hidden animate-in fade-in duration-300">
        {['一', '二', '三', '四', '五', '六', '日'].map((d) => (
          <div key={d} className="bg-muted px-2 py-1.5 text-center text-xs text-muted-foreground">周{d}</div>
        ))}
        {cells.map((cell) => (
          <CalendarCell
            key={cell.date}
            date={cell.date}
            inMonth={cell.inMonth}
            isToday={cell.date === today}
            events={byDate.get(cell.date) ?? []}
          />
        ))}
      </div>
    </div>
  )
}

/** 单日格子：固定等高（事件再多也不变形），超出 MAX_VISIBLE 收进 Popover */
function CalendarCell({ date, inMonth, isToday, events }: {
  date: string
  inMonth: boolean
  isToday: boolean
  events: CalendarEvent[]
}) {
  const visible = events.slice(0, MAX_VISIBLE)
  const overflow = events.slice(MAX_VISIBLE)
  const hasDeadline = events.some((e) => e.type === 'deadline')

  return (
    <div
      className={cn(
        'bg-card h-28 md:h-32 p-1.5 flex flex-col transition-colors hover:bg-accent/30',
        !inMonth && 'bg-muted/40',
      )}
    >
      {/* 日期头：左日期右 deadline 红点锚点；今天用反色圆点而非整格 ring（不侵占事件空间） */}
      <div className="flex items-center justify-between h-6 mb-0.5 shrink-0">
        {isToday ? (
          <span
            aria-current="date"
            title="今天"
            className="flex size-5 items-center justify-center rounded-full bg-primary text-[11px] font-bold text-primary-foreground"
          >
            {Number(date.slice(8))}
          </span>
        ) : (
          <span className={cn('text-xs tabular-nums', inMonth ? 'text-muted-foreground' : 'text-muted-foreground/60')}>
            {Number(date.slice(8))}
          </span>
        )}
        {/* role=img 让 generic span 的 aria-label 被屏幕阅读器采纳（空 span 无 role 时会被跳过） */}
        {hasDeadline && <span role="img" aria-label="当日有投递截止" className="size-1.5 rounded-full bg-red-500" />}
      </div>

      {/* 事件区：裁剪而非撑开 */}
      <div className="flex-1 space-y-0.5 overflow-hidden">
        {visible.map((e, i) => (
          <EventTag key={i} event={e} dimmed={!inMonth} />
        ))}
        {overflow.length > 0 && (
          <DayOverflow date={date} overflow={overflow} dimmed={!inMonth} />
        )}
      </div>
    </div>
  )
}

/** 事件条：左侧 2px 类型色条 + 极淡底色（替代全色块，多日密集时降噪） */
function EventTag({ event, dimmed }: { event: CalendarEvent; dimmed?: boolean }) {
  const meta = CALENDAR_TYPE_META[event.type]
  return (
    <Link
      to={event.job_id ? `/jobs/${event.job_id}` : '#'}
      title={event.title}
      aria-label={`${meta?.label ?? '事件'}：${event.title}`}
      className={cn(
        'block truncate border-l-2 rounded-r px-1.5 py-0.5 text-[11px] leading-tight transition-all hover:translate-x-0.5',
        meta?.bar,
        dimmed && 'opacity-60 grayscale-[0.3]',
      )}
    >
      {event.title}
    </Link>
  )
}

/** 「+N」溢出胶囊：title 零依赖预告全部溢出项，点击 Popover 展开完整列表 */
function DayOverflow({ date, overflow, dimmed }: { date: string; overflow: CalendarEvent[]; dimmed?: boolean }) {
  const preview = overflow.map((e) => e.title).join('、')
  return (
    <Popover>
      <PopoverTrigger asChild>
        <button
          type="button"
          title={`还有 ${overflow.length} 项：${preview}`}
          aria-label={`展开当日其余 ${overflow.length} 项事件`}
          className={cn(
            'mt-0.5 inline-flex h-5 w-full items-center justify-center rounded-full bg-muted text-[10px] font-medium text-muted-foreground transition-colors hover:bg-accent hover:text-accent-foreground cursor-pointer focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring',
            dimmed && 'opacity-60',
          )}
        >
          +{overflow.length}
        </button>
      </PopoverTrigger>
      <PopoverContent
        className="w-64 p-3"
        align="start"
        collisionPadding={12}
        aria-label={`${Number(date.slice(5, 7))}月${Number(date.slice(8))}日全部事件`}
      >
        <div className="mb-2 text-sm font-semibold">
          {Number(date.slice(5, 7))}月{Number(date.slice(8))}日 · 全部事件
        </div>
        <div className="max-h-80 space-y-2 overflow-y-auto pr-1">
          {overflow.map((e, i) => (
            <Link
              key={i}
              to={e.job_id ? `/jobs/${e.job_id}` : '#'}
              className="flex items-start gap-2 text-xs hover:opacity-80"
            >
              <span className={cn('mt-1 inline-block size-2 shrink-0 rounded-sm', CALENDAR_TYPE_META[e.type]?.className?.split(' ')[0])} />
              <span className="min-w-0">
                <span className="mr-1 text-muted-foreground">{CALENDAR_TYPE_META[e.type]?.label}</span>
                {e.title}
              </span>
            </Link>
          ))}
        </div>
      </PopoverContent>
    </Popover>
  )
}

/** 生成 6×7 月历网格（周一开头） */
function buildMonthCells(month: string) {
  const [y, m] = month.split('-').map(Number)
  const first = new Date(y, m - 1, 1)
  // JS Date: 0=周日；转为周一开头的偏移
  const offset = (first.getDay() + 6) % 7
  const start = new Date(y, m - 1, 1 - offset)
  return Array.from({ length: 42 }, (_, i) => {
    const d = new Date(start.getFullYear(), start.getMonth(), start.getDate() + i)
    const date = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
    return { date, inMonth: d.getMonth() === m - 1 }
  })
}

/** 本地时区今日 YYYY-MM-DD（toISOString 是 UTC，早 8 点前会错标昨天） */
function localToday(): string {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}
