/** Decorative hints only: this never changes a record's business category. */
export type ArtworkKind =
  | "coffee" | "tea" | "meal" | "bakery" | "print"
  | "grocery" | "stationery" | "sports" | "general";

export interface ArtworkContext {
  subject?: string;
  merchantName?: string;
  category?: string;
}

const rules: ReadonlyArray<readonly [ArtworkKind, RegExp]> = [
  ["coffee", /咖啡|拿铁|美式|摩卡|卡布奇诺|馥芮白|冷萃|手冲|\b(coffee|cafe|café|latte|espresso)\b/i],
  ["tea", /茶饮|奶茶|冷泡茶|乌龙|奶绿|绿茶|红茶|茶铺|茶点|果茶|果汁|饮品|\b(tea|juice|beverage)\b/i],
  ["print", /文印|打印|复印|彩印|彩打|装订|胶装|扫描|\b(print|printing|copy)\b/i],
  ["bakery", /烘焙|甜点|蛋糕|面包|可颂|贝果|吐司|曲奇|司康|点心|泡芙|\b(bakery|bread|cake|pastry)\b/i],
  ["stationery", /图书|文创|文具|书屋|书店|书签|笔记|手账|明信片|小说|中性笔|铅笔|\b(book|books|stationery)\b/i],
  ["sports", /运动|体育|球馆|球拍|篮球|足球|羽毛球|乒乓球|健身|\b(sport|sports|fitness)\b/i],
  ["meal", /餐饮|简餐|轻食|食堂|砂锅|餐厅|套餐|米饭|炒饭|饭团|盖饭|小食|冰粉|沙拉|面食|牛肉|排骨|\b(meal|food|dining|restaurant)\b/i],
  ["grocery", /便利|零售|零食|超市|补给|日用|生活用品|洗护|商店|杂货|坚果|\b(grocery|retail|market)\b/i],
];

/** An explicit item takes precedence over the store that sells it. */
export function resolveArtworkKind(context: ArtworkContext = {}): ArtworkKind {
  const category = typeof context.category === "string" ? context.category.trim() : "";
  // A broad accounting category should not erase a more specific storefront hint.
  const broadCategory = /^(food|retail|market|餐饮|餐饮服务|零售|校园零售|日用零售|其他|其他服务)$/i.test(category);
  const hints = broadCategory
    ? [context.subject, context.merchantName, category]
    : [context.subject, category, context.merchantName];
  for (const value of hints) {
    if (typeof value !== "string" || !value.trim()) continue;
    const match = rules.find(([, pattern]) => pattern.test(value));
    if (match) return match[0];
  }
  return "general";
}
