// Mirrors of the backend response records. Kept by hand: the API is small and the
// compiler flags every place that has to change when a contract changes.

export type Role = 'CUSTOMER' | 'ADMIN';

export interface User {
  id: number;
  name: string;
  email: string;
  role: Role;
  createdAt: string;
}

export interface AuthResponse {
  accessToken: string;
  tokenType: 'Bearer';
  expiresAt: string;
  user: User;
}

export interface Page<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface Product {
  id: number;
  sku: string;
  name: string;
  description: string | null;
  price: number;
  stockQuantity: number;
  active: boolean;
  version: number;
}

export type OrderStatus = 'PENDING' | 'AWAITING_PAYMENT' | 'CONFIRMED' | 'COMPENSATING' | 'CANCELLED';
export type CancellationCause = 'OUT_OF_STOCK' | 'PAYMENT_DECLINED' | 'TIMED_OUT' | 'CUSTOMER_REQUEST';
export type Participant = 'ORDER' | 'INVENTORY' | 'PAYMENT';
export type StepKind = 'INFO' | 'COMMAND' | 'SUCCESS' | 'FAILURE' | 'COMPENSATION';

export type SagaStep =
  | 'ORDER_PLACED'
  | 'RESERVE_STOCK_SENT'
  | 'STOCK_RESERVED'
  | 'STOCK_REJECTED'
  | 'PROCESS_PAYMENT_SENT'
  | 'PAYMENT_COMPLETED'
  | 'PAYMENT_DECLINED'
  | 'COMPENSATION_STARTED'
  | 'RELEASE_STOCK_SENT'
  | 'STOCK_RELEASED'
  | 'REFUND_PAYMENT_SENT'
  | 'PAYMENT_REFUNDED'
  | 'COMPENSATION_RETRIED'
  | 'ORDER_CONFIRMED'
  | 'ORDER_CANCELLED'
  | 'LATE_REPLY_IGNORED';

export interface TimelineEntry {
  step: SagaStep;
  participant: Participant;
  kind: StepKind;
  detail: string | null;
  occurredAt: string;
}

export interface LineItem {
  productId: number;
  productName: string;
  quantity: number;
  unitPrice: number;
  lineTotal: number;
}

export interface OrderSummary {
  id: number;
  customerId: number;
  status: OrderStatus;
  totalAmount: number;
  itemCount: number;
  cancellationCause: CancellationCause | null;
  cancellationReason: string | null;
  createdAt: string;
  completedAt: string | null;
}

export interface Order extends OrderSummary {
  paymentReference: string | null;
  deadlineAt: string | null;
  updatedAt: string;
  items: LineItem[];
  timeline: TimelineEntry[];
}

export interface OrderStats {
  byStatus: Record<OrderStatus, number>;
  confirmedLast24h: number;
  cancelledLast24h: number;
  cancellationsByCauseLast24h: Partial<Record<CancellationCause, number>>;
  avgSagaSeconds: number | null;
  p95SagaSeconds: number | null;
}

export type PaymentStatus = 'COMPLETED' | 'DECLINED' | 'REFUNDED' | 'VOIDED';

export interface Payment {
  orderId: number;
  customerId: number | null;
  amount: number | null;
  status: PaymentStatus;
  reference: string | null;
  failureReason: string | null;
  refundReason: string | null;
  createdAt: string;
  updatedAt: string;
}

export type SimulationMode = 'APPROVE_UNDER_LIMIT' | 'APPROVE_ALL' | 'DECLINE_ALL';

export interface PaymentSimulation {
  mode: SimulationMode;
  approvalLimit: number;
  latencyMs: number;
}

export interface Notification {
  id: number;
  customerId: number;
  orderId: number;
  type: 'ORDER_PLACED' | 'ORDER_CONFIRMED' | 'ORDER_CANCELLED';
  subject: string;
  body: string;
  createdAt: string;
}

/** RFC 9457 problem document, as returned by every service and the gateway. */
export interface Problem {
  status: number;
  title?: string;
  detail?: string;
  code?: string;
  errors?: { field: string; message: string }[];
}
