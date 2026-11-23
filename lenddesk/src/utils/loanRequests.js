export function pendingLoanRequests({ collateral, returns }) {
  return [
    ...returns.filter(row => row.status === 'PENDING').map(row => ({
      row, type: 'Return receipt', group: 'returns', prefix: 'RET',
      confirm: 'return', confirmLabel: 'Confirm receipt', reject: 'returnReject', rejectLabel: 'Reject return',
      note: 'Confirm only after the securities have been received.',
    })),
    ...collateral.filter(row => row.status === 'PLEDGE_PENDING').map(row => ({
      row, type: 'Collateral receipt', group: 'collateral', prefix: 'COL',
      confirm: 'collateralConfirm', confirmLabel: 'Confirm receipt', reject: 'collateralReject', rejectLabel: 'Reject pledge',
      note: 'Confirm only after verifying the custody receipt.',
    })),
    ...collateral.filter(row => row.status === 'RELEASE_REQUESTED').map(row => ({
      row, type: 'Collateral release', group: 'collateral', prefix: 'REL',
      confirm: 'collateralRelease', confirmLabel: 'Accept release', reject: 'collateralReleaseReject', rejectLabel: 'Reject release',
      note: 'The service rechecks collateral coverage before accepting the release.',
    })),
  ].map(item => ({ ...item, id: `${item.prefix}-${String(item.row.id).padStart(6, '0')}` }))
}
