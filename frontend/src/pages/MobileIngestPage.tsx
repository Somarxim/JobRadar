import { useState } from 'react'
import { Link } from 'react-router-dom'
import { toast } from 'sonner'
import { api } from '@/api/client'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Link2, Radar, Type } from 'lucide-react'

type Mode = 'text' | 'url'

/**
 * 手机端收藏页（/m）：移动端浏览器直连，免侧边栏。
 * 两种模式：粘贴 JD 文本（任何 App 里长按复制）/ 粘贴文章链接（公众号、官网）。
 * 手机上推荐「添加到主屏幕」（PWA），之后从桌面图标一步直达。
 */
export default function MobileIngestPage() {
  const [mode, setMode] = useState<Mode>('text')
  const [rawText, setRawText] = useState('')
  const [url, setUrl] = useState('')
  const [loading, setLoading] = useState(false)
  const [result, setResult] = useState<{ jobId: number; alreadyExists: boolean; company?: string; title?: string } | null>(null)

  async function submit() {
    const isUrl = mode === 'url'
    const payload = isUrl
      ? { source: 'mobile_url', url: url.trim(), hints: {} }
      : { source: 'mobile_paste', raw_text: rawText.trim(), hints: {} }
    if (isUrl && !url.trim()) { toast.error('请粘贴文章链接'); return }
    if (!isUrl && !rawText.trim()) { toast.error('请粘贴 JD 文本'); return }

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

        {/* 模式切换：大触控区 */}
        <div className="grid grid-cols-2 gap-2">
          <button
            type="button"
            onClick={() => setMode('text')}
            className={`flex items-center justify-center gap-1.5 rounded-lg border px-3 py-3 text-sm transition-colors ${
              mode === 'text' ? 'border-primary bg-primary/5 text-primary font-medium' : 'bg-card text-muted-foreground'
            }`}
          >
            <Type className="size-4" />粘贴文本
          </button>
          <button
            type="button"
            onClick={() => setMode('url')}
            className={`flex items-center justify-center gap-1.5 rounded-lg border px-3 py-3 text-sm transition-colors ${
              mode === 'url' ? 'border-primary bg-primary/5 text-primary font-medium' : 'bg-card text-muted-foreground'
            }`}
          >
            <Link2 className="size-4" />粘贴链接
          </button>
        </div>

        {mode === 'text' ? (
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
        ) : (
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
            <p className="text-xs text-muted-foreground">
              服务端会抓取正文并解析；链接失效或被删除时请改用「粘贴文本」
            </p>
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
