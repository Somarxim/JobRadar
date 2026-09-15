-- V9: 信息源扩展——央国企/军工所覆盖（2026-09-15 探测结论，详见私有仓 document/plans/2026-09-15-*）
--
-- 背景：牛客单一来源导致每日推荐全是互联网岗位。央国企/军工所的真实发布渠道实测结论：
-- * 国聘网：岗位 API 需登录态 + HmacSHA256 双层签名（gp-api.iguopin.com），匿名返回空——
--   放弃纯匿名接入；如需接入需用户提供账号（签名算法已逆向留档，见探测日志）。
-- * 24365 国家大学生就业服务平台（job.ncss.cn，教育部官方）：搜索 JSON API 匿名可用，
--   GET /student/jobs/jobslist/ajax/，jobName 关键词 + offset 分页；央国企密集。✅ 本版接入
-- * 搜狗微信（weixin.sogou.com）：公众号文章搜索匿名可用——军工所校招启事大量发在公众号，
--   「集团名 + 2027校园招聘」关键词即用户真实检索路径的自动化。✅ 本版接入
-- * 高校人才网（gaoxiaojob.com）：SSR 内联 JSON 提取，研究所/事业单位岗位密集。✅ 本版接入
-- * 高校就业网（北理工/哈工大/北航/西工大）：多数有 WAF/412，暂缓。

-- ============ 24365（教育部平台）集团关键词源 ============
INSERT INTO crawl_sources (name, url, parser, category, enabled, meta) VALUES
('24365·航天科技',   'https://job.ncss.cn/student/jobs/jobslist/ajax/', 'ncss-search', 'soe_other', TRUE,
 '{"platform":"ncss","request":{"method":"GET","params":{"jobName":"航天科技","limit":"10"},"headers":{"Referer":"https://job.ncss.cn/student/jobs/index.html","X-Requested-With":"XMLHttpRequest"}},"pagination":{"pageParam":"offset","start":1,"pages":3}}'),
('24365·航天科工',   'https://job.ncss.cn/student/jobs/jobslist/ajax/', 'ncss-search', 'soe_other', TRUE,
 '{"platform":"ncss","request":{"method":"GET","params":{"jobName":"航天科工","limit":"10"},"headers":{"Referer":"https://job.ncss.cn/student/jobs/index.html","X-Requested-With":"XMLHttpRequest"}},"pagination":{"pageParam":"offset","start":1,"pages":3}}'),
('24365·航空工业',   'https://job.ncss.cn/student/jobs/jobslist/ajax/', 'ncss-search', 'soe_other', TRUE,
 '{"platform":"ncss","request":{"method":"GET","params":{"jobName":"航空工业","limit":"10"},"headers":{"Referer":"https://job.ncss.cn/student/jobs/index.html","X-Requested-With":"XMLHttpRequest"}},"pagination":{"pageParam":"offset","start":1,"pages":3}}'),
('24365·中国电科',   'https://job.ncss.cn/student/jobs/jobslist/ajax/', 'ncss-search', 'soe_other', TRUE,
 '{"platform":"ncss","request":{"method":"GET","params":{"jobName":"中国电科","limit":"10"},"headers":{"Referer":"https://job.ncss.cn/student/jobs/index.html","X-Requested-With":"XMLHttpRequest"}},"pagination":{"pageParam":"offset","start":1,"pages":3}}'),
('24365·兵器工业',   'https://job.ncss.cn/student/jobs/jobslist/ajax/', 'ncss-search', 'soe_other', TRUE,
 '{"platform":"ncss","request":{"method":"GET","params":{"jobName":"兵器工业","limit":"10"},"headers":{"Referer":"https://job.ncss.cn/student/jobs/index.html","X-Requested-With":"XMLHttpRequest"}},"pagination":{"pageParam":"offset","start":1,"pages":3}}'),
('24365·中国航发',   'https://job.ncss.cn/student/jobs/jobslist/ajax/', 'ncss-search', 'soe_other', TRUE,
 '{"platform":"ncss","request":{"method":"GET","params":{"jobName":"中国航发","limit":"10"},"headers":{"Referer":"https://job.ncss.cn/student/jobs/index.html","X-Requested-With":"XMLHttpRequest"}},"pagination":{"pageParam":"offset","start":1,"pages":3}}'),
('24365·中国船舶',   'https://job.ncss.cn/student/jobs/jobslist/ajax/', 'ncss-search', 'soe_other', TRUE,
 '{"platform":"ncss","request":{"method":"GET","params":{"jobName":"中国船舶","limit":"10"},"headers":{"Referer":"https://job.ncss.cn/student/jobs/index.html","X-Requested-With":"XMLHttpRequest"}},"pagination":{"pageParam":"offset","start":1,"pages":3}}'),
('24365·国家电网',   'https://job.ncss.cn/student/jobs/jobslist/ajax/', 'ncss-search', 'soe_other', TRUE,
 '{"platform":"ncss","request":{"method":"GET","params":{"jobName":"国家电网","limit":"10"},"headers":{"Referer":"https://job.ncss.cn/student/jobs/index.html","X-Requested-With":"XMLHttpRequest"}},"pagination":{"pageParam":"offset","start":1,"pages":3}}'),
('24365·中国电信',   'https://job.ncss.cn/student/jobs/jobslist/ajax/', 'ncss-search', 'soe_other', TRUE,
 '{"platform":"ncss","request":{"method":"GET","params":{"jobName":"中国电信","limit":"10"},"headers":{"Referer":"https://job.ncss.cn/student/jobs/index.html","X-Requested-With":"XMLHttpRequest"}},"pagination":{"pageParam":"offset","start":1,"pages":3}}'),
('24365·中国移动',   'https://job.ncss.cn/student/jobs/jobslist/ajax/', 'ncss-search', 'soe_other', TRUE,
 '{"platform":"ncss","request":{"method":"GET","params":{"jobName":"中国移动","limit":"10"},"headers":{"Referer":"https://job.ncss.cn/student/jobs/index.html","X-Requested-With":"XMLHttpRequest"}},"pagination":{"pageParam":"offset","start":1,"pages":3}}');

-- ============ 搜狗微信发现源（公众号校招公告，集团级关键词） ============
INSERT INTO crawl_sources (name, url, parser, category, enabled, meta) VALUES
('微信·航天科技校招', 'https://weixin.sogou.com/weixin', 'sogou-weixin', 'institute_military', TRUE,
 '{"platform":"wechat","request":{"method":"GET","params":{"type":"2","query":"航天科技 2027校园招聘"}},"pagination":{"pageParam":"page","start":1,"pages":1}}'),
('微信·航天科工校招', 'https://weixin.sogou.com/weixin', 'sogou-weixin', 'institute_military', TRUE,
 '{"platform":"wechat","request":{"method":"GET","params":{"type":"2","query":"航天科工 2027校园招聘"}},"pagination":{"pageParam":"page","start":1,"pages":1}}'),
('微信·航空工业校招', 'https://weixin.sogou.com/weixin', 'sogou-weixin', 'institute_military', TRUE,
 '{"platform":"wechat","request":{"method":"GET","params":{"type":"2","query":"航空工业 2027校园招聘"}},"pagination":{"pageParam":"page","start":1,"pages":1}}'),
('微信·中国电科校招', 'https://weixin.sogou.com/weixin', 'sogou-weixin', 'institute_military', TRUE,
 '{"platform":"wechat","request":{"method":"GET","params":{"type":"2","query":"中国电科 2027校园招聘"}},"pagination":{"pageParam":"page","start":1,"pages":1}}'),
('微信·兵器工业校招', 'https://weixin.sogou.com/weixin', 'sogou-weixin', 'institute_military', TRUE,
 '{"platform":"wechat","request":{"method":"GET","params":{"type":"2","query":"兵器工业 2027校园招聘"}},"pagination":{"pageParam":"page","start":1,"pages":1}}'),
('微信·中国航发校招', 'https://weixin.sogou.com/weixin', 'sogou-weixin', 'institute_military', TRUE,
 '{"platform":"wechat","request":{"method":"GET","params":{"type":"2","query":"中国航发 2027校园招聘"}},"pagination":{"pageParam":"page","start":1,"pages":1}}'),
('微信·中核校招',     'https://weixin.sogou.com/weixin', 'sogou-weixin', 'institute_military', TRUE,
 '{"platform":"wechat","request":{"method":"GET","params":{"type":"2","query":"中核集团 2027校园招聘"}},"pagination":{"pageParam":"page","start":1,"pages":1}}'),
('微信·中国电信校招', 'https://weixin.sogou.com/weixin', 'sogou-weixin', 'operator', TRUE,
 '{"platform":"wechat","request":{"method":"GET","params":{"type":"2","query":"中国电信 2027校园招聘"}},"pagination":{"pageParam":"page","start":1,"pages":1}}'),
('微信·中国移动校招', 'https://weixin.sogou.com/weixin', 'sogou-weixin', 'operator', TRUE,
 '{"platform":"wechat","request":{"method":"GET","params":{"type":"2","query":"中国移动 2027校园招聘"}},"pagination":{"pageParam":"page","start":1,"pages":1}}'),
('微信·中国联通校招', 'https://weixin.sogou.com/weixin', 'sogou-weixin', 'operator', TRUE,
 '{"platform":"wechat","request":{"method":"GET","params":{"type":"2","query":"中国联通 2027校园招聘"}},"pagination":{"pageParam":"page","start":1,"pages":1}}'),
('微信·国有银行校招', 'https://weixin.sogou.com/weixin', 'sogou-weixin', 'bank', TRUE,
 '{"platform":"wechat","request":{"method":"GET","params":{"type":"2","query":"国有银行 2027校园招聘"}},"pagination":{"pageParam":"page","start":1,"pages":1}}');

-- ============ 高校人才网（关键词过滤，只留研究所/军工相关） ============
INSERT INTO crawl_sources (name, url, parser, category, enabled, meta) VALUES
('高校人才网·军工院所', 'https://www.gaoxiaojob.com/job', 'gaoxiaojob', 'community', TRUE,
 '{"platform":"gaoxiaojob","filterKeywords":["研究所","研究院","航天","航空","电科","兵器","军工","航发","船舶","核工","中科院"]}');
