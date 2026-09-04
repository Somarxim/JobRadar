import { useCallback, useEffect, useState } from 'react'
import { useParams, Link } from 'react-router-dom'
import { toast } from 'sonner'
import { api } from '@/api/client'
import type { EventItem, JobDetail, Stage } from '@/api/types'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import {
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
} from '@/components/ui/select'
import { CHANNEL_LABELS, COMPANY_TYPE_LABELS, STAGE_META, STAGES, fmtDate, fmtDateTime } from '@/lib/labels'
import { ArrowLeft } from 'lucide-react'

/** 岗位详情：完整 JD + 投递状态 + 阶段流转 + 事件时间线 */
export default function JobDetailPage() {
  const { id } = useParams()
  const jobId = Number(id)
  const [job, setJob] = useState<JobDetail | null>(null)
  const [events, setEvents] = useState<EventItem[]>([])
  const [error, setError] = useState<string | null>(null)
  const [toStage, setToStage] = useState<Stage>('planned')
  const [channel, setChannel] = useState('')
  const [note, setNote] = useState('')
  const [submitting, setSubmitting] = useState(false)

  const load = useCallback(() => {
    api.getJob(jobId).then((j) => {
      setJob(j)
      if (j.application) {
        api.events(j.application.id).then(setEvents).catch(() => setEvents([]))
      } else {
        setEvents([])
      }
    }).catch((e) => setError(e.message))
  }, [jobId])

  useEffect(load, [load])

  async function collect() {
    setSubmitting(true)
    try {
      await api.createApplication({ job_id: jobId })
      toast.success('已收藏到看板')
      load()
    } catch (e) {
      toast.error(e instanceof Error ? e.message : String(e))
    } finally {
      setSubmitting(false)
    }
  }

  async function doTransition() {
    if (!job?.application) return
    setSubmitting(true)
    try {
      await api.transition(job.application.id, {
        to_stage: toStage,
        note: note || undefined,
        channel: channel || undefined,
      })
      toast.success(`已流转到「${STAGE_META[toStage].label}」`)
      setNote('')
      load()
    } catch (e) {
      toast.error(e instanceof Error ? e.message : String(e))
    } finally {
      setSubmitting(false)
    }
  }

  if (error) return <p className="text-destructive">加载失败：{error}</p>
  if (!job) return <p className="text-muted-foreground">加载中…</p>

  const app = job.application

  return (
    <div className="space-y-4 max-w-4xl">
      <Link to="/jobs" className="inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground">
        <ArrowLeft className="size-4" />返回岗位库
      </Link>

      <div className="flex items-start justify-between gap-4 flex-wrap">
        <div>
          <h1 className="text-xl font-semibold">{job.title}</h1>
          <p className="text-muted-foreground mt-1">
            {job.company.name}
            {job.city ? ` · ${job.city}` : ''}
            {job.salary_range ? ` · ${job.salary_range}` : ''}
          </p>
        </div>
        <div className="flex gap-2 items-center">
          <Badge variant="outline">{COMPANY_TYPE_LABELS[job.company.company_type]}</Badge>
          <Badge variant="secondary">{job.source_platform}</Badge>
          {job.deadline && <Badge variant="destructive">DDL {fmtDate(job.deadline)}</Badge>}
        </div>
      </div>

      {/* 投递操作区 */}
      <Card>
        <CardHeader>
          <CardTitle className="text-base">投递状态</CardTitle>
        </CardHeader>
        <CardContent className="space-y-3">
          {app ? (
            <>
              <div className="flex items-center gap-2 text-sm">
                <span>当前阶段：</span>
                <Badge className={STAGE_META[app.stage].className}>{STAGE_META[app.stage].label}</Badge>
                {app.next_action && (
                  <span className="text-muted-foreground">
                    下一步：{app.next_action}（{fmtDateTime(app.next_action_at)}）
                  </span>
                )}
              </div>
              <div className="flex gap-2 items-end flex-wrap">
                <div className="grid gap-1.5">
                  <Label>流转到</Label>
                  <Select value={toStage} onValueChange={(v) => setToStage(v as Stage)}>
                    <SelectTrigger className="w-36"><SelectValue /></SelectTrigger>
                    <SelectContent>
                      {STAGES.filter((s) => s !== app.stage).map((s) => (
                        <SelectItem key={s} value={s}>{STAGE_META[s].label}</SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                </div>
                {toStage === 'applied' && (
                  <div className="grid gap-1.5">
                    <Label>渠道 *</Label>
                    <Select value={channel} onValueChange={setChannel}>
                      <SelectTrigger className="w-36"><SelectValue placeholder="选择渠道" /></SelectTrigger>
                      <SelectContent>
                        {Object.entries(CHANNEL_LABELS).map(([v, l]) => (
                          <SelectItem key={v} value={v}>{l}</SelectItem>
                        ))}
                      </SelectContent>
                    </Select>
                  </div>
                )}
                <div className="grid gap-1.5 flex-1 min-w-40">
                  <Label>备注</Label>
                  <Input value={note} onChange={(e) => setNote(e.target.value)} placeholder="可选" />
                </div>
                <Button onClick={doTransition} disabled={submitting || (toStage === 'applied' && !channel)}>
                  {submitting ? '提交中…' : '流转'}
                </Button>
              </div>
            </>
          ) : (
            <div className="flex items-center gap-3">
              <span className="text-sm text-muted-foreground">尚未收藏该岗位</span>
              <Button size="sm" onClick={collect} disabled={submitting}>
                {submitting ? '收藏中…' : '收藏'}
              </Button>
            </div>
          )}
        </CardContent>
      </Card>

      {/* JD 全文 */}
      <Card>
        <CardHeader><CardTitle className="text-base">职位描述</CardTitle></CardHeader>
        <CardContent>
          {job.jd_text
            ? <pre className="whitespace-pre-wrap text-sm font-sans">{job.jd_text}</pre>
            : <p className="text-sm text-muted-foreground">暂无 JD</p>}
        </CardContent>
      </Card>

      {/* 事件时间线 */}
      {events.length > 0 && (
        <Card>
          <CardHeader><CardTitle className="text-base">流转记录</CardTitle></CardHeader>
          <CardContent>
            <ol className="relative border-l space-y-3 ml-2">
              {events.map((e) => (
                <li key={e.id} className="ml-4">
                  <div className="absolute -left-1.5 mt-1.5 size-3 rounded-full bg-muted-foreground/40" />
                  <div className="text-sm">
                    {e.from_stage
                      ? <>{STAGE_META[e.from_stage].label} → <b>{STAGE_META[e.to_stage].label}</b></>
                      : <>创建：<b>{STAGE_META[e.to_stage].label}</b></>}
                    {e.note && <span className="text-muted-foreground">（{e.note}）</span>}
                  </div>
                  <div className="text-xs text-muted-foreground">{fmtDateTime(e.created_at)}</div>
                </li>
              ))}
            </ol>
          </CardContent>
        </Card>
      )}
    </div>
  )
}
