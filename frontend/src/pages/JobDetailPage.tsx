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
import JobEditDialog from '@/components/JobEditDialog'
import MatchReportCard from '@/components/MatchReportCard'
import TransitionConfirmDialog, { type RollbackRequest } from '@/components/TransitionConfirmDialog'
import {
  CHANNEL_LABELS, COMPANY_TYPE_META, STAGE_META, STAGES,
  deadlineClass, deadlineCountdown, fmtDate, fmtDateTime, isRollback,
} from '@/lib/labels'
import { cn } from '@/lib/utils'
import { ArrowLeft, ExternalLink } from 'lucide-react'

/**
 * 岗位详情布局（按用户反馈调整）：
 * - 左主栏（2/3）：岗位信息（高度稳定，概览置顶）→ 投递状态 → 职位描述（高度不定，放最后）
 * - 右侧边栏（1/3）：AI 匹配报告占位（固定高度，在上）→ 流转记录（高度不定，在下，
 *   避免记录变长把匹配报告顶出首屏）
 */
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
  // 待确认的回退流转（非 null 时弹确认框）
  const [rollbackReq, setRollbackReq] = useState<RollbackRequest | null>(null)

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

  /** 实际执行流转（已过了回退确认这一关） */
  async function doTransition(target: Stage) {
    if (!job?.application) return
    setSubmitting(true)
    try {
      await api.transition(job.application.id, {
        to_stage: target,
        note: note || undefined,
        channel: channel || undefined,
      })
      toast.success(`已流转到「${STAGE_META[target].label}」`)
      setNote('')
      load()
    } catch (e) {
      toast.error(e instanceof Error ? e.message : String(e))
    } finally {
      setSubmitting(false)
    }
  }

  /** 流转入口：主线回退先弹确认框（建议归档），其余直接执行 */
  function requestTransition() {
    if (!job?.application) return
    if (isRollback(job.application.stage, toStage)) {
      setRollbackReq({ fromStage: job.application.stage, toStage })
      return
    }
    doTransition(toStage)
  }

  /** 确认框选择：rollback=按原计划回退；rejected/withdrawn=改为归档终态 */
  function onRollbackChoose(choice: 'rollback' | 'rejected' | 'withdrawn') {
    if (!rollbackReq) return
    const target = choice === 'rollback' ? rollbackReq.toStage : choice
    setRollbackReq(null)
    doTransition(target)
  }

  if (error) return <p className="text-destructive">加载失败：{error}</p>
  if (!job) return <p className="text-muted-foreground">加载中…</p>

  const app = job.application
  const typeMeta = COMPANY_TYPE_META[job.company.company_type]

  return (
    <div className="space-y-4">
      <Link to="/jobs" className="inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground">
        <ArrowLeft className="size-4" />返回岗位库
      </Link>

      {/* 头部：标题 + 元信息 + 操作 */}
      <div className="flex items-start justify-between gap-4 flex-wrap">
        <div>
          <h1 className="text-2xl font-semibold">{job.title}</h1>
          <p className="text-muted-foreground mt-1">
            {job.company.name}
            {job.city ? ` · ${job.city}` : ''}
            {job.salary_range ? ` · ${job.salary_range}` : ''}
          </p>
          <div className="flex gap-2 items-center mt-2 flex-wrap">
            <Badge className={typeMeta?.className} variant="outline">{typeMeta?.label}</Badge>
            <Badge variant="secondary">{job.source_platform}</Badge>
            {job.deadline && (
              <Badge variant="outline" className={cn('border-current/30', deadlineClass(job.deadline))}>
                DDL {fmtDate(job.deadline)}（{deadlineCountdown(job.deadline)}）
              </Badge>
            )}
          </div>
        </div>
        <JobEditDialog jobId={jobId} onDone={load} />
      </div>

      <div className="grid gap-4 lg:grid-cols-3 items-start">
        {/* 左主栏：岗位信息 → 投递状态 → 职位描述（JD 高度不定，放最后） */}
        <div className="space-y-4 lg:col-span-2 min-w-0">
          {/* 关键信息：定义列表样式，高度稳定，置顶作为概览 */}
          <Card>
            <CardHeader><CardTitle className="text-base">岗位信息</CardTitle></CardHeader>
            <CardContent>
              <dl className="grid grid-cols-2 sm:grid-cols-[5rem_1fr_5rem_1fr] gap-y-2 gap-x-4 text-sm">
                <dt className="text-muted-foreground">公司</dt>
                <dd className="flex items-center gap-1.5">
                  <span className={cn('size-2 rounded-full', typeMeta?.dot)} />
                  {job.company.name}
                </dd>
                <dt className="text-muted-foreground">公司类型</dt>
                <dd>{typeMeta?.label}</dd>
                <dt className="text-muted-foreground">城市</dt>
                <dd>{job.city ?? '—'}</dd>
                <dt className="text-muted-foreground">薪资</dt>
                <dd>{job.salary_range ?? '—'}</dd>
                <dt className="text-muted-foreground">投递截止</dt>
                <dd className={deadlineClass(job.deadline)}>
                  {fmtDate(job.deadline)}
                  {job.deadline && <span className="ml-1 text-xs">（{deadlineCountdown(job.deadline)}）</span>}
                </dd>
                <dt className="text-muted-foreground">发布日期</dt>
                <dd>{fmtDate(job.publish_date)}</dd>
                <dt className="text-muted-foreground">来源</dt>
                <dd>{job.source_platform}</dd>
                <dt className="text-muted-foreground">收录时间</dt>
                <dd>{fmtDate(job.created_at)}</dd>
                {job.source_url && (
                  <>
                    <dt className="text-muted-foreground">链接</dt>
                    <dd className="sm:col-span-3">
                      <a
                        href={job.source_url}
                        target="_blank"
                        rel="noreferrer"
                        className="inline-flex items-center gap-1 text-primary hover:underline break-all"
                      >
                        查看原帖<ExternalLink className="size-3" />
                      </a>
                    </dd>
                  </>
                )}
              </dl>
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle className="text-base">投递状态</CardTitle>
            </CardHeader>
            <CardContent className="space-y-3">
              {app ? (
                <>
                  <div className="flex items-center gap-2 text-sm flex-wrap">
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
                    <Button
                      onClick={requestTransition}
                      disabled={submitting || (toStage === 'applied' && !channel)}
                    >
                      {submitting ? '提交中…' : '流转'}
                    </Button>
                  </div>
                </>
              ) : (
                <div className="flex items-center gap-3">
                  <span className="text-sm text-muted-foreground">尚未收藏该岗位</span>
                  <Button size="sm" onClick={collect} disabled={submitting}>
                    {submitting ? '收藏中…' : '收藏到看板'}
                  </Button>
                </div>
              )}
            </CardContent>
          </Card>

          <Card>
            <CardHeader><CardTitle className="text-base">职位描述</CardTitle></CardHeader>
            <CardContent>
              {job.jd_text
                ? <pre className="whitespace-pre-wrap text-sm font-sans leading-relaxed">{job.jd_text}</pre>
                : <p className="text-sm text-muted-foreground">暂无 JD，可点击右上角「编辑」补充</p>}
            </CardContent>
          </Card>
        </div>

        {/* 右侧边栏：AI 匹配报告（固定高度在上）→ 流转记录（高度不定在下） */}
        <div className="space-y-4">
          <MatchReportCard jobId={jobId} report={job.latest_match_report} onGenerated={load} />

          <Card>
            <CardHeader><CardTitle className="text-base">流转记录</CardTitle></CardHeader>
            <CardContent>
              {events.length === 0 ? (
                <p className="text-sm text-muted-foreground">收藏岗位后，每次阶段流转都会在此留痕</p>
              ) : (
                <ol className="relative border-l space-y-3 ml-2">
                  {events.map((e) => (
                    <li key={e.id} className="ml-4">
                      {/* 时间线圆点用目标阶段的颜色，扫一眼即可看出走向 */}
                      <div className={cn('absolute -left-1.5 mt-1.5 size-3 rounded-full', STAGE_META[e.to_stage].dot)} />
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
              )}
            </CardContent>
          </Card>
        </div>
      </div>

      <TransitionConfirmDialog
        request={rollbackReq}
        submitting={submitting}
        onChoose={onRollbackChoose}
        onCancel={() => setRollbackReq(null)}
      />
    </div>
  )
}
