import test from "node:test";
import assert from "node:assert/strict";
import { resolveArtworkKind } from "../src/utils/categoryArtwork.ts";

test("campus items receive a relevant visual, including mixed orders", () => {
  const examples = [
    ["冰美式", "coffee"], ["美式与柠檬磅蛋糕", "coffee"],
    ["双人咖啡甜点", "coffee"], ["茶会点心与冷泡茶", "tea"],
    ["实验报告彩印", "print"], ["课程资料胶装", "print"],
    ["可颂与吐司分享盒", "bakery"], ["素页手账本", "stationery"],
    ["羽毛球拍", "sports"], ["菌菇砂锅饭", "meal"],
    ["洗护补给组合", "grocery"], ["班会零食组合", "grocery"],
  ];
  for (const [subject, expected] of examples) {
    assert.equal(resolveArtworkKind({ subject }), expected, subject);
  }
});

test("item context wins over the merchant while absent subjects use known metadata", () => {
  assert.equal(resolveArtworkKind({ subject: "柠檬蛋糕", merchantName: "海棠咖啡店", category: "咖啡简餐" }), "bakery");
  assert.equal(resolveArtworkKind({ subject: "临时订单", merchantName: "松风文印社" }), "print");
  assert.equal(resolveArtworkKind({ category: "图书文创", merchantName: "新商户" }), "stationery");
  assert.equal(resolveArtworkKind({ category: "COFFEE" }), "coffee");
  assert.equal(resolveArtworkKind({ merchantName: "青瓦茶铺" }), "tea");
});

test("unknown or missing data stays neutral and input is left intact", () => {
  const context = Object.freeze({ subject: "活动报名", merchantName: "未知商户", category: "其他" });
  assert.equal(resolveArtworkKind(context), "general");
  assert.equal(resolveArtworkKind(), "general");
  assert.equal(resolveArtworkKind({ subject: null, merchantName: 42 }), "general");
  assert.equal(resolveArtworkKind({ subject: "金额待核实", merchantName: "校园印象" }), "general");
});

test("broad accounting categories retain specific coffee and printing storefronts", () => {
  for (const category of ["FOOD", "餐饮"]) {
    assert.equal(resolveArtworkKind({ merchantName: "海棠咖啡店", category }), "coffee");
  }
  for (const category of ["RETAIL", "校园零售"]) {
    assert.equal(resolveArtworkKind({ merchantName: "松风文印社", category }), "print");
  }
  assert.equal(resolveArtworkKind({ merchantName: "新商户", category: "餐饮" }), "meal");
});
