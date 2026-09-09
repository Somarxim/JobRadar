-- V6: W3 数据源扩展探测（2026-09-10，详见 document/plans/2026-09-10-w3-todo-and-source-probe.md）
--
-- 探测结论：
-- * 智联招聘：POST fe-api.zhaopin.com/c/i/search/positions（JSON body，匿名可用，
--   需 Referer 头）；count 封顶 100；字段名不直观（职位名在 name、城市在 workCity、
--   详情页在 positionURL）。痛点：无结构化校招过滤（campusTypeSearch 等参数实测不生效），
--   关键词搜索社招噪音大 → 源落库但默认禁用，待关键词矩阵调优后启用。
--   详情页 jobs.zhaopin.com 有安全验证页，只存链接不二次抓取。
-- * 前程无忧（51job）：搜索页与匿名 API 均被阿里云 WAF JS 挑战拦截
--   （响应为 aliyun_waf 挑战页而非数据）。逆向 WAF 投入产出不划算，记录结论禁用。
-- * 应届生求职网：主站已变为 App 下载页，职位搜索迁至 q.yingjiesheng.com，
--   同为阿里云 WAF 拦截。记录结论禁用。

-- 智联：能力就绪（zhaopin-search 解析器已实现并有单测），但社招噪音问题未解，禁用待调优
INSERT INTO crawl_sources (name, url, parser, category, enabled, meta) VALUES
('智联·综合·央国企', 'https://fe-api.zhaopin.com/c/i/search/positions', 'zhaopin-search', 'soe_other', FALSE,
 '{"platform":"zhaopin",
   "request":{"method":"POST","contentType":"json",
               "headers":{"Referer":"https://sou.zhaopin.com/"},
               "params":{"kw":"国企 央企 校招 2026","pageSize":"20"}},
   "pagination":{"pageParam":"pageIndex","start":0,"pages":3},
   "note":"W3 实测匿名可用（2026-09-10）；无校招结构化过滤，社招噪音大，启用前先调关键词"}'::jsonb),
('前程无忧·综合', 'https://we.51job.com/api/job/search-pc', 'selector-list', 'soe_other', FALSE,
 '{"platform":"51job",
   "note":"W3 实测（2026-09-10）：搜索页与匿名 API 均被阿里云 WAF JS 挑战拦截，无可用解析器；如需接入需 Playwright 或破 WAF，暂搁置"}'::jsonb),
('应届生求职网·综合', 'https://q.yingjiesheng.com/jobs/search/', 'selector-list', 'community', FALSE,
 '{"platform":"yingjiesheng",
   "note":"W3 实测（2026-09-10）：职位搜索已迁至 q 子站，同被阿里云 WAF 拦截（与 51job 同一 WAF 产品）；暂搁置"}'::jsonb);
