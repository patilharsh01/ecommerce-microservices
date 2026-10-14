import { useState } from 'react';
import { Link } from 'react-router';
import { useOrders } from '../api/hooks';
import type { OrderStatus, OrderSummary } from '../api/types';
import { EmptyState, ErrorMessage, Money, PageHeader, Spinner, StatusBadge } from '../components/ui';
import { formatRelative } from '../lib/format';
import { causeLabels } from '../lib/orders';

const FILTERS: { label: string; status?: OrderStatus }[] = [
  { label: 'All' },
  { label: 'Confirmed', status: 'CONFIRMED' },
  { label: 'Cancelled', status: 'CANCELLED' },
  { label: 'Awaiting payment', status: 'AWAITING_PAYMENT' },
];

export function OrdersPage() {
  const [filter, setFilter] = useState<OrderStatus | undefined>();
  const orders = useOrders('mine', filter);

  return (
    <>
      <PageHeader title="My orders" subtitle="Running sagas update live." />
      <div className="tabs" role="tablist" aria-label="Filter orders">
        {FILTERS.map((option) => (
          <button key={option.label} role="tab" type="button" aria-selected={filter === option.status}
                  className={filter === option.status ? 'tab tab-active' : 'tab'} onClick={() => setFilter(option.status)}>
            {option.label}
          </button>
        ))}
      </div>
      {orders.isPending && <Spinner label="Loading orders" />}
      <ErrorMessage error={orders.error} title="Could not load your orders" />
      {orders.data && orders.data.content.length === 0 && (
        <EmptyState title="No orders yet"><Link to="/">Place your first order</Link></EmptyState>
      )}
      {orders.data && orders.data.content.length > 0 && <OrderTable orders={orders.data.content} />}
    </>
  );
}

export function OrderTable({ orders, showCustomer = false }: { orders: OrderSummary[]; showCustomer?: boolean }) {
  return (
    <div className="card table-card">
      <table className="table">
        <thead>
          <tr>
            <th>Order</th>
            {showCustomer && <th>Customer</th>}
            <th>Placed</th>
            <th className="numeric">Items</th>
            <th className="numeric">Total</th>
            <th>Status</th>
            <th>Why</th>
          </tr>
        </thead>
        <tbody>
          {orders.map((order) => (
            <tr key={order.id}>
              <td><Link to={`/orders/${order.id}`}>#{order.id}</Link></td>
              {showCustomer && <td>#{order.customerId}</td>}
              <td title={order.createdAt}>{formatRelative(order.createdAt)}</td>
              <td className="numeric">{order.itemCount}</td>
              <td className="numeric"><Money amount={order.totalAmount} /></td>
              <td><StatusBadge status={order.status} /></td>
              <td className="muted truncate" title={order.cancellationReason ?? undefined}>
                {order.cancellationCause ? causeLabels[order.cancellationCause] : '—'}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
