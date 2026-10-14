import { ApiError, get, query, request } from './client';
import type {
  AuthResponse,
  Notification,
  Order,
  OrderStats,
  OrderStatus,
  OrderSummary,
  Page,
  Payment,
  PaymentSimulation,
  Product,
  User,
} from './types';

export interface OrderLineInput {
  productId: number;
  quantity: number;
}

export interface ProductInput {
  sku?: string;
  name: string;
  description: string | null;
  price: number;
  stockQuantity: number;
  active?: boolean;
  version?: number;
}

export const api = {
  login: async (email: string, password: string) =>
    (await request<AuthResponse>('/auth/login', { method: 'POST', body: { email, password } })).data,

  register: async (name: string, email: string, password: string) =>
    (await request<AuthResponse>('/auth/register', { method: 'POST', body: { name, email, password } })).data,

  me: () => get<User>('/auth/me'),

  products: (params: { q?: string; includeInactive?: boolean; page?: number; size?: number }, signal?: AbortSignal) =>
    get<Page<Product>>(`/products${query({ ...params, sort: 'id,asc' })}`, signal),

  createProduct: async (input: ProductInput) =>
    (await request<Product>('/products', { method: 'POST', body: input })).data,

  updateProduct: async (id: number, input: ProductInput) =>
    (await request<Product>(`/products/${id}`, { method: 'PUT', body: input })).data,

  adjustStock: async (id: number, delta: number) =>
    (await request<Product>(`/products/${id}/stock-adjustments`, { method: 'POST', body: { delta } })).data,

  deactivateProduct: async (id: number) => {
    await request<void>(`/products/${id}`, { method: 'DELETE' });
  },

  /**
   * The idempotency key is chosen by the caller and reused if the same checkout is retried,
   * so a timeout or double click can never create two orders.
   */
  placeOrder: async (items: OrderLineInput[], idempotencyKey: string) => {
    const response = await request<Order>('/orders', {
      method: 'POST',
      body: { items },
      headers: { 'Idempotency-Key': idempotencyKey },
    });
    return { order: response.data, replayed: response.headers.get('Idempotent-Replayed') === 'true' };
  },

  orders: (params: { scope?: 'mine' | 'all'; status?: OrderStatus; page?: number; size?: number }, signal?: AbortSignal) =>
    get<Page<OrderSummary>>(`/orders${query(params)}`, signal),

  order: (id: number, signal?: AbortSignal) => get<Order>(`/orders/${id}`, signal),

  cancelOrder: async (id: number) => (await request<Order>(`/orders/${id}/cancel`, { method: 'POST' })).data,

  orderStats: (signal?: AbortSignal) => get<OrderStats>('/orders/stats', signal),

  /** null until payments has recorded something for the order. */
  paymentForOrder: async (orderId: number, signal?: AbortSignal): Promise<Payment | null> => {
    try {
      return await get<Payment>(`/payments/orders/${orderId}`, signal);
    } catch (error) {
      if (error instanceof ApiError && error.status === 404) {
        return null;
      }
      throw error;
    }
  },

  simulation: (signal?: AbortSignal) => get<PaymentSimulation>('/payments/simulation', signal),

  updateSimulation: async (settings: PaymentSimulation) =>
    (await request<PaymentSimulation>('/payments/simulation', { method: 'PUT', body: settings })).data,

  notifications: (orderId: number | undefined, signal?: AbortSignal) =>
    get<Page<Notification>>(`/notifications${query({ orderId, size: 50 })}`, signal),
};
