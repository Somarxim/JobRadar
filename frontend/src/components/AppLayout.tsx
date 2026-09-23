import { NavLink, Outlet, useNavigate } from 'react-router-dom'
import { Briefcase, CalendarDays, FileUser, KanbanSquare, LayoutDashboard, LogOut, Radar } from 'lucide-react'
import { cn } from '@/lib/utils'
import { api } from '@/api/client'
import { toast } from 'sonner'

const NAV = [
  { to: '/', label: '仪表盘', icon: LayoutDashboard, end: true },
  { to: '/jobs', label: '岗位库', icon: Briefcase },
  { to: '/board', label: '投递看板', icon: KanbanSquare },
  { to: '/calendar', label: '日历', icon: CalendarDays },
  { to: '/resumes', label: '简历', icon: FileUser },
]

/**
 * 全局布局：桌面左侧导航，移动端顶部横条（React Router Outlet 渲染子路由）。
 * 响应式取舍：md 断点以下侧边栏折叠为「品牌行 + 可横滑导航条」，
 * 不做汉堡抽屉——五个导航项横排即达，比抽屉少一次点击。
 */
export default function AppLayout() {
  const navigate = useNavigate()

  async function doLogout() {
    try {
      await api.logout()
      toast.success('已退出登录')
      navigate('/login', { replace: true })
    } catch {
      toast.error('退出失败')
    }
  }

  return (
    <div className="flex min-h-screen flex-col md:flex-row">
      {/* 深色导航区：移动端 = 顶部横条；桌面端 = 左侧 w-52 竖栏 */}
      <aside className="w-full shrink-0 bg-slate-800 md:flex md:w-52 md:flex-col">
        {/* 品牌行：移动端同时承载退出按钮（桌面端退出在栏底） */}
        <div className="flex h-12 items-center justify-between px-3 font-semibold text-white md:h-14 md:justify-start md:gap-2 md:px-4">
          <span className="flex items-center gap-2">
            <span className="flex size-7 items-center justify-center rounded-md bg-primary text-primary-foreground">
              <Radar className="size-4" />
            </span>
            JobRadar
          </span>
          <button
            type="button"
            onClick={doLogout}
            aria-label="退出登录"
            className="rounded-md p-1.5 text-slate-300 transition-colors hover:bg-slate-700 hover:text-white md:hidden"
          >
            <LogOut className="size-4" />
          </button>
        </div>
        {/* 导航：移动端可横滑横排；桌面端竖排撑满 */}
        <nav className="flex gap-1 overflow-x-auto px-2 pb-2 md:flex-1 md:flex-col md:gap-0 md:space-y-1 md:overflow-visible md:p-2 md:pb-0">
          {NAV.map(({ to, label, icon: Icon, end }) => (
            <NavLink
              key={to}
              to={to}
              end={end}
              className={({ isActive }) =>
                cn(
                  'flex shrink-0 items-center gap-2 whitespace-nowrap rounded-md px-3 py-2 text-sm transition-colors',
                  isActive
                    ? 'bg-primary text-primary-foreground'
                    : 'text-slate-300 hover:bg-slate-700 hover:text-white'
                )
              }
            >
              <Icon className="size-4" />
              {label}
            </NavLink>
          ))}
        </nav>
        {/* 桌面端栏底退出（移动端已放品牌行） */}
        <div className="hidden border-t border-slate-700 p-2 md:block">
          <button
            type="button"
            onClick={doLogout}
            className="flex w-full items-center gap-2 rounded-md px-3 py-2 text-sm text-slate-300 transition-colors hover:bg-slate-700 hover:text-white"
          >
            <LogOut className="size-4" />
            退出登录
          </button>
        </div>
      </aside>
      {/* 内容区铺浅灰底，让白色卡片从背景中「浮」出来；移动端减小内边距 */}
      <main className="min-w-0 flex-1 bg-muted/30 p-3 md:p-6">
        <Outlet />
      </main>
    </div>
  )
}
