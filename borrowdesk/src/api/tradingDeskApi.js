import { get, post, put, del, orgUrl, unwrapRows } from "./apiClient";
const base = orgUrl("");
const marketBase = (
  import.meta.env.VITE_MARKET_DATA_API || "http://localhost:8090/market-data"
).replace(/\/$/, "");
async function marketGet(path) {
  const response = await fetch(marketBase + path);
  const payload = await response.json();
  if (!response.ok)
    throw new Error(
      payload?.message || payload?.detail || payload?.title || "Market data unavailable",
    );
  return payload?.data ?? payload;
}
export const tradingDeskApi = {
  agreements: () => get(base + "/agreements?page=0&size=200"),
  lenders: () => get(base + "/lenders"),
  securities: (q) =>
    get("/api/v1/securities?search=" + encodeURIComponent(q || "")),
  watchlist: () => get(base + "/watchlist"),
  addWatch: (id) => put(base + "/watchlist/" + id, {}),
  removeWatch: (id) => del(base + "/watchlist/" + id),
  candles: (symbol, exchange) =>
    marketGet(
      "/api/v1/security-data/history?symbol=" +
        encodeURIComponent(symbol) +
        "&exchange=" +
        encodeURIComponent(exchange),
    ),
  orders: () => get(base + "/orders?page=0&size=100"),
  trades: () => get(base + "/trades?page=0&size=100"),
  positions: () => get(base + "/positions"),
  holdings: () => get(base + "/holdings"),
  loans: () => get(base + "/loans?page=0&size=100"),
  collateral: () => get(base + "/collateral"),
  account: () => get(base + "/account"),
  dashboard: () => get(base + "/dashboard"),
  placeOrder: (payload) => post(base + "/orders", payload),
  updateOrder: (id, payload) => put(base + "/orders/" + id, payload),
  cancelOrder: (id) => post(base + "/orders/" + id + "/cancel", {}),
};
export { unwrapRows };
