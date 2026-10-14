import { useState, type FormEvent } from 'react';
import { ApiError } from '../../api/client';
import { useProductMutations, useProducts } from '../../api/hooks';
import type { Product } from '../../api/types';
import { ErrorMessage, Money, PageHeader, Spinner } from '../../components/ui';
import { formatCount } from '../../lib/format';

type Editing = { mode: 'create' } | { mode: 'edit'; product: Product } | null;

export function AdminProductsPage() {
  const [search, setSearch] = useState('');
  const products = useProducts(search.trim(), true);
  const { adjustStock, deactivate } = useProductMutations();
  const [editing, setEditing] = useState<Editing>(null);

  return (
    <>
      <PageHeader
        title="Catalog"
        subtitle="Stock changes here are concurrency-safe: edits carry the version you loaded, restocks are relative adjustments."
        actions={<button type="button" className="button" onClick={() => setEditing({ mode: 'create' })}>New product</button>}
      />

      {editing && (
        <ProductEditor key={editing.mode === 'edit' ? editing.product.id : 'new'} editing={editing} onDone={() => setEditing(null)} />
      )}

      <div className="toolbar">
        <input type="search" className="search" placeholder="Search by name or SKU" value={search}
               onChange={(event) => setSearch(event.target.value)} aria-label="Search products" />
      </div>
      <ErrorMessage error={adjustStock.error ?? deactivate.error} title="Update failed" />
      {products.isPending && <Spinner label="Loading products" />}
      <ErrorMessage error={products.error} title="Could not load products" />

      {products.data && (
        <div className="card table-card">
          <table className="table">
            <thead>
              <tr>
                <th>SKU</th><th>Name</th><th className="numeric">Price</th><th className="numeric">Stock</th>
                <th>Restock</th><th>Status</th><th />
              </tr>
            </thead>
            <tbody>
              {products.data.content.map((product) => (
                <tr key={product.id} className={product.active ? undefined : 'row-muted'}>
                  <td><code>{product.sku}</code></td>
                  <td>{product.name}</td>
                  <td className="numeric"><Money amount={product.price} /></td>
                  <td className="numeric">{formatCount(product.stockQuantity)}</td>
                  <td>
                    <div className="button-row">
                      {[1, 10].map((delta) => (
                        <button key={delta} type="button" className="button button-secondary button-small"
                                disabled={adjustStock.isPending}
                                onClick={() => adjustStock.mutate({ id: product.id, delta })}>+{delta}</button>
                      ))}
                    </div>
                  </td>
                  <td>{product.active ? <span className="badge status-confirmed">Active</span> : <span className="badge">Inactive</span>}</td>
                  <td className="numeric">
                    <div className="button-row button-row-end">
                      <button type="button" className="button button-ghost button-small"
                              onClick={() => setEditing({ mode: 'edit', product })}>Edit</button>
                      {product.active && (
                        <button type="button" className="button button-ghost button-small"
                                onClick={() => deactivate.mutate(product.id)}>Deactivate</button>
                      )}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  );
}

function ProductEditor({ editing, onDone }: { editing: Exclude<Editing, null>; onDone: () => void }) {
  const existing = editing.mode === 'edit' ? editing.product : null;
  const { create, update } = useProductMutations();
  const [sku, setSku] = useState(existing?.sku ?? '');
  const [name, setName] = useState(existing?.name ?? '');
  const [description, setDescription] = useState(existing?.description ?? '');
  const [price, setPrice] = useState(String(existing?.price ?? ''));
  const [stock, setStock] = useState(String(existing?.stockQuantity ?? '0'));
  const [active, setActive] = useState(existing?.active ?? true);
  const mutation = existing ? update : create;

  async function submit(event: FormEvent) {
    event.preventDefault();
    const input = {
      name: name.trim(),
      description: description.trim() || null,
      price: Number(price),
      stockQuantity: Number(stock),
    };
    if (existing) {
      await update.mutateAsync({ id: existing.id, input: { ...input, active, version: existing.version } });
    } else {
      await create.mutateAsync({ ...input, sku: sku.trim().toUpperCase() });
    }
    onDone();
  }

  return (
    <form className="card editor" onSubmit={(event) => { submit(event).catch(() => undefined); }}>
      <h2>{existing ? `Edit ${existing.sku}` : 'New product'}</h2>
      <ErrorMessage error={mutation.error}
                    title={mutation.error instanceof ApiError && mutation.error.code === 'STALE_VERSION'
                      ? 'Someone changed this product meanwhile — reload and retry' : 'Could not save'} />
      <div className="form-grid">
        {!existing && (
          <label className="field">
            <span>SKU</span>
            <input value={sku} onChange={(event) => setSku(event.target.value)} required pattern="[A-Za-z0-9][A-Za-z0-9-]{2,63}" />
          </label>
        )}
        <label className="field">
          <span>Name</span>
          <input value={name} onChange={(event) => setName(event.target.value)} required maxLength={200} />
        </label>
        <label className="field">
          <span>Price (₹)</span>
          <input type="number" min="0.01" step="0.01" value={price} onChange={(event) => setPrice(event.target.value)} required />
        </label>
        <label className="field">
          <span>Stock</span>
          <input type="number" min="0" step="1" value={stock} onChange={(event) => setStock(event.target.value)} required />
        </label>
        <label className="field field-wide">
          <span>Description</span>
          <textarea value={description} onChange={(event) => setDescription(event.target.value)} maxLength={2000} rows={2} />
        </label>
        {existing && (
          <label className="checkbox">
            <input type="checkbox" checked={active} onChange={(event) => setActive(event.target.checked)} />
            <span>Active (sold in the shop)</span>
          </label>
        )}
      </div>
      <div className="button-row">
        <button type="submit" className="button" disabled={mutation.isPending}>{mutation.isPending ? 'Saving…' : 'Save'}</button>
        <button type="button" className="button button-ghost" onClick={onDone}>Cancel</button>
      </div>
    </form>
  );
}
