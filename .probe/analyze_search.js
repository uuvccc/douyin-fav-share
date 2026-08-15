// 分析 Edge 渲染后的抖音搜索页 DOM
const fs = require('fs');
const html = fs.readFileSync(process.env.TEMP + '/dy_search.html', 'utf8');

const out = [];
out.push('TITLE: ' + ((html.match(/<title>([^<]*)<\/title>/) || [])[1] || '(none)'));
out.push('LENGTH: ' + html.length);

['用户不存在', '404', '访问过于频繁', '请完成验证', 'captcha', '验证码', '搜索'].forEach((kw) => {
  if (html.includes(kw)) out.push('FOUND: ' + kw);
});
out.push('sec_uid: ' + (html.match(/sec_uid/g) || []).length);
out.push('nickname: ' + (html.match(/nickname/g) || []).length);
out.push('/user/: ' + (html.match(/\/user\//g) || []).length);
out.push('/video/: ' + (html.match(/\/video\//g) || []).length);

const secMatch = html.match(/sec_uid[^0-9A-Za-z"']*["']?([A-Za-z0-9_\-]{20,})/);
if (secMatch) out.push('sec_uid: ' + secMatch[1]);

const bodyText = html.replace(/<script[\s\S]*?<\/script>/g, ' ')
  .replace(/<style[\s\S]*?<\/style>/g, ' ')
  .replace(/<[^>]+>/g, ' ')
  .replace(/\s+/g, ' ')
  .trim();
out.push('PAGE TEXT (first 400): ' + bodyText.slice(0, 400));

console.log(out.join('\n'));
