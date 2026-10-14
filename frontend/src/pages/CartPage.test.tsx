import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router';
import { AuthProvider } from '../auth/AuthContext';
import { CartProvider } from '../cart/CartContext';
import { CartPage } from './CartPage';

function signedInWithCart() {
  localStorage.setItem('shopsaga.session', JSON.stringify({
    token: 'token', expiresAt: new Date(Date.now() + 3_600_000).toISOString(),
    user: { id: 7, name: 'Asha', email: 'asha@example.com', role: 'CUSTOMER', createdAt: new Date().toISOString() },
  }));
  localStorage.setItem('shopsaga.cart', JSON.stringify([
    { product: { id: 1, name: 'Keyboard', price: 1000, sku: 'KB-1' }, quantity: 2 },
  ]));
}

function renderCart() {
  render(
    <QueryClientProvider client={new QueryClient()}>
      <AuthProvider>
        <CartProvider>
          <MemoryRouter initialEntries={['/cart']}>
            <Routes>
              <Route path="/cart" element={<CartPage />} />
              <Route path="/orders/:id" element={<p>order page</p>} />
            </Routes>
          </MemoryRouter>
        </CartProvider>
      </AuthProvider>
    </QueryClientProvider>,
  );
}

describe('CartPage checkout', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('retries with the same idempotency key after an ambiguous failure, so the order is never placed twice', async () => {
    signedInWithCart();
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({ status: 503, detail: 'Gateway timeout' }), { status: 503 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ id: 42, status: 'PENDING' }), {
        status: 200, headers: { 'Idempotent-Replayed': 'true' },
      }));
    vi.stubGlobal('fetch', fetchMock);
    renderCart();

    await userEvent.click(screen.getByRole('button', { name: 'Place order' }));
    await screen.findByText('Order status unknown');
    await userEvent.click(screen.getByRole('button', { name: 'Retry placing order' }));

    await screen.findByText('order page');
    const keys = fetchMock.mock.calls.map(([, init]) => (init as RequestInit & { headers: Record<string, string> }).headers['Idempotency-Key']);
    expect(keys).toHaveLength(2);
    expect(keys[0]).toBeTruthy();
    expect(keys[1]).toBe(keys[0]);
    expect(JSON.parse(fetchMock.mock.calls[0]![1].body as string)).toEqual({ items: [{ productId: 1, quantity: 2 }] });
  });

  it('starts a fresh idempotency key when the cart changes', async () => {
    signedInWithCart();
    const fetchMock = vi.fn().mockImplementation(() =>
      Promise.resolve(new Response(JSON.stringify({ status: 409, detail: 'conflict' }), { status: 409 })));
    vi.stubGlobal('fetch', fetchMock);
    renderCart();

    await userEvent.click(screen.getByRole('button', { name: 'Place order' }));
    await screen.findByText('Could not place the order');
    await userEvent.click(screen.getByRole('button', { name: 'More Keyboard' }));
    await userEvent.click(screen.getByRole('button', { name: 'Place order' }));

    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2));
    const keys = fetchMock.mock.calls.map(([, init]) => (init as RequestInit & { headers: Record<string, string> }).headers['Idempotency-Key']);
    expect(keys[1]).not.toBe(keys[0]);
  });
});
