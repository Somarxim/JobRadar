import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from '@/api/client'
import type { JobSummary } from '@/api/types'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import {
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
} from '@/components/ui/select'
import {
  Table, TableBody, TableCell, TableHead, TableHeader, TableRow,
} from '@/components/ui/table'
import { COMPANY_TYPE_LABELS, COMPANY_TYPE_META, STAGE_META, deadlineClass, fmtDate } from '@/lib/labels'
import { cn } from '@/lib/utils'
import JobCreateDialog from '@/components/JobCreateDialog'
import JobEditDialog from '@/components/JobEditDialog'
import IngestDialog from '@/components/IngestDialog'
import { STAGES } from '@/lib/labels'

/** 岗位库：搜索筛选 + 表格 + 手动录入/粘贴导入（roadmap W1 核心页） */
export default function JobsPage() {
  const [q, setQ] = useState('')
  const [companyType, setCompanyType] = useState('')
  const [stage, setStage] = useState('')
  const [sort, setSort] = useState('created_desc')
  const [page, setPage] = useState(1)
  const [data, setData] = useState<{ items: JobSummary[]; total: number } | null>(null)
  const [error, setError] = useState<string | null>(null)

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

      {error && <p className="text-destructive text-sm">加载失败:{error}</p>}

      <Table>
        <TableHeader>
          <TableRow>
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
            <TableRow key={j.id} className="cursor-pointer">
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
              <TableCell colSpan={8} className="text-center text-muted-foreground py-8">
                暂无岗位，点击右上角「手动录入」或「粘贴导入」添加
              </TableCell>
            </TableRow>
          )}
        </TableBody>
      </Table>

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
