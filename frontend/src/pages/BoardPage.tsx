import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { DndContext, PointerSensor, useDraggable, useDroppable, useSensor, useSensors, type DragEndEvent } from '@dnd-kit/core'
import { CSS } from '@dnd-kit/utilities'
import { toast } from 'sonner'
import { api } from '@/api/client'
import type { ApplicationCard, Stage } from '@/api/types'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import {
  Dialog, DialogContent, DialogDescription, DialogFooter,
  DialogHeader, DialogTitle,
} from '@/components/ui/dialog'
import {
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
} from '@/components/ui/select'
import { CHANNEL_LABELS, STAGE_META, STAGES, fmtDateTime, isRollback } from '@/lib/labels'
import { cn } from '@/lib/utils'
import TransitionConfirmDialog, { type RollbackRequest } from '@/components/TransitionConfirmDialog'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { KanbanSquare, List, Pencil, Search } from 'lucide-react'
import { ErrorState, LoadingState } from '@/components/StatusStates'
import {
  Table, TableBody, TableCell, TableHead, TableHeader, TableRow,
} from '@/components/ui/table'

/**
 * 投递看板：每列一个阶段，拖拽卡片跨列即触发状态流转 API。
 * 拖到「已投递」时弹渠道确认框（契约要求 channel），其余阶段直接流转。
 */
export default function BoardPage() {
  const [groups, setGroups] = useState<Record<Stage, ApplicationCard[]> | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [pendingApplied, setPendingApplied] = useState<{ appId: number; title: string } | null>(null)
  const [rollbackReq, setRollbackReq] = useState<(RollbackRequest & { appId: number }) | null>(null)
  // 待办编辑：editingTodo 非 null 时弹出编辑框
  const [editingTodo, setEditingTodo] = useState<ApplicationCard | null>(null)
  const [todoText, setTodoText] = useState('')
  const [todoAt, setTodoAt] = useState('')
  const [channel, setChannel] = useState('official')
  const [submitting, setSubmitting] = useState(false)
  const sensors = useSensors(useSensor(PointerSensor, { activationConstraint: { distance: 6 } }))

  // 视图切换：看板 vs 列表（localStorage 记忆）
  const [view, setView] = useState<'board' | 'list'>(() => {
    const v = localStorage.getItem('board.view')
    return v === 'list' ? 'list' : 'board'
  })
  useEffect(() => { localStorage.setItem('board.view', view) }, [view])

  // 列表视图筛选
  const [filterStage, setFilterStage] = useState<Stage | 'all'>('all')
  const [searchQ, setSearchQ] = useState('')

  const load = useCallback(() => {
    api.board().then((b) => setGroups(b.groups)).catch((e) => setError(e.message))
  }, [])

  useEffect(load, [load])

  async function doTransition(appId: number, toStage: Stage, ch?: string) {
    setSubmitting(true)
    try {
      await api.transition(appId, { to_stage: toStage, channel: ch })
      toast.success(`已流转到「${STAGE_META[toStage].label}」`)
      load()
    } catch (e) {
      toast.error(e instanceof Error ? e.message : String(e))
    } finally {
      setSubmitting(false)
    }
  }

  function onDragEnd(ev: DragEndEvent) {
    const appId = ev.active.data.current?.appId as number | undefined
    const title = ev.active.data.current?.title as string | undefined
    const fromStage = ev.active.data.current?.stage as Stage | undefined
    const toStage = ev.over?.id as Stage | undefined
    if (!appId || !toStage || !fromStage || fromStage === toStage) return

    if (toStage === 'applied') {
      // 契约要求 applied 必带渠道：先弹渠道选择框
      setPendingApplied({ appId, title: title ?? '' })
    } else if (isRollback(fromStage, toStage)) {
      // 主线回退（如 面试→笔试）：弹确认框，引导用户考虑归档
      setRollbackReq({ appId, fromStage, toStage })
    } else {
      doTransition(appId, toStage)
    }
  }

  function openTodoEdit(card: ApplicationCard) {
    setEditingTodo(card)
    setTodoText(card.next_action ?? '')
    // datetime-local 需要本地时区的 YYYY-MM-DDTHH:mm；后端给的是带时区 ISO
    setTodoAt(card.next_action_at ? toLocalInputValue(card.next_action_at) : '')
  }

  async function saveTodo() {
    if (!editingTodo) return
    setSubmitting(true)
    try {
      await api.updateApplication(editingTodo.id, {
        next_action: todoText.trim(),
        // 时间留空 = 保持原值（后端 PATCH null 语义）；填了才覆盖
        next_action_at: todoAt ? new Date(todoAt).toISOString() : undefined,
      })
      toast.success('待办已更新')
      setEditingTodo(null)
      load()
    } catch (e) {
      toast.error(e instanceof Error ? e.message : String(e))
    } finally {
      setSubmitting(false)
    }
  }

  /** 回退确认框选择：rollback=按原计划回退；rejected/withdrawn=改为归档终态 */
  function onRollbackChoose(choice: 'rollback' | 'rejected' | 'withdrawn') {
    if (!rollbackReq) return
    const target = choice === 'rollback' ? rollbackReq.toStage : choice
    const appId = rollbackReq.appId
    setRollbackReq(null)
    doTransition(appId, target)
  }

  if (error) return <ErrorState message={error} onRetry={load} />
  if (!groups) return <LoadingState />

  return (
    <div className="space-y-4 h-full flex flex-col">
      <div className="flex items-center justify-between gap-3 flex-wrap">
        <h1 className="text-xl font-semibold">投递管理</h1>
        <div className="inline-flex rounded-md border bg-muted p-0.5">
          <button
            type="button"
            onClick={() => setView('board')}
            className={cn(
              'flex items-center gap-1.5 rounded-sm px-3 py-1 text-sm transition-colors',
              view === 'board' ? 'bg-card text-foreground shadow-sm' : 'text-muted-foreground hover:text-foreground'
            )}
          >
            <KanbanSquare className="size-3.5" />看板
          </button>
          <button
            type="button"
            onClick={() => setView('list')}
            className={cn(
              'flex items-center gap-1.5 rounded-sm px-3 py-1 text-sm transition-colors',
              view === 'list' ? 'bg-card text-foreground shadow-sm' : 'text-muted-foreground hover:text-foreground'
            )}
          >
            <List className="size-3.5" />列表
          </button>
        </div>
      </div>

      {view === 'board' ? (
      <DndContext sensors={sensors} onDragEnd={onDragEnd}>
        <div className="grid grid-cols-4 gap-3 xl:grid-cols-8 flex-1 items-start">
          {STAGES.map((stage) => (
            <Column key={stage} stage={stage} cards={groups[stage] ?? []} onEditTodo={openTodoEdit} />
          ))}
        </div>
      </DndContext>
      ) : (
        <ApplicationListView
          groups={groups}
          filterStage={filterStage}
          setFilterStage={setFilterStage}
          searchQ={searchQ}
          setSearchQ={setSearchQ}
        />
      )}

      {/* 拖到「已投递」时的渠道确认框 */}
      <Dialog open={pendingApplied !== null} onOpenChange={(open) => !open && setPendingApplied(null)}>
        <DialogContent className="max-w-sm">
          <DialogHeader>
            <DialogTitle>确认投递</DialogTitle>
            <DialogDescription>{pendingApplied?.title}：请选择投递渠道</DialogDescription>
          </DialogHeader>
          <Select value={channel} onValueChange={setChannel}>
            <SelectTrigger><SelectValue /></SelectTrigger>
            <SelectContent>
              {Object.entries(CHANNEL_LABELS).map(([v, l]) => (
                <SelectItem key={v} value={v}>{l}</SelectItem>
              ))}
            </SelectContent>
          </Select>
          <DialogFooter>
            <Button
              disabled={submitting}
              onClick={async () => {
                if (!pendingApplied) return
                await doTransition(pendingApplied.appId, 'applied', channel)
                setPendingApplied(null)
              }}
            >
              {submitting ? '提交中…' : '确认投递'}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* 待办编辑框：清空文本即清除待办；时间留空保持原值 */}
      <Dialog open={editingTodo !== null} onOpenChange={(open) => !open && setEditingTodo(null)}>
        <DialogContent className="max-w-sm">
          <DialogHeader>
            <DialogTitle>编辑待办</DialogTitle>
            <DialogDescription>{editingTodo?.company_name} · {editingTodo?.title}</DialogDescription>
          </DialogHeader>
          <div className="space-y-3">
            <div className="grid gap-1.5">
              <Label>待办内容</Label>
              <Input
                value={todoText}
                onChange={(e) => setTodoText(e.target.value)}
                placeholder="如：准备自我介绍 / 约面试时间"
              />
            </div>
            <div className="grid gap-1.5">
              <Label>时间（留空保持原值）</Label>
              <Input
                type="datetime-local"
                value={todoAt}
                onChange={(e) => setTodoAt(e.target.value)}
              />
            </div>
          </div>
          <DialogFooter>
            <Button variant="ghost" onClick={() => setEditingTodo(null)} disabled={submitting}>取消</Button>
            <Button onClick={saveTodo} disabled={submitting}>{submitting ? '保存中…' : '保存'}</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* 回退流转确认框 */}
      <TransitionConfirmDialog
        request={rollbackReq}
        submitting={submitting}
        onChoose={onRollbackChoose}
        onCancel={() => setRollbackReq(null)}
      />
    </div>
  )
}

function Column({ stage, cards, onEditTodo }: {
  stage: Stage
  cards: ApplicationCard[]
  onEditTodo: (card: ApplicationCard) => void
}) {
  const { setNodeRef, isOver } = useDroppable({ id: stage })
  const meta = STAGE_META[stage]
  return (
    <div
      ref={setNodeRef}
      className={cn(
        'rounded-lg border bg-muted/40 min-h-40 p-2 space-y-2 transition-colors',
        isOver && 'ring-2 ring-primary bg-accent'
      )}
    >
      <div className="flex items-center justify-between px-1">
        <span className="flex items-center gap-1.5 text-sm font-medium">
          <span className={cn('size-2 rounded-full', meta.dot)} />
          {meta.label}
        </span>
        <Badge variant="secondary">{cards.length}</Badge>
      </div>
      {cards.map((c) => (
        <CardView key={c.id} card={c} onEditTodo={onEditTodo} />
      ))}
    </div>
  )
}

function CardView({ card, onEditTodo }: { card: ApplicationCard; onEditTodo: (card: ApplicationCard) => void }) {
  const { attributes, listeners, setNodeRef, transform, isDragging } = useDraggable({
    id: `app-${card.id}`,
    data: { appId: card.id, stage: card.stage, title: `${card.company_name} · ${card.title}` },
  })
  return (
    <div
      ref={setNodeRef}
      style={{ transform: CSS.Translate.toString(transform) }}
      {...listeners}
      {...attributes}
      className={cn(
        'group relative rounded-md border bg-card p-2 shadow-xs cursor-grab active:cursor-grabbing select-none',
        'hover:shadow-md hover:border-primary/40 transition-shadow',
        isDragging && 'opacity-60 ring-2 ring-primary z-50 relative'
      )}
    >
      <Link
        to={`/jobs/${card.job_id}`}
        className="text-sm font-medium hover:underline block truncate"
        onPointerDown={(e) => e.stopPropagation()} // 让链接可点击而不触发拖拽
      >
        {card.company_name}
      </Link>
      <div className="text-xs text-muted-foreground truncate">{card.title}</div>
      {card.next_action && (
        <div className="text-xs text-amber-600 mt-1 truncate">
          {card.next_action} · {fmtDateTime(card.next_action_at)}
        </div>
      )}
      <button
        type="button"
        aria-label="编辑待办"
        title="编辑待办"
        onPointerDown={(e) => e.stopPropagation()}
        onClick={(e) => { e.stopPropagation(); onEditTodo(card) }}
        className="absolute right-1.5 top-1.5 rounded p-1 text-muted-foreground/50 opacity-0 transition-opacity hover:bg-accent hover:text-foreground group-hover:opacity-100"
      >
        <Pencil className="size-3" />
      </button>
    </div>
  )
}

/** 列表视图：扁平表格，支持阶段筛选与公司/岗位搜索 */
function ApplicationListView({
  groups,
  filterStage,
  setFilterStage,
  searchQ,
  setSearchQ,
}: {
  groups: Record<Stage, ApplicationCard[]>
  filterStage: Stage | 'all'
  setFilterStage: (s: Stage | 'all') => void
  searchQ: string
  setSearchQ: (s: string) => void
}) {
  const all = STAGES.flatMap((stage) => (groups[stage] ?? []).map((c) => ({ ...c, stage })))
    .filter((c) => (filterStage === 'all' ? true : c.stage === filterStage))
    .filter((c) => {
      const q = searchQ.trim().toLowerCase()
      if (!q) return true
      return c.company_name.toLowerCase().includes(q) || c.title.toLowerCase().includes(q)
    })
    .sort((a, b) => new Date(b.updated_at).getTime() - new Date(a.updated_at).getTime())

  return (
    <div className="space-y-3">
      <div className="flex items-center gap-2 flex-wrap">
        <div className="relative">
          <Search className="absolute left-2.5 top-1/2 -translate-y-1/2 size-4 text-muted-foreground" />
          <Input
            placeholder="搜索公司或岗位…"
            value={searchQ}
            onChange={(e) => setSearchQ(e.target.value)}
            className="pl-9 w-64"
          />
        </div>
        <Select value={filterStage} onValueChange={(v) => setFilterStage(v as Stage | 'all')}>
          <SelectTrigger className="w-32">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="all">全部阶段</SelectItem>
            {STAGES.map((s) => (
              <SelectItem key={s} value={s}>{STAGE_META[s].label}</SelectItem>
            ))}
          </SelectContent>
        </Select>
        <span className="text-xs text-muted-foreground ml-auto">共 {all.length} 条投递</span>
      </div>

      {all.length === 0 ? (
        <div className="rounded-md border border-dashed px-3 py-12 text-center text-sm text-muted-foreground">
          没有符合条件的投递记录
        </div>
      ) : (
        <div className="rounded-md border">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>公司 · 岗位</TableHead>
                <TableHead>城市</TableHead>
                <TableHead>阶段</TableHead>
                <TableHead>优先级</TableHead>
                <TableHead>待办</TableHead>
                <TableHead>最近更新</TableHead>
                <TableHead className="text-right">操作</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {all.map((c) => (
                <TableRow key={c.id}>
                  <TableCell>
                    <div className="flex flex-col">
                      <Link to={`/jobs/${c.job_id}`} className="font-medium hover:underline truncate max-w-[16rem]">
                        {c.company_name}
                      </Link>
                      <span className="text-xs text-muted-foreground truncate max-w-[16rem]">{c.title}</span>
                    </div>
                  </TableCell>
                  <TableCell className="text-muted-foreground">{c.city ?? '—'}</TableCell>
                  <TableCell>
                    <Badge variant="outline" className={STAGE_META[c.stage].className}>
                      {STAGE_META[c.stage].label}
                    </Badge>
                  </TableCell>
                  <TableCell>{c.priority}</TableCell>
                  <TableCell>
                    {c.next_action ? (
                      <div className="text-xs">
                        <div className="text-amber-700 truncate max-w-[10rem]">{c.next_action}</div>
                        <div className="text-muted-foreground">{fmtDateTime(c.next_action_at)}</div>
                      </div>
                    ) : (
                      <span className="text-xs text-muted-foreground">—</span>
                    )}
                  </TableCell>
                  <TableCell className="text-xs text-muted-foreground">{fmtDateTime(c.updated_at)}</TableCell>
                  <TableCell className="text-right">
                    <Button variant="ghost" size="sm" asChild>
                      <Link to={`/jobs/${c.job_id}`}>查看岗位</Link>
                    </Button>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      )}
    </div>
  )
}

/** ISO 时间串 → datetime-local 输入框格式（本地时区 YYYY-MM-DDTHH:mm） */
function toLocalInputValue(iso: string): string {
  const d = new Date(iso)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`
}
