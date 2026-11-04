import { test, expect } from '@playwright/test';
import { readFile } from 'node:fs/promises';
import path from 'node:path';

test('public introduction, illustrative reconciliation and login entry', async ({page}) => {
  const businessWrites: string[] = [];
  page.on('request', request => {
    if (request.url().includes('/api/v1/') && !['GET', 'HEAD', 'OPTIONS'].includes(request.method())) {
      businessWrites.push(`${request.method()} ${new URL(request.url()).pathname}`);
    }
  });
  await page.goto('/');
  await expect(page).toHaveURL(/\/welcome$/);
  await expect(page.locator('h1')).toHaveText(/给校园小店的\s*收款对账台/);
  await expect(page.getByText('演示账单 · 4 笔交易', {exact:true})).toBeVisible();
  const demo = page.getByTestId('reconciliation-demo');
  const scene = demo.getByTestId('demo-scene');
  const steps = ['01 导入账单', '02 核对记录', '03 发现差异', '04 分派与复核'];
  await expect(scene).toHaveAttribute('data-step', '0');
  for (const [index, name] of steps.entries()) {
    const step = demo.getByRole('button', {name, exact:true});
    await step.click();
    await expect(step).toHaveAttribute('aria-pressed', 'true');
    await expect(demo.locator('button[aria-pressed="true"]')).toHaveCount(1);
    await expect(scene).toHaveAttribute('data-step', String(index));
    if (index === 2) {
      await expect(scene).toContainText(/3\s*笔匹配/);
      await expect(scene).toContainText(/1\s*笔差异/);
      await expect(scene).toContainText(/0\.50/);
    }
    if (index === 3) await expect(scene).toContainText('待独立复核');
  }
  // Returning to an earlier step must replace the current state, even during a transition.
  await demo.getByRole('button', {name:steps[1], exact:true}).click();
  await demo.getByRole('button', {name:steps[3], exact:true}).click();
  await demo.getByRole('button', {name:steps[0], exact:true}).click();
  await expect(scene).toHaveAttribute('data-step', '0');
  await expect(demo.getByRole('button', {name:steps[0], exact:true})).toHaveAttribute('aria-pressed', 'true');
  expect(businessWrites, 'The public demonstration must not write business data').toEqual([]);
  const repository=page.getByRole('link',{name:'GitHub 项目仓库（在新标签页打开）'}).first();
  await expect(repository).toHaveAttribute('target','_blank');
  await expect(repository).toHaveAttribute('rel',/noopener/);
  await expect(repository).toHaveAttribute('href','https://github.com/floatingmist828-eng/Reconis');
  await page.getByRole('link',{name:'开始使用',exact:true}).first().click();
  await expect(page).toHaveURL(/\/login$/);
  await expect(page.getByPlaceholder('请输入用户名')).toBeVisible();
});

test('authenticated entry preserves protected destination and skips repeated login', async ({page}) => {
  const credentials=await readFile(path.resolve(process.cwd(),'../private/demo-accounts.txt'),'utf8');
  const password=credentials.match(/统一初始密码[：:]\s*([^\r\n]+)/)?.[1]?.trim();
  if(!password) throw new Error('Local verification credential missing');
  await page.goto('/orders');
  await expect(page).toHaveURL(/\/login\?redirect=/);
  await page.getByPlaceholder('请输入用户名').fill('finance');
  await page.getByPlaceholder('请输入密码').fill(password);
  await page.getByRole('button',{name:'进入工作空间'}).click();
  await expect(page.locator('.app-shell')).toBeVisible();
  await expect(page).toHaveURL(/\/orders$/);
  await page.waitForLoadState('networkidle');
  await page.goto('/login');
  await expect(page.locator('.workspace h1')).toHaveText('经营概览');
  await page.goto('/welcome');
  await expect(page.getByRole('link',{name:'进入工作台',exact:true}).first()).toBeVisible();
  await page.getByRole('link',{name:'进入工作台',exact:true}).first().click();
  await expect(page.locator('.workspace h1')).toBeVisible();
  await page.waitForLoadState('networkidle');
  // A list response arriving after explicit logout must not replace the login page with expiry.
  let release!: () => void;
  const pending = new Promise<void>(resolve => { release = resolve; });
  let requested!: () => void;
  const started = new Promise<void>(resolve => { requested = resolve; });
  await page.route('**/api/v1/orders?*', async route => {
    requested(); await pending;
    await route.fulfill({status:401,contentType:'application/json',body:JSON.stringify({message:'请先登录'})});
  });
  await page.getByRole('link',{name:'收款订单',exact:true}).click();
  await started;
  await page.getByRole('button',{name:'退出登录',exact:true}).click();
  await expect(page.getByPlaceholder('请输入用户名')).toBeVisible();
  release();
  await page.waitForLoadState('networkidle');
  await expect(page).toHaveURL(/\/login$/);
  await expect(page.getByText('登录已失效，请重新登录',{exact:true})).toHaveCount(0);
  await page.unroute('**/api/v1/orders?*');
  // Restore this SPA through auth/me after a second tab signs in with the shared cookie.
  const second=await page.context().newPage();
  await second.goto('/login');
  await second.getByPlaceholder('请输入用户名').fill('finance');
  await second.getByPlaceholder('请输入密码').fill(password);
  await second.getByRole('button',{name:'进入工作空间'}).click();
  await expect(second.locator('.app-shell')).toBeVisible();
  await page.getByRole('link',{name:'返回产品首页',exact:true}).click();
  await page.getByRole('link',{name:'进入工作台',exact:true}).first().click();
  await expect(page.locator('.workspace h1')).toBeVisible();
  await page.waitForLoadState('networkidle');
  await page.route('**/api/v1/dashboard?*', route=>route.fulfill({status:401,contentType:'application/json',body:JSON.stringify({message:'请先登录'})}));
  await page.getByRole('button',{name:'刷新工作台',exact:true}).click();
  await expect(page).toHaveURL(/\/login\?expired=1$/);
  await expect(page.getByText('登录已失效，请重新登录',{exact:true})).toBeVisible();
  await second.close();
});

test('mobile public pages support reduced motion and keyboard navigation', async ({page}) => {
  await page.setViewportSize({width:320,height:844});
  await page.emulateMedia({reducedMotion:'reduce'});
  await page.goto('/welcome');
  await expect(page.locator('h1')).toBeVisible();
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1)).toBe(true);
  await page.getByRole('link',{name:'试用对账示例',exact:true}).click();
  await expect(page.locator('#reconciliation-demo-section')).toBeFocused();
  const demo = page.getByTestId('reconciliation-demo');
  await demo.getByRole('button', {name:'03 发现差异', exact:true}).press('Enter');
  await expect(demo.getByTestId('demo-scene')).toHaveAttribute('data-step', '2');
  await demo.getByRole('button', {name:'04 分派与复核', exact:true}).press('Space');
  await expect(demo.getByTestId('demo-scene')).toHaveAttribute('data-step', '3');
  await expect(demo.getByTestId('demo-scene')).toContainText('待独立复核');
  await page.goto('/login');
  await page.getByRole('button',{name:'注册账号',exact:true}).click();
  await expect(page.getByPlaceholder('便于协作人员识别')).toBeVisible();
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1)).toBe(true);
});

for (const width of [1440, 1280, 390]) {
  test(`public product scenes remain readable without motion at ${width}px`, async ({page}) => {
    await page.setViewportSize({width, height:900});
    await page.emulateMedia({reducedMotion:'reduce'});
    await page.goto('/welcome');
    await expect(page.locator('h1')).toBeVisible();
    await expect(page.getByRole('link', {name:'开始使用', exact:true}).first()).toBeVisible();
    const demo = page.getByTestId('reconciliation-demo');
    for (const [index, name] of ['01 导入账单', '02 核对记录', '03 发现差异', '04 分派与复核'].entries()) {
      await demo.getByRole('button', {name, exact:true}).click();
      await expect(demo.getByTestId('demo-scene')).toHaveAttribute('data-step', String(index));
      await expect(demo.getByTestId('demo-scene')).toBeVisible();
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), `Step ${index} must fit ${width}px`).toBe(true);
    }
    const analysis = page.getByTestId('analysis-demo');
    await expect(analysis).toBeVisible();
    const initialBounds = await analysis.boundingBox();
    expect(initialBounds).not.toBeNull();
    const expectStableAnalysis = async (scope: string) => {
      await expect(analysis).toHaveAttribute('data-scope', scope);
      await expect(analysis).toContainText(/66\.00/);
      await expect(analysis).toContainText('3 个订单，4 笔资金记录');
      await expect.poll(async () => {
        const bounds = await analysis.boundingBox();
        return bounds && initialBounds ? Math.abs(bounds.height - initialBounds.height) : Infinity;
      }, {message:'Changing analysis depth must preserve the outer frame height'}).toBeLessThanOrEqual(2);
    };
    await expectStableAnalysis('0');
    await analysis.getByRole('button', {name:'查看门店', exact:true}).click();
    await expect(analysis.getByRole('button', {name:'查看 4 笔流水', exact:true})).toBeVisible();
    await expect(analysis.getByRole('button', {name:'门店', exact:true})).toBeFocused();
    await expectStableAnalysis('1');
    await analysis.getByRole('button', {name:'查看 4 笔流水', exact:true}).click();
    await expect(analysis.getByText('退款 · 原订单 001', {exact:true})).toBeVisible();
    await expect(analysis.getByText('−12.00', {exact:true})).toBeVisible();
    await expect(analysis.getByRole('button', {name:'流水', exact:true})).toBeFocused();
    await expectStableAnalysis('2');
    await analysis.getByRole('button', {name:'图书馆店', exact:true}).click();
    await expect(analysis.getByRole('button', {name:'查看 4 笔流水', exact:true})).toBeVisible();
    await expectStableAnalysis('1');
    await analysis.getByRole('button', {name:'商户', exact:true}).press('Enter');
    await expect(analysis.getByRole('button', {name:'查看门店', exact:true})).toBeVisible();
    await expect(analysis.getByRole('button', {name:'商户', exact:true})).toBeFocused();
    await expectStableAnalysis('0');
    await expect(page.locator('#capabilities')).toBeVisible();
    await page.getByRole('link', {name:'开始使用', exact:true}).last().scrollIntoViewIfNeeded();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true);
  });
}
