import { useState } from 'react';
import { useOrderStats, useOrders } from '../../api/hooks';
import type { CancellationCause, OrderStatus } from '../../api/types';
import { ErrorMessage, PageHeader, Spinner, StatCard } from '../../components/ui';
import { formatCount } from '../../lib/format';
import { toolLinks } from '../../lib/links';
import { causeLabels, statusLabels } from '../../lib/orders';
import { OrderTable } from '../OrdersPage';

const IN_FLIGHT: OrderStatus[] = ['PENDING', 'AWAITING_PAYMENT', 'COMPENSATING'];

export function AdminOverviewPage() {
  const stats = useOrderStats();
  const [status, setStatus] = useState<OrderStatus | undefined>();
  const orders = useOrders('all', status, 15);

  const finished = (stats.data?.confirmedLast24h ?? 0) + (stats.data?.cancelledLast24h ?? 0);
  const successRate = finished ? (stats.data!.confirmedLast24h / finished) : null;
  const causes = Object.entries(stats.data?.cancellationsByCauseLast24h ?? {}) as [CancellationCause, number][];
  const maxCause = Math.max(1, ...causes.map(([, count]) => count));

  return (
    <>
      <PageHeader title="Operations" subtitle="Live view of every saga across all customers." />
      <ErrorMessage error={stats.error} title="Could not load statistics" />
      {stats.isPending && <Spinner label="Loading statistics" />}

      {stats.data && (
        <>
          <div className="stat-grid">
            <StatCard label="Confirmed (24 h)" value={formatCount(stats.data.confirmedLast24h)} tone="good" />
            <StatCard label="Cancelled (24 h)" value={formatCount(stats.data.cancelledLast24h)}
                      tone={stats.data.cancelledLast24h > stats.data.confirmedLast24h ? 'bad' : undefined} />
            <StatCard label="Success rate (24 h)" value={successRate === null ? '—' : `${Math.round(successRate * 100)}%`}
                      tone={successRate !== null && successRate < 0.7 ? 'warn' : undefined} />
            <StatCard label="Saga time p95" value={seconds(stats.data.p95SagaSeconds)}
                      hint={`average ${seconds(stats.data.avgSagaSeconds)}`} />
          </div>

          <div className="two-column">
            <section className="card">
              <h2>Running sagas</h2>
              <ul className="inflight">
                {IN_FLIGHT.map((state) => (
                  <li key={state} className={stats.data.byStatus[state] > 0 ? 'inflight-active' : undefined}>
                    <span>{statusLabels[state]}</span>
                    <strong>{formatCount(stats.data.byStatus[state] ?? 0)}</strong>
                  </li>
                ))}
              </ul>
              <p className="muted small">
                A count stuck under <em>Cancelling</em> means a participant stopped answering; Prometheus alerts on it after five minutes.
              </p>
            </section>
            <section className="card">
              <h2>Why orders were cancelled (24 h)</h2>
              {causes.length === 0 && <p className="muted">No cancellations.</p>}
              <ul className="bars">
                {causes.map(([cause, count]) => (
                  <li key={cause}>
                    <span>{causeLabels[cause]}</span>
                    <span className="bar"><span style={{ width: `${(count / maxCause) * 100}%` }} /></span>
                    <strong>{formatCount(count)}</strong>
                  </li>
                ))}
              </ul>
            </section>
          </div>
        </>
      )}

      <section className="section">
        <div className="section-header">
          <h2>Latest orders</h2>
          <select value={status ?? ''} onChange={(event) => setStatus((event.target.value || undefined) as OrderStatus | undefined)}
                  aria-label="Filter by status">
            <option value="">All statuses</option>
            {(Object.keys(statusLabels) as OrderStatus[]).map((option) => (
              <option key={option} value={option}>{statusLabels[option]}</option>
            ))}
          </select>
        </div>
        <ErrorMessage error={orders.error} title="Could not load orders" />
        {orders.data && <OrderTable orders={orders.data.content} showCustomer />}
      </section>

      <section className="section">
        <h2>Under the hood</h2>
        <div className="tool-grid">
          <ToolLink href={toolLinks.grafana()} title="Grafana" text="Saga outcomes, latency percentiles, outbox backlog, dead letters" />
          <ToolLink href={toolLinks.jaeger()} title="Jaeger" text="One trace per order across gateway → orders → Kafka → inventory → payments" />
          <ToolLink href={toolLinks.swagger()} title="Swagger UI" text="Every service's API, aggregated at the gateway" />
          <ToolLink href={toolLinks.prometheus()} title="Prometheus alerts" text="Stuck compensations, outbox backlog, open circuit breaker" />
          <ToolLink href={toolLinks.eureka()} title="Eureka" text="Registered service instances" />
          <ToolLink href={toolLinks.kafkaUi()} title="Kafka UI" text="Topics and dead-letter queues (start with --profile tools)" />
        </div>
      </section>
    </>
  );
}

function ToolLink({ href, title, text }: { href: string; title: string; text: string }) {
  return (
    <a className="card tool-link" href={href} target="_blank" rel="noopener noreferrer">
      <strong>{title} ↗</strong>
      <span className="muted">{text}</span>
    </a>
  );
}

function seconds(value: number | null): string {
  if (value === null) return '—';
  return value < 1 ? `${Math.round(value * 1000)} ms` : `${value.toFixed(1)} s`;
}
