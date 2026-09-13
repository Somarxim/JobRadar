import { useCallback, useEffect, useMemo, useRef, useState, Fragment } from 'react'
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
import { COMPANY_TYPE_LABELS, COMPANY_TYPE_META, STAGE_META, TIER_META, deadlineClass, fmtDate } from '@/lib/labels'
import { cn } from '@/lib/utils'
import JobCreateDialog from '@/components/JobCreateDialog'
import JobEditDialog from '@/components/JobEditDialog'
import IngestDialog from '@/components/IngestDialog'
import { STAGES } from '@/lib/labels'
import { ErrorState, LoadingState } from '@/components/StatusStates'
import { ChevronDown, ListTree, Trash2 } from 'lucide-react'

/** 岗位库：搜索筛选 + 表格 + 手动录入/粘贴导入（roadmap W1 核心页） */
export default function JobsPage() {
  const [q, setQ] = useState('')
  const [companyType, setCompanyType] = useState('')
  const [tier, setTier] = useState('')
  const [stage, setStage] = useState('')
  const [sort, setSort] = useState('created_desc')
  const [page, setPage] = useState(1)
  const [data, setData] = useState<{ items: JobSummary[]; total: number } | null>(null)
  const [error, setError] = useState<string | null>(null)
  // 批量选择（跨页保留，翻页不清空——清理脏数据常要翻页挑）
  const [selected, setSelected] = useState<Set<number>>(new Set())
  const [confirming, setConfirming] = useState(false)
  const [deleting, setDeleting] = useState(false)
  // 同公司折叠分组：默认开（解决「同公司岗位刷屏」），开关状态跨会话记忆
  const [grouped, setGrouped] = useState(() => localStorage.getItem('jobs.grouped') !== '0')
  const [collapsed, setCollapsed] = useState<Set<number>>(new Set())
  // collapsing：退出动画播放中的组（子行还在但正淡出），150ms 后才真正写入 collapsed。
  // 双 Set 拆分让「视觉消失」与「DOM 卸载」错开，table 行无 height 过渡也能有完整双向动画。
  const [collapsing, setCollapsing] = useState<Set<number>>(new Set())
  const collapseTimers = useRef<Map<number, number>>(new Map())
  useEffect(() => () => collapseTimers.current.forEach((t) => window.clearTimeout(t)), [])

  const toggleGrouped = () => {
    setGrouped((g) => {
      localStorage.setItem('jobs.grouped', g ? '0' : '1')
      return !g
    })
  }
  const toggleCollapse = (companyId: number) => {
    // 取消该组进行中的折叠定时器（快速连点 = 反悔，不折叠）
    const pending = collapseTimers.current.get(companyId)
    if (pending) {
      window.clearTimeout(pending)
      collapseTimers.current.delete(companyId)
    }
    if (collapsed.has(companyId) || collapsing.has(companyId)) {
      setCollapsing((prev) => { const n = new Set(prev); n.delete(companyId); return n })
      setCollapsed((prev) => { const n = new Set(prev); n.delete(companyId); return n })
    } else {
      setCollapsing((prev) => new Set(prev).add(companyId))
      const t = window.setTimeout(() => {
        setCollapsed((prev) => new Set(prev).add(companyId))
        setCollapsing((prev) => { const n = new Set(prev); n.delete(companyId); return n })
        collapseTimers.current.delete(companyId)
      }, 150) // 与 animate-out duration-150 对齐
      collapseTimers.current.set(companyId, t)
    }
  }

  // 分组：本页内按公司聚簇（保序，组间按首次出现排序），仅 ≥2 岗位的组出折叠头。
  // 注意这是「页内分组」而非服务端聚合——跨页的同公司岗位会各自成组，纯排序诉求可关掉分组
  const groups = useMemo(() => {
    if (!data) return []
    const map = new Map<number, { company: JobSummary['company']; jobs: JobSummary[] }>()
    for (const j of data.items) {
      const g = map.get(j.company.id)
      if (g) g.jobs.push(j)
      else map.set(j.company.id, { company: j.company, jobs: [j] })
    }
    return [...map.values()]
  }, [data])

  const load = useCallback(() => {
    api.listJobs({
      q: q || undefined,
      company_type: companyType || undefined,
      stage: stage || undefined,
      tier: tier || undefined,
      sort,
      page,
      size: 20,
    }).then(setData).catch((e) => setError(e.message))
  }, [q, companyType, stage, tier, sort, page])

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

  /** 单个岗位行（分组/平铺两种渲染共用）。indent=组内子行（层级线+缩进）；lone=分组模式下的单岗位公司（弱左边线对齐视觉节奏） */
  const renderJobRow = (j: JobSummary, opts?: { indent?: boolean; lone?: boolean; index?: number; exiting?: boolean }) => (
    <TableRow
      key={j.id}
      className={cn(
        'cursor-pointer',
        selected.has(j.id) && 'bg-muted/50',
        // 分组子行：进入时阶梯淡入（index*30ms 级联），折叠时快速淡出
        opts?.indent && !opts.exiting && 'animate-in fade-in slide-in-from-top-1 duration-200 fill-mode-backwards',
        opts?.exiting && 'animate-out fade-out slide-out-to-top-1 duration-150 fill-mode-forwards',
      )}
      style={opts?.indent && !opts.exiting && opts.index !== undefined
        ? { animationDelay: `${opts.index * 30}ms` }
        : undefined}
    >
      <TableCell
        onClick={(e) => e.stopPropagation()}
        className={cn(
          opts?.indent && 'relative pl-6',
          opts?.lone && 'border-l-2 border-l-muted/20 pl-3',
        )}
      >
        {/* 层级导引线：与组头 chevron 纵向对齐，形成「组头 → 子行」的树状归属关系 */}
        {opts?.indent && (
          <span aria-hidden className="absolute left-2.5 top-1/2 h-4 w-px -translate-y-1/2 bg-border/80" />
        )}
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
        {j.company.tier !== 'none' && TIER_META[j.company.tier] && (
          <Badge variant="outline" className={cn('ml-1.5 px-1 py-0 text-[10px]', TIER_META[j.company.tier].className)}>
            {TIER_META[j.company.tier].label}
          </Badge>
        )}
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
  )

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
        <Select value={tier} onValueChange={(v) => { setTier(v === '__all' ? '' : v); setPage(1) }}>
          <SelectTrigger className="w-32"><SelectValue placeholder="公司分级" /></SelectTrigger>
          <SelectContent>
            <SelectItem value="__all">全部分级</SelectItem>
            <SelectItem value="none">未分级</SelectItem>
            {Object.entries(TIER_META).map(([v, m]) => (
              <SelectItem key={v} value={v}>{m.label}</SelectItem>
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
        {/* 分组开关：同公司岗位聚簇+可折叠，防「同公司刷屏」；纯排序浏览时可关闭 */}
        <Button
          variant={grouped ? 'secondary' : 'outline'}
          size="sm"
          className="h-9"
          onClick={toggleGrouped}
          title="本页内同公司 ≥2 个岗位时折叠成组"
        >
          <ListTree className="size-3.5" />按公司分组
        </Button>
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
          {grouped
            ? groups.map((g, groupIdx) => {
              const isCollapsed = collapsed.has(g.company.id)
              const isCollapsing = collapsing.has(g.company.id)
              const multi = g.jobs.length >= 2
              const meta = COMPANY_TYPE_META[g.company.company_type]
              return (
              <Fragment key={g.company.id}>
                {/* 组间呼吸缝：非首组前插一空隙行，避免不同公司的组头/子行粘连成一片 */}
                {groupIdx > 0 && <tr aria-hidden className="h-1 border-0 hover:bg-transparent" />}
                {/* 组头：仅同页 ≥2 岗位的公司出现（单岗位公司不出头行，避免双重噪音） */}
                {multi && (
                  <TableRow className={isCollapsed
                    ? 'bg-muted/30 hover:bg-muted/30'
                    : 'bg-muted/60 hover:bg-muted/60'}>
                    <TableCell colSpan={9} className={cn('border-l-2 py-2.5 transition-colors',
                      meta?.band ?? 'border-l-primary', isCollapsed && 'opacity-50')}>
                      <button
                        type="button"
                        className="-ml-1.5 flex w-full items-center gap-2 rounded-sm px-1.5 py-1 text-left transition-colors active:bg-primary/[0.08] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-1"
                        onClick={() => toggleCollapse(g.company.id)}
                        aria-label={`${isCollapsed ? '展开' : '折叠'} ${g.company.name}`}
                        title={isCollapsed ? `点击展开，显示 ${g.jobs.length} 个岗位` : `点击折叠，隐藏 ${g.jobs.length} 个岗位`}
                      >
                        {/* chevron 容器化：圆角小按钮提供「可点」affordance；单图标旋转替代双图标切换，反馈连续 */}
                        <span className={cn(
                          'inline-flex size-6 items-center justify-center rounded-md border border-border/60 bg-background/70 shadow-sm transition-transform duration-200',
                          isCollapsed && '-rotate-90',
                        )}>
                          <ChevronDown className="size-4 text-primary" />
                        </span>
                        <span className={cn('inline-block size-2 rounded-full', meta?.dot ?? 'bg-zinc-300')} />
                        <span className="font-semibold text-sm text-foreground">{g.company.name}</span>
                        {g.company.tier !== 'none' && TIER_META[g.company.tier] && (
                          <Badge variant="outline" className={cn('px-1 py-0 text-[10px]', TIER_META[g.company.tier].className)}>
                            {TIER_META[g.company.tier].label}
                          </Badge>
                        )}
                        <span className="ml-auto flex items-center gap-1.5">
                          <span className="text-xs text-muted-foreground">{meta?.label ?? '其他'}</span>
                          <Badge variant="secondary" className="text-[10px] font-normal">{g.jobs.length} 个岗位</Badge>
                          {isCollapsed && (
                            <Badge variant="outline" className="border-dashed text-[10px] font-normal text-muted-foreground">
                              已折叠
                            </Badge>
                          )}
                        </span>
                      </button>
                    </TableCell>
                  </TableRow>
                )}
                {(!isCollapsed || !multi) && g.jobs.map((j, idx) => renderJobRow(j, {
                  indent: multi,
                  lone: !multi,
                  index: idx,
                  exiting: isCollapsing,
                }))}
              </Fragment>
              )
            })
            : data?.items.map((j) => renderJobRow(j))}
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
