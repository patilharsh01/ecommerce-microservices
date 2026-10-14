import type { ReactNode } from 'react';
import type { OrderStatus } from '../api/types';
import { ApiError } from '../api/client';
import { statusLabels } from '../lib/orders';
import { formatMoney } from '../lib/format';

export function StatusBadge({ status }: { status: OrderStatus }) {
  return (
    <span className={`badge status-${status.toLowerCase()}`}>
      {(status === 'PENDING' || status === 'AWAITING_PAYMENT' || status === 'COMPENSATING') && (
        <span className="badge-pulse" aria-hidden="true" />
      )}
      {statusLabels[status]}
    </span>
  );
}

export function Money({ amount }: { amount: number }) {
  return <span className="money">{formatMoney(amount)}</span>;
}

export function Spinner({ label = 'Loading' }: { label?: string }) {
  return (
    <div className="loading" role="status">
      <span className="spinner" aria-hidden="true" />
      <span>{label}…</span>
    </div>
  );
}

export function ErrorMessage({ error, title = 'Something went wrong' }: { error: unknown; title?: string }) {
  if (!error) return null;
  const message = error instanceof Error ? error.message : String(error);
  const fieldErrors = error instanceof ApiError ? error.fieldErrors : [];
  return (
    <div className="alert alert-error" role="alert">
      <strong>{title}</strong>
      <span>{message}</span>
      {fieldErrors.length > 0 && (
        <ul>
          {fieldErrors.map((fieldError) => (
            <li key={`${fieldError.field}-${fieldError.message}`}>
              <code>{fieldError.field}</code> {fieldError.message}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

export function EmptyState({ title, children }: { title: string; children?: ReactNode }) {
  return (
    <div className="empty">
      <strong>{title}</strong>
      {children && <div>{children}</div>}
    </div>
  );
}

export function StatCard({ label, value, hint, tone }: { label: string; value: ReactNode; hint?: string; tone?: 'good' | 'bad' | 'warn' }) {
  return (
    <div className={`stat ${tone ? `stat-${tone}` : ''}`}>
      <span className="stat-label">{label}</span>
      <span className="stat-value">{value}</span>
      {hint && <span className="stat-hint">{hint}</span>}
    </div>
  );
}

export function PageHeader({ title, subtitle, actions }: { title: string; subtitle?: ReactNode; actions?: ReactNode }) {
  return (
    <header className="page-header">
      <div>
        <h1>{title}</h1>
        {subtitle && <p className="muted">{subtitle}</p>}
      </div>
      {actions && <div className="page-actions">{actions}</div>}
    </header>
  );
}
