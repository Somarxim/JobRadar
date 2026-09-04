import type { Stage } from '@/api/types'
import { Button } from '@/components/ui/button'
import {
  Dialog, DialogContent, DialogDescription, DialogFooter,
  DialogHeader, DialogTitle,
} from '@/components/ui/dialog'
import { STAGE_META } from '@/lib/labels'
import { AlertTriangle, Archive, Undo2, XCircle } from 'lucide-react'

export interface RollbackRequest {
  fromStage: Stage
  toStage: Stage
}

/**
 * 回退流转确认框：主线阶段逆向流转（如 面试→笔试）通常不合常理，
 * 多半是流程已结束（挂了/不想去了），因此提供归档快捷选项。
 *
 * 用户在详情页/看板触发回退时，先弹出本框：
 * - 「仍然回退」→ 按原目标阶段执行（确属二面改笔试等真实场景）
 * - 「标记未通过 / 标记放弃」→ 改为终态归档，保持漏斗数据干净
 *
 * 校验放在前端而非后端：后端契约保持「状态机不限制流转方向」的宽松语义
 * （浏览器插件/MCP 等自动化入口不应被 UI 规则卡死），交互层的引导由前端负责。
 */
export default function TransitionConfirmDialog({
  request,
  submitting,
  onChoose,
  onCancel,
}: {
  request: RollbackRequest | null
  submitting: boolean
  /** choice：rollback=按原计划回退；rejected/withdrawn=改为归档终态 */
  onChoose: (choice: 'rollback' | 'rejected' | 'withdrawn') => void
  onCancel: () => void
}) {
  return (
    <Dialog open={request !== null} onOpenChange={(open) => !open && onCancel()}>
      <DialogContent className="max-w-md">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <AlertTriangle className="size-5 text-amber-500" />
            确认回退阶段？
          </DialogTitle>
          <DialogDescription asChild>
            <div className="space-y-2 pt-1">
              <p>
                你正在从「{request && STAGE_META[request.fromStage].label}」回退到
                「{request && STAGE_META[request.toStage].label}」。
                阶段回退较为少见——如果该岗位的流程实际已结束，建议直接归档，
                这样投递漏斗统计会更准确。
              </p>
            </div>
          </DialogDescription>
        </DialogHeader>
        <div className="grid gap-2">
          <Button
            variant="outline"
            className="justify-start"
            disabled={submitting}
            onClick={() => onChoose('rollback')}
          >
            <Undo2 className="size-4 text-blue-500" />
            仍然回退到「{request && STAGE_META[request.toStage].label}」
            <span className="text-xs text-muted-foreground ml-1">（如：面试改笔试、流程重开）</span>
          </Button>
          <Button
            variant="outline"
            className="justify-start"
            disabled={submitting}
            onClick={() => onChoose('rejected')}
          >
            <XCircle className="size-4 text-red-500" />
            标记为「未通过」
            <span className="text-xs text-muted-foreground ml-1">（流程被终止）</span>
          </Button>
          <Button
            variant="outline"
            className="justify-start"
            disabled={submitting}
            onClick={() => onChoose('withdrawn')}
          >
            <Archive className="size-4 text-zinc-500" />
            标记为「已放弃」
            <span className="text-xs text-muted-foreground ml-1">（主动不再推进）</span>
          </Button>
        </div>
        <DialogFooter>
          <Button variant="ghost" disabled={submitting} onClick={onCancel}>取消</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
