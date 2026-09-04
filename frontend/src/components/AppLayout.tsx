import { NavLink, Outlet } from 'react-router-dom'
import { Briefcase, CalendarDays, KanbanSquare, LayoutDashboard, Radar } from 'lucide-react'
import { cn } from '@/lib/utils'

const NAV = [
  { to: '/', label: '仪表盘', icon: LayoutDashboard, end: true },
  { to: '/jobs', label: '岗位库', icon: Briefcase },
  { to: '/board', label: '投递看板', icon: KanbanSquare },
  { to: '/calendar', label: '日历', icon: CalendarDays },
]

/** 全局布局：左侧导航 + 右侧内容区（React Router Outlet 渲染子路由） */
export default function AppLayout() {
  return (
    <div className="flex min-h-screen">
      <aside className="w-52 shrink-0 border-r bg-card flex flex-col">
        <div className="flex items-center gap-2 px-4 h-14 border-b font-semibold">
          <Radar className="size-5" />
          JobRadar
        </div>
        <nav className="flex-1 p-2 space-y-1">
          {NAV.map(({ to, label, icon: Icon, end }) => (
            <NavLink
              key={to}
              to={to}
              end={end}
              className={({ isActive }) =>
                cn(
                  'flex items-center gap-2 rounded-md px-3 py-2 text-sm transition-colors',
                  isActive
                    ? 'bg-primary text-primary-foreground'
                    : 'text-muted-foreground hover:bg-accent hover:text-accent-foreground'
                )
              }
            >
              <Icon className="size-4" />
              {label}
            </NavLink>
          ))}
        </nav>
      </aside>
      <main className="flex-1 min-w-0 p-6">
        <Outlet />
      </main>
    </div>
  )
}
