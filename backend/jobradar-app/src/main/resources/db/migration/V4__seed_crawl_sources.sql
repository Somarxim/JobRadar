-- V4: W3 爬虫源种子数据。
-- 注意：两个源均为 SPA 页面（静态 HTML 里无岗位列表），初始 enabled=false，
-- 待 W3-2 实地验证 URL/选择器（或改走站点 JSON API）后启用——
-- 启用了但解析不出数据的源会每天空跑，徒增噪音。

INSERT INTO crawl_sources (name, url, parser, category, enabled, meta) VALUES
('国聘网·校招', 'https://www.iguopin.com/', 'selector-list', 'soe_other', FALSE,
 '{"platform": "guopin", "note": "W3-2 实地验证：列表页疑似 SPA，优先找 JSON API；selector-list meta 待补 item/title 选择器"}'),
('牛客·校招职位', 'https://www.nowcoder.com/jobs/campus', 'selector-list', 'community', FALSE,
 '{"platform": "niuke", "note": "W3-2 实地验证：已确认列表页为纯 JS 渲染（curl 仅 4KB 空壳），需找 JSON API 或换 Playwright"}');
