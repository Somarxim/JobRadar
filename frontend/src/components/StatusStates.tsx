import { Button } from '@/components/ui/button'
import { Loader2, RefreshCw } from 'lucide-react'
import type { ReactNode } from 'react'
import { isRouteErrorResponse, useRouteError } from 'react-router-dom'

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

/**
 * 路由级渲染崩溃兜底（挂在 router 的 errorElement）：
 * 之前任何组件抛错都会白屏并露出 react-router 默认的 "Unexpected Application Error" 堆栈页。
 * 这里统一成友好提示 + 刷新入口；错误细节进 console 方便排查。
 */
export function RouteErrorState() {
  const error = useRouteError()
  console.error('[route-error]', error)
  // 无匹配路由时 react-router 抛 Response(404)，与组件渲染异常分开表述
  const is404 = isRouteErrorResponse(error) && error.status === 404
  const message = is404
    ? '页面不存在或已被移动'
    : error instanceof Error
      ? error.message
      : '未知错误'
  return (
    <div className="flex min-h-[60vh] flex-col items-center justify-center gap-3 py-16 text-center">
      <p className="text-sm font-medium">{is404 ? '404' : '页面出错了'}</p>
      <p className="max-w-md text-xs break-all text-muted-foreground">{message}</p>
      <div className="mt-2 flex gap-2">
        <Button variant="outline" size="sm" onClick={() => window.location.reload()}>
          <RefreshCw className="size-3.5" />刷新页面
        </Button>
        <Button variant="ghost" size="sm" onClick={() => window.location.assign('/')}>
          回到仪表盘
        </Button>
      </div>
    </div>
  )
}
