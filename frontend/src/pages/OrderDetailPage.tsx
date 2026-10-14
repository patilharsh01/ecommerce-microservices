import { Link, useLocation, useParams } from 'react-router';
import { useCancelOrder, useNotifications, useOrder, usePayment } from '../api/hooks';
import type { Order, Payment } from '../api/types';
import { SagaTimeline } from '../components/SagaTimeline';
import { EmptyState, ErrorMessage, Money, PageHeader, Spinner, StatusBadge } from '../components/ui';
import { formatDateTime, formatDuration, formatRelative } from '../lib/format';
import { causeLabels, isCancellable, isTerminal } from '../lib/orders';

export function OrderDetailPage() {
  const id = Number(useParams().id);
  const location = useLocation();
  const order = useOrder(id);
  const cancel = useCancelOrder();

  if (!Number.isInteger(id) || id <= 0) {
    return <EmptyState title="That is not an order number" />;
  }
  if (order.isPending) {
    return <Spinner label={`Loading order #${id}`} />;
  }
  if (order.error || !order.data) {
    return <ErrorMessage error={order.error} title={`Could not load order #${id}`} />;
  }

  const data = order.data;
  const justPlaced = (location.state as { justPlaced?: boolean } | null)?.justPlaced && !isTerminal(data.status);

  return (
    <>
      <PageHeader
        title={`Order #${data.id}`}
        subtitle={<>Placed {formatDateTime(data.createdAt)} · <Money amount={data.totalAmount} /> · {data.itemCount} item(s)</>}
        actions={
          <>
            <StatusBadge status={data.status} />
            {isCancellable(data.status) && (
              <button type="button" className="button button-danger button-small" disabled={cancel.isPending}
                      onClick={() => cancel.mutate(data.id)}>
                {cancel.isPending ? 'Cancelling…' : 'Cancel order'}
              </button>
            )}
          </>
        }
      />

      {justPlaced && (
        <div className="callout callout-live" aria-live="polite">
          <strong>Your order is on its way through the saga</strong>
          <span>Stock is being reserved and the payment charged. This page updates as each service answers.</span>
        </div>
      )}
      <ErrorMessage error={cancel.error} title="Could not cancel" />
      <Outcome order={data} />

      <div className="detail-layout">
        <section className="card">
          <h2>Saga timeline</h2>
          <SagaTimeline order={data} />
        </section>

        <div className="detail-side">
          <section className="card">
            <h2>Items</h2>
            <ul className="item-list">
              {data.items.map((item) => (
                <li key={item.productId}>
                  <span>{item.quantity} × {item.productName}</span>
                  <Money amount={item.lineTotal} />
                </li>
              ))}
              <li className="item-total"><span>Total</span><Money amount={data.totalAmount} /></li>
            </ul>
          </section>
          <PaymentPanel order={data} />
          <NotificationsPanel orderId={data.id} live={!isTerminal(data.status)} />
        </div>
      </div>
      <p className="muted back-link"><Link to="/orders">← All orders</Link></p>
    </>
  );
}

function Outcome({ order }: { order: Order }) {
  if (!isTerminal(order.status) || !order.completedAt) return null;
  const took = formatDuration(new Date(order.completedAt).getTime() - new Date(order.createdAt).getTime());
  if (order.status === 'CONFIRMED') {
    return (
      <div className="alert alert-success">
        <strong>Confirmed in {took}</strong>
        <span>Stock held and payment captured ({order.paymentReference}).</span>
      </div>
    );
  }
  return (
    <div className="alert alert-warn">
      <strong>{order.cancellationCause ? causeLabels[order.cancellationCause] : 'Cancelled'} · settled in {took}</strong>
      <span>{order.cancellationReason}. Every completed step was undone; nothing is held or charged.</span>
    </div>
  );
}

function PaymentPanel({ order }: { order: Order }) {
  const payment = usePayment(order);
  return (
    <section className="card">
      <h2>Payment</h2>
      {order.status === 'PENDING' && <p className="muted">Requested once stock is reserved.</p>}
      {payment.isLoading && <Spinner label="Checking payment" />}
      <ErrorMessage error={payment.error} title="Could not load the payment" />
      {payment.data === null && order.status !== 'PENDING' && <p className="muted">No payment recorded yet.</p>}
      {payment.data && <PaymentDetails payment={payment.data} />}
    </section>
  );
}

function PaymentDetails({ payment }: { payment: Payment }) {
  return (
    <dl className="facts">
      <dt>Status</dt><dd><span className={`badge payment-${payment.status.toLowerCase()}`}>{payment.status}</span></dd>
      {payment.amount !== null && <><dt>Amount</dt><dd><Money amount={payment.amount} /></dd></>}
      {payment.reference && <><dt>Reference</dt><dd><code>{payment.reference}</code></dd></>}
      {payment.failureReason && <><dt>Declined</dt><dd>{payment.failureReason}</dd></>}
      {payment.refundReason && <><dt>{payment.status === 'VOIDED' ? 'Voided' : 'Refunded'}</dt><dd>{payment.refundReason}</dd></>}
    </dl>
  );
}

function NotificationsPanel({ orderId, live }: { orderId: number; live: boolean }) {
  const notifications = useNotifications(orderId, live);
  return (
    <section className="card">
      <h2>Emails sent</h2>
      {notifications.data?.content.length === 0 && <p className="muted">None yet.</p>}
      <ErrorMessage error={notifications.error} title="Could not load notifications" />
      <ul className="notification-list">
        {notifications.data?.content.map((notification) => (
          <li key={notification.id}>
            <strong>{notification.subject}</strong>
            <span className="muted">{formatRelative(notification.createdAt)}</span>
            <p>{notification.body}</p>
          </li>
        ))}
      </ul>
    </section>
  );
}
