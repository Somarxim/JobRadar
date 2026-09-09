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
import { ErrorState, LoadingState } from '@/components/StatusStates'

/**
 * 投递看板：每列一个阶段，拖拽卡片跨列即触发状态流转 API。
 * 拖到「已投递」时弹渠道确认框（契约要求 channel），其余阶段直接流转。
 */
export default function BoardPage() {
  const [groups, setGroups] = useState<Record<Stage, ApplicationCard[]> | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [pendingApplied, setPendingApplied] = useState<{ appId: number; title: string } | null>(null)
  const [rollbackReq, setRollbackReq] = useState<(RollbackRequest & { appId: number }) | null>(null)
  const [channel, setChannel] = useState('official')
  const [submitting, setSubmitting] = useState(false)
  const sensors = useSensors(useSensor(PointerSensor, { activationConstraint: { distance: 6 } }))

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
      <h1 className="text-xl font-semibold">投递看板</h1>
      <DndContext sensors={sensors} onDragEnd={onDragEnd}>
        <div className="grid grid-cols-4 gap-3 xl:grid-cols-8 flex-1 items-start">
          {STAGES.map((stage) => (
            <Column key={stage} stage={stage} cards={groups[stage] ?? []} />
          ))}
        </div>
      </DndContext>

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

function Column({ stage, cards }: { stage: Stage; cards: ApplicationCard[] }) {
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
        <CardView key={c.id} card={c} />
      ))}
    </div>
  )
}

function CardView({ card }: { card: ApplicationCard }) {
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
        'rounded-md border bg-card p-2 shadow-xs cursor-grab active:cursor-grabbing select-none',
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
    </div>
  )
}
