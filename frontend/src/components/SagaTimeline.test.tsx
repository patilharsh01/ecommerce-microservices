import { render, screen, within } from '@testing-library/react';
import type { Order, TimelineEntry } from '../api/types';
import { pendingLane, SagaTimeline } from './SagaTimeline';

const at = (seconds: number) => new Date(Date.UTC(2026, 0, 1, 10, 0, seconds)).toISOString();

function order(status: Order['status'], timeline: TimelineEntry[]): Order {
  return {
    id: 7, customerId: 1, status, totalAmount: 2000, itemCount: 2, paymentReference: null,
    cancellationCause: null, cancellationReason: null, deadlineAt: null, createdAt: at(0), updatedAt: at(0),
    completedAt: null, items: [], timeline,
  };
}

const placed: TimelineEntry = { step: 'ORDER_PLACED', participant: 'ORDER', kind: 'INFO', detail: '2 item(s)', occurredAt: at(0) };
const reserve: TimelineEntry = { step: 'RESERVE_STOCK_SENT', participant: 'INVENTORY', kind: 'COMMAND', detail: null, occurredAt: at(0) };
const reserved: TimelineEntry = { step: 'STOCK_RESERVED', participant: 'INVENTORY', kind: 'SUCCESS', detail: null, occurredAt: at(1) };
const released: TimelineEntry = { step: 'STOCK_RELEASED', participant: 'INVENTORY', kind: 'COMPENSATION', detail: 'Held stock returned to inventory', occurredAt: at(3) };

describe('SagaTimeline', () => {
  it('draws one row per entry with the elapsed time and the step in its service lane', () => {
    render(<SagaTimeline order={order('CANCELLED', [placed, reserve, reserved, released])} />);

    const rows = screen.getAllByRole('row').slice(1);
    expect(rows).toHaveLength(4);
    expect(within(rows[1]!).getByText('→ Reserve stock')).toBeInTheDocument();
    expect(within(rows[3]!).getByText('+3.0 s')).toBeInTheDocument();
    expect(within(rows[3]!).getByText('Held stock returned to inventory')).toBeInTheDocument();
    const filledCells = within(rows[3]!).getAllByRole('cell').filter((cell) => !cell.classList.contains('saga-cell-empty'));
    expect(filledCells).toHaveLength(1);
    expect(filledCells[0]).toHaveClass('lane-inventory');
  });

  it('shows what the saga is still waiting for', () => {
    render(<SagaTimeline order={order('AWAITING_PAYMENT', [placed, reserve, reserved])} />);

    expect(screen.getByText('Waiting for payments…')).toBeInTheDocument();
  });

  it('shows no waiting row once the saga has finished', () => {
    render(<SagaTimeline order={order('CONFIRMED', [placed])} />);

    expect(screen.queryByText(/Waiting for/)).not.toBeInTheDocument();
  });
});

describe('pendingLane', () => {
  it.each([
    ['PENDING', 'INVENTORY'],
    ['AWAITING_PAYMENT', 'PAYMENT'],
    ['COMPENSATING', 'ORDER'],
    ['CONFIRMED', null],
    ['CANCELLED', null],
  ] as const)('%s -> %s', (status, lane) => {
    expect(pendingLane({ status })).toBe(lane);
  });
});
