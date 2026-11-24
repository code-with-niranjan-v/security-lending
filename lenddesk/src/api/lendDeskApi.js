import { lendoraUrl } from './sessionApi'

function errorMessage(payload, status) {
  return payload?.message || payload?.detail || payload?.title || payload?.error || `Lender API returned ${status}`
}

const API_BASE_URL = import.meta.env.VITE_LENDER_API_URL ?? 'http://localhost:8090/lender'

function unwrap(payload) {
  if (!payload?.success) throw new Error(payload?.message ?? 'Lender API request failed')
  return payload.data
}

export async function lenderGet(path, signal) {
  const response = await fetch(`${API_BASE_URL}${path}`, { credentials: 'include', headers: { Accept: 'application/json' }, signal })
  if (response.status === 401 && import.meta.env.VITE_LENDORA_AUTH_REQUIRED !== 'false') window.location.replace(lendoraUrl + '/login')
  if (!response.ok) {
    const body = await response.json().catch(() => null)
    throw new Error(errorMessage(body, response.status))
  }
  return unwrap(await response.json())
}

export async function lenderPost(path, body, signal) {
  const response = await fetch(`${API_BASE_URL}${path}`, {
    credentials: 'include', method: 'POST', headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
    body: JSON.stringify(body), signal,
  })
  if (response.status === 401 && import.meta.env.VITE_LENDORA_AUTH_REQUIRED !== 'false') window.location.replace(lendoraUrl + '/login')
  const payload = await response.json().catch(() => null)
  if (!response.ok) throw new Error(errorMessage(payload, response.status))
  return unwrap(payload)
}

export async function lenderPatch(path, body, signal) {
  const response = await fetch(`${API_BASE_URL}${path}`, {
    credentials: 'include', method: 'PATCH', headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
    body: JSON.stringify(body), signal,
  })
  if (response.status === 401 && import.meta.env.VITE_LENDORA_AUTH_REQUIRED !== 'false') window.location.replace(lendoraUrl + '/login')
  const payload = await response.json().catch(() => null)
  if (!response.ok) throw new Error(errorMessage(payload, response.status))
  return unwrap(payload)
}

export async function lenderPut(path, body, signal) {
  const response = await fetch(`${API_BASE_URL}${path}`, {
    credentials: 'include', method: 'PUT', headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
    body: JSON.stringify(body), signal,
  })
  if (response.status === 401 && import.meta.env.VITE_LENDORA_AUTH_REQUIRED !== 'false') window.location.replace(lendoraUrl + '/login')
  const payload = await response.json().catch(() => null)
  if (!response.ok) throw new Error(errorMessage(payload, response.status))
  return unwrap(payload)
}

export const lendDeskApi = {
  dashboard: (organizationId, signal) => lenderGet(`/api/v1/organizations/${organizationId}/lenddesk/dashboard`, signal),
  list: (organizationId, resource, signal) => lenderGet(`/api/v1/organizations/${organizationId}/lenddesk/${resource}?page=0&size=200`, signal),
  inventory: (organizationId, signal) => lenderGet(`/api/v1/organizations/${organizationId}/lenddesk/inventory?page=0&size=200`, signal),
  inventoryDetail: (organizationId, inventoryId, signal) => lenderGet(`/api/v1/organizations/${organizationId}/lenddesk/inventory/${inventoryId}`, signal),
  agreement: (organizationId, agreementId, signal) => lenderGet(`/api/v1/organizations/${organizationId}/lenddesk/agreements/${agreementId}`, signal),
  agreementExpanded: (organizationId, agreementId, signal) => lenderGet(`/api/v1/organizations/${organizationId}/lenddesk/agreements/${agreementId}/expanded`, signal),
  loan: (organizationId, loanId, signal) => lenderGet(`/api/v1/organizations/${organizationId}/lenddesk/loans/${loanId}`, signal),
}
