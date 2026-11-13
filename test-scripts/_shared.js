import axios from "axios";
import { existsSync, readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";
const here = dirname(fileURLToPath(import.meta.url));
const envFile = resolve(here, ".env");
if (existsSync(envFile)) {
  for (const line of readFileSync(envFile, "utf8").split(/\r?\n/)) {
    const m = line.match(/^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*?)\s*$/);
    if (m && process.env[m[1]] === undefined) process.env[m[1]] = m[2].replace(/^["']|["']$/g, "");
  }
}
const serviceUrls = {
  lender: process.env.LENDER_API_URL || "http://127.0.0.1:8084",
  lifecycle: process.env.LIFECYCLE_API_URL || "http://127.0.0.1:8085",
  lenderAdapter: process.env.LENDER_FIX_API_URL || "http://127.0.0.1:8083",
  market: process.env.MARKET_DATA_API_URL || "http://127.0.0.1:8080",
};
const resolveBody = (v) => Array.isArray(v) ? v.map(resolveBody) : v && typeof v === "object"
  ? Object.fromEntries(Object.entries(v).map(([k,x])=>[k,resolveBody(x)]))
  : typeof v === "string" ? v.replaceAll("$RUN_ID", Date.now().toString()) : v;

export async function runEndpoint(test) {
  const { name, service, method, path: route, body, orgScoped=false, paged=false, idEnv } = test;
  const baseURL = serviceUrls[service];
  let path = route;
  if (path.includes("{id}")) {
    const id = process.env[idEnv || "RESOURCE_ID"];
    if (!id || !/^[A-Za-z0-9_-]+$/.test(id)) throw new Error(`Set ${idEnv || "RESOURCE_ID"} in test-scripts/.env before running this script.`);
    path = path.replaceAll("{id}", encodeURIComponent(id));
  }
  if (orgScoped) {
    const orgId = process.env.LENDER_ORG_ID || "1";
    if (!/^\d+$/.test(orgId)) throw new Error("LENDER_ORG_ID must be numeric.");
    path = `/api/v1/organizations/${orgId}${path}`;
  }
  if (paged) path += (path.includes("?") ? "&" : "?") + new URLSearchParams({page:process.env.PAGE||"0",size:process.env.PAGE_SIZE||"25"}).toString();
  const write = !["GET","HEAD"].includes(method);
  if (write && process.env.ALLOW_WRITES !== "true") {
    console.log(`\n[DRY RUN] ${name}\n${method} ${baseURL}${path}\nNo HTTP request sent. Set ALLOW_WRITES=true in .env to execute this state-changing endpoint.`);
    if (body !== undefined) console.log("Body:", JSON.stringify(resolveBody(body),null,2));
    return;
  }
  console.log(`\n[REQUEST] ${name}\n${method} ${baseURL}${path}`);
  if (body !== undefined) console.log("Body:", JSON.stringify(resolveBody(body),null,2));
  try {
    const response = await axios({baseURL,url:path,method,data:body===undefined?undefined:resolveBody(body),timeout:Number(process.env.REQUEST_TIMEOUT_MS||25000),headers:{Accept:"application/json",...(body!==undefined?{"Content-Type":"application/json"}:{})},validateStatus:()=>true});
    console.log(`[HTTP ${response.status}] ${name}`);
    console.log(typeof response.data==="string"?response.data:JSON.stringify(response.data,null,2));
    if(response.status<200||response.status>=300)process.exitCode=1;
  } catch(error) {
    console.error(`[REQUEST FAILED] ${name}: ${error.code||error.message}`);
    if(error.response)console.error("HTTP status:",error.response.status,"\n",JSON.stringify(error.response.data,null,2));
    else console.error("Check service availability and configured URL.");
    process.exitCode=1;
  }
}
