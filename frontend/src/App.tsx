import { Suspense, lazy, type ReactNode, useEffect, useState } from 'react'
import { createBrowserRouter, Navigate, Outlet, RouterProvider } from 'react-router-dom'
import AppLayout from '@/components/AppLayout'
import { Toaster } from '@/components/ui/sonner'
import { LoadingState, RouteErrorState } from '@/components/StatusStates'
import { api } from '@/api/client'

// 路由级代码分割：Dashboard 是 recharts 图表的唯一消费者，懒加载后
// 看板/岗位库等首屏不再下载 400KB 的 charts chunk（vite advancedChunks 分组）
const DashboardPage = lazy(() => import('@/pages/DashboardPage'))
const JobsPage = lazy(() => import('@/pages/JobsPage'))
const JobDetailPage = lazy(() => import('@/pages/JobDetailPage'))
const BoardPage = lazy(() => import('@/pages/BoardPage'))
const CalendarPage = lazy(() => import('@/pages/CalendarPage'))
const ResumesPage = lazy(() => import('@/pages/ResumesPage'))
const LoginPage = lazy(() => import('@/pages/LoginPage'))

const lazyPage = (el: ReactNode) => <Suspense fallback={<LoadingState />}>{el}</Suspense>

/** 路由守卫：启动时调 /api/auth/me 校验登录态，未登录跳转登录页 */
function ProtectedLayout() {
  const [checked, setChecked] = useState(false)
  const [authed, setAuthed] = useState(false)

  useEffect(() => {
    api.me()
      .then(() => { setAuthed(true) })
      .catch(() => { setAuthed(false) })
      .finally(() => { setChecked(true) })
  }, [])

  if (!checked) return <LoadingState />
  if (!authed) return <Navigate to="/login" replace />
  return <Outlet />
}

const router = createBrowserRouter([
  {
    path: '/login',
    element: lazyPage(<LoginPage />),
    errorElement: <RouteErrorState />,
  },
  {
    element: <ProtectedLayout />,
    errorElement: <RouteErrorState />,
    children: [
      {
        element: <AppLayout />,
        children: [
          { path: '/', element: lazyPage(<DashboardPage />) },
          { path: '/jobs', element: lazyPage(<JobsPage />) },
          { path: '/jobs/:id', element: lazyPage(<JobDetailPage />) },
          { path: '/board', element: lazyPage(<BoardPage />) },
          { path: '/calendar', element: lazyPage(<CalendarPage />) },
          { path: '/resumes', element: lazyPage(<ResumesPage />) },
        ],
      },
    ],
  },
])

export default function App() {
  return (
    <>
      <RouterProvider router={router} />
      <Toaster />
    </>
  )
}
