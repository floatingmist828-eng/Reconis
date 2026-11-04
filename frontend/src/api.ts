import { ElMessage } from "element-plus";
export type Row = Record<string, any>;
export function codeName(value: unknown) {
  const names: Record<string, string> = {
    MATCHED: "核对一致",
    CHANNEL_ONLY: "渠道单边",
    LOCAL_ONLY: "本地单边",
    AMOUNT_MISMATCH: "金额不符",
    BILL_DUPLICATE: "账单重复",
    KEY_UNRESOLVED: "标识待核实",
    PENDING_CONFIRMATION: "待确认",
    EXTRA_SUCCESS: "额外收款",
    PAYMENT_CONFIRMATION: "支付同步",
    MISSING_CHANNEL_EVENT: "账单缺失",
    STATEMENT_INCOMPLETE: "账单未齐",
    DUPLICATE_PAYMENT: "重复支付",
    DUPLICATE_INPUT: "重复记录",
    AMOUNT_DIFFERENCE: "金额差异",
    MISSING_EVENT_KEY: "标识缺失",
    CHANNEL_ID_CONFLICT: "标识不符",
    IDENTICAL: "核对一致",
    OWNER: "负责人",
    CASHIER: "收银员",
    FINANCE: "财务",
    REVIEWER: "复核员",
    MEMBER: "普通成员",
    ADMIN: "管理员",
    AUDITOR: "审计员",
    PLATFORM_FINANCE: "平台财务",
    PAYMENT_QUERY: "支付查询",
    REFUND_QUERY: "退款查询",
    REFUND_CALL: "发起退款",
    RECONCILED: "重新对账已匹配",
    INVALID_INPUT: "输入账单无效",
    FALSE_POSITIVE: "核实为误报",
    KNOWN_EXCEPTION: "已说明的例外",
    OWNERSHIP_CONFIRMED: "已确认商户归属",
    UNASSIGNED_MERCHANT: "待确认商户归属",
    REFUND_PAYMENT_MISMATCH: "退款与原支付不符",
    DECLARED_COMPLETE: "声明完整",
    PROVIDER_COMPLETE: "渠道确认完整",
    UNKNOWN: "未知",
    DEMO_SEED: "渠道账单",
    TRADE_QUERY: "单笔渠道核查",
    ALIPAY_CSV: "支付宝 CSV",
    ALIPAY_ZIP: "支付宝账单压缩包",
    AUTO_JOB: "自动获取任务",
  };
  return names[String(value)] || String(value || "—");
}
let token = "";
let sessionRevision = 0;
let signingOut = false;
let signOutPending = false;
async function csrf() {
  const r = await fetch("/api/v1/auth/csrf", { credentials: "same-origin" });
  if (!r.ok) throw new Error("服务尚未就绪，请确认后端和 SQL Server 已启动");
  token = (await r.json()).data.token;
}
export async function api<T = any>(
  path: string,
  options: { method?: string; body?: any; quiet?: boolean } = {},
): Promise<T> {
  const revision = sessionRevision;
  const startedDuringSignOut = signingOut;
  const startedDuringSignOutRequest = signOutPending;
  try {
    const method = options.method || "GET";
    if (method !== "GET" && !token) await csrf();
    const form = options.body instanceof FormData;
    const r = await fetch("/api/v1" + path, {
      method,
      credentials: "same-origin",
      headers: {
        ...(options.body && !form
          ? { "Content-Type": "application/json" }
          : {}),
        ...(method !== "GET" ? { "X-XSRF-TOKEN": token } : {}),
      },
      body: options.body
        ? form
          ? options.body
          : JSON.stringify(options.body)
        : undefined,
    });
    const raw = await r.text();
    if (r.status === 401 && !path.startsWith('/auth/')) {
      if (signingOut || startedDuringSignOut || revision !== sessionRevision) {
        throw Object.assign(new Error('会话已退出'), { reported: true, obsoleteSession: true });
      }
      token = '';
      window.location.replace('/login?expired=1');
      throw new Error('登录已失效，请重新登录');
    }
    let result: any;
    try {
      result = JSON.parse(raw);
    } catch {
      throw new Error("无法连接服务");
    }
    if (!r.ok) {
      if (r.status === 403) token = "";
      const trace = r.status >= 500 && result.requestId ? `（请求编号：${result.requestId}）` : "";
      throw new Error((result.message || `请求未完成 (${r.status})`) + trace);
    }
    if ((path === '/auth/login' && method === 'POST') ||
        (path === '/auth/me' && result.data && signingOut && !startedDuringSignOutRequest && revision === sessionRevision)) {
      signingOut = false;
      sessionRevision++;
    }
    return result.data as T;
  } catch (e: any) {
    if (!options.quiet && !e.obsoleteSession) {
      ElMessage.error(e.message || "网络连接失败");
      e.reported = true;
    }
    throw e;
  }
}
export const post = (path: string, body: any = {}) =>
  api(path, { method: "POST", body });
export async function signOut() {
  signingOut = true;
  signOutPending = true;
  sessionRevision++;
  try {
    await post('/auth/logout');
    token = '';
  } catch (error) {
    signingOut = false;
    throw error;
  } finally {
    signOutPending = false;
  }
}
export const patch = (path: string, body: any) =>
  api(path, { method: "PATCH", body });
export function query(values: Row) {
  const p = new URLSearchParams();
  Object.entries(values).forEach(([k, v]) => {
    if (v !== "" && v != null) p.set(k, String(v));
  });
  return p.toString();
}
export function money(value: unknown) {
  const n = BigInt(String(value || "0"));
  const a = n < 0n ? -n : n;
  return `${n < 0n ? "-" : ""}${(a / 100n).toLocaleString("zh-CN")}.${(a % 100n).toString().padStart(2, "0")}`;
}
export function time(value: unknown) {
  if (!value) return "—";
  return new Date(String(value)).toLocaleString("zh-CN", {
    hour12: false,
    timeZone: "Asia/Shanghai",
  });
}
export function dateDays(n = 0) {
  const d = new Date(Date.now() + n * 86400000);
  return new Intl.DateTimeFormat("en-CA", { timeZone: "Asia/Shanghai" }).format(
    d,
  );
}
export async function exportFile(
  kind: string,
  format: string,
  filters: Row = {},
) {
  const file = await post("/exports", { kind, format, filters });
  window.location.assign(file.url);
  ElMessage.success("文件已生成，正在下载");
}
export function toCent(value: string) {
  if (!/^\d+(\.\d{1,2})?$/.test(value))
    throw new Error("金额请保留至多两位小数");
  const [yuan, fraction = ""] = value.split(".");
  return (BigInt(yuan!) * 100n + BigInt(fraction.padEnd(2, "0"))).toString();
}
export const action = async (fn: () => Promise<any>) => {
  try {
    await fn();
  } catch (e: any) {
    if (e instanceof Error && !(e as any).reported) ElMessage.error(e.message);
  }
};
