import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { toast } from 'sonner'
import { api } from '@/api/client'
import type { JobSummary } from '@/api/types'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import {
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
} from '@/components/ui/select'
import {
  Dialog, DialogContent, DialogDescription, DialogFooter,
  DialogHeader, DialogTitle,
} from '@/components/ui/dialog'
import {
  Table, TableBody, TableCell, TableHead, TableHeader, TableRow,
} from '@/components/ui/table'
import { COMPANY_TYPE_LABELS, COMPANY_TYPE_META, STAGE_META, deadlineClass, fmtDate } from '@/lib/labels'
import { cn } from '@/lib/utils'
import JobCreateDialog from '@/components/JobCreateDialog'
import JobEditDialog from '@/components/JobEditDialog'
import IngestDialog from '@/components/IngestDialog'
import { STAGES } from '@/lib/labels'
import { ErrorState, LoadingState } from '@/components/StatusStates'
import { Trash2 } from 'lucide-react'

/** 岗位库：搜索筛选 + 表格 + 手动录入/粘贴导入（roadmap W1 核心页） */
export default function JobsPage() {
  const [q, setQ] = useState('')
  const [companyType, setCompanyType] = useState('')
  const [stage, setStage] = useState('')
  const [sort, setSort] = useState('created_desc')
  const [page, setPage] = useState(1)
  const [data, setData] = useState<{ items: JobSummary[]; total: number } | null>(null)
  const [error, setError] = useState<string | null>(null)
  // 批量选择（跨页保留，翻页不清空——清理脏数据常要翻页挑）
  const [selected, setSelected] = useState<Set<number>>(new Set())
  const [confirming, setConfirming] = useState(false)
  const [deleting, setDeleting] = useState(false)

  const load = useCallback(() => {
    api.listJobs({
      q: q || undefined,
      company_type: companyType || undefined,
      stage: stage || undefined,
      sort,
      page,
      size: 20,
    }).then(setData).catch((e) => setError(e.message))
  }, [q, companyType, stage, sort, page])

  useEffect(load, [load])

  const totalPages = data ? Math.max(1, Math.ceil(data.total / 20)) : 1

  const pageIds = data?.items.map((j) => j.id) ?? []
  const allPageSelected = pageIds.length > 0 && pageIds.every((id) => selected.has(id))

  const toggle = (id: number) => {
    setSelected((prev) => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })
  }
  const togglePage = () => {
    setSelected((prev) => {
      const next = new Set(prev)
      if (allPageSelected) pageIds.forEach((id) => next.delete(id))
      else pageIds.forEach((id) => next.add(id))
      return next
    })
  }

  const batchDelete = async () => {
    setDeleting(true)
    try {
      const res = await api.batchDeleteJobs([...selected])
      const parts = [`已删除 ${res.deleted} 个`]
      if (res.archived > 0) parts.push(`${res.archived} 个已有投递/推荐记录，改为归档保留`)
      if (res.missing > 0) parts.push(`${res.missing} 个不存在`)
      toast.success(parts.join('，'))
      setSelected(new Set())
      setConfirming(false)
      load()
    } catch (e) {
      toast.error(e instanceof Error ? e.message : '删除失败')
    } finally {
      setDeleting(false)
    }
  }

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between gap-2 flex-wrap">
        <h1 className="text-xl font-semibold">岗位库</h1>
        <div className="flex gap-2">
          <IngestDialog onDone={load} />
          <JobCreateDialog onDone={load} />
        </div>
      </div>

      {/* 搜索筛选栏 */}
      <div className="flex gap-2 flex-wrap">
        <Input
          placeholder="搜索公司 / 岗位 / 城市…"
          value={q}
          onChange={(e) => { setQ(e.target.value); setPage(1) }}
          className="w-64"
        />
        <Select value={companyType} onValueChange={(v) => { setCompanyType(v === '__all' ? '' : v); setPage(1) }}>
          <SelectTrigger className="w-40"><SelectValue placeholder="公司类型" /></SelectTrigger>
          <SelectContent>
            <SelectItem value="__all">全部类型</SelectItem>
            {Object.entries(COMPANY_TYPE_LABELS).map(([v, l]) => (
              <SelectItem key={v} value={v}>{l}</SelectItem>
            ))}
          </SelectContent>
        </Select>
        <Select value={stage} onValueChange={(v) => { setStage(v === '__all' ? '' : v); setPage(1) }}>
          <SelectTrigger className="w-40"><SelectValue placeholder="投递阶段" /></SelectTrigger>
          <SelectContent>
            <SelectItem value="__all">全部阶段</SelectItem>
            <SelectItem value="none">未收藏</SelectItem>
            {STAGES.map((s) => (
              <SelectItem key={s} value={s}>{STAGE_META[s].label}</SelectItem>
            ))}
          </SelectContent>
        </Select>
        <Select value={sort} onValueChange={setSort}>
          <SelectTrigger className="w-40"><SelectValue /></SelectTrigger>
          <SelectContent>
            <SelectItem value="created_desc">最新收录</SelectItem>
            <SelectItem value="deadline_asc">DDL 从近到远</SelectItem>
          </SelectContent>
        </Select>
      </div>

      {error && <ErrorState message={error} onRetry={load} />}
      {!error && !data && <LoadingState />}

      {/* 批量操作栏：有选中时浮现在筛选栏下方 */}
      {selected.size > 0 && (
        <div className="flex items-center gap-3 rounded-md border bg-muted/40 px-3 py-2 text-sm">
          <span>已选 <b>{selected.size}</b> 个岗位</span>
          <Button variant="destructive" size="sm" onClick={() => setConfirming(true)}>
            <Trash2 className="size-3.5" />删除选中
          </Button>
          <Button variant="ghost" size="sm" onClick={() => setSelected(new Set())}>取消选择</Button>
        </div>
      )}

      <Table>
        <TableHeader>
          <TableRow>
            <TableHead className="w-8">
              <input
                type="checkbox"
                className="size-4 align-middle accent-primary"
                checked={allPageSelected}
                onChange={togglePage}
                aria-label="全选本页"
              />
            </TableHead>
            <TableHead>公司</TableHead>
            <TableHead>岗位</TableHead>
            <TableHead>城市</TableHead>
            <TableHead>薪资</TableHead>
            <TableHead>DDL</TableHead>
            <TableHead>阶段</TableHead>
            <TableHead>来源</TableHead>
            <TableHead className="w-12">操作</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {data?.items.map((j) => (
            <TableRow key={j.id} className={cn('cursor-pointer', selected.has(j.id) && 'bg-muted/50')}>
              <TableCell onClick={(e) => e.stopPropagation()}>
                <input
                  type="checkbox"
                  className="size-4 align-middle accent-primary"
                  checked={selected.has(j.id)}
                  onChange={() => toggle(j.id)}
                  aria-label={`选择 ${j.company.name} ${j.title}`}
                />
              </TableCell>
              <TableCell className="font-medium">
                {/* 公司类型色点：低成本增加表格色彩层次，颜色语义与详情页徽章一致 */}
                <span className={cn('mr-1.5 inline-block size-2 rounded-full align-middle', COMPANY_TYPE_META[j.company.company_type]?.dot ?? 'bg-zinc-300')} />
                <Link to={`/jobs/${j.id}`} className="hover:underline">{j.company.name}</Link>
              </TableCell>
              <TableCell className="max-w-72 truncate">
                <Link to={`/jobs/${j.id}`} className="hover:underline">{j.title}</Link>
              </TableCell>
              <TableCell>{j.city ?? '—'}</TableCell>
              <TableCell>{j.salary_range ?? '—'}</TableCell>
              {/* DDL 按紧急度着色：≤3 天红 / ≤7 天橙 / 过期删除线（阈值集中在 labels.ts） */}
              <TableCell className={deadlineClass(j.deadline)}>{fmtDate(j.deadline)}</TableCell>
              <TableCell>
                {j.application_stage
                  ? <Badge className={STAGE_META[j.application_stage].className}>{STAGE_META[j.application_stage].label}</Badge>
                  : <span className="text-muted-foreground text-xs">未收藏</span>}
              </TableCell>
              <TableCell className="text-muted-foreground text-xs">{j.source_platform}</TableCell>
              <TableCell>
                <JobEditDialog jobId={j.id} onDone={load} iconOnly />
              </TableCell>
            </TableRow>
          ))}
          {data && data.items.length === 0 && (
            <TableRow>
              <TableCell colSpan={9} className="text-center text-muted-foreground py-8">
                暂无岗位，点击右上角「手动录入」或「粘贴导入」添加
              </TableCell>
            </TableRow>
          )}
        </TableBody>
      </Table>

      {/* 批量删除确认：说明两种命运（无关联真删 / 有关联归档），防误删 */}
      <Dialog open={confirming} onOpenChange={(open) => !open && setConfirming(false)}>
        <DialogContent className="max-w-md">
          <DialogHeader>
            <DialogTitle className="flex items-center gap-2">
              <Trash2 className="size-5 text-destructive" />
              删除选中的 {selected.size} 个岗位？
            </DialogTitle>
            <DialogDescription>
              没有投递/匹配/推荐记录的岗位会被彻底删除；
              已有记录的岗位会改为归档（从列表消失但保留历史数据），避免误删你的投递进度。
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button variant="ghost" onClick={() => setConfirming(false)} disabled={deleting}>取消</Button>
            <Button variant="destructive" onClick={batchDelete} disabled={deleting}>
              {deleting ? '删除中…' : '确认删除'}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {data && data.total > 20 && (
        <div className="flex items-center justify-end gap-2 text-sm">
          <Button variant="outline" size="sm" disabled={page <= 1} onClick={() => setPage(page - 1)}>上一页</Button>
          <span className="text-muted-foreground">{page} / {totalPages}（共 {data.total} 条）</span>
          <Button variant="outline" size="sm" disabled={page >= totalPages} onClick={() => setPage(page + 1)}>下一页</Button>
        </div>
      )}
    </div>
  )
}
