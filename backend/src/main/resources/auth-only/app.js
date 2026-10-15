'use strict';
const labels = {passed: 'Passed', failed: 'Failed', pending: 'Pending', not_configured: 'Not configured', not_implemented: 'Not implemented'};
let busy = false;
async function refresh() {
  if (busy) return;
  busy = true;
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 5000);
  try {
    const response = await fetch('/auth/status', {cache: 'no-store', credentials: 'same-origin', signal: controller.signal});
    if (!response.ok) throw new Error('Unavailable');
    const data = await response.json();
    if (!Array.isArray(data.checks) || data.authenticated !== false) throw new Error('Unexpected report');
    const list = document.getElementById('checks');
    list.replaceChildren();
    for (const check of data.checks) {
      const item = document.createElement('li');
      const title = document.createElement('strong'); title.textContent = check.label;
      const badge = document.createElement('span');
      badge.className = 'badge ' + (Object.hasOwn(labels, check.status) ? check.status : 'pending');
      badge.textContent = labels[check.status] || 'Unknown';
      const detail = document.createElement('p'); detail.textContent = check.detail;
      item.append(title, badge, detail); list.append(item);
    }
    document.getElementById('overall').textContent = 'Authentication incomplete';
    document.getElementById('freshness').textContent = 'Last checked: ' + new Date(data.checkedAt).toLocaleTimeString();
  } catch (_) {
    document.getElementById('overall').textContent = 'Current status unavailable';
    document.getElementById('freshness').textContent = 'Could not verify this request. Earlier results have been cleared.';
    document.getElementById('checks').replaceChildren();
  } finally { clearTimeout(timeout); busy = false; }
}
document.getElementById('refresh').addEventListener('click', refresh);
document.addEventListener('visibilitychange', () => { if (!document.hidden) refresh(); });
refresh();
setInterval(() => { if (!document.hidden) refresh(); }, 15000);
