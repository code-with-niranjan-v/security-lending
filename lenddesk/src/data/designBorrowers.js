// Static snapshot from lender-ui-designs/borrowers.png.
export const designBorrowers = [
 ['Everest Capital','5493001KJ8F4Y1B72N60',5,14,769087500,102,0,0,2,'Jan 28, 2025 14:21:37'],
 ['Bridgewater Securities','549300J1K3P2Q8Z4N91',4,9,492216000,103,1,0,1,'Jan 10, 2025 16:03:21'],
 ['Atlas Capital LP','549300P6R5L7M9T3A48',3,12,232104000,102.5,0,1,1,'Feb 1, 2025 10:47:09'],
 ['Summit Global','549300Q7Z8D1W6K9M22',3,7,267465000,104,0,0,0,'Feb 12, 2025 11:22:36'],
 ['Crestview Markets','549300R3T7H9V5B6P90',4,11,421500000,102,2,0,1,'Mar 1, 2025 09:51:03'],
 ['Orion Hedge Fund','549300S6F2N8J4C1V77',3,6,145065000,103,0,1,0,'Mar 15, 2025 15:20:18'],
 ['Rivermere Advisors','549300T8M4U1P7D9K33',4,8,252900000,102,0,0,1,'Mar 18, 2025 13:08:44'],
 ['Northlake Capital','549300U9V3Y6E2H5R11',2,5,98340000,102,0,0,0,'Apr 1, 2025 10:17:26'],
 ['Granite Partners','549300V1B8K5R7N2D66',2,4,76125000,103.5,0,0,0,'Apr 8, 2025 14:05:11'],
 ['Meridian Asset Mgmt','549300W4G9Q2M6T8L55',3,10,318740000,102.5,1,0,1,'Apr 15, 2025 11:49:37'],
 ['Pinebrook Capital','549300X5N1D7P3K9V40',2,4,61925000,102,0,0,0,'Apr 18, 2025 09:33:02'],
 ['Lakeshore Investment','549300Y7C3F8L2Q6M19',2,5,123480000,102.8,0,0,1,'Apr 22, 2025 14:27:19'],
 ['Maplewood Capital','549301A2P6H9V4R8G77',2,6,188320000,103.2,0,0,0,'Apr 24, 2025 10:05:48'],
 ['Evergreen Partners','549301B5R8K2W7D3N88',2,5,109760000,102.1,0,0,0,'Apr 25, 2025 16:18:07'],
].map(([name,lei,mslas,loans,exposure,ratio,marginCalls,recalls,settlements,activity])=>({id:lei,name,lei,mslas,loans,exposure,ratio,marginCalls,recalls,settlements,activity,status:'Active'}))
export const borrowerMslas = [
 ['MSLA-00124','Jan 2, 2024','Jan 2, 2026'], ['MSLA-00137','Mar 1, 2024','Mar 1, 2026'], ['MSLA-00152','Jun 10, 2024','Jun 10, 2026'], ['MSLA-00166','Sep 1, 2024','Sep 1, 2026'], ['MSLA-00178','Nov 15, 2024','Nov 15, 2026'],
]
