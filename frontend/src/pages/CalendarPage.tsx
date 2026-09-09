import { useCallback, useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from '@/api/client'
import type { CalendarEvent } from '@/api/types'
import { Button } from '@/components/ui/button'
import { CALENDAR_TYPE_META } from '@/lib/labels'
import { cn } from '@/lib/utils'
import { ErrorState } from '@/components/StatusStates'
import { ChevronLeft, ChevronRight } from 'lucide-react'

/** 日历页：月视图，单元格内展示当日事件（deadline/笔试/面试/计划/待办） */
export default function CalendarPage() {
  const [month, setMonth] = useState(() => new Date().toISOString().slice(0, 7)) // YYYY-MM
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
    return map
  }, [events])

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h1 className="text-xl font-semibold">日历</h1>
        <div className="flex items-center gap-2">
          <Button variant="outline" size="icon" onClick={() => shiftMonth(-1)}><ChevronLeft className="size-4" /></Button>
          <span className="text-sm font-medium w-28 text-center">{month}</span>
          <Button variant="outline" size="icon" onClick={() => shiftMonth(1)}><ChevronRight className="size-4" /></Button>
        </div>
      </div>

      <div className="flex gap-3 text-xs text-muted-foreground">
        {Object.entries(CALENDAR_TYPE_META).map(([k, v]) => (
          <span key={k} className="inline-flex items-center gap-1">
            <span className={cn('inline-block size-2 rounded-sm', v.className)} />{v.label}
          </span>
        ))}
      </div>

      {error && <ErrorState message={error} onRetry={load} />}

      <div className="grid grid-cols-7 gap-px rounded-lg border bg-border overflow-hidden">
        {['一', '二', '三', '四', '五', '六', '日'].map((d) => (
          <div key={d} className="bg-muted px-2 py-1 text-center text-xs text-muted-foreground">周{d}</div>
        ))}
        {cells.map((cell) => (
          <div
            key={cell.date}
            className={cn(
              'bg-card min-h-24 p-1.5 space-y-1',
              !cell.inMonth && 'opacity-40',
              cell.date === new Date().toISOString().slice(0, 10) && 'ring-2 ring-inset ring-primary'
            )}
          >
            <div className="text-xs text-muted-foreground">{Number(cell.date.slice(8))}</div>
            {(byDate.get(cell.date) ?? []).map((e, i) => (
              <Link
                key={i}
                to={e.job_id ? `/jobs/${e.job_id}` : '#'}
                className={cn('block truncate rounded px-1 py-0.5 text-xs hover:opacity-80', CALENDAR_TYPE_META[e.type]?.className)}
                title={e.title}
              >
                {e.title}
              </Link>
            ))}
          </div>
        ))}
      </div>
    </div>
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
