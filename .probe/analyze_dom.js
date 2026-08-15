// 分析 Edge 渲染后的抖音主页 DOM
const fs = require('fs');
const html = fs.readFileSync(process.env.TEMP + '/dy_dom.html', 'utf8');

const out = [];
out.push('=== TITLE ===');
const t = html.match(/<title>([^<]*)<\/title>/);
out.push(t ? t[1] : '(no title)');

out.push('=== 错误/风控特征 ===');
['用户不存在', '页面不存在', '404', '访问过于频繁', '请完成验证', '网络异常', '出错了', 'verification', 'captcha'].forEach((kw) => {
  if (html.includes(kw)) out.push('FOUND: ' + kw);
});

out.push('=== 关键词出现次数 ===');
['sec_uid', 'nickname', 'favorite_collection', 'favorite', 'collection', '收藏', 'signature'].forEach((kw) => {
  out.push(kw + ' : ' + (html.match(new RegExp(kw, 'g')) || []).length);
});

out.push('=== 视频/用户链接 ===');
out.push('/video/ links : ' + (html.match(/\/video\//g) || []).length);
out.push('/user/ links : ' + (html.match(/\/user\//g) || []).length);

// 尝试提取 sec_uid
const secMatch = html.match(/sec_uid[^0-9A-Za-z"']*["']?([A-Za-z0-9_\-]{20,})/);
if (secMatch) out.push('sec_uid found: ' + secMatch[1]);

// 提取页面正文可见文本（前 600 字）
const bodyText = html.replace(/<script[\s\S]*?<\/script>/g, ' ')
  .replace(/<style[\s\S]*?<\/style>/g, ' ')
  .replace(/<[^>]+>/g, ' ')
  .replace(/\s+/g, ' ')
  .trim();
out.push('=== PAGE TEXT (first 600) ===');
out.push(bodyText.slice(0, 600));

console.log(out.join('\n'));
