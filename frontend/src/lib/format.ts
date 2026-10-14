const rupees = new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 2 });
const integer = new Intl.NumberFormat('en-IN');
const dateTime = new Intl.DateTimeFormat('en-IN', { dateStyle: 'medium', timeStyle: 'medium' });

/** ₹1,23,456.50 — Intl applies Indian digit grouping for en-IN. */
export function formatMoney(amount: number): string {
  return rupees.format(amount);
}

export function formatCount(value: number): string {
  return integer.format(value);
}

export function formatDateTime(iso: string): string {
  return dateTime.format(new Date(iso));
}

/** Compact duration: 850 ms, 2.4 s, 1 m 05 s. */
export function formatDuration(milliseconds: number): string {
  if (milliseconds < 1000) {
    return `${Math.max(0, Math.round(milliseconds))} ms`;
  }
  const seconds = milliseconds / 1000;
  if (seconds < 60) {
    return `${seconds.toFixed(seconds < 10 ? 1 : 0)} s`;
  }
  const minutes = Math.floor(seconds / 60);
  const rest = Math.round(seconds % 60);
  return `${minutes} m ${String(rest).padStart(2, '0')} s`;
}

/** "+1.2 s" relative to a reference instant, for saga timelines. */
export function formatOffset(fromIso: string, toIso: string): string {
  return `+${formatDuration(new Date(toIso).getTime() - new Date(fromIso).getTime())}`;
}

export function formatRelative(iso: string, now: number = Date.now()): string {
  const seconds = Math.round((now - new Date(iso).getTime()) / 1000);
  if (seconds < 5) return 'just now';
  if (seconds < 60) return `${seconds}s ago`;
  const minutes = Math.round(seconds / 60);
  if (minutes < 60) return `${minutes} min ago`;
  const hours = Math.round(minutes / 60);
  if (hours < 24) return `${hours} h ago`;
  return formatDateTime(iso);
}

/** A fresh idempotency key per checkout attempt. */
export function newIdempotencyKey(): string {
  return crypto.randomUUID();
}
