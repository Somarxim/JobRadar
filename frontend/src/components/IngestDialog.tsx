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
 * W2 起公司/岗位可留空——由 LLM 从 JD 全文自动提取（后端合并策略：手填 > AI）；
 * AI 未配置或解析失败时后端返回 422，提示改用手动填写。
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
          company: form.company || undefined,
          title: form.title || undefined,
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
          <DialogDescription>粘贴 JD 全文即可，公司与岗位名可由 AI 自动提取（手填优先）。</DialogDescription>
        </DialogHeader>
        <div className="grid gap-3">
          <div className="grid grid-cols-2 gap-3">
            <div className="grid gap-1.5">
              <Label>公司 <span className="text-muted-foreground font-normal">（可选）</span></Label>
              <Input value={form.company} onChange={(e) => setForm({ ...form, company: e.target.value })} placeholder="留空由 AI 提取" />
            </div>
            <div className="grid gap-1.5">
              <Label>岗位 <span className="text-muted-foreground font-normal">（可选）</span></Label>
              <Input value={form.title} onChange={(e) => setForm({ ...form, title: e.target.value })} placeholder="留空由 AI 提取" />
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
            <Label>JD 全文 *</Label>
            <Textarea rows={6} value={form.raw_text} onChange={(e) => setForm({ ...form, raw_text: e.target.value })} />
          </div>
        </div>
        <DialogFooter>
          <Button onClick={submit} disabled={submitting || !form.raw_text.trim()}>
            {submitting ? 'AI 解析中…' : '导入'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

