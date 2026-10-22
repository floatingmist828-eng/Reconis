import test from "node:test";
import assert from "node:assert/strict";
import { drillFilters, ratioPercent } from "../src/analyticsFilters.ts";

const parent = {
  start: "2026-10-15",
  end: "2026-10-21",
  merchantId: "",
  storeId: "",
  minCent: "500",
  maxCent: "2000",
  dimension: "year",
  reconciliationSource: "BILL",
  asOf: "2026-10-21T08:00:00Z",
};

test("year and month drill preserve the selected week and original parent", () => {
  const month = drillFilters(parent, "2026");
  const day = drillFilters(month, "2026-10");
  assert.equal(month.dimension, "month");
  assert.equal(day.dimension, "day");
  assert.equal(day.start, parent.start);
  assert.equal(day.end, parent.end);
  assert.equal(parent.dimension, "year");
  assert.equal(day.asOf, parent.asOf);
  assert.equal(day.minCent, parent.minCent);
});

test("cross-year drill intersects instead of widening and handles leap February", () => {
  const month = drillFilters(
    { ...parent, start: "2023-12-29", end: "2024-03-02" },
    "2024",
  );
  const day = drillFilters(month, "2024-02");
  assert.equal(month.start, "2024-01-01");
  assert.equal(month.end, "2024-03-02");
  assert.equal(day.start, "2024-02-01");
  assert.equal(day.end, "2024-02-29");
});

test("merchant/store drill keeps period, money filters and cutoff", () => {
  const store = drillFilters({ ...parent, dimension: "merchant" }, "12");
  const detail = drillFilters(store, "42");
  assert.equal(detail.merchantId, "12");
  assert.equal(detail.storeId, "42");
  assert.equal(detail.start, parent.start);
  assert.equal(detail.maxCent, parent.maxCent);
  assert.equal(detail.asOf, parent.asOf);
});

test("stale groups outside the selected range cannot create an expanded query", () => {
  assert.throws(() => drillFilters(parent, "2025"), /不在当前统计范围/);
  assert.throws(
    () => drillFilters({ ...parent, dimension: "day" }, "2026-10-22"),
    /不在当前统计范围/,
  );
});

test("risk percentages distinguish empty denominators and keep large counts exact", () => {
  assert.equal(ratioPercent("0", "0"), "—");
  assert.equal(ratioPercent("0", "100"), "0.00%");
  assert.equal(ratioPercent("2", "3"), "66.67%");
  assert.equal(ratioPercent("9007199254740993", "18014398509481986"), "50.00%");
});
