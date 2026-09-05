import { createBrowserRouter, RouterProvider } from 'react-router-dom'
import AppLayout from '@/components/AppLayout'
import { Toaster } from '@/components/ui/sonner'
import DashboardPage from '@/pages/DashboardPage'
import JobsPage from '@/pages/JobsPage'
import JobDetailPage from '@/pages/JobDetailPage'
import BoardPage from '@/pages/BoardPage'
import CalendarPage from '@/pages/CalendarPage'
import ResumesPage from '@/pages/ResumesPage'

const router = createBrowserRouter([
  {
    element: <AppLayout />,
    children: [
      { path: '/', element: <DashboardPage /> },
      { path: '/jobs', element: <JobsPage /> },
      { path: '/jobs/:id', element: <JobDetailPage /> },
      { path: '/board', element: <BoardPage /> },
      { path: '/calendar', element: <CalendarPage /> },
      { path: '/resumes', element: <ResumesPage /> },
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
