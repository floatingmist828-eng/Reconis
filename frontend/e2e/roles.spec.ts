import { test, expect, type Page, type Browser, type BrowserContext } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import { readFile, mkdir, writeFile } from 'node:fs/promises';
import path from 'node:path';

// Only temporary accounts are disabled; financial facts and audit evidence remain intact.
// Credentials and authentication cookies are never written to the public result files.
const base = process.env.RECONIS_UI_URL || 'http://127.0.0.1:8080';
const root = path.resolve(process.cwd(), '..');
const run = 'UI' + Date.now().toString() + randomUUID().slice(0, 5);
const secret = randomUUID() + 'aA1!';
const roles = ['owner', 'handler', 'reviewer', 'customer', 'outsider', 'finance', 'auditor'] as const;
type Role = typeof roles[number] | 'admin';
const users: Record<string, any> = {};
const records: Record<string, any> = { run, pages: [], accounts: [], cleanup: [] };
const output = path.join(root, 'artifacts/validation/browser-' + run);
const clients: Record<string, Client> = {};
let adminSecret: string;

class Client {
  cookies = new Map<string, string>(); token = ''; me: any;
  async call(method: string, route: string, body?: any, expected = 200) {
    const form = body instanceof FormData;
    const response = await fetch(base + '/api/v1' + route, {
      method, redirect: 'manual', signal: AbortSignal.timeout(45_000),
      headers: { Cookie: [...this.cookies].map(([k,v]) => `${k}=${v}`).join('; '),
        ...(method !== 'GET' ? {'X-XSRF-TOKEN': this.token} : {}),
        ...(body && !form ? {'Content-Type':'application/json'} : {}) },
      body: body === undefined ? undefined : form ? body : JSON.stringify(body),
    });
    for (const cookie of response.headers.getSetCookie()) {
      const first = cookie.split(';')[0]!; const at = first.indexOf('=');
      this.cookies.set(first.slice(0, at), first.slice(at+1));
    }
    const data: any = await response.json();
    if (response.status !== expected) throw new Error(`${method} ${route}: ${response.status} ${data.message || ''}`);
    return data.data;
  }
  async login(username: string, password: string) {
    this.token = (await this.call('GET', '/auth/csrf')).token;
    await this.call('POST', '/auth/login', {username, password});
    this.token = (await this.call('GET', '/auth/csrf')).token;
    this.me = await this.call('GET', '/auth/me'); return this;
  }
}
async function persist() { await writeFile(path.join(output, 'checks.json'), JSON.stringify(records, null, 2)); }
async function login(browser: Browser, role: Role, width = 1440) {
  const context = await browser.newContext({ baseURL: base, viewport: {width, height: 1000} });
  const page = await context.newPage();
  await page.goto('/login');
  await page.getByPlaceholder('请输入用户名').fill(role === 'admin' ? 'admin' : users[role].username);
  await page.getByPlaceholder('请输入密码').fill(role === 'admin' ? adminSecret : secret);
  await page.getByRole('button', {name:'进入工作空间'}).click();
  await expect(page.locator('.app-shell')).toBeVisible();
  return {context, page};
}
async function visit(page: Page, route: string, role: string) {
  const failures: string[] = [];
  const listener = (r: any) => { if(r.url().includes('/api/v1/') && r.status() >= 400) failures.push(`${r.status()} ${new URL(r.url()).pathname}`); };
  const errors: string[] = []; const onError = (e: Error) => errors.push(e.message);
  page.on('response', listener); page.on('pageerror', onError);
  try {
    await page.goto(route); await expect(page.locator('.workspace h1')).toBeVisible();
    await page.waitForLoadState('networkidle');
    await expect(page.locator('.el-loading-mask:visible')).toHaveCount(0);
    expect(errors, `${role} ${route} browser errors`).toEqual([]);
    expect(failures, `${role} ${route} failed requests`).toEqual([]);
    records.pages.push({role, route, status:'PASSED', width:page.viewportSize()?.width}); await persist();
  } finally { page.off('response', listener); page.off('pageerror', onError); }
}
async function approveUI(page: Page, username: string) {
  await page.goto('/users');
  await page.getByPlaceholder('用户名 / 显示姓名').fill(username);
  await page.getByRole('button',{name:'查询账号',exact:true}).click();
  await page.locator('.el-table__row').filter({hasText:username}).getByRole('button',{name:'审核与权限'}).click();
  const dialog = page.getByRole('dialog');
  await dialog.getByLabel('审核意见').fill('页面验收：核实注册信息');
  await dialog.getByRole('button',{name:'保存审核结果'}).click();
  await expect(dialog).toBeHidden();
}
test.beforeAll(async () => {
  await mkdir(output, {recursive:true});
  adminSecret = process.env.RECONIS_VERIFY_PASSWORD || (await readFile(path.join(root,'private/demo-accounts.txt'),'utf8')).match(/统一初始密码[：:]\s*([^\r\n]+)/)?.[1]?.trim() || '';
  if(!adminSecret) throw new Error('DEMO administrator password is required locally.');
  clients.admin = await new Client().login('admin', adminSecret);
  expect((await clients.admin.call('GET','/integration/status')).environment).toBe('DEMO');
  const anon = new Client(); anon.token = (await anon.call('GET','/auth/csrf')).token;
  for(const role of roles) {
    const username = `${run}_${role}`;
    await anon.call('POST','/auth/register',{username,password:secret,displayName:`页面验收 ${role}`});
    const row = (await clients.admin.call('GET','/admin/users?q='+username)).items.find((u:any)=>u.username===username);
    users[role] = {id:row.id, username}; records.accounts.push(users[role]); await persist();
    await clients.admin.call('POST',`/admin/users/${row.id}/review`,{status:'ACTIVE', platformRole:role==='finance'?'PLATFORM_FINANCE':role==='auditor'?'AUDITOR':'MEMBER',reason:run});
    clients[role] = await new Client().login(username,secret);
  }
  const merchant = await clients.owner.call('POST','/merchants',{name:`页面验收 ${run}`,categoryCode:'校园餐饮',contact:'本地页面验证'});
  records.merchantId = merchant.id;
  const pending = (await clients.admin.call('GET','/merchants?q='+run)).items.find((m:any)=>m.id===merchant.id);
  await clients.admin.call('POST',`/merchants/${merchant.id}/review`,{approve:true,version:pending.version,reason:run});
  records.merchantCode = pending.merchantCode;
  records.storeId = (await clients.owner.call('POST',`/merchants/${merchant.id}/stores`,{storeCode:'S'+run.slice(-10),name:'页面验收一店',locationText:'校园区'})).id;
  for(const [role,code] of [['handler','CASHIER'],['reviewer','REVIEWER']]) {
    await clients.owner.call('POST',`/merchants/${merchant.id}/members`,{username:users[role!].username,roleCode:code});
  }
  const draft = await clients.owner.call('POST','/orders',{clientRequestId:randomUUID(),storeId:records.storeId,subject:run+' 对账样本',consumerUsername:users.customer.username,items:[{itemName:'校园商品',quantity:1,unitPriceCent:10000}]});
  records.sampleOrderId = draft.id;
  await clients.owner.call('POST',`/orders/${draft.id}/submit`);
  const payment = await clients.customer.call('POST',`/orders/${draft.id}/payments`,{clientRequestId:randomUUID()});
  await clients.customer.call('POST',`/payments/${payment.id}/demo`,{mode:'NOTIFY'});
  const order = await clients.owner.call('GET',`/orders/${draft.id}`);
  const entry = order.entries[0];
  records.bill = `<?xml version="1.0" encoding="UTF-8"?><statement schemaVersion="1" channelEnv="DEMO"><event merchantCode="${records.merchantCode}" type="RECEIPT"><outTradeNo>${entry.outTradeNo}</outTradeNo><channelTradeNo>${entry.channelTradeNo}</channelTradeNo><outRequestNo></outRequestNo><amountCent>10001</amountCent><occurredAt>${entry.channelOccurredAt}</occurredAt></event></statement>`;
  const day = new Intl.DateTimeFormat('en-CA',{timeZone:'Asia/Shanghai'}).format(new Date());
  const form = new FormData(); form.set('file',new Blob([records.bill]),run+'.xml');
  for(const [key,value] of Object.entries({start:day,end:day,merchantId:records.merchantId,completeness:'DECLARED_COMPLETE',preview:'false'})) form.set(key,String(value));
  const imported = await clients.owner.call('POST','/reconciliation/imports',form);
  records.batchId = imported.batches[0].id;
  await clients.owner.call('POST',`/reconciliation/batches/${records.batchId}/run`);
  records.caseId = (await clients.owner.call('GET','/cases')).items.find((c:any)=>c.merchantId===records.merchantId).id;
  await persist();
});
test.afterAll(async () => {
  for(const account of records.accounts) {
    try {
      const current = (await clients.admin.call('GET','/admin/users?q='+account.username)).items.find((u:any)=>u.id===account.id && u.username===account.username);
      if(!current || !account.username.startsWith(run+'_')) throw new Error('Cleanup identity mismatch');
      await clients.admin.call('POST',`/admin/users/${account.id}/review`,{status:'DISABLED',platformRole:current.platformRole,reason:run+' 页面验收结束'});
      records.cleanup.push({id:account.id,status:'DISABLED'});
    } catch { records.cleanup.push({id:account.id,status:'PENDING'}); }
  }
  await persist(); expect(records.cleanup.filter((c:any)=>c.status==='PENDING')).toEqual([]);
});

test('eight roles: page access, permitted actions and empty states', async ({browser}) => {
  for(const role of ['admin',...roles] as Role[]) {
    const {page,context} = await login(browser,role);
    try {
      const routes = role==='admin' ? ['/','/users','/merchants','/audit','/integration'] : ['/','/orders','/refunds','/merchants','/reconciliation','/cases','/search','/analytics','/integration',...(role==='auditor'?['/audit']:[])];
      for(const route of routes) await visit(page,route,role);
      await page.goto('/orders');
      if(['customer','outsider','reviewer','auditor','admin'].includes(role)) await expect(page.getByRole('button',{name:'新建收款订单',exact:true})).toHaveCount(0);
      else await expect(page.getByRole('button',{name:'新建收款订单',exact:true})).toBeVisible();
      if(['reviewer','auditor','customer','handler'].includes(role)) {
        await visit(page,'/orders/'+records.sampleOrderId,role);
        if(['reviewer','auditor'].includes(role)) await expect(page.getByRole('button',{name:'申请退款',exact:true})).toHaveCount(0);
        if(role==='customer') await expect(page.getByRole('button',{name:'取消订单',exact:true})).toHaveCount(0);
      }
      if(role==='outsider') {
        await page.getByPlaceholder('订单编号 / 收款主题').fill(run);
        await page.getByRole('button',{name:'查询',exact:true}).click();
        await expect(page.locator('.el-table__body-wrapper .el-table__row')).toHaveCount(0);
        await page.goto('/users'); await expect(page).toHaveURL(base+'/');
      }
      await page.getByRole('button',{name:'退出登录',exact:true}).click();
      await expect(page).toHaveURL(/\/login$/);
    } finally { await context.close(); }
  }
});

test('browser order creation, consumer payment and independent refund approval', async ({browser}) => {
  const owner = await login(browser,'owner'), customer = await login(browser,'customer');
  try {
    await owner.page.goto('/orders'); await owner.page.getByRole('button',{name:'新建收款订单'}).click();
    const form = owner.page.getByRole('dialog');
    await form.getByLabel('订单主题',{exact:true}).fill(run+' 页面收款');
    await form.getByPlaceholder('留空为柜台匿名订单').fill(users.customer.username);
    await form.locator('.el-table__row input').nth(0).fill('校园服务');
    await form.locator('.el-table__row input').nth(2).fill('12.34');
    await form.getByRole('button',{name:'创建订单草稿'}).click();
    await expect(owner.page).toHaveURL(/\/orders\/\d+$/);
    const id = owner.page.url().split('/').pop()!; records.uiOrderId = id;
    await owner.page.getByRole('button',{name:'提交收款',exact:true}).click();
    await expect(owner.page.getByRole('button',{name:'打开付款',exact:true})).toBeVisible();
    await customer.page.goto('/orders/'+id);
    await expect(customer.page.getByRole('button',{name:'取消订单',exact:true})).toHaveCount(0);
    await customer.page.getByRole('button',{name:'打开付款',exact:true}).click();
    const pay = customer.page.getByRole('dialog');
    await expect(pay.getByAltText('收款二维码')).toBeVisible();
    await pay.getByRole('button',{name:'模拟支付通知',exact:true}).click();
    await expect(pay.getByText('付款已确认，可关闭此窗口查看收款记录。')).toBeVisible();
    await pay.getByRole('button',{name:'关闭',exact:true}).click();
    await customer.page.getByRole('button',{name:'申请退款',exact:true}).click();
    const refund = customer.page.getByRole('dialog');
    await refund.getByLabel('退款金额（元）').fill('12.34');
    await refund.getByLabel('申请原因').fill(run+' 页面全额退款');
    await refund.getByRole('button',{name:'提交退款申请'}).click();
    await expect(refund).toBeHidden();
    await customer.page.goto('/refunds');
    await customer.page.getByPlaceholder('退款编号 / 申请原因').fill(run);
    await customer.page.getByRole('button',{name:'查询',exact:true}).click();
    await expect(customer.page.getByRole('button',{name:'批准',exact:true})).toHaveCount(0);
    await expect(customer.page.getByText('等待他人审批')).toBeVisible();
    await owner.page.goto('/refunds');
    await owner.page.getByPlaceholder('退款编号 / 申请原因').fill(run);
    await owner.page.getByRole('button',{name:'查询',exact:true}).click();
    await owner.page.getByRole('button',{name:'批准',exact:true}).click();
    const approval = owner.page.getByRole('dialog');
    await approval.getByRole('textbox').fill('已核实订单与退款申请，独立同意');
    await approval.getByRole('button',{name:/确定|确认/}).click();
    await expect(approval).toBeHidden();
    await customer.page.goto('/orders/'+id);
    await expect(customer.page.getByText('已全额退款',{exact:true})).toBeVisible();
    await expect(customer.page.getByRole('button',{name:'申请退款',exact:true})).toHaveCount(0);
    await customer.page.screenshot({path:path.join(output,'customer-refunded.png'),fullPage:true});
    const saved = await clients.customer.call('GET','/orders/'+id);
    expect(saved.entries.map((e:any)=>e.entryType).sort()).toEqual(['RECEIPT','REFUND']);
    expect(saved.entries.every((e:any)=>e.amountCent==='1234')).toBe(true);
    records.paymentFlow = 'PASSED'; await persist();
  } finally { await owner.context.close(); await customer.context.close(); }
});

test('assignment, evidence, return, resubmission, independent review and dossier download', async ({browser}) => {
  const owner = await login(browser,'owner'), handler = await login(browser,'handler'), reviewer = await login(browser,'reviewer');
  try {
    const route = '/cases/'+records.caseId;
    await owner.page.goto(route); await owner.page.getByRole('button',{name:'分派任务',exact:true}).click();
    const assign = owner.page.getByRole('dialog');
    await assign.getByRole('combobox').click();
    await owner.page.getByRole('option').filter({hasText:'页面验收 handler'}).click();
    await assign.getByRole('button',{name:'确认分派'}).click(); await expect(assign).toBeHidden();
    await handler.page.goto(route);
    await handler.page.getByLabel('原因分析',{exact:true}).fill('页面验收：渠道金额多一分，核对原始凭据');
    await handler.page.getByLabel('处理步骤与结论',{exact:true}).fill('已核实教学账单差额，保留原始对账结果');
    await handler.page.getByRole('button',{name:'保存处理记录'}).click();
    await handler.page.locator('input[type=file]').setInputFiles({name:'evidence.txt',mimeType:'text/plain',buffer:Buffer.from(run+' 页面核查证据')});
    await handler.page.getByRole('button',{name:'上传证据',exact:true}).click();
    await expect(handler.page.getByRole('button',{name:'下载已保存证据 ↓'})).toBeVisible();
    await handler.page.getByRole('button',{name:'提交独立复核',exact:true}).click();
    await expect(handler.page.getByRole('button',{name:'提交独立复核',exact:true})).toHaveCount(0);
    await expect(handler.page.getByRole('button',{name:'独立复核',exact:true})).toHaveCount(0);
    await reviewer.page.goto(route); await reviewer.page.getByRole('button',{name:'独立复核',exact:true}).click();
    let review = reviewer.page.getByRole('dialog');
    await review.getByText('退回继续处理',{exact:true}).click();
    await review.getByLabel('复核意见与依据').fill('请补充差额处理依据');
    await review.getByRole('button',{name:'提交复核结论'}).click(); await expect(review).toBeHidden();
    await handler.page.reload();
    await handler.page.getByLabel('处理步骤与结论',{exact:true}).fill('已补充：教学账单一分差额核查完毕，原始差错与证据继续保留');
    await handler.page.getByRole('button',{name:'保存处理记录'}).click();
    await handler.page.getByRole('button',{name:'提交独立复核',exact:true}).click();
    await expect(handler.page.getByRole('button',{name:'提交独立复核',exact:true})).toHaveCount(0);
    await reviewer.page.reload(); await reviewer.page.getByRole('button',{name:'独立复核',exact:true}).click();
    review = reviewer.page.getByRole('dialog');
    await review.getByLabel('复核意见与依据').fill('独立核查原始凭据与差额说明，按已说明例外结案');
    await review.getByRole('button',{name:'提交复核结论'}).click(); await expect(review).toBeHidden();
    await expect(reviewer.page.locator('.heading-actions')).toContainText('已关闭');
    await reviewer.page.getByRole('button',{name:'查看档案',exact:true}).click();
    const downloadPromise = reviewer.page.waitForEvent('download');
    await reviewer.page.getByRole('button',{name:'下载 ZIP',exact:true}).click();
    const download = await downloadPromise; await download.saveAs(path.join(output,'case-evidence.zip'));
    await reviewer.page.screenshot({path:path.join(output,'reviewer-closed-case.png'),fullPage:true});
    await handler.page.reload(); await expect(handler.page.getByLabel('原因分析',{exact:true})).toBeDisabled();
    expect((await clients.reviewer.call('GET','/cases/'+records.caseId)).status).toBe('CLOSED');
    records.caseFlow = 'PASSED'; await persist();
  } finally { await owner.context.close(); await handler.context.close(); await reviewer.context.close(); }
});

test('registration, administrator review, mobile layout and payment error presentation', async ({browser}) => {
  const applicantContext = await browser.newContext({baseURL:base}); const applicant = await applicantContext.newPage();
  const admin = await login(browser,'admin'), customer = await login(browser,'customer',390);
  try {
    const username = run+'_new';
    await applicant.goto('/login'); await applicant.getByRole('button',{name:'注册账号',exact:true}).click();
    await applicant.getByPlaceholder('请输入用户名').fill(username);
    await applicant.getByPlaceholder('便于协作人员识别').fill('页面注册申请');
    await applicant.getByPlaceholder('至少10位密码').fill(secret);
    await applicant.getByRole('button',{name:'提交注册申请'}).click();
    await expect(applicant.getByRole('button',{name:'进入工作空间'})).toBeVisible();
    const account = (await clients.admin.call('GET','/admin/users?q='+username)).items.find((u:any)=>u.username===username);
    records.accounts.push({id:account.id,username}); await persist();
    await applicant.getByRole('button',{name:'进入工作空间'}).click();
    await expect(applicant.locator('.el-message--error')).toBeVisible();
    await approveUI(admin.page,username);
    await applicant.getByRole('button',{name:'进入工作空间'}).click(); await expect(applicant.locator('.app-shell')).toBeVisible();
    await clients.admin.call('POST',`/admin/users/${account.id}/review`,{status:'DISABLED',platformRole:'MEMBER',reason:run+' 验证停用立即退出'});
    await applicant.getByRole('button',{name:'刷新工作台',exact:true}).click();
    await expect(applicant).toHaveURL(/\/login\?expired=1$/);
    await expect(applicant.getByText('登录已失效，请重新登录',{exact:true})).toBeVisible();
    for(const route of ['/','/orders','/refunds','/merchants','/reconciliation','/cases','/search','/analytics','/integration','/orders/'+records.sampleOrderId]) {
      await visit(customer.page,route,'customer-mobile');
      expect(await customer.page.evaluate(()=>document.documentElement.scrollWidth <= window.innerWidth+1),route+' mobile width').toBe(true);
    }
    await customer.page.screenshot({path:path.join(output,'customer-mobile.png'),fullPage:true});
    // Inject only a read response to inspect persisted failure UI; no simulated channel mutation.
    await customer.page.route('**/api/v1/orders/'+records.sampleOrderId,async route=>{
      const response = await route.fetch(); const body = await response.json();
      body.data.payments[0].status = 'FAILED'; body.data.payments[0].lastResultMessage = '支付渠道拒绝了本次请求，请联系商户检查接入配置。';
      await route.fulfill({response,json:body});
    });
    await customer.page.reload();
    await expect(customer.page.getByText('支付渠道拒绝了本次请求，请联系商户检查接入配置。',{exact:true})).toBeVisible();
    await customer.page.unrouteAll();
    records.registrationAndMobile = 'PASSED'; await persist();
  } finally {await applicantContext.close();await admin.context.close();await customer.context.close();}
});

test('bill import and comparison, analytics drilldown, risk details, reports and search', async ({browser}) => {
  const {page,context} = await login(browser,'owner');
  try {
    await page.goto('/reconciliation');
    await page.getByRole('button',{name:'＋ 导入渠道账单',exact:true}).click();
    const form = page.getByRole('dialog',{name:'导入渠道账单',exact:true});
    await form.locator('input[type=file]').setInputFiles({name:'browser-bill.xml',mimeType:'application/xml',buffer:Buffer.from(records.bill.replace('</statement>','<!--browser import--></statement>'))});
    await form.getByRole('button',{name:'预览与校验'}).click();
    await expect(form.getByRole('button',{name:'导入有效原始行'})).toBeEnabled();
    await form.getByRole('button',{name:'导入有效原始行'}).click();
    await expect(form).toBeHidden();
    const drawer = page.getByRole('dialog',{name:/^对账结果/});
    await drawer.getByRole('button',{name:'执行对账',exact:true}).click();
    await expect(drawer.getByRole('button',{name:'创建重跑批次',exact:true})).toBeVisible();
    await expect(drawer.getByText('金额不符',{exact:true}).first()).toBeVisible();
    await drawer.locator('.el-table__row').filter({hasText:'金额不符'}).first().click();
    await expect(page.getByRole('dialog').last()).toContainText('本地');
    await page.getByRole('dialog').last().locator('.el-dialog__headerbtn').click();
    await drawer.getByRole('tab',{name:'历史批次比较',exact:true}).click();
    await drawer.locator('.el-select').filter({hasText:'选择同一归属范围的基线批次'}).click();
    await page.getByRole('option').first().click();
    await drawer.getByRole('button',{name:'比较两个运行',exact:true}).click();
    await expect(drawer.getByRole('tabpanel',{name:'历史批次比较'}).locator('.el-table__row').first()).toBeVisible();
    await page.goto('/analytics'); await page.waitForLoadState('networkidle');
    const groupPanel = page.locator('section.panel').filter({has:page.getByRole('heading',{name:'分组汇总与钻取',exact:true})});
    await groupPanel.locator('.el-table__row').filter({hasText:run}).click();
    await groupPanel.locator('.el-table__row').filter({hasText:'页面验收一店'}).click();
    await expect(page.getByRole('heading',{name:'交易明细',exact:true})).toBeVisible();
    await expect(page.locator('.filter-breadcrumb').filter({hasText:'全部范围'})).toContainText('页面验收一店');
    for(const [label,filename] of [['PDF 图文统计报告','report.pdf'],['Word 图文统计报告','report.docx'],['Excel 交易明细','transactions.xlsx'],['XML 标准账单','transactions.xml']]) {
      await page.getByRole('button',{name:'导出分析 / 流水 ↓',exact:true}).hover();
      const downloading = page.waitForEvent('download');
      await page.getByRole('menuitem',{name:label,exact:true}).click();
      const file=await downloading; await file.saveAs(path.join(output,filename!));
      expect((await readFile(path.join(output,filename!))).length).toBeGreaterThan(100);
    }
    await page.getByRole('button',{name:/^差错率/}).click();
    await expect(page.getByRole('dialog')).toContainText('有效差错事件');
    await expect(page.getByRole('dialog').locator('.el-table__row').first()).toBeVisible();
    await page.goto('/search');
    await page.getByPlaceholder('描述遇到的问题，例如：支付成功但通知未到达').fill('账单');
    const response = page.waitForResponse(r=>r.url().includes('/api/v1/cases/search?'));
    await page.getByRole('button',{name:'全文检索',exact:true}).click();
    expect((await (await response).json()).data.engine).toBe('SQL_SERVER_FREETEXTTABLE');
    await expect(page.locator('.help-note')).toContainText('条结果');
    records.advancedBrowserFlow='PASSED'; await persist();
  } finally {await context.close();}
});
