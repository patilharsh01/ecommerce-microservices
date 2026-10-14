import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, type OrderLineInput, type ProductInput } from './endpoints';
import type { Order, OrderStatus, OrderSummary, PaymentSimulation } from './types';
import { isTerminal } from '../lib/orders';

export const queryKeys = {
  products: (q: string, includeInactive: boolean) => ['products', q, includeInactive] as const,
  orders: (scope: 'mine' | 'all', status: OrderStatus | undefined) => ['orders', scope, status ?? 'ANY'] as const,
  order: (id: number) => ['order', id] as const,
  payment: (orderId: number) => ['payment', orderId] as const,
  notifications: (orderId: number | undefined) => ['notifications', orderId ?? 'ALL'] as const,
  stats: ['order-stats'] as const,
  simulation: ['payment-simulation'] as const,
};

/** Poll quickly while any saga on screen is still running; stop once everything has settled. */
const LIVE_INTERVAL_MS = 1000;

export function useProducts(q: string, includeInactive = false) {
  return useQuery({
    queryKey: queryKeys.products(q, includeInactive),
    queryFn: ({ signal }) => api.products({ q, includeInactive, size: 100 }, signal),
    placeholderData: keepPreviousData,
  });
}

export function useOrders(scope: 'mine' | 'all', status?: OrderStatus, size = 25) {
  return useQuery({
    queryKey: [...queryKeys.orders(scope, status), size],
    queryFn: ({ signal }) => api.orders({ scope, status, size }, signal),
    placeholderData: keepPreviousData,
    refetchInterval: (q) => (anyRunning(q.state.data?.content) ? LIVE_INTERVAL_MS : 5000),
  });
}

export function useOrder(id: number) {
  return useQuery({
    queryKey: queryKeys.order(id),
    queryFn: ({ signal }) => api.order(id, signal),
    refetchInterval: (q) => (q.state.data && isTerminal(q.state.data.status) ? false : LIVE_INTERVAL_MS),
  });
}

export function usePayment(order: Order | undefined) {
  return useQuery({
    queryKey: queryKeys.payment(order?.id ?? 0),
    queryFn: ({ signal }) => api.paymentForOrder(order!.id, signal),
    enabled: !!order && order.status !== 'PENDING',
    refetchInterval: () => (order && isTerminal(order.status) ? false : LIVE_INTERVAL_MS),
  });
}

export function useNotifications(orderId: number | undefined, live = false) {
  return useQuery({
    queryKey: queryKeys.notifications(orderId),
    queryFn: ({ signal }) => api.notifications(orderId, signal),
    refetchInterval: live ? LIVE_INTERVAL_MS : 10_000,
  });
}

export function useOrderStats() {
  return useQuery({
    queryKey: queryKeys.stats,
    queryFn: ({ signal }) => api.orderStats(signal),
    refetchInterval: 3000,
  });
}

export function useSimulation() {
  return useQuery({ queryKey: queryKeys.simulation, queryFn: ({ signal }) => api.simulation(signal) });
}

export function usePlaceOrder() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({ items, idempotencyKey }: { items: OrderLineInput[]; idempotencyKey: string }) =>
      api.placeOrder(items, idempotencyKey),
    onSuccess: ({ order }) => {
      client.setQueryData(queryKeys.order(order.id), order);
      void client.invalidateQueries({ queryKey: ['orders'] });
      void client.invalidateQueries({ queryKey: ['products'] });
    },
  });
}

export function useCancelOrder() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => api.cancelOrder(id),
    onSuccess: (order) => {
      client.setQueryData(queryKeys.order(order.id), order);
      void client.invalidateQueries({ queryKey: ['orders'] });
    },
  });
}

export function useUpdateSimulation() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (settings: PaymentSimulation) => api.updateSimulation(settings),
    onSuccess: (settings) => client.setQueryData(queryKeys.simulation, settings),
  });
}

export function useProductMutations() {
  const client = useQueryClient();
  const refresh = () => client.invalidateQueries({ queryKey: ['products'] });
  return {
    create: useMutation({ mutationFn: (input: ProductInput) => api.createProduct(input), onSuccess: refresh }),
    update: useMutation({
      mutationFn: ({ id, input }: { id: number; input: ProductInput }) => api.updateProduct(id, input),
      onSuccess: refresh,
    }),
    adjustStock: useMutation({
      mutationFn: ({ id, delta }: { id: number; delta: number }) => api.adjustStock(id, delta),
      onSuccess: refresh,
    }),
    deactivate: useMutation({ mutationFn: (id: number) => api.deactivateProduct(id), onSuccess: refresh }),
  };
}

function anyRunning(orders: OrderSummary[] | undefined): boolean {
  return !!orders?.some((order) => !isTerminal(order.status));
}
