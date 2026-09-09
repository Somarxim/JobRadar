import { useCallback, useEffect, useState } from 'react'
import { toast } from 'sonner'
import { api } from '@/api/client'
import type { ParsedResume, ResumeDetail, ResumeSummary } from '@/api/types'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import {
  Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle,
} from '@/components/ui/dialog'
import { fmtDate } from '@/lib/labels'
import { ErrorState, LoadingState } from '@/components/StatusStates'
import {
  Award, Briefcase, FileText, GraduationCap, RefreshCw, Star, Upload, Wrench,
} from 'lucide-react'

const MAX_FILE_BYTES = 10 * 1024 * 1024

/** 经历类型 → 中文标签与颜色（与 labels.ts 的设计令牌思路一致：语义集中在字典里） */
const EXP_TYPE_META: Record<string, { label: string; className: string }> = {
  internship: { label: '实习', className: 'bg-blue-100 text-blue-700' },
  project: { label: '项目', className: 'bg-violet-100 text-violet-700' },
  competition: { label: '竞赛', className: 'bg-amber-100 text-amber-700' },
  research: { label: '科研', className: 'bg-emerald-100 text-emerald-700' },
}

/**
 * 简历管理页：上传 PDF → AI 结构化（ResumeProfile）→ 确认查看。
 * 默认简历是投递与匹配 Agent 使用的版本（W2 匹配报告的数据源之一）。
 */
export default function ResumesPage() {
  const [items, setItems] = useState<ResumeSummary[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [uploading, setUploading] = useState(false)
  const [detail, setDetail] = useState<ResumeDetail | null>(null)
  const [busyId, setBusyId] = useState<number | null>(null)

  const load = useCallback(() => {
    api.listResumes().then((r) => setItems(r.items)).catch((e) => setError(e.message))
  }, [])
  useEffect(load, [load])

  async function pickFile(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0]
    e.target.value = '' // 允许重选同一文件
    if (!file) return
    if (file.size > MAX_FILE_BYTES) {
      toast.error('文件超过 10MB，请压缩后再传')
      return
    }
    setUploading(true)
    try {
      const r = await api.uploadResume(file)
      if (r.parse_status === 'parsed') {
        toast.success(`「${r.name}」解析完成，请查看 AI 档案确认`)
      } else {
        toast.warning(`「${r.name}」已上传，但未能自动解析（可能是扫描件），可稍后点「重新解析」`)
      }
      load()
    } catch (err) {
      toast.error(err instanceof Error ? err.message : String(err))
    } finally {
      setUploading(false)
    }
  }

  async function reparse(id: number) {
    setBusyId(id)
    try {
      const r = await api.reparseResume(id)
      toast.success(r.parse_status === 'parsed' ? '重新解析完成' : '仍未解析成功（扫描件暂无文字层）')
      load()
    } catch (err) {
      toast.error(err instanceof Error ? err.message : String(err))
    } finally {
      setBusyId(null)
    }
  }

  async function setDefault(id: number) {
    setBusyId(id)
    try {
      await api.setDefaultResume(id)
      toast.success('已设为默认简历')
      load()
    } catch (err) {
      toast.error(err instanceof Error ? err.message : String(err))
    } finally {
      setBusyId(null)
    }
  }

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between gap-2 flex-wrap">
        <div>
          <h1 className="text-xl font-semibold">简历</h1>
          <p className="text-sm text-muted-foreground mt-0.5">
            上传 PDF，AI 自动提取结构化档案，供匹配 Agent 使用。默认简历 = 投递版本。
          </p>
        </div>
        <Button disabled={uploading} asChild>
          <label className="cursor-pointer">
            <Upload className="size-4" />{uploading ? 'AI 解析中…' : '上传简历'}
            <input type="file" accept="application/pdf" className="hidden" onChange={pickFile} />
          </label>
        </Button>
      </div>

      {error && <ErrorState message={error} onRetry={load} />}
      {!error && !items && <LoadingState />}
      {items && items.length === 0 && (
        <Card>
          <CardContent className="py-12 text-center text-muted-foreground">
            <FileText className="size-10 mx-auto mb-3 opacity-40" />
            <p>还没有简历。上传第一份 PDF，AI 会自动提取你的教育、技能与经历档案。</p>
          </CardContent>
        </Card>
      )}

      <div className="grid gap-3 md:grid-cols-2">
        {items?.map((r) => (
          <Card key={r.id} className={r.is_default ? 'border-primary/50' : undefined}>
            <CardHeader className="pb-2">
              <div className="flex items-center justify-between gap-2">
                <CardTitle className="text-base flex items-center gap-2">
                  <FileText className="size-4 text-muted-foreground" />{r.name}
                </CardTitle>
                <div className="flex gap-1.5">
                  {r.is_default && <Badge className="bg-primary/10 text-primary border-primary/30">默认</Badge>}
                  {r.parse_status === 'parsed'
                    ? <Badge variant="outline" className="text-emerald-600 border-emerald-300">已解析</Badge>
                    : <Badge variant="outline" className="text-amber-600 border-amber-300">待解析</Badge>}
                </div>
              </div>
            </CardHeader>
            <CardContent className="space-y-3">
              {r.summary && (
                <p className="text-sm text-muted-foreground line-clamp-2">{r.summary}</p>
              )}
              <div className="flex items-center justify-between">
                <span className="text-xs text-muted-foreground">{fmtDate(r.created_at)}</span>
                <div className="flex gap-1">
                  {r.parse_status === 'parsed' && (
                    <Button variant="outline" size="sm" onClick={() => api.getResume(r.id).then(setDetail)}>
                      查看档案
                    </Button>
                  )}
                  <Button variant="outline" size="sm" disabled={busyId === r.id} onClick={() => reparse(r.id)}>
                    <RefreshCw className="size-3.5" />重新解析
                  </Button>
                  {!r.is_default && (
                    <Button variant="ghost" size="sm" disabled={busyId === r.id} onClick={() => setDefault(r.id)}>
                      <Star className="size-3.5" />设为默认
                    </Button>
                  )}
                </div>
              </div>
            </CardContent>
          </Card>
        ))}
      </div>

      <ResumeDetailDialog detail={detail} onClose={() => setDetail(null)} />
    </div>
  )
}

/** AI 档案确认弹窗：分区展示 ResumeProfile，便于人工核对 LLM 提取结果 */
function ResumeDetailDialog({ detail, onClose }: { detail: ResumeDetail | null; onClose: () => void }) {
  const p = detail?.parsed
  return (
    <Dialog open={detail != null} onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="max-w-2xl max-h-[85vh] overflow-y-auto">
        <DialogHeader>
          <DialogTitle>{p?.name ? `${p.name} 的 AI 档案` : detail?.name}</DialogTitle>
          <DialogDescription>以下内容由 AI 从 PDF 提取，请核对；有误可反馈后重新解析。</DialogDescription>
        </DialogHeader>
        {p && <ResumeProfileView p={p} />}
      </DialogContent>
    </Dialog>
  )
}

function ResumeProfileView({ p }: { p: ParsedResume }) {
  return (
    <div className="space-y-5">
      {p.summary && (
        <p className="text-sm rounded-md bg-violet-50 border border-violet-200 p-3 text-violet-900">{p.summary}</p>
      )}

      {(p.target_positions.length > 0 || p.target_cities.length > 0) && (
        <section>
          <SectionTitle icon={<Briefcase className="size-4 text-blue-500" />} text="求职意向" />
          <div className="flex flex-wrap gap-1.5">
            {p.target_positions.map((t) => <Badge key={t} variant="secondary">{t}</Badge>)}
            {p.target_cities.map((c) => <Badge key={c} variant="outline">{c}</Badge>)}
          </div>
        </section>
      )}

      <section>
        <SectionTitle icon={<GraduationCap className="size-4 text-emerald-500" />} text="教育经历" />
        <div className="space-y-2">
          {p.education.map((e, i) => (
            <div key={i} className="flex items-center gap-2 text-sm flex-wrap">
              <span className="font-medium">{e.school}</span>
              {e.is985 && <Badge className="bg-red-100 text-red-700 border-red-200">985</Badge>}
              {e.is211 && !e.is985 && <Badge className="bg-orange-100 text-orange-700 border-orange-200">211</Badge>}
              <span className="text-muted-foreground">{e.degree} · {e.major}</span>
              <span className="text-xs text-muted-foreground ml-auto">{e.period}</span>
            </div>
          ))}
        </div>
      </section>

      {p.skills.length > 0 && (
        <section>
          <SectionTitle icon={<Wrench className="size-4 text-cyan-500" />} text="技能" />
          <div className="flex flex-wrap gap-1.5">
            {p.skills.map((s) => <Badge key={s} variant="secondary">{s}</Badge>)}
          </div>
        </section>
      )}

      {p.experiences.length > 0 && (
        <section>
          <SectionTitle icon={<Briefcase className="size-4 text-violet-500" />} text="实习与项目" />
          <div className="space-y-3">
            {p.experiences.map((e, i) => {
              const meta = EXP_TYPE_META[e.type] ?? { label: e.type, className: 'bg-slate-100 text-slate-700' }
              return (
                <div key={i} className="rounded-md border p-3 space-y-1.5">
                  <div className="flex items-center gap-2 flex-wrap">
                    <Badge className={meta.className}>{meta.label}</Badge>
                    <span className="font-medium text-sm">{e.org}</span>
                    <span className="text-sm text-muted-foreground">{e.role}</span>
                    <span className="text-xs text-muted-foreground ml-auto">{e.period}</span>
                  </div>
                  <ul className="list-disc pl-5 text-sm text-muted-foreground space-y-0.5">
                    {e.highlights.map((h, j) => <li key={j}>{h}</li>)}
                  </ul>
                </div>
              )
            })}
          </div>
        </section>
      )}

      {p.awards.length > 0 && (
        <section>
          <SectionTitle icon={<Award className="size-4 text-amber-500" />} text="获奖与证书" />
          <ul className="list-disc pl-5 text-sm text-muted-foreground space-y-0.5">
            {p.awards.map((a, i) => <li key={i}>{a}</li>)}
          </ul>
        </section>
      )}
    </div>
  )
}

function SectionTitle({ icon, text }: { icon: React.ReactNode; text: string }) {
  return (
    <h3 className="flex items-center gap-1.5 text-sm font-semibold mb-2">{icon}{text}</h3>
  )
}
