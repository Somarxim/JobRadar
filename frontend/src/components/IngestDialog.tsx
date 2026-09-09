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
import { ClipboardPaste, ImagePlus, X } from 'lucide-react'

/** 海报图片大小上限 4MB：公众号海报通常 1-2MB，过大图片 base64 后请求体膨胀且模型收益递减 */
const MAX_IMAGE_BYTES = 4 * 1024 * 1024

interface PosterImage {
  base64: string
  mediaType: string
  name: string
  previewUrl: string
}

/**
 * 粘贴导入对话框（POST /api/jobs/ingest）。
 * 两条输入路径二选一：JD 文本（LLM 文本解析）或海报图片（两段式：视觉转录 + 文本结构化）；
 * 公司/岗位建议手填（AI 兜底），其余字段由 AI 自动补全（W2-1 修正：AI 主职是补全而非识别）。
 */
export default function IngestDialog({ onDone }: { onDone: () => void }) {
  const [open, setOpen] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [form, setForm] = useState({ company: '', title: '', city: '', url: '', deadline: '', raw_text: '' })
  const [image, setImage] = useState<PosterImage | null>(null)

  function pickImage(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0]
    if (!file) return
    if (file.size > MAX_IMAGE_BYTES) {
      toast.error('图片超过 4MB，请压缩后再传')
      return
    }
    const reader = new FileReader()
    reader.onload = () => {
      const dataUrl = reader.result as string
      // data:image/png;base64,xxxx → 契约要求分离 media_type 与纯 base64
      const [header, base64] = dataUrl.split(',')
      setImage({
        base64,
        mediaType: header.match(/data:(.*?);/)?.[1] ?? 'image/png',
        name: file.name,
        previewUrl: dataUrl,
      })
    }
    reader.readAsDataURL(file)
    e.target.value = '' // 允许重选同一文件
  }

  function reset() {
    setForm({ company: '', title: '', city: '', url: '', deadline: '', raw_text: '' })
    setImage(null)
  }

  async function submit() {
    setSubmitting(true)
    try {
      const res = await api.ingestJob({
        source: image && !form.raw_text.trim() ? 'poster_image' : 'manual_paste',
        url: form.url || undefined,
        raw_text: form.raw_text || undefined,
        image_base64: image?.base64,
        image_media_type: image?.mediaType,
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
      reset()
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
          <DialogDescription>
            粘贴 JD 全文或上传招聘海报图片。公司/岗位建议手填（留空由 AI 兜底识别），
            城市、截止日期等字段由 AI 自动补全。
          </DialogDescription>
        </DialogHeader>
        <div className="grid gap-3">
          <div className="grid grid-cols-2 gap-3">
            <div className="grid gap-1.5">
              <Label>公司 <span className="text-muted-foreground font-normal">（建议填写）</span></Label>
              <Input value={form.company} onChange={(e) => setForm({ ...form, company: e.target.value })} placeholder="留空由 AI 兜底识别" />
            </div>
            <div className="grid gap-1.5">
              <Label>岗位 <span className="text-muted-foreground font-normal">（建议填写）</span></Label>
              <Input value={form.title} onChange={(e) => setForm({ ...form, title: e.target.value })} placeholder="留空由 AI 兜底识别" />
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
            <Label>JD 全文 <span className="text-muted-foreground font-normal">（与海报图片二选一）</span></Label>
            <Textarea rows={5} value={form.raw_text} onChange={(e) => setForm({ ...form, raw_text: e.target.value })} />
          </div>
          <div className="grid gap-1.5">
            <Label>海报图片 <span className="text-muted-foreground font-normal">（公众号海报大图，AI 视觉识别）</span></Label>
            {image ? (
              <div className="flex items-center gap-3 rounded-md border p-2">
                <img src={image.previewUrl} alt="海报预览" className="h-16 rounded object-cover" />
                <span className="text-sm text-muted-foreground truncate flex-1">{image.name}</span>
                <Button variant="ghost" size="icon" onClick={() => setImage(null)} title="移除图片">
                  <X className="size-4" />
                </Button>
              </div>
            ) : (
              <label className="flex cursor-pointer items-center justify-center gap-2 rounded-md border border-dashed py-4 text-sm text-muted-foreground hover:bg-accent">
                <ImagePlus className="size-4" />
                选择图片（PNG/JPG，≤4MB）
                <input type="file" accept="image/*" className="hidden" onChange={pickImage} />
              </label>
            )}
          </div>
        </div>
        <DialogFooter>
          <Button onClick={submit} disabled={submitting || (!form.raw_text.trim() && !image)}>
            {submitting ? 'AI 解析中…' : '导入'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
