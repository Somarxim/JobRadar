import { useState } from 'react'
import { api } from '@/api/client'
import { Button } from '@/components/ui/button'
import {
  Dialog, DialogContent, DialogDescription, DialogFooter,
  DialogHeader, DialogTitle, DialogTrigger,
} from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'
import {
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
} from '@/components/ui/select'
import { COMPANY_TYPE_LABELS } from '@/lib/labels'
import { Plus } from 'lucide-react'

/** 手动录入岗位对话框（POST /api/jobs） */
export default function JobCreateDialog({ onDone }: { onDone: () => void }) {
  const [open, setOpen] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [form, setForm] = useState({
    company_name: '', title: '', city: '', salary_range: '',
    deadline: '', jd_text: '', company_type: 'other',
  })

  const set = (k: keyof typeof form) => (e: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement>) =>
    setForm({ ...form, [k]: e.target.value })

  async function submit() {
    setSubmitting(true)
    setError(null)
    try {
      await api.createJob({
        ...form,
        company_type: form.company_type,
        city: form.city || undefined,
        salary_range: form.salary_range || undefined,
        deadline: form.deadline || undefined,
        jd_text: form.jd_text || undefined,
      })
      setOpen(false)
      setForm({ company_name: '', title: '', city: '', salary_range: '', deadline: '', jd_text: '', company_type: 'other' })
      onDone()
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild>
        <Button><Plus className="size-4" />手动录入</Button>
      </DialogTrigger>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>手动录入岗位</DialogTitle>
          <DialogDescription>公司按名自动复用或新建；重复岗位（公司+岗位+城市）会被拒绝。</DialogDescription>
        </DialogHeader>
        <div className="grid gap-3">
          <div className="grid gap-1.5">
            <Label htmlFor="c">公司 *</Label>
            <Input id="c" value={form.company_name} onChange={set('company_name')} placeholder="中国电科XX研究所" />
          </div>
          <div className="grid gap-1.5">
            <Label htmlFor="t">岗位名称 *</Label>
            <Input id="t" value={form.title} onChange={set('title')} placeholder="嵌入式软件工程师（2026校招）" />
          </div>
          <div className="grid grid-cols-2 gap-3">
            <div className="grid gap-1.5">
              <Label htmlFor="ct">公司类型</Label>
              <Select value={form.company_type} onValueChange={(v) => setForm({ ...form, company_type: v })}>
                <SelectTrigger id="ct"><SelectValue /></SelectTrigger>
                <SelectContent>
                  {Object.entries(COMPANY_TYPE_LABELS).map(([v, l]) => (
                    <SelectItem key={v} value={v}>{l}</SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
            <div className="grid gap-1.5">
              <Label htmlFor="city">城市</Label>
              <Input id="city" value={form.city} onChange={set('city')} placeholder="成都" />
            </div>
          </div>
          <div className="grid grid-cols-2 gap-3">
            <div className="grid gap-1.5">
              <Label htmlFor="salary">薪资范围</Label>
              <Input id="salary" value={form.salary_range} onChange={set('salary_range')} placeholder="18-25万/年" />
            </div>
            <div className="grid gap-1.5">
              <Label htmlFor="ddl">投递截止</Label>
              <Input id="ddl" type="date" value={form.deadline} onChange={set('deadline')} />
            </div>
          </div>
          <div className="grid gap-1.5">
            <Label htmlFor="jd">JD 全文</Label>
            <Textarea id="jd" rows={5} value={form.jd_text} onChange={set('jd_text')} placeholder="粘贴职位描述全文…" />
          </div>
          {error && <p className="text-sm text-destructive">{error}</p>}
        </div>
        <DialogFooter>
          <Button onClick={submit} disabled={submitting || !form.company_name.trim() || !form.title.trim()}>
            {submitting ? '提交中…' : '保存'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
