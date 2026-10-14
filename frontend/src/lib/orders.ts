import type { CancellationCause, OrderStatus, Participant, SagaStep, StepKind } from '../api/types';

export function isTerminal(status: OrderStatus): boolean {
  return status === 'CONFIRMED' || status === 'CANCELLED';
}

export function isCancellable(status: OrderStatus): boolean {
  return status === 'PENDING' || status === 'AWAITING_PAYMENT';
}

export const statusLabels: Record<OrderStatus, string> = {
  PENDING: 'Reserving stock',
  AWAITING_PAYMENT: 'Awaiting payment',
  CONFIRMED: 'Confirmed',
  COMPENSATING: 'Cancelling',
  CANCELLED: 'Cancelled',
};

export const causeLabels: Record<CancellationCause, string> = {
  OUT_OF_STOCK: 'Out of stock',
  PAYMENT_DECLINED: 'Payment declined',
  TIMED_OUT: 'Timed out',
  CUSTOMER_REQUEST: 'Cancelled by request',
};

export const participantLabels: Record<Participant, string> = {
  ORDER: 'Order service',
  INVENTORY: 'Inventory',
  PAYMENT: 'Payments',
};

export const stepLabels: Record<SagaStep, string> = {
  ORDER_PLACED: 'Order placed',
  RESERVE_STOCK_SENT: 'Reserve stock',
  STOCK_RESERVED: 'Stock reserved',
  STOCK_REJECTED: 'Stock unavailable',
  PROCESS_PAYMENT_SENT: 'Charge payment',
  PAYMENT_COMPLETED: 'Payment captured',
  PAYMENT_DECLINED: 'Payment declined',
  COMPENSATION_STARTED: 'Compensation started',
  RELEASE_STOCK_SENT: 'Release stock',
  STOCK_RELEASED: 'Stock released',
  REFUND_PAYMENT_SENT: 'Refund payment',
  PAYMENT_REFUNDED: 'Payment refunded',
  COMPENSATION_RETRIED: 'Compensation re-sent',
  ORDER_CONFIRMED: 'Order confirmed',
  ORDER_CANCELLED: 'Order cancelled',
  LATE_REPLY_IGNORED: 'Late reply ignored',
};

export const kindLabels: Record<StepKind, string> = {
  INFO: 'Event',
  COMMAND: 'Command',
  SUCCESS: 'Success',
  FAILURE: 'Failure',
  COMPENSATION: 'Compensation',
};
