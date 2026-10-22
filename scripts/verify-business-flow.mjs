// Real HTTP verification against an already initialized DEMO server.
// Existing records are never deleted. All created accounts and business records use a unique run ID.
// Test accounts are disabled afterwards; financial facts, bills and audit evidence remain immutable.
// Passwords are supplied only through the environment and are excluded from output artifacts.
import assert from 'node:assert/strict';
import { randomUUID, createHash } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { inflateRawSync } from 'node:zlib';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { loadVerificationJar } from './verification-jar.mjs';

const args = Object.fromEntries(process.argv.slice(2).reduce((pairs, value, i, all) => {
  if (i % 2 === 0) pairs.push([value.replace(/^--/, ''), all[i + 1]]);
  return pairs;
}, []));
const base = (args['base-url'] || 'http://127.0.0.1:8080').replace(/\/$/, '');
const baseUrl = new URL(base);
if (!['http:', 'https:'].includes(baseUrl.protocol) || !['127.0.0.1', 'localhost', '[::1]'].includes(baseUrl.hostname)
  || baseUrl.username || baseUrl.password || baseUrl.search || baseUrl.hash || baseUrl.pathname !== '/') {
  throw new Error('Business verification accepts only a loopback HTTP(S) origin without credentials, query or fragment.');
}
const previousResult = args['cleanup-results'] ? JSON.parse(await readFile(args['cleanup-results'], 'utf8')) : null;
if (previousResult && (!/^V\d{14}[a-f0-9]{6}$/.test(previousResult.runId) || previousResult.baseUrl !== base || !previousResult.records)) {
  throw new Error('Cleanup requires an original verification result for this same local server.');
}
const verificationJar = previousResult ? null : await loadVerificationJar({
  root: path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..'),
  jarFile: args.jar || process.env.RECONIS_VERIFY_JAR,
  expectedSha256: args.jar ? args['jar-sha256'] : process.env.RECONIS_VERIFY_JAR_SHA256,
});
const runId = previousResult?.runId || `V${new Date().toISOString().replace(/\D/g, '').slice(0, 14)}${randomUUID().slice(0, 6)}`;
const password = process.env.RECONIS_VERIFY_PASSWORD;
if (!password) throw new Error('RECONIS_VERIFY_PASSWORD is required; never pass a password on the command line.');
const testPassword = `${randomUUID()}aA1!`;
const results = { runId, baseUrl: base, mode: previousResult ? 'CLEANUP' : 'VERIFY', startedAt: new Date().toISOString(), checks: [], records: previousResult?.records || {}, status: 'RUNNING' };
if (verificationJar) results.verificationJar = { path: verificationJar.path, sha256: verificationJar.sha256, releaseId: verificationJar.releaseId };
const output = path.resolve(args.output || 'artifacts/validation');
await mkdir(output, { recursive: true });
const resultFile = path.join(output, `${runId}${previousResult ? `.cleanup-${Date.now()}` : ''}.json`);
const fileDirectory = path.join(output, `${runId}-files`);
const createdAccounts = [];
let demoConfirmed = false;
let currentStep = 'startup';
async function checkpoint() { await writeFile(resultFile, JSON.stringify(results, null, 2), 'utf8'); }

class Client {
  constructor(name) { this.name = name; this.cookies = new Map(); }
  async call(method, route, body, expected = 200, raw = false) {
    const headers = { Cookie: [...this.cookies].map(([k, v]) => `${k}=${v}`).join('; ') };
    if (method !== 'GET' && this.csrf) headers[this.csrf.headerName] = this.csrf.token;
    if (body !== undefined && !(body instanceof FormData)) {
      headers['Content-Type'] = 'application/json';
      body = JSON.stringify(body);
    }
    const response = await fetch(`${base}${route}`, { method, headers, body, redirect: 'manual', signal: AbortSignal.timeout(90000) });
    for (const cookie of response.headers.getSetCookie()) {
      const first = cookie.split(';', 1)[0];
      const split = first.indexOf('=');
      this.cookies.set(first.slice(0, split), first.slice(split + 1));
    }
    const bytes = Buffer.from(await response.arrayBuffer());
    let payload;
    try { payload = JSON.parse(bytes.toString('utf8')); } catch { payload = null; }
    const expectedStatuses = Array.isArray(expected) ? expected : [expected];
    if (!expectedStatuses.includes(response.status)) {
      throw new Error(`${this.name}: ${method} ${route}: HTTP ${response.status}; expected ${expectedStatuses}; ${payload?.message || payload?.error || 'unexpected response'}; requestId=${payload?.requestId || '-'}`);
    }
    if (raw) return { bytes, headers: Object.fromEntries(response.headers), status: response.status };
    if (response.status >= 400) return payload;
    return payload?.data;
  }
  async token() { this.csrf = await this.call('GET', '/api/v1/auth/csrf'); }
  async login(username, secret) {
    await this.token();
    await this.call('POST', '/api/v1/auth/login', { username, password: secret });
    await this.token();
    this.me = await this.call('GET', '/api/v1/auth/me');
    return this;
  }
}
const admin = new Client('admin');
const owner = new Client('owner');
const handler = new Client('handler');
const reviewer = new Client('reviewer');
const customer = new Client('customer');
const outsider = new Client('outsider');
const finance = new Client('finance');
const auditor = new Client('auditor');
const anon = new Client('anonymous');
const clients = { owner, handler, reviewer, customer, outsider, finance, auditor };
const ids = results.records;
const query = (values) => new URLSearchParams(Object.entries(values).filter(([, v]) => v !== null && v !== undefined).map(([k, v]) => [k, String(v)])).toString();
const xmlEscape = (s) => String(s ?? '').replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;').replaceAll('"', '&quot;').replaceAll("'", '&apos;');
const statement = (entries, tag = '') => `<?xml version="1.0" encoding="UTF-8"?><statement schemaVersion="1" channelEnv="DEMO"><!--${tag}-->${entries.map(e => `<event merchantCode="${xmlEscape(e.merchantCode)}" type="${e.entryType}"><outTradeNo>${xmlEscape(e.outTradeNo)}</outTradeNo><channelTradeNo>${xmlEscape(e.channelTradeNo)}</channelTradeNo><outRequestNo>${xmlEscape(e.outRequestNo)}</outRequestNo><amountCent>${e.amountCent}</amountCent><occurredAt>${e.channelOccurredAt}</occurredAt></event>`).join('')}</statement>`;
const number = (v) => Number(v ?? 0);
async function step(name, action) {
  currentStep = name;
  const started = Date.now();
  const detail = await action();
  results.checks.push({ name, status: 'PASSED', durationMs: Date.now() - started, ...(detail ? { detail } : {}) });
  await checkpoint();
  console.log(`PASS ${name}`);
}
async function createAccount(key, role = 'MEMBER') {
  const username = `${runId}_${key}`.slice(0, 32);
  const client = clients[key];
  await anon.token();
  await anon.call('POST', '/api/v1/auth/register', { username, password: testPassword, displayName: `验证${key}${runId}` });
  await anon.call('POST', '/api/v1/auth/login', { username, password: testPassword }, 403);
  const found = await admin.call('GET', `/api/v1/admin/users?${query({ q: username })}`);
  const user = found.items.find(x => x.username === username);
  assert.ok(user, 'registered account must exist');
  const record = { id: user.id, username };
  ids[key] = record;
  createdAccounts.push(record);
  await checkpoint(); // Record IDs before approval so interrupted runs can safely disable them later.
  await admin.call('POST', `/api/v1/admin/users/${user.id}/review`, { platformRole: role, status: 'ACTIVE', reason: `${runId} 自动化业务验证` });
  await client.login(username, testPassword);
  assert.equal(client.me.platformRole, role);
  return record;
}

async function disableVerificationAccounts() {
  const cleanup = { policy: 'Disable only accounts from this exact run; retain financial facts, imported bills, generated files and audit history.', accounts: [], status: 'COMPLETED' };
  const accounts = previousResult ? Object.keys(clients).map(key => {
    const account = ids[key];
    if (!account) return null;
    assert.equal(account.username, `${runId}_${key}`.slice(0, 32), 'Cleanup account name does not belong to this run');
    assert.match(String(account.id), /^[1-9]\d*$/, 'Cleanup account ID is invalid');
    return account;
  }).filter(Boolean) : createdAccounts;
  for (const account of accounts) {
    try {
      assert.notEqual(account.id, admin.me.id, 'Never disable the administrator executing verification');
      const found = await admin.call('GET', `/api/v1/admin/users?${query({ q: account.username })}`);
      const current = found.items.find(row => row.username === account.username && row.id === account.id);
      assert.ok(current, 'Saved ID and unique username must both match before changing an account');
      if (current.status !== 'DISABLED') await admin.call('POST', `/api/v1/admin/users/${account.id}/review`, {
        platformRole: current.platformRole, status: 'DISABLED', reason: `${runId} 自动化验证结束，停用本次验证账号并保留业务与审计历史`,
      });
      cleanup.accounts.push({ id: account.id, username: account.username, status: 'DISABLED' });
    } catch (error) {
      cleanup.status = 'PENDING';
      cleanup.accounts.push({ id: account.id, username: account.username, status: 'PENDING', message: error.message });
    }
    results.cleanup = cleanup;
    await checkpoint();
  }
  results.cleanup = cleanup;
  return cleanup.status === 'COMPLETED';
}

// Read selected ordinary ZIP entries without extracting arbitrary paths or requiring npm libraries.
function zipEntries(bytes, select) {
  assert.ok(bytes.length >= 22, 'ZIP file is incomplete');
  const minimum = Math.max(0, bytes.length - 65557);
  let end = bytes.length - 22;
  while (end >= minimum && bytes.readUInt32LE(end) !== 0x06054b50) end--;
  assert.ok(end >= minimum, 'ZIP central directory is missing');
  const count = bytes.readUInt16LE(end + 10);
  assert.ok(count < 10000, 'ZIP entry count is excessive');
  let offset = bytes.readUInt32LE(end + 16), expanded = 0;
  const entries = new Map();
  for (let index = 0; index < count; index++) {
    assert.equal(bytes.readUInt32LE(offset), 0x02014b50, 'Invalid ZIP central directory');
    const method = bytes.readUInt16LE(offset + 10), compressedSize = bytes.readUInt32LE(offset + 20), size = bytes.readUInt32LE(offset + 24);
    const nameLength = bytes.readUInt16LE(offset + 28), extraLength = bytes.readUInt16LE(offset + 30), commentLength = bytes.readUInt16LE(offset + 32);
    const local = bytes.readUInt32LE(offset + 42), name = bytes.subarray(offset + 46, offset + 46 + nameLength).toString('utf8');
    if (select(name)) {
      assert.ok(size <= 20000000 && expanded + size <= 50000000, 'Selected ZIP content exceeds verification limits');
      assert.equal(bytes.readUInt32LE(local), 0x04034b50, 'Invalid ZIP local entry');
      const start = local + 30 + bytes.readUInt16LE(local + 26) + bytes.readUInt16LE(local + 28);
      assert.ok(start + compressedSize <= bytes.length, 'ZIP entry extends past the file');
      const data = bytes.subarray(start, start + compressedSize);
      assert.ok(method === 0 || method === 8, 'Unsupported ZIP compression');
      const decoded = method === 0 ? data : inflateRawSync(data, { maxOutputLength: 20000000 });
      assert.equal(decoded.length, size, 'ZIP uncompressed size is inconsistent');
      expanded += size; entries.set(name, decoded);
    }
    offset += 46 + nameLength + extraLength + commentLength;
  }
  return entries;
}
const xmlText = value => value.replace(/<[^>]*>/g, '').replaceAll('&lt;', '<').replaceAll('&gt;', '>').replaceAll('&quot;', '"').replaceAll('&apos;', "'").replaceAll('&amp;', '&');
function verifyReportText(text) {
  const compact = text.replace(/\s+/g, '');
  const parts = Object.fromEntries(new Intl.DateTimeFormat('en-GB', {
    timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
  }).formatToParts(new Date(ids.filters.asOf)).map(part => [part.type, part.value]));
  // Reports intentionally present the exact snapshot's Shanghai time to minute precision.
  const displayedAsOf = `${parts.year}-${parts.month}-${parts.day} ${parts.hour}:${parts.minute}`;
  for (const expected of ['DEMO', ids.merchantCode, ids.filters.start, displayedAsOf, '150.00', '30.00', '120.00']) {
    assert.ok(compact.includes(expected.replace(/\s+/g, '')), `Report text must contain ${expected}`);
  }
}
async function verifyPdfContent(filename) {
  const archive = verificationJar.bytes;
  const libraries = zipEntries(archive, name => /^BOOT-INF\/lib\/(pdfbox|pdfbox-io|fontbox|commons-logging)-[^/]+\.jar$/.test(name));
  assert.equal(libraries.size, 4, 'The built application must provide PDFBox, FontBox, PDFBox IO and commons-logging');
  const directory = path.join(fileDirectory, 'pdf-check'); await mkdir(directory, { recursive: true });
  const classpath = [];
  for (const [name, bytes] of libraries) {
    const local = path.join(directory, path.basename(name)); await writeFile(local, bytes); classpath.push(local);
  }
  const source = path.join(directory, 'PdfContentProbe.java');
  await writeFile(source, `import java.io.File; import java.nio.charset.StandardCharsets; import java.util.Base64; import org.apache.pdfbox.Loader; import org.apache.pdfbox.text.PDFTextStripper; import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
class PdfContentProbe { public static void main(String[] args) throws Exception { try (var doc=Loader.loadPDF(new File(args[0]))) { int images=0; for(var page:doc.getPages()) { if(page.getResources()!=null) for(var name:page.getResources().getXObjectNames()) if(page.getResources().getXObject(name) instanceof PDImageXObject) images++; } System.out.println("CONTENT " + doc.getNumberOfPages()+" "+images+" "+Base64.getEncoder().encodeToString(new PDFTextStripper().getText(doc).getBytes(StandardCharsets.UTF_8))); } } }`, 'utf8');
  const { stdout } = await promisify(execFile)(process.env.RECONIS_VERIFY_JAVA || 'java', ['-Xmx128m', '-XX:ActiveProcessorCount=2', '--class-path', classpath.join(path.delimiter), '--source', '21', source, filename], { timeout: 45000, maxBuffer: 2000000, windowsHide: true });
  const match = stdout.match(/^CONTENT (\d+) (\d+) ([A-Za-z0-9+/=]+)$/m);
  assert.ok(match, 'PDF content extraction must finish');
  assert.ok(Number(match[1]) >= 2 && Number(match[2]) >= 1, 'PDF must contain report and chart pages');
  verifyReportText(Buffer.from(match[3], 'base64').toString('utf8'));
  return { pages: Number(match[1]), charts: Number(match[2]), textAndTotalsVerified: true };
}
async function createPaidOrder(amount, mode) {
  const request = randomUUID();
  const body = { clientRequestId: request, storeId: ids.storeId, consumerUsername: ids.customer.username, subject: `${runId} ${mode} 消费`, items: [{ itemName: '校园验证服务', categoryCode: '验证', quantity: 1, unitPriceCent: amount }] };
  const created = await owner.call('POST', '/api/v1/orders', body);
  const repeated = await owner.call('POST', '/api/v1/orders', body);
  assert.equal(repeated.id, created.id, 'order request must be idempotent');
  await owner.call('POST', '/api/v1/orders', { ...body, subject: '不一致的幂等内容' }, 409);
  let order = await owner.call('GET', `/api/v1/orders/${created.id}`);
  assert.equal(number(order.amountCent), amount);
  await owner.call('PATCH', `/api/v1/orders/${order.id}`, { version: order.version, subject: body.subject, remark: runId, items: body.items });
  await owner.call('PATCH', `/api/v1/orders/${order.id}`, { version: order.version, subject: body.subject, remark: runId, items: body.items }, 409);
  await owner.call('POST', `/api/v1/orders/${order.id}/submit`);
  const paymentRequest = { clientRequestId: randomUUID() };
  const payment = await customer.call('POST', `/api/v1/orders/${order.id}/payments`, paymentRequest);
  assert.equal((await customer.call('POST', `/api/v1/orders/${order.id}/payments`, paymentRequest)).id, payment.id);
  if (mode === 'NOTIFY') {
    await customer.call('POST', `/api/v1/payments/${payment.id}/demo`, { mode: 'TIMEOUT' });
    const unknown = await customer.call('POST', `/api/v1/payments/${payment.id}/sync`);
    assert.equal(unknown.status, 'UNKNOWN', 'channel timeout must not be treated as successful payment');
  }
  await customer.call('POST', `/api/v1/payments/${payment.id}/demo`, { mode });
  await customer.call('POST', `/api/v1/payments/${payment.id}/sync`);
  await customer.call('POST', `/api/v1/payments/${payment.id}/sync`);
  order = await owner.call('GET', `/api/v1/orders/${order.id}`);
  assert.equal(order.status, 'PAID');
  assert.equal(order.entries.filter(e => e.entryType === 'RECEIPT').length, 1, 'payment replay must not duplicate ledger facts');
  await owner.call('DELETE', `/api/v1/orders/${order.id}`, undefined, 409);
  return { order, payment };
}
async function importBill(xml, { preview = false, completeness = 'DECLARED_COMPLETE', merchantId = ids.merchantId, client = owner } = {}) {
  const form = new FormData();
  form.set('file', new Blob([xml], { type: 'application/xml' }), `${runId}.xml`);
  form.set('start', ids.periodStart); form.set('end', ids.periodEnd);
  form.set('completeness', completeness); form.set('preview', String(preview));
  if (merchantId) form.set('merchantId', String(merchantId));
  return client.call('POST', '/api/v1/reconciliation/imports', form);
}
async function runBill(xml, options) {
  const imported = await importBill(xml, options);
  assert.ok(imported.batches?.length, 'import must return a batch');
  const client = options?.client || owner;
  for (const batch of imported.batches) await client.call('POST', `/api/v1/reconciliation/batches/${batch.id}/run`);
  return imported;
}

try {
  if (previousResult) {
    currentStep = 'cleanup existing verification accounts';
    await admin.login(args.admin || 'admin', password);
    assert.equal(admin.me.platformRole, 'ADMIN');
    assert.equal((await admin.call('GET', '/api/v1/integration/status')).environment, 'DEMO');
    demoConfirmed = true;
    results.status = 'CLEANUP_COMPLETED';
  } else {
  await step('health and anonymous access', async () => {
    assert.equal((await anon.call('GET', '/api/v1/health')).status, 'UP');
    await anon.call('GET', '/api/v1/orders', undefined, [401, 403]);
    await admin.login(args.admin || 'admin', password);
    assert.equal(admin.me.platformRole, 'ADMIN');
    const integration = await admin.call('GET', '/api/v1/integration/status');
    assert.equal(integration.environment, 'DEMO', 'This verifier must never create simulated records in a sandbox or production environment');
    demoConfirmed = true;
  });
  await step('registration, review, login and independent roles', async () => {
    for (const key of ['owner', 'handler', 'reviewer', 'customer', 'outsider', 'finance', 'auditor']) ids[key] = await createAccount(key, key === 'finance' ? 'PLATFORM_FINANCE' : key === 'auditor' ? 'AUDITOR' : 'MEMBER');
    await owner.call('GET', '/api/v1/admin/users', undefined, 403);
  });
  await step('merchant approval, stale version and empty re-review protection', async () => {
    const created = await owner.call('POST', '/api/v1/merchants', { name: `${runId} 校园验证商户`, categoryCode: '校园验证', contact: '本地自动化验证记录' });
    ids.merchantId = created.id;
    let merchant = (await admin.call('GET', `/api/v1/merchants?${query({ q: runId })}`)).items.find(x => x.id === created.id);
    const originalVersion = merchant.version;
    await admin.call('POST', `/api/v1/merchants/${merchant.id}/review`, { approve: true, reason: '验证资料审核', version: merchant.version });
    await admin.call('POST', `/api/v1/merchants/${merchant.id}/review`, { approve: true, reason: '拒绝陈旧审核', version: originalVersion }, 409);
    merchant = (await owner.call('GET', `/api/v1/merchants?${query({ q: runId })}`)).items.find(x => x.id === created.id);
    await admin.call('POST', `/api/v1/merchants/${merchant.id}/review`, { approve: true, reason: '拒绝无候选资料重复审核', version: merchant.version }, 409);
    await owner.call('PATCH', `/api/v1/merchants/${merchant.id}`, { name: `${runId} 校园验证商户更新`, categoryCode: '校园验证', contact: '更新资料测试', version: merchant.version });
    merchant = (await admin.call('GET', `/api/v1/merchants?${query({ q: runId })}`)).items.find(x => x.id === created.id);
    assert.ok(merchant.proposedName);
    await admin.call('POST', `/api/v1/merchants/${merchant.id}/review`, { approve: true, reason: '验证变更审核', version: merchant.version });
    ids.merchantCode = merchant.merchantCode;
    await outsider.call('GET', `/api/v1/merchants/${merchant.id}/stores`, undefined, [403, 404]);
  });
  await step('store maintenance and member authorization', async () => {
    const store = await owner.call('POST', `/api/v1/merchants/${ids.merchantId}/stores`, { storeCode: `S${runId.slice(-8)}`, name: '验证一店', locationText: '校园测试区' });
    ids.storeId = store.id;
    const current = (await owner.call('GET', `/api/v1/merchants/${ids.merchantId}/stores`)).find(x => x.id === store.id);
    await owner.call('PATCH', `/api/v1/merchants/${ids.merchantId}/stores/${store.id}`, { name: '验证一店更新', locationText: '校园测试区 A', status: 'ACTIVE', version: current.version });
    await owner.call('POST', `/api/v1/merchants/${ids.merchantId}/members`, { username: ids.handler.username, roleCode: 'CASHIER' });
    await owner.call('POST', `/api/v1/merchants/${ids.merchantId}/members`, { username: ids.reviewer.username, roleCode: 'REVIEWER' });
    await handler.call('POST', `/api/v1/merchants/${ids.merchantId}/stores`, { storeCode: 'DENIED', name: '越权门店', locationText: '' }, 403);
  });
  let first, second;
  await step('order, payment notification, idempotency and ledger integrity', async () => { first = await createPaidOrder(10000, 'NOTIFY'); ids.firstOrderId = first.order.id; });
  await step('lost notification recovery through channel query', async () => { second = await createPaidOrder(5000, 'LOST'); ids.secondOrderId = second.order.id; });
  await step('forged external callback is rejected and leaves ledger unchanged', async () => {
    const before = await owner.call('GET', `/api/v1/orders/${first.order.id}`);
    const notifyId = `${runId}_FORGED`;
    const form = new FormData();
    for (const [key, value] of Object.entries({ out_trade_no: first.payment.outTradeNo, trade_no: first.order.payments.find(p => p.id === first.payment.id).channelTradeNo, app_id: 'DEMO_APP', seller_id: 'DEMO_SELLER', total_amount: '100.00', trade_status: 'TRADE_SUCCESS', sign_type: 'RSA2', notify_type: 'trade_status_sync', sign: 'invalid', notify_id: notifyId })) form.set(key, value);
    const response = await anon.call('POST', '/api/v1/callbacks/alipay', form, 200, true);
    assert.equal(response.bytes.toString(), 'failure');
    assert.match(response.headers['content-type'], /^text\/plain/);
    const order = await owner.call('GET', `/api/v1/orders/${first.order.id}`);
    assert.equal(order.entries.length, 1);
    assert.deepEqual(order.entries, before.entries);
    assert.equal(order.status, before.status);
    assert.equal(order.payments.find(p => p.id === first.payment.id).status, 'SUCCEEDED');
    // An unverified merchant order number must not attach attacker-supplied evidence
    // to an order. Rejection is kept in the global notification audit with payment_id NULL.
    assert.deepEqual(order.notifications, before.notifications);
    return { notifyId, ledgerUnchanged: true, untrustedNotificationNotLinked: true };
  });
  await step('refund independent approval, idempotency and remaining limit', async () => {
    const body = { clientRequestId: randomUUID(), amountCent: 3000, reason: `${runId} 部分退款` };
    const refund = await customer.call('POST', `/api/v1/payments/${first.payment.id}/refunds`, body);
    ids.refundId = refund.id;
    assert.equal((await customer.call('POST', `/api/v1/payments/${first.payment.id}/refunds`, body)).id, refund.id);
    await customer.call('POST', `/api/v1/refunds/${refund.id}/review`, { approve: true, reason: '越权自审' }, 403);
    const approved = await owner.call('POST', `/api/v1/refunds/${refund.id}/review`, { approve: true, reason: '核实取消部分服务' });
    assert.equal(approved.status, 'SUCCEEDED');
    await owner.call('POST', `/api/v1/refunds/${refund.id}/sync`);
    await owner.call('POST', `/api/v1/refunds/${refund.id}/sync`);
    first.order = await owner.call('GET', `/api/v1/orders/${first.order.id}`);
    assert.equal(first.order.entries.filter(e => e.entryType === 'REFUND').length, 1);
    assert.equal(number(first.order.payments.find(p => p.id === first.payment.id).refundableCent), 7000);
    const over = await customer.call('POST', `/api/v1/payments/${first.payment.id}/refunds`, { clientRequestId: randomUUID(), amountCent: 7001, reason: `${runId} 超额退款验证` });
    await owner.call('POST', `/api/v1/refunds/${over.id}/review`, { approve: true, reason: '应拒绝超额' }, 409);
    await owner.call('POST', `/api/v1/refunds/${over.id}/review`, { approve: false, reason: '验证后拒绝超额申请' });
    const selfApplied = await owner.call('POST', `/api/v1/payments/${first.payment.id}/refunds`, { clientRequestId: randomUUID(), amountCent: 100, reason: `${runId} 验证申请审批分离` });
    await owner.call('POST', `/api/v1/refunds/${selfApplied.id}/review`, { approve: true, reason: '申请人不可审批自己的申请' }, 409);
    await finance.call('POST', `/api/v1/refunds/${selfApplied.id}/review`, { approve: false, reason: '独立人员终止验证申请' });
    await outsider.call('GET', `/api/v1/orders/${first.order.id}`, undefined, [403, 404]);
    await auditor.call('POST', `/api/v1/payments/${first.payment.id}/sync`, undefined, 403);
  });
  await step('draft deletion and cancellation limited to own verification records', async () => {
    for (const operation of ['delete', 'cancel']) {
      const draft = await owner.call('POST', '/api/v1/orders', { clientRequestId: randomUUID(), storeId: ids.storeId, subject: `${runId} ${operation}`, items: [{ itemName: '验证草稿', quantity: 1, unitPriceCent: 100 }] });
      if (operation === 'delete') {
        await owner.call('DELETE', `/api/v1/orders/${draft.id}`);
        await owner.call('GET', `/api/v1/orders/${draft.id}`, undefined, 404);
      } else {
        await owner.call('POST', `/api/v1/orders/${draft.id}/cancel`);
        assert.equal((await owner.call('GET', `/api/v1/orders/${draft.id}`)).status, 'CANCELED');
      }
    }
    await owner.call('DELETE', `/api/v1/merchants/${ids.merchantId}/stores/${ids.storeId}`, undefined, 409);
  });
  let entries, goodXml, baseline, mismatchBatch, caseOne, caseTwo, exportedXlsx;
  await step('transaction drilldown, associated details and real pagination', async () => {
    const day = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date());
    ids.filters = { merchantId: ids.merchantId, start: day, end: day };
    const summary = await owner.call('GET', `/api/v1/analytics/transactions?${query(ids.filters)}`);
    ids.filters.asOf = summary.asOf;
    assert.equal(number(summary.summary.receiptCent), 15000); assert.equal(number(summary.summary.refundCent), 3000); assert.equal(number(summary.summary.netCent), 12000);
    for (const dimension of ['merchant', 'store', 'year', 'month', 'day']) {
      const stats = await owner.call('GET', `/api/v1/analytics/transactions?${query({ ...ids.filters, dimension })}`);
      assert.equal(number(stats.summary.netCent), 12000);
      assert.equal(stats.filters.start, day); assert.equal(stats.filters.end, day); assert.equal(stats.asOf, summary.asOf);
    }
    const page1 = await owner.call('GET', `/api/v1/analytics/transactions/details?${query({ ...ids.filters, storeId: ids.storeId, pageSize: 1 })}`);
    const page2 = await owner.call('GET', `/api/v1/analytics/transactions/details?${query({ ...ids.filters, storeId: ids.storeId, pageSize: 1, page: 2 })}`);
    assert.equal(number(page1.total), 3); assert.equal(page1.items.length, 1); assert.notEqual(page1.items[0].id, page2.items[0].id);
    const all = await owner.call('GET', `/api/v1/analytics/transactions/details?${query(ids.filters)}`);
    entries = all.items;
    assert.ok(entries.every(e => e.merchantId === ids.merchantId && e.storeId === ids.storeId && e.orderId));
    goodXml = statement(entries, `${runId} first exact bill`);
    ids.periodStart = day; ids.periodEnd = day;
    await outsider.call('GET', `/api/v1/analytics/transactions?${query(ids.filters)}`, undefined, [403, 404]);
  });
  await step('Excel, XML, PDF, Word exports, hashes and download permissions', async () => {
    await mkdir(fileDirectory, { recursive: true });
    results.exports = [];
    for (const format of ['xlsx', 'xml', 'pdf', 'docx']) {
      const exported = await owner.call('POST', '/api/v1/exports', { kind: 'transactions', format, filters: ids.filters });
      const file = await owner.call('GET', exported.url, undefined, 200, true);
      assert.ok(file.bytes.length > 100);
      assert.equal(createHash('sha256').update(file.bytes).digest('hex'), exported.sha256);
      const filename = path.join(fileDirectory, `transactions.${format}`);
      await writeFile(filename, file.bytes);
      const evidence = { format, path: filename, bytes: file.bytes.length, sha256: exported.sha256 };
      if (format === 'pdf') assert.equal(file.bytes.subarray(0, 4).toString(), '%PDF');
      if (format === 'xlsx' || format === 'docx') assert.equal(file.bytes.subarray(0, 2).toString(), 'PK');
      if (format === 'xlsx') {
        exportedXlsx = file.bytes;
        const sheets = zipEntries(file.bytes, name => name === 'xl/workbook.xml' || name === 'xl/worksheets/sheet1.xml');
        assert.equal(sheets.size, 2); assert.equal((sheets.get('xl/worksheets/sheet1.xml').toString().match(/<row[\s>]/g) || []).length, 4);
        evidence.rows = 3; evidence.roundTripCheck = 'Performed in the later Excel re-import step';
      }
      if (format === 'xml') {
        assert.equal((file.bytes.toString().match(/<event /g) || []).length, 3);
        goodXml = file.bytes.toString('utf8'); // Reconcile this actual exported XML in the next step.
        evidence.rows = 3; evidence.roundTripCheck = 'Performed in the later exact reconciliation step';
      }
      if (format === 'docx') {
        const contents = zipEntries(file.bytes, name => name === 'word/document.xml' || name.startsWith('word/media/'));
        const document = contents.get('word/document.xml')?.toString('utf8');
        assert.ok(document && document.includes('<w:drawing'));
        verifyReportText(xmlText(document));
        assert.equal((document.match(/<w:tr[\s>]/g) || []).length, 2, 'Word must contain the header and this merchant group');
        evidence.charts = [...contents.keys()].filter(name => name.startsWith('word/media/')).length;
        assert.ok(evidence.charts >= 1); evidence.textAndTotalsVerified = true;
      }
      if (format === 'pdf') Object.assign(evidence, await verifyPdfContent(filename));
      results.exports.push(evidence);
      await checkpoint();
      await outsider.call('GET', exported.url, undefined, 403);
    }
    await owner.call('POST', '/api/v1/exports', { kind: 'reconciliation', format: 'pdf', filters: {} }, 422);
    for (const format of ['xlsx', 'xml']) assert.ok((await owner.call('GET', `/api/v1/templates?format=${format}`, undefined, 200, true)).bytes.length > 50);
  });
  await step('bill preview, import deduplication and exact reconciliation', async () => {
    const preview = await importBill(goodXml, { preview: true });
    assert.equal(number(preview.rows), 3); assert.equal(preview.errors.length, 0); assert.equal(preview.canImport, true);
    const imported = await runBill(goodXml); baseline = imported.batches[0].id; ids.baselineBatchId = baseline;
    assert.equal((await importBill(goodXml)).batches[0].id, baseline);
    const reconciled = await owner.call('GET', `/api/v1/reconciliation/batches/${baseline}/results`);
    assert.equal(number(reconciled.total), 3); assert.ok(reconciled.items.every(x => x.classification === 'MATCHED'));
  });
  await step('Excel export re-import preserves all transaction facts', async () => {
    const form = new FormData();
    form.set('file', new Blob([exportedXlsx]), `${runId}.xlsx`);
    form.set('start', ids.periodStart); form.set('end', ids.periodEnd);
    form.set('merchantId', ids.merchantId); form.set('completeness', 'DECLARED_COMPLETE'); form.set('preview', 'false');
    const imported = await owner.call('POST', '/api/v1/reconciliation/imports', form);
    assert.equal(number(imported.rows), 3); assert.equal(imported.errors.length, 0);
    for (const batch of imported.batches) {
      await owner.call('POST', `/api/v1/reconciliation/batches/${batch.id}/run`);
      const rows = await owner.call('GET', `/api/v1/reconciliation/batches/${batch.id}/results`);
      assert.equal(number(rows.total), 3); assert.ok(rows.items.every(x => x.classification === 'MATCHED'));
    }
  });
  await step('bulk merchant import remains pending until explicit review', async () => {
    const code = `${runId}_BULK`;
    const xml = `<?xml version="1.0" encoding="UTF-8"?><merchants schemaVersion="1"><merchant code="${code}"><name>${runId} 批量导入商户</name><categoryCode>校园验证</categoryCode><contact>测试导入资料</contact></merchant></merchants>`;
    for (const preview of [true, false]) {
      const form = new FormData(); form.set('file', new Blob([xml]), `${runId}-merchants.xml`); form.set('preview', String(preview));
      const imported = await admin.call('POST', '/api/v1/merchants/imports', form);
      assert.equal(number(imported.accepted), 1); assert.equal(imported.errors.length, 0);
    }
    const merchant = (await admin.call('GET', `/api/v1/merchants?${query({ q: code })}`)).items.find(x => x.merchantCode === code);
    assert.ok(merchant); assert.equal(merchant.status, 'PENDING'); ids.importedMerchantId = merchant.id;
    await admin.call('POST', `/api/v1/merchants/${merchant.id}/review`, { approve: true, reason: '批量导入后独立审核资料', version: merchant.version });
    assert.equal((await admin.call('GET', `/api/v1/merchants?${query({ q: code })}`)).items.find(x => x.id === merchant.id).status, 'ACTIVE');
  });
  await step('amount mismatch detection and automatic exception creation', async () => {
    const bad = entries.map(e => e.entryType === 'RECEIPT' ? { ...e, amountCent: number(e.amountCent) + 100 } : e);
    const imported = await runBill(statement(bad, `${runId} mismatches`)); mismatchBatch = imported.batches[0].id; ids.mismatchBatchId = mismatchBatch;
    const reconciled = await owner.call('GET', `/api/v1/reconciliation/batches/${mismatchBatch}/results`);
    assert.equal(reconciled.items.filter(x => x.classification === 'AMOUNT_MISMATCH').length, 2);
    const cases = await owner.call('GET', '/api/v1/cases?classification=AMOUNT_MISMATCH');
    const created = cases.items.filter(x => x.merchantId === ids.merchantId);
    assert.equal(created.length, 2);
    [caseOne, caseTwo] = created; ids.caseIds = created.map(x => x.id);
  });
  await step('complete empty bill detects all local-only events', async () => {
    const imported = await runBill(statement([], `${runId} empty complete`));
    const reconciled = await owner.call('GET', `/api/v1/reconciliation/batches/${imported.batches[0].id}/results`);
    assert.equal(number(reconciled.total), 3); assert.ok(reconciled.items.every(x => x.classification === 'LOCAL_ONLY'));
  });
  await step('platform quarantine for unknown ownership and role isolation', async () => {
    const unknown = { ...entries[0], merchantCode: 'UNRECOGNIZED', entryType: 'RECEIPT', outTradeNo: `${runId}_UNKNOWN`, channelTradeNo: `${runId}_CHANNEL`, outRequestNo: '', amountCent: 777 };
    const imported = await runBill(statement([unknown], `${runId} unknown`), { merchantId: null, client: finance, completeness: 'UNKNOWN' });
    const batch = imported.batches.find(b => b.scopeType === 'PLATFORM_UNASSIGNED');
    assert.ok(batch); ids.unassignedBatchId = batch.id;
    await owner.call('GET', `/api/v1/reconciliation/batches/${batch.id}/results`, undefined, [403, 404]);
    const result = await finance.call('GET', `/api/v1/reconciliation/batches/${batch.id}/results`);
    assert.ok(result.items.some(x => ['KEY_UNRESOLVED', 'CHANNEL_ONLY'].includes(x.classification)));
    const identified = await finance.call('POST', `/api/v1/reconciliation/batches/${batch.id}/identify?preview=true`);
    assert.ok(identified);
  });
  await step('fresh corrected bill, batch comparison and reconciliation evidence', async () => {
    const fixed = await runBill(statement(entries, `${runId} fresh corrected bill`)); ids.correctedBatchId = fixed.batches[0].id;
    const compared = await owner.call('GET', `/api/v1/reconciliation/compare?${query({ baseline: mismatchBatch, current: ids.correctedBatchId })}`);
    assert.equal(compared.filter(x => x.changeType === 'RESOLVED').length, 2);
    const rerun = await owner.call('POST', `/api/v1/reconciliation/batches/${ids.correctedBatchId}/rerun`);
    assert.equal(rerun.baselineBatchId, ids.correctedBatchId);
    const current = await owner.call('GET', `/api/v1/cases/${caseOne.id}`);
    assert.ok(current.matchedEvidence.length > 0);
  });
  await step('case assignment, version protection, evidence and independent review', async () => {
    let c = await owner.call('GET', `/api/v1/cases/${caseOne.id}`);
    const assignees = await owner.call('GET', `/api/v1/cases/${c.id}/assignees`);
    assert.ok(assignees.some(x => x.userId === ids.handler.id));
    await owner.call('POST', `/api/v1/cases/${c.id}/assign`, { assigneeId: ids.handler.id, version: c.version });
    await owner.call('POST', `/api/v1/cases/${c.id}/assign`, { assigneeId: ids.handler.id, version: c.version }, 409);
    c = await handler.call('GET', `/api/v1/cases/${c.id}`);
    await handler.call('PATCH', `/api/v1/cases/${c.id}`, { analysisText: `${runId} 核对账单与订单支付退款关联，渠道输入金额错误。`, resolutionText: '获取修订账单重新核对，同一事件已匹配。', version: c.version });
    c = await handler.call('GET', `/api/v1/cases/${c.id}`);
    const form = new FormData(); form.set('file', new Blob([`${runId}\n修订账单与原交易一致。`]), 'validation-evidence.txt'); form.set('version', c.version);
    const evidence = await handler.call('POST', `/api/v1/cases/${c.id}/evidence`, form);
    assert.ok((await reviewer.call('GET', `/api/v1/files/${evidence.fileId}`, undefined, 200, true)).bytes.length > 10);
    c = await handler.call('GET', `/api/v1/cases/${c.id}`);
    await handler.call('POST', `/api/v1/cases/${c.id}/submit`, { version: c.version });
    c = await handler.call('GET', `/api/v1/cases/${c.id}`);
    assert.equal(c.status, 'PENDING_REVIEW');
    await handler.call('PATCH', `/api/v1/cases/${c.id}`, { analysisText: '非法修改', resolutionText: '非法修改', version: c.version }, 409);
    await handler.call('POST', `/api/v1/cases/${c.id}/review`, { approve: true, resolutionCode: 'RECONCILED', reason: '非法自审', version: c.version, evidenceResultId: c.matchedEvidence[0].id }, 403);
    await reviewer.call('POST', `/api/v1/cases/${c.id}/review`, { approve: false, reason: '补充核实材料', version: c.version });
    c = await handler.call('GET', `/api/v1/cases/${c.id}`); assert.equal(c.status, 'PROCESSING');
    await handler.call('POST', `/api/v1/cases/${c.id}/submit`, { version: c.version });
    c = await reviewer.call('GET', `/api/v1/cases/${c.id}`);
    await reviewer.call('POST', `/api/v1/cases/${c.id}/review`, { approve: true, resolutionCode: 'RECONCILED', reason: '独立复核支付号、金额、账期和修订批次一致', version: c.version, evidenceResultId: c.matchedEvidence[0].id });
    c = await reviewer.call('GET', `/api/v1/cases/${c.id}`);
    assert.equal(c.status, 'CLOSED'); assert.equal(c.classification, 'AMOUNT_MISMATCH');
    assert.ok(c.history.some(x => x.afterJson?.includes('analysisText') || x.afterJson?.includes('analysis_text')));
    assert.ok(c.history.length >= 5);
  });
  await step('fulltext search and historical-case recommendations', async () => {
    let readiness;
    for (let attempt = 0; attempt < 20; attempt++) {
      readiness = await owner.call('GET', '/api/v1/cases/search/status');
      if (readiness.ready) break;
      await new Promise(resolve => setTimeout(resolve, 2000));
    }
    assert.equal(readiness.ready, true, 'Chinese fulltext must be ready, not silently replaced by LIKE');
    let search;
    for (let attempt = 0; attempt < 15; attempt++) {
      search = await owner.call('GET', `/api/v1/cases/search?${query({ q: '金额' })}`);
      if (search.items.some(x => ids.caseIds.includes(x.id))) break;
      await new Promise(resolve => setTimeout(resolve, 2000));
    }
    assert.equal(search.engine, 'SQL_SERVER_FREETEXTTABLE');
    assert.ok(search.items.some(x => ids.caseIds.includes(x.id)), 'fulltext index must contain newly created cases');
    const recommendations = await owner.call('GET', `/api/v1/cases/${caseTwo.id}/recommendations`);
    assert.ok(recommendations.some(x => x.id === caseOne.id && number(x.score) > 0), 'same-category closed case should be recommended');
  });
  await step('reconciliation statistics, case statistics and dashboard', async () => {
    // Fresh statistics include work completed after the earlier financial snapshot.
    const filters = { ...ids.filters }; delete filters.asOf;
    assert.ok(await owner.call('GET', `/api/v1/analytics/reconciliation?${query(filters)}`));
    assert.ok(await owner.call('GET', `/api/v1/analytics/cases?${query(filters)}`));
    assert.ok(await owner.call('GET', `/api/v1/dashboard?${query(filters)}`));
    const unchanged = await owner.call('GET', `/api/v1/analytics/transactions?${query(ids.filters)}`);
    assert.equal(number(unchanged.summary.netCent), 12000, 'reconciliation and case operations must not rewrite financial facts');
  });
  await step('risk metrics, historical snapshot, exact money and drill-through', async () => {
    const filters = { ...ids.filters, asOf: new Date().toISOString(), reconciliationSource: 'BILL' };
    const risk = await owner.call('GET', `/api/v1/analytics/risk?${query(filters)}`);
    assert.equal(number(risk.summary.eventCount), 3);
    assert.equal(number(risk.summary.exceptionEventCount), 0, 'latest corrected bill is fully matched');
    assert.equal(typeof risk.summary.pendingCent, 'string', 'money must be JSON-safe exact text');
    assert.ok(number(risk.summary.closedCaseCount) >= 1);
    assert.ok(risk.definitions.some(text => text.includes('不代表实际损失')));
    const detail = await owner.call('GET', `/api/v1/analytics/risk/details?${query({ ...filters, kind: 'pending', pageSize: 1 })}`);
    assert.equal(number(detail.total), number(risk.summary.pendingEventCount));
    assert.equal(detail.items.length, Math.min(1, number(detail.total)));
    assert.ok(detail.items.every(x => x.merchantId === ids.merchantId));
    const historical = await owner.call('GET', `/api/v1/analytics/risk?${query(ids.filters)}`);
    assert.equal(number(historical.summary.caseCount), 0, 'the earlier snapshot cannot contain later-created cases');
    const forbidden = await outsider.call('GET', `/api/v1/analytics/risk?${query(filters)}`, undefined, [403, 404]);
    assert.ok(forbidden);
    await owner.call('GET', `/api/v1/analytics/risk/details?${query({ ...filters, kind: 'pending', ageBucket: 'INVALID' })}`, undefined, 422);
  });
  await step('case dossier, original-file hashes, archive contents and tenant isolation', async () => {
    const dossier = await reviewer.call('GET', `/api/v1/cases/${caseOne.id}/dossier`);
    assert.equal(dossier.scope.caseId, caseOne.id);
    assert.ok(dossier.reconciliationVersions.length >= 2);
    assert.ok(dossier.workflowHistory.length >= 5);
    assert.ok(dossier.files.some(x => x.verification === 'VERIFIED' && x.originalName === 'validation-evidence.txt'));
    const archive = await reviewer.call('GET', `/api/v1/cases/${caseOne.id}/dossier/archive`, undefined, 200, true);
    assert.equal(createHash('sha256').update(archive.bytes).digest('hex'), archive.headers['x-content-sha256']);
    const contents = zipEntries(archive.bytes, () => true);
    const manifestBytes = contents.get('manifest.json');
    assert.ok(manifestBytes);
    assert.equal(contents.get('manifest.sha256').toString().split(' ')[0], createHash('sha256').update(manifestBytes).digest('hex'));
    const manifest = JSON.parse(manifestBytes.toString('utf8'));
    for (const file of manifest.files.filter(f => f.included)) {
      const bytes = contents.get(file.archivePath); assert.ok(bytes);
      assert.equal(createHash('sha256').update(bytes).digest('hex'), file.sha256);
    }
    await writeFile(path.join(fileDirectory, 'case-dossier.zip'), archive.bytes);
    await outsider.call('GET', `/api/v1/cases/${caseOne.id}/dossier`, undefined, [403, 404]);
    await outsider.call('GET', `/api/v1/cases/${caseOne.id}/dossier/archive`, undefined, [403, 404]);
  });
  await step('automatic daily-bill acquisition, task idempotency and access control', async () => {
    const yesterday = new Date(Date.now() + 8 * 3600000 - 86400000).toISOString().slice(0, 10);
    const body = { start: yesterday, end: yesterday, daily: false };
    await owner.call('POST', '/api/v1/reconciliation/automatic', body, 403);
    await auditor.call('POST', '/api/v1/reconciliation/automatic', body, 403);
    const jobs = await finance.call('POST', '/api/v1/reconciliation/automatic', body);
    assert.equal(jobs.length, 1); ids.autoJobId = jobs[0].id;
    const repeated = await finance.call('POST', '/api/v1/reconciliation/automatic', body);
    assert.equal(repeated[0].id, jobs[0].id);
    let job;
    for (let attempt = 0; attempt < 35; attempt++) {
      job = await finance.call('GET', `/api/v1/reconciliation/automatic/${ids.autoJobId}`);
      if (!['QUEUED', 'FETCHING', 'VALIDATING', 'RECONCILING'].includes(job.acquisitionState)) break;
      await new Promise(resolve => setTimeout(resolve, 2000));
    }
    assert.equal(job.acquisitionState, 'SUCCEEDED', `DEMO automatic acquisition failed: ${job.acquisitionMessage}`);
    const original = await auditor.call('GET', `/api/v1/reconciliation/automatic/${ids.autoJobId}/original`, undefined, 200, true);
    assert.equal(createHash('sha256').update(original.bytes).digest('hex'), job.fileSha256);
    await owner.call('GET', `/api/v1/reconciliation/automatic/${ids.autoJobId}`, undefined, 403);
    await finance.call('POST', `/api/v1/reconciliation/batches/${ids.autoJobId}/run`, undefined, 422);
    assert.equal(job.acquisitionDaily, false, 'verification never enables recurring acquisition');
    return { taskId: ids.autoJobId, source: 'DEMO', state: job.acquisitionState };
  });
  await step('audit query, statistics and unauthorized access rejection', async () => {
    const events = await auditor.call('GET', `/api/v1/audit/events?${query({ merchantId: ids.merchantId })}`);
    assert.ok(number(events.total) > 10); assert.ok(events.items.every(x => x.scopeMerchantId === ids.merchantId));
    const stats = await auditor.call('GET', `/api/v1/audit/statistics?${query({ merchantId: ids.merchantId })}`);
    assert.ok(stats.categories.some(x => x.eventCategory === 'ACCESS'));
    assert.ok(stats.categories.some(x => x.eventCategory === 'DB_CHANGE'));
    await owner.call('GET', '/api/v1/audit/events', undefined, 403);
    const auth = await auditor.call('GET', `/api/v1/audit/events?${query({ category: 'AUTH', actorId: ids.owner.id, result: 'FAILED' })}`);
    assert.ok(number(auth.total) >= 1, 'pending account login must be audited as failed');
  });
  await step('logout invalidates session', async () => {
    await customer.call('POST', '/api/v1/auth/logout');
    await customer.call('GET', '/api/v1/auth/me', undefined, [401, 403]);
  });
  results.status = 'PASSED';
  }
} catch (error) {
  results.status = 'FAILED';
  results.checks.push({ name: currentStep, status: 'FAILED', message: error.message });
  console.error(`FAIL ${currentStep}: ${error.message}`);
  process.exitCode = 1;
} finally {
  if (demoConfirmed && admin.me) {
    try {
      if (!await disableVerificationAccounts()) {
        if (results.status !== 'FAILED') results.status = previousResult ? 'CLEANUP_PENDING' : 'PASSED_WITH_CLEANUP_PENDING';
        process.exitCode = 1;
        console.error('Verification account cleanup is incomplete; rerun with --cleanup-results pointing to this result file when the server is available.');
      }
    } catch (error) {
      results.cleanup = { status: 'PENDING', message: error.message };
      if (results.status !== 'FAILED') results.status = previousResult ? 'CLEANUP_PENDING' : 'PASSED_WITH_CLEANUP_PENDING';
      process.exitCode = 1;
    }
    try { await admin.call('POST', '/api/v1/auth/logout'); } catch { /* The admin cookie remains only in this terminating process. */ }
  }
  results.finishedAt = new Date().toISOString();
  await checkpoint();
  console.log(`${results.status}: ${results.checks.filter(x => x.status === 'PASSED').length} checks. Result: ${resultFile}`);
}
