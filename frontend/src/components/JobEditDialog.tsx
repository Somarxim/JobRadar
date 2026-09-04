import { useEffect, useState } from 'react'
import { toast } from 'sonner'
import { api } from '@/api/client'
import { Button } from '@/components/ui/button'
import {
  Dialog, DialogContent, DialogDescription, DialogFooter,
  DialogHeader, DialogTitle, DialogTrigger,
} from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'
import { Pencil } from 'lucide-react'

/**
 * 岗位编辑对话框（PATCH /api/jobs/{id}）。
 *
 * 设计要点：
 * - 打开时才拉取详情预填，避免在列表页为每行都请求一次 JD 全文
 * - 公司名只读：公司是独立实体（多岗位复用），改名会牵连同公司其他岗位，
 *   故 W1 契约不提供入口；真要改需在「公司管理」中做（后续迭代）
 * - 空字符串转 null 提交：后端 PATCH 语义为「null = 保持原值」（dirty checking），
 *   直接传 "" 会把字段清空成空串而非跳过
 */
export default function JobEditDialog({
  jobId,
  onDone,
  iconOnly = false,
}: {
  jobId: number
  onDone: () => void
  /** 表格操作列用图标按钮，详情页用带文字的按钮 */
  iconOnly?: boolean
}) {
  const [open, setOpen] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [companyName, setCompanyName] = useState('')
  const [form, setForm] = useState({
    title: '', city: '', salary_range: '', deadline: '', source_url: '', jd_text: '', active: true,
  })

  useEffect(() => {
    if (!open) return
    api.getJob(jobId)
      .then((j) => {
        setCompanyName(j.company.name)
        setForm({
          title: j.title,
          city: j.city ?? '',
          salary_range: j.salary_range ?? '',
          deadline: j.deadline ?? '',
          source_url: j.source_url ?? '',
          jd_text: j.jd_text ?? '',
          active: j.active,
        })
      })
      .catch((e) => toast.error(e instanceof Error ? e.message : String(e)))
  }, [open, jobId])

  const set = (k: keyof typeof form) => (e: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement>) =>
    setForm({ ...form, [k]: e.target.value })

  async function submit() {
    setSubmitting(true)
    try {
      await api.updateJob(jobId, {
        title: form.title,
        city: form.city || null,
        salary_range: form.salary_range || null,
        deadline: form.deadline || null,
        source_url: form.source_url || null,
        jd_text: form.jd_text || null,
        active: form.active,
      })
      toast.success('修改已保存')
      setOpen(false)
      onDone()
    } catch (e) {
      toast.error(e instanceof Error ? e.message : String(e))
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild>
        {iconOnly ? (
          <Button variant="ghost" size="icon" title="编辑岗位"><Pencil className="size-4" /></Button>
        ) : (
          <Button variant="outline"><Pencil className="size-4" />编辑</Button>
        )}
      </DialogTrigger>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>编辑岗位</DialogTitle>
          <DialogDescription>{companyName}（公司为共享实体，名称不在此处修改）</DialogDescription>
        </DialogHeader>
        <div className="grid gap-3">
          <div className="grid gap-1.5">
            <Label htmlFor="et">岗位名称 *</Label>
            <Input id="et" value={form.title} onChange={set('title')} />
          </div>
          <div className="grid grid-cols-2 gap-3">
            <div className="grid gap-1.5">
              <Label htmlFor="ecity">城市</Label>
              <Input id="ecity" value={form.city} onChange={set('city')} />
            </div>
            <div className="grid gap-1.5">
              <Label htmlFor="esalary">薪资范围</Label>
              <Input id="esalary" value={form.salary_range} onChange={set('salary_range')} />
            </div>
          </div>
          <div className="grid grid-cols-2 gap-3">
            <div className="grid gap-1.5">
              <Label htmlFor="eddl">投递截止</Label>
              <Input id="eddl" type="date" value={form.deadline} onChange={set('deadline')} />
            </div>
            <div className="grid gap-1.5">
              <Label htmlFor="eurl">来源链接</Label>
              <Input id="eurl" value={form.source_url} onChange={set('source_url')} placeholder="https://…" />
            </div>
          </div>
          <div className="grid gap-1.5">
            <Label htmlFor="ejd">JD 全文</Label>
            <Textarea id="ejd" rows={5} value={form.jd_text} onChange={set('jd_text')} />
          </div>
          <label className="flex items-center gap-2 text-sm text-muted-foreground select-none">
            <input
              type="checkbox"
              checked={!form.active}
              onChange={(e) => setForm({ ...form, active: !e.target.checked })}
              className="accent-primary"
            />
            归档该岗位（归档后不再出现在岗位库列表）
          </label>
        </div>
        <DialogFooter>
          <Button onClick={submit} disabled={submitting || !form.title.trim()}>
            {submitting ? '保存中…' : '保存修改'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
