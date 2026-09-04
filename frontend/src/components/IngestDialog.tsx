import { useState } from 'react'
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
import { ClipboardPaste } from 'lucide-react'

/**
 * 粘贴导入对话框（POST /api/jobs/ingest）。
 * W1 纯文本版：公司与岗位名需手动给出（hints），JD 全文直接粘贴；
 * W2 接入 LLM 后 hints 将由模型自动提取。
 */
export default function IngestDialog({ onDone }: { onDone: () => void }) {
  const [open, setOpen] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [form, setForm] = useState({ company: '', title: '', city: '', url: '', deadline: '', raw_text: '' })

  async function submit() {
    setSubmitting(true)
    try {
      const res = await api.ingestJob({
        source: 'manual_paste',
        url: form.url || undefined,
        raw_text: form.raw_text,
        hints: {
          company: form.company,
          title: form.title,
          city: form.city || undefined,
          deadline: form.deadline || undefined,
        },
      })
      if (res.already_exists) {
        toast.warning('该岗位已存在（已去重），无需重复导入')
        return
      }
      toast.success('导入成功' + (res.warnings.length ? `：${res.warnings.join('；')}` : ''))
      setOpen(false)
      setForm({ company: '', title: '', city: '', url: '', deadline: '', raw_text: '' })
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
        <Button variant="outline"><ClipboardPaste className="size-4" />粘贴导入</Button>
      </DialogTrigger>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>粘贴导入</DialogTitle>
          <DialogDescription>粘贴 JD 全文，填写公司与岗位名（W2 起将由 LLM 自动识别）。</DialogDescription>
        </DialogHeader>
        <div className="grid gap-3">
          <div className="grid grid-cols-2 gap-3">
            <div className="grid gap-1.5">
              <Label>公司 *</Label>
              <Input value={form.company} onChange={(e) => setForm({ ...form, company: e.target.value })} />
            </div>
            <div className="grid gap-1.5">
              <Label>岗位 *</Label>
              <Input value={form.title} onChange={(e) => setForm({ ...form, title: e.target.value })} />
            </div>
          </div>
          <div className="grid grid-cols-3 gap-3">
            <div className="grid gap-1.5">
              <Label>城市</Label>
              <Input value={form.city} onChange={(e) => setForm({ ...form, city: e.target.value })} />
            </div>
            <div className="grid gap-1.5">
              <Label>投递截止</Label>
              <Input type="date" value={form.deadline} onChange={(e) => setForm({ ...form, deadline: e.target.value })} />
            </div>
            <div className="grid gap-1.5">
              <Label>来源链接</Label>
              <Input value={form.url} onChange={(e) => setForm({ ...form, url: e.target.value })} placeholder="https://…" />
            </div>
          </div>
          <div className="grid gap-1.5">
            <Label>JD 全文</Label>
            <Textarea rows={6} value={form.raw_text} onChange={(e) => setForm({ ...form, raw_text: e.target.value })} />
          </div>
        </div>
        <DialogFooter>
          <Button onClick={submit} disabled={submitting || !form.company.trim() || !form.title.trim()}>
            {submitting ? '导入中…' : '导入'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

