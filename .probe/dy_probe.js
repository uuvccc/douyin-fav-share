// 临时探查：验证 douyin.com/user/{数字uid} 的行为（headless 快速版）
const { chromium } = require('playwright-core');

(async () => {
  const edge = 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe';
  let browser;
  try {
    browser = await chromium.launch({
      channel: 'msedge',
      headless: true,
      args: [
        '--disable-blink-features=AutomationControlled',
        '--no-first-run',
        '--no-default-browser-check',
        '--disable-gpu',
        '--no-sandbox',
      ],
    });
    const ctx = await browser.newContext({
      userAgent: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36',
      viewport: { width: 1440, height: 900 },
    });
    const page = await ctx.newPage();

    let apiHits = 0;
    page.on('response', (resp) => {
      if (resp.url().includes('listcollection')) {
        apiHits++;
        console.log('[API] listcollection hit #' + apiHits + ' status=' + resp.status());
      }
    });

    const target = process.argv[2] || 'https://www.douyin.com/user/54132528295';
    console.log('OPEN:', target);
    await page.goto(target, { waitUntil: 'domcontentloaded', timeout: 30000 });
    await page.waitForTimeout(6000);

    console.log('URL after load:', page.url());
    console.log('TITLE:', await page.title().catch(() => '(no title)'));

    const info = await page.evaluate(() => {
      const text = (document.body ? document.body.innerText : '').slice(0, 300);
      const videoLinks = document.querySelectorAll('a[href*="/video/"]').length;
      return { text, videoLinks };
    });
    console.log('VIDEO LINKS IN DOM:', info.videoLinks);
    console.log('PAGE TEXT (first 300):', JSON.stringify(info.text));

    await ctx.close();
  } catch (e) {
    console.error('ERROR:', e.message);
    if (browser) await browser.close().catch(() => {});
    process.exit(1);
  }
})();
