-- V5: W3-2 站点适配——牛客校招接通实测可用的 JSON API 并启用；国聘网实测需登录态，保持禁用。
--
-- 探测结论（2026-09-09，详见 document/plans/2026-09-09-w3-site-adaptation.md）：
-- * 牛客：POST https://nowpick.nowcoder.com/u/job/square-search 表单编码匿名可用，
--   query 关键词 + recruitType=1 校招 + page 分页，每页 20 条；JD 全文在 ext 内嵌 JSON。
-- * 国聘：职位接口 gp-api /api/jobs/v3/list 需个人账号登录态（403「账号类型错误」）；
--   appv5 接口请求/响应全链路 AES 加密。匿名无法采集，保持禁用，后续可选：
--   ① 用户浏览器登录后复制 __token__ 到 meta（会过期，体验差）② Playwright 带登录态。

-- 牛客：原 selector-list 占位源改造为第一个关键词源
UPDATE crawl_sources SET
  name    = '牛客·校招·软件开发',
  url     = 'https://nowpick.nowcoder.com/u/job/square-search',
  parser  = 'nowcoder-search',
  enabled = TRUE,
  meta    = '{"platform":"niuke",
              "request":{"method":"POST","contentType":"form",
                          "headers":{"Origin":"https://www.nowcoder.com"},
                          "params":{"query":"软件开发","recruitType":"1"}},
              "pagination":{"pageParam":"page","start":1,"pages":3},
              "note":"W3-2 实测匿名可用；每页20条，3页≈60条/天"}'::jsonb
WHERE name = '牛客·校招职位';

-- 关键词矩阵补齐（方向对齐 docs/target-sources.md §1：AI/大模型、后端、银行科技）
INSERT INTO crawl_sources (name, url, parser, category, enabled, meta) VALUES
('牛客·校招·大模型', 'https://nowpick.nowcoder.com/u/job/square-search', 'nowcoder-search', 'community', TRUE,
 '{"platform":"niuke","request":{"method":"POST","contentType":"form","headers":{"Origin":"https://www.nowcoder.com"},"params":{"query":"大模型","recruitType":"1"}},"pagination":{"pageParam":"page","start":1,"pages":3}}'::jsonb),
('牛客·校招·Java后端', 'https://nowpick.nowcoder.com/u/job/square-search', 'nowcoder-search', 'community', TRUE,
 '{"platform":"niuke","request":{"method":"POST","contentType":"form","headers":{"Origin":"https://www.nowcoder.com"},"params":{"query":"Java后端","recruitType":"1"}},"pagination":{"pageParam":"page","start":1,"pages":3}}'::jsonb),
('牛客·校招·银行', 'https://nowpick.nowcoder.com/u/job/square-search', 'nowcoder-search', 'community', TRUE,
 '{"platform":"niuke","request":{"method":"POST","contentType":"form","headers":{"Origin":"https://www.nowcoder.com"},"params":{"query":"银行","recruitType":"1"}},"pagination":{"pageParam":"page","start":1,"pages":3}}'::jsonb);

-- 国聘网：记录探测结论，保持禁用（解析器不存在也无所谓——禁用的源不会进入调度）
UPDATE crawl_sources SET
  meta = '{"platform":"guopin",
           "note":"W3-2 实测：职位列表需个人账号登录态（gp-api 403）；appv5 接口加密。保持禁用，方案见 V5 注释"}'::jsonb
WHERE name = '国聘网·校招';
