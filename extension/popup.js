/**
 * JobRadar 采集助手 popup 逻辑。
 *
 * 流程：打开 popup → 向当前标签页注入提取函数（选中文字优先，其次主容器正文）
 * → 用户确认/补全字段 → POST localhost:8080/api/jobs/ingest（带 X-Local-Token）
 * → 成功后给出「查看详情」链接（前端 dev server /jobs/{id}）。
 *
 * 设计说明：
 * - 提取函数在页面上下文执行（chrome.scripting.executeScript 的 func 注入），
 *   站点无关：不解析特定网站 DOM，选中文字 > 主容器文本 > body 兜底，
 *   结构化由后端 LLM 负责（prompt 内含噪音过滤指令，双保险）。
 * - 招聘页的痛点：整页 innerText 会混入侧边推荐/广告/安全提示（W2-5 实测），
 *   因此提取分三层：DOM 主容器定位（治本）→ 噪音标记截断（安全网）→ LLM 过滤（兜底）。
 * - token 存 chrome.storage.local（仅本机浏览器可读写，不进页面上下文）。
 */
const API = 'http://localhost:8080'
const WEB = 'http://localhost:5173'

const $ = (id) => document.getElementById(id)

/**
 * 注入到页面上下文执行：选中文字优先，否则主容器正文（截断 8000 字防超长 prompt）。
 * 注意：此函数被序列化注入页面，内部不能引用外部变量（NOISE_MARKERS 需内联）。
 */
function extractPage() {
  // 招聘页噪音边界词：命中即丢弃该处及之后的内容。
  // 依据：innerText 按 DOM 顺序输出，推荐位/安全提示通常排在 JD 正文之后
  // （侧边栏视觉在左/右但 DOM 多在主内容后）。此列表随实测持续补充。
  const NOISE_MARKERS = [
    '安全提示', '防诈骗', '看过该职位的人还在看', '看过该职位的人还看了',
    '猜你喜欢', '为你推荐', '相似职位', '推荐职位', '热门职位', '相关职位推荐',
    '大家都在看', '精选职位', '最新推荐',
  ]

  function truncateNoise(text) {
    let cut = text.length
    for (const m of NOISE_MARKERS) {
      const i = text.indexOf(m)
      if (i >= 0 && i < cut) cut = i
    }
    return text.slice(0, cut)
  }

  /**
   * 定位主内容容器：优先语义标签（main/article/[role=main]），
   * 否则从 body 向下钻取——每步进入「占父级文本 ≥70%」的最大子元素，
   * 直到文本开始分裂（说明已到正文容器层）。Readability 的简化版，
   * 站点无关；招聘页正文远大于导航/侧栏，钻取会停在 JD 容器附近。
   */
  function pickMainContainer() {
    const direct = document.querySelector('main, article, [role="main"]')
    if (direct && (direct.innerText || '').trim().length > 200) return direct
    let cur = document.body
    while (cur) {
      const curLen = (cur.innerText || '').trim().length
      let next = null
      let nextLen = 0
      for (const child of cur.children) {
        const len = (child.innerText || '').trim().length
        if (len > nextLen) { nextLen = len; next = child }
      }
      // 最大子元素占父级不到 70%（文本分裂到多个区块）或已足够短：停
      if (!next || nextLen < curLen * 0.7 || nextLen < 500) break
      cur = next
    }
    return cur
  }

  const sel = window.getSelection()?.toString().trim()
  if (sel && sel.length > 20) {
    return { text: truncateNoise(sel).slice(0, 8000), from: 'selection', title: document.title }
  }
  const main = pickMainContainer()
  const text = truncateNoise((main?.innerText || document.body?.innerText || ''))
    .replace(/\n{3,}/g, '\n\n').trim()
  return { text: text.slice(0, 8000), from: main && main !== document.body ? 'main' : 'body', title: document.title }
}

let pageUrl = ''

async function init() {
  const { token } = await chrome.storage.local.get('token')
  if (token) $('token').value = token

  const [tab] = await chrome.tabs.query({ active: true, currentWindow: true })
  if (!tab?.id) return
  pageUrl = tab.url || ''

  try {
    const [{ result }] = await chrome.scripting.executeScript({
      target: { tabId: tab.id },
      func: extractPage,
    })
    if (result?.text) {
      $('rawText').value = result.text
      // 让用户看到提取来源：主容器提取不到时会退回整页文本（可能混入推荐/广告，可改用手动选中）
      const fromLabel = { selection: '选中文字', main: '页面主内容', body: '整页文本（建议手动选中 JD 区域）' }[result.from]
      if (fromLabel) showStatus(`已提取：${fromLabel}`, true)
    }
    // 页面标题常常是「岗位名_公司名_招聘」格式，预填岗位名减少手输（AI 兜底）
    if (result?.title) $('title').placeholder = `页面标题：${result.title.slice(0, 30)}…`
  } catch (e) {
    // chrome:// 等受限页无法注入——留空让用户手贴
    showStatus('无法读取当前页面内容（受限页面），请手动粘贴 JD 文本', false)
  }
}

function showStatus(msg, ok, link) {
  const el = $('status')
  el.className = ok ? 'ok' : 'err'
  el.innerHTML = link ? `${msg} <a href="${link}" target="_blank">查看详情 →</a>` : msg
}

async function save() {
  const token = (await chrome.storage.local.get('token')).token
  if (!token) {
    showStatus('请先在下方「设置」里保存 X-Local-Token', false)
    return
  }
  const rawText = $('rawText').value.trim()
  if (!rawText && !$('company').value.trim()) {
    showStatus('请提供 JD 文本（或至少手填公司+岗位）', false)
    return
  }

  $('save').disabled = true
  $('save').textContent = 'AI 解析中…'
  try {
    const res = await fetch(`${API}/api/jobs/ingest`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-Local-Token': token },
      body: JSON.stringify({
        source: 'extension',
        url: pageUrl,
        raw_text: rawText || undefined,
        hints: {
          company: $('company').value.trim() || undefined,
          title: $('title').value.trim() || undefined,
          city: $('city').value.trim() || undefined,
          deadline: $('deadline').value || undefined,
        },
      }),
    })
    const body = await res.json().catch(() => ({}))
    if (!res.ok) {
      showStatus(body.detail || `${res.status} ${res.statusText}`, false)
      return
    }
    if (body.already_exists) {
      showStatus('该岗位已收藏过（自动去重）', true, `${WEB}/jobs/${body.job_id}`)
    } else {
      const warn = body.warnings?.length ? `（${body.warnings.join('；')}）` : ''
      showStatus(`已入库${warn}`, true, `${WEB}/jobs/${body.job_id}`)
    }
  } catch (e) {
    showStatus(`请求失败：${e.message}（后端是否已启动？）`, false)
  } finally {
    $('save').disabled = false
    $('save').textContent = 'AI 解析并入库'
  }
}

$('save').addEventListener('click', save)
$('saveToken').addEventListener('click', async () => {
  await chrome.storage.local.set({ token: $('token').value.trim() })
  showStatus('令牌已保存', true)
})
init()
