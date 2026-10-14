const sameDay = (a, b) => a.toDateString() === b.toDateString();

function yesterday() {
  const d = new Date();
  d.setDate(d.getDate() - 1);
  return d;
}

export const clock = (iso) => new Date(iso).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });

/** Chat list column: 14:05 / Yesterday / Monday / 12/10/2026 */
export function listTime(iso) {
  if (!iso) return '';
  const d = new Date(iso);
  const now = new Date();
  if (sameDay(d, now)) return clock(iso);
  if (sameDay(d, yesterday())) return 'Yesterday';
  if (now - d < 6 * 864e5) return d.toLocaleDateString([], { weekday: 'long' });
  return d.toLocaleDateString();
}

/** Date chip between message groups: Today / Yesterday / 12 October 2026 */
export function dayLabel(iso) {
  const d = new Date(iso);
  if (sameDay(d, new Date())) return 'Today';
  if (sameDay(d, yesterday())) return 'Yesterday';
  return d.toLocaleDateString([], { day: 'numeric', month: 'long', year: 'numeric' });
}

export function lastSeen(iso) {
  if (!iso) return '';
  const d = new Date(iso);
  if (sameDay(d, new Date())) return `last seen today at ${clock(iso)}`;
  if (sameDay(d, yesterday())) return `last seen yesterday at ${clock(iso)}`;
  return `last seen ${d.toLocaleDateString()}`;
}

export const isSameDay = (a, b) => sameDay(new Date(a), new Date(b));
