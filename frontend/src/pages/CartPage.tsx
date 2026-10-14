import { useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router';
import { ApiError } from '../api/client';
import { usePlaceOrder } from '../api/hooks';
import { useAuth } from '../auth/AuthContext';
import { MAX_UNITS_PER_PRODUCT, useCart } from '../cart/CartContext';
import { EmptyState, ErrorMessage, Money, PageHeader } from '../components/ui';
import { newIdempotencyKey } from '../lib/format';

export function CartPage() {
  const { lines, total, setQuantity, remove, clear } = useCart();
  const { user } = useAuth();
  const navigate = useNavigate();
  const placeOrder = usePlaceOrder();
  const [retrying, setRetrying] = useState(false);

  // One key per checkout attempt. A retry after a timeout or network error reuses it, so the
  // server returns the order it may already have created instead of creating a second one.
  const attemptKey = useRef<string | null>(null);
  const cartSignature = lines.map((line) => `${line.product.id}x${line.quantity}`).join(',');
  const keyedSignature = useRef(cartSignature);
  if (keyedSignature.current !== cartSignature) {
    keyedSignature.current = cartSignature;
    attemptKey.current = null;
  }

  async function checkout() {
    attemptKey.current ??= newIdempotencyKey();
    try {
      const { order } = await placeOrder.mutateAsync({
        items: lines.map((line) => ({ productId: line.product.id, quantity: line.quantity })),
        idempotencyKey: attemptKey.current,
      });
      attemptKey.current = null;
      clear();
      navigate(`/orders/${order.id}`, { state: { justPlaced: true } });
    } catch (error) {
      setRetrying(error instanceof ApiError && error.isTransient);
    }
  }

  if (lines.length === 0) {
    return (
      <>
        <PageHeader title="Cart" />
        <EmptyState title="Your cart is empty"><Link to="/">Browse the shop</Link></EmptyState>
      </>
    );
  }

  return (
    <>
      <PageHeader title="Cart" subtitle="Prices are confirmed against the live catalog when you place the order." />
      <div className="checkout-layout">
        <div className="card">
          <table className="table">
            <thead>
              <tr><th>Product</th><th className="numeric">Price</th><th>Quantity</th><th className="numeric">Total</th><th /></tr>
            </thead>
            <tbody>
              {lines.map((line) => (
                <tr key={line.product.id}>
                  <td><strong>{line.product.name}</strong><br /><code className="muted">{line.product.sku}</code></td>
                  <td className="numeric"><Money amount={line.product.price} /></td>
                  <td>
                    <div className="stepper">
                      <button type="button" aria-label={`Fewer ${line.product.name}`}
                              onClick={() => setQuantity(line.product.id, line.quantity - 1)}>−</button>
                      <input type="number" min={1} max={MAX_UNITS_PER_PRODUCT} value={line.quantity}
                             aria-label={`Quantity of ${line.product.name}`}
                             onChange={(event) => setQuantity(line.product.id, Number(event.target.value))} />
                      <button type="button" aria-label={`More ${line.product.name}`}
                              onClick={() => setQuantity(line.product.id, line.quantity + 1)}>+</button>
                    </div>
                  </td>
                  <td className="numeric"><Money amount={line.product.price * line.quantity} /></td>
                  <td className="numeric">
                    <button type="button" className="button button-ghost button-small" onClick={() => remove(line.product.id)}>Remove</button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>

        <aside className="card summary">
          <h2>Summary</h2>
          <dl className="summary-lines">
            <dt>Items</dt><dd>{lines.reduce((sum, line) => sum + line.quantity, 0)}</dd>
            <dt>Total</dt><dd className="summary-total"><Money amount={total} /></dd>
          </dl>
          {total > 50000 && (
            <p className="hint hint-warn">Above the demo card limit of <Money amount={50000} />: payment will be declined and the stock released.</p>
          )}
          <ErrorMessage error={placeOrder.error} title={retrying ? 'Order status unknown' : 'Could not place the order'} />
          {retrying && <p className="hint">Retrying is safe: the same idempotency key is sent, so you won't be charged twice.</p>}
          {user ? (
            <button type="button" className="button button-block" onClick={checkout} disabled={placeOrder.isPending}>
              {placeOrder.isPending ? 'Placing order…' : retrying ? 'Retry placing order' : 'Place order'}
            </button>
          ) : (
            <Link to="/login?next=/cart" className="button button-block">Sign in to place the order</Link>
          )}
        </aside>
      </div>
    </>
  );
}
