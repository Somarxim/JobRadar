import { useState } from 'react'
import { Link } from 'react-router-dom'
import { toast } from 'sonner'
import { api } from '@/api/client'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Image as ImageIcon, Link2, Radar, Type, X } from 'lucide-react'

type Mode = 'text' | 'url' | 'image'

interface PickedImage {
  base64: string
  mediaType: string
  previewUrl: string
}

const MAX_IMAGE_BYTES = 4 * 1024 * 1024

/**
 * 手机端收藏页（/m）：移动端浏览器直连，免侧边栏。
 * 三种模式覆盖一切场景：
 *  - 粘贴文本：能复制时最快（长按全选复制 JD）
 *  - 粘贴链接：公众号/官网文章，服务端抓正文
 *  - 截图上传：JD 无法复制时（图片/小程序/防复制 App），系统截图后上传，
 *    走多模态海报解析管线（与电脑端海报导入同一套）
 */
export default function MobileIngestPage() {
  const [mode, setMode] = useState<Mode>('text')
  const [rawText, setRawText] = useState('')
  const [url, setUrl] = useState('')
  const [image, setImage] = useState<PickedImage | null>(null)
  const [loading, setLoading] = useState(false)
  const [result, setResult] = useState<{ jobId: number; alreadyExists: boolean } | null>(null)

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
      const [header, base64] = dataUrl.split(',')
      setImage({
        base64,
        mediaType: header.match(/data:(.*?);/)?.[1] ?? 'image/png',
        previewUrl: dataUrl,
      })
    }
    reader.readAsDataURL(file)
    e.target.value = ''
  }

  async function submit() {
    let payload: Record<string, unknown>
    if (mode === 'url') {
      if (!url.trim()) { toast.error('请粘贴文章链接'); return }
      payload = { source: 'mobile_url', url: url.trim(), hints: {} }
    } else if (mode === 'image') {
      if (!image) { toast.error('请先选择截图'); return }
      payload = { source: 'mobile_photo', image_base64: image.base64, image_media_type: image.mediaType, hints: {} }
    } else {
      if (!rawText.trim()) { toast.error('请粘贴 JD 文本'); return }
      payload = { source: 'mobile_paste', raw_text: rawText.trim(), hints: {} }
    }

    setLoading(true)
    setResult(null)
    try {
      const res = await api.ingestJob(payload)
      setResult({ jobId: res.job_id, alreadyExists: res.already_exists })
      toast.success(res.already_exists ? '该岗位已收藏过' : '已入库')
    } catch (e) {
      toast.error(e instanceof Error ? e.message : String(e))
    } finally {
      setLoading(false)
    }
  }

  const MODES: { key: Mode; label: string; icon: typeof Type }[] = [
    { key: 'text', label: '粘贴文本', icon: Type },
    { key: 'url', label: '粘贴链接', icon: Link2 },
    { key: 'image', label: '截图上传', icon: ImageIcon },
  ]

  return (
    <div className="min-h-screen bg-muted/40 p-4">
      <div className="mx-auto max-w-md space-y-5 pt-8">
        <div className="flex items-center gap-2">
          <span className="flex size-8 items-center justify-center rounded-md bg-primary text-primary-foreground">
            <Radar className="size-4" />
          </span>
          <div>
            <h1 className="text-lg font-semibold">收藏岗位</h1>
            <p className="text-xs text-muted-foreground">手机收藏到 JobRadar</p>
          </div>
        </div>

        <div className="grid grid-cols-3 gap-2">
          {MODES.map(({ key, label, icon: Icon }) => (
            <button
              key={key}
              type="button"
              onClick={() => setMode(key)}
              className={`flex items-center justify-center gap-1.5 rounded-lg border px-2 py-3 text-sm transition-colors ${
                mode === key ? 'border-primary bg-primary/5 text-primary font-medium' : 'bg-card text-muted-foreground'
              }`}
            >
              <Icon className="size-4" />{label}
            </button>
          ))}
        </div>

        {mode === 'text' && (
          <div className="space-y-2">
            <Label>JD 文本（在文章里长按 → 全选 → 复制，粘贴到这里）</Label>
            <textarea
              value={rawText}
              onChange={(e) => setRawText(e.target.value)}
              rows={10}
              placeholder="把岗位描述全文粘贴到这里……"
              className="w-full rounded-lg border bg-card p-3 text-sm outline-none focus:border-primary focus:ring-2 focus:ring-primary/20"
            />
          </div>
        )}

        {mode === 'url' && (
          <div className="space-y-2">
            <Label>文章链接（公众号文章 / 招聘官网页面）</Label>
            <Input
              value={url}
              onChange={(e) => setUrl(e.target.value)}
              placeholder="https://mp.weixin.qq.com/s/…"
              inputMode="url"
              autoCapitalize="off"
              autoCorrect="off"
              className="h-11"
            />
            <p className="text-xs text-muted-foreground">服务端抓取正文解析；链接失效时改用「粘贴文本」或「截图上传」</p>
          </div>
        )}

        {mode === 'image' && (
          <div className="space-y-2">
            <Label>JD 截图（适用无法复制的页面：图片/小程序/防复制 App）</Label>
            {image ? (
              <div className="relative rounded-lg border bg-card p-2">
                <img src={image.previewUrl} alt="JD 截图预览" className="w-full rounded-md" />
                <button
                  type="button"
                  onClick={() => setImage(null)}
                  aria-label="移除图片"
                  className="absolute right-3 top-3 flex size-7 items-center justify-center rounded-full bg-black/60 text-white"
                >
                  <X className="size-4" />
                </button>
              </div>
            ) : (
              <label className="flex h-40 cursor-pointer flex-col items-center justify-center gap-2 rounded-lg border-2 border-dashed bg-card text-sm text-muted-foreground active:bg-muted">
                <ImageIcon className="size-6" />
                点这里选择截图 / 拍照
                <input type="file" accept="image/*" className="hidden" onChange={pickImage} />
              </label>
            )}
            <p className="text-xs text-muted-foreground">系统截图（电源键+音量键）任何页面都能截，AI 会识别图片里的文字</p>
          </div>
        )}

        <Button onClick={submit} disabled={loading} className="h-12 w-full text-base">
          {loading ? '解析中…' : 'AI 解析并收藏'}
        </Button>

        {result && (
          <div className="rounded-lg border bg-card p-4 text-sm space-y-1">
            <p className="font-medium">{result.alreadyExists ? '该岗位此前已收藏过' : '收藏成功'}</p>
            <Link to={`/jobs/${result.jobId}`} className="text-primary underline">
              查看岗位详情 →
            </Link>
          </div>
        )}

        <p className="text-center text-xs text-muted-foreground">
          添加到主屏幕后可像 App 一样一键打开
        </p>
      </div>
    </div>
  )
}
