/**
 * JobRadar 采集助手 popup 逻辑。
 *
 * 流程：打开 popup → 向当前标签页注入提取函数（选中文字优先，其次正文前 8000 字）
 * → 用户确认/补全字段 → POST localhost:8080/api/jobs/ingest（带 X-Local-Token）
 * → 成功后给出「查看详情」链接（前端 dev server /jobs/{id}）。
 *
 * 设计说明：
 * - 提取函数在页面上下文执行（chrome.scripting.executeScript 的 func 注入），
 *   站点无关：不解析特定网站 DOM，选中文字 > 全页文本，由后端 LLM 负责结构化。
 * - token 存 chrome.storage.local（仅本机浏览器可读写，不进页面上下文）。
 */
const API = 'http://localhost:8080'
const WEB = 'http://localhost:5173'

const $ = (id) => document.getElementById(id)

/** 注入到页面上下文执行：选中文字优先，否则取可见正文（截断 8000 字防超长 prompt） */
function extractPage() {
  const sel = window.getSelection()?.toString().trim()
  if (sel && sel.length > 20) {
    return { text: sel.slice(0, 8000), from: 'selection', title: document.title }
  }
  // 去掉脚本样式节点的纯文本；招聘页正文通常就是 body 文本
  const text = (document.body?.innerText || '').replace(/\n{3,}/g, '\n\n').trim()
  return { text: text.slice(0, 8000), from: 'body', title: document.title }
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
    if (result?.text) $('rawText').value = result.text
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
