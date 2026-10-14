import { useDeferredValue, useState } from 'react';
import { Link } from 'react-router';
import { useProducts } from '../api/hooks';
import type { Product } from '../api/types';
import { useCart } from '../cart/CartContext';
import { EmptyState, ErrorMessage, Money, PageHeader, Spinner } from '../components/ui';
import { formatCount } from '../lib/format';

export function ShopPage() {
  const [search, setSearch] = useState('');
  const deferredSearch = useDeferredValue(search);
  const products = useProducts(deferredSearch.trim());
  const { count, total } = useCart();

  return (
    <>
      <PageHeader
        title="Shop"
        subtitle="Every order you place runs as a saga: stock is reserved, the card is charged, and if anything fails the earlier steps are undone."
        actions={count > 0 && (
          <Link to="/cart" className="button">
            Checkout · {formatCount(count)} item{count === 1 ? '' : 's'} · <Money amount={total} />
          </Link>
        )}
      />

      <div className="callout">
        <strong>Try the failure paths</strong>
        <span>
          Orders over <Money amount={50000} /> are declined by the simulated card limit, so the reserved stock is released.
          Order more than is in stock to see the reservation rejected. Admins can inject latency and declines under Operations.
        </span>
      </div>

      <div className="toolbar">
        <input type="search" className="search" placeholder="Search by name or SKU" value={search}
               onChange={(event) => setSearch(event.target.value)} aria-label="Search products" />
        {products.isFetching && <span className="spinner spinner-small" aria-label="Refreshing" />}
      </div>

      {products.isPending && <Spinner label="Loading products" />}
      <ErrorMessage error={products.error} title="Could not load the catalog" />
      {products.data && products.data.content.length === 0 && (
        <EmptyState title="No products found">Try a different search.</EmptyState>
      )}

      <div className="product-grid">
        {products.data?.content.map((product) => <ProductCard key={product.id} product={product} />)}
      </div>
    </>
  );
}

function ProductCard({ product }: { product: Product }) {
  const { add, lines } = useCart();
  const inCart = lines.find((line) => line.product.id === product.id)?.quantity ?? 0;

  return (
    <article className="card product-card">
      <div className="product-art" aria-hidden="true">{product.sku.split('-')[0]}</div>
      <div className="product-body">
        <div className="product-meta">
          <code>{product.sku}</code>
          <StockLabel stock={product.stockQuantity} />
        </div>
        <h2>{product.name}</h2>
        {product.description && <p className="muted">{product.description}</p>}
      </div>
      <div className="product-footer">
        <Money amount={product.price} />
        {/* Deliberately not disabled when sold out: ordering anyway shows the saga rejecting it. */}
        <button type="button" className="button button-small" onClick={() => add(product)}>
          {inCart > 0 ? `Add another (${inCart})` : 'Add to cart'}
        </button>
      </div>
    </article>
  );
}

function StockLabel({ stock }: { stock: number }) {
  if (stock === 0) return <span className="stock stock-out">Out of stock</span>;
  if (stock <= 5) return <span className="stock stock-low">Only {stock} left</span>;
  return <span className="stock stock-ok">{formatCount(stock)} in stock</span>;
}
