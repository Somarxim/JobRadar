import { Toaster as SonnerToaster } from 'sonner'

/** 全局 toast 挂载点（sonner，shadcn 官方推荐的通知组件） */
export function Toaster() {
  return <SonnerToaster position="top-center" richColors closeButton />
}
