export type AnalysisFilters = {
  start: string;
  end: string;
  merchantId: string;
  storeId: string;
  minCent: string;
  maxCent: string;
  dimension: string;
  reconciliationSource: string;
  asOf?: string;
};

/** Integer basis points keep counts beyond Number.MAX_SAFE_INTEGER exact. */
export function ratioPercent(numerator: unknown, denominator: unknown): string {
  const n = BigInt(String(numerator ?? 0)), d = BigInt(String(denominator ?? 0));
  if (d <= 0n) return "—";
  const basisPoints = (n * 10000n + d / 2n) / d;
  return `${basisPoints / 100n}.${String(basisPoints % 100n).padStart(2, "0")}%`;
}

/** A child can only narrow the applied parent range, never expand it. */
export function drillFilters(
  parent: AnalysisFilters,
  groupKey: string,
): AnalysisFilters {
  const next = { ...parent };
  const intersect = (start: string, end: string) => {
    next.start = start > parent.start ? start : parent.start;
    next.end = end < parent.end ? end : parent.end;
    if (next.start > next.end)
      throw new Error("该分组已不在当前统计范围内，请刷新分析");
  };
  switch (parent.dimension) {
    case "merchant":
      next.merchantId = String(groupKey);
      next.storeId = "";
      next.dimension = "store";
      break;
    case "store":
      next.storeId = String(groupKey);
      break;
    case "year":
      intersect(`${groupKey}-01-01`, `${groupKey}-12-31`);
      next.dimension = "month";
      break;
    case "month": {
      const [year, month] = groupKey.split("-").map(Number);
      intersect(
        `${groupKey}-01`,
        new Date(Date.UTC(year!, month!, 0)).toISOString().slice(0, 10),
      );
      next.dimension = "day";
      break;
    }
    case "day":
      intersect(groupKey, groupKey);
      break;
    default:
      throw new Error("不支持的统计维度");
  }
  return next;
}
