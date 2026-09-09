import { Button } from '@/components/ui/button'
import { Loader2, RefreshCw } from 'lucide-react'
import type { ReactNode } from 'react'

/**
 * 全站统一的加载/错误/空态（W4-4 打磨）：
 * 之前各页是纯文本「加载失败」且无重试入口——网络抖动后用户只能刷新整页。
 * 三个状态集中在此，保证视觉一致 + 后续改版只改一处。
 */

/** 加载中：spinner + 文案，垂直居中留白 */
export function LoadingState({ label = '加载中…' }: { label?: string }) {
  return (
    <div className="flex items-center justify-center gap-2 py-16 text-sm text-muted-foreground">
      <Loader2 className="size-4 animate-spin" />
      {label}
    </div>
  )
}

/** 加载失败：错误文案 + 重试按钮（retry 回调一般是页面的 load 函数） */
export function ErrorState({ message, onRetry }: { message: string; onRetry?: () => void }) {
  return (
    <div className="flex flex-col items-center justify-center gap-3 py-16">
      <p className="text-sm text-destructive">加载失败：{message}</p>
      {onRetry && (
        <Button variant="outline" size="sm" onClick={onRetry}>
          <RefreshCw className="size-3.5" />重试
        </Button>
      )}
    </div>
  )
}

/** 空态：图标/插画位 + 主文案 + 引导动作（如「去岗位库收藏」链接按钮） */
export function EmptyState({ title, hint, action }: { title: string; hint?: string; action?: ReactNode }) {
  return (
    <div className="flex flex-col items-center justify-center gap-1.5 py-12 text-center">
      <p className="text-sm text-muted-foreground">{title}</p>
      {hint && <p className="text-xs text-muted-foreground/70">{hint}</p>}
      {action && <div className="mt-2">{action}</div>}
    </div>
  )
}
