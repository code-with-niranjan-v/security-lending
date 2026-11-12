const API_BASE_URL = import.meta.env.VITE_LENDER_API_URL ?? 'http://localhost:8084'

function unwrap(payload) {
  if (!payload?.success) throw new Error(payload?.message ?? 'Lender API request failed')
  return payload.data
}

export async function lenderGet(path, signal) {
  const response = await fetch(`${API_BASE_URL}${path}`, { headers: { Accept: 'application/json' }, signal })
  if (!response.ok) {
    const body = await response.json().catch(() => null)
    throw new Error(body?.detail ?? `Lender API returned ${response.status}`)
  }
  return unwrap(await response.json())
}

export async function lenderPost(path, body, signal) {
  const response = await fetch(`${API_BASE_URL}${path}`, {
    method: 'POST', headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
    body: JSON.stringify(body), signal,
  })
  const payload = await response.json().catch(() => null)
  if (!response.ok) throw new Error(payload?.detail ?? `Lender API returned ${response.status}`)
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
