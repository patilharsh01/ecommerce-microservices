import { useEffect, useState, type ReactNode } from 'react';
import type { Order, Participant, TimelineEntry } from '../api/types';
import { formatDateTime, formatDuration, formatOffset } from '../lib/format';
import { kindLabels, participantLabels, stepLabels } from '../lib/orders';

const LANES: Participant[] = ['ORDER', 'INVENTORY', 'PAYMENT'];

/**
 * The order's saga drawn as swim lanes: one column per service, one row per thing that
 * happened, in order. Commands the orchestrator sent appear in the lane of the service they
 * were sent to; compensations are amber so the "undo" path stands out.
 */
export function SagaTimeline({ order }: { order: Order }) {
  const start = order.timeline[0]?.occurredAt ?? order.createdAt;
  const waitingOn = pendingLane(order);

  return (
    <div className="saga" role="table" aria-label={`Saga timeline for order ${order.id}`}>
      <div className="saga-header" role="row">
        <span role="columnheader" className="saga-time-header">Time</span>
        {LANES.map((lane) => (
          <span role="columnheader" key={lane} className={`saga-lane-title lane-${lane.toLowerCase()}`}>
            {participantLabels[lane]}
          </span>
        ))}
      </div>
      <ol className="saga-rows">
        {order.timeline.map((entry, index) => (
          <SagaRow key={`${entry.step}-${entry.occurredAt}-${index}`} entry={entry} start={start} />
        ))}
        {waitingOn && <WaitingRow lane={waitingOn} order={order} />}
      </ol>
    </div>
  );
}

function SagaRow({ entry, start }: { entry: TimelineEntry; start: string }) {
  return (
    <li className={`saga-row kind-${entry.kind.toLowerCase()}`} role="row">
      <time className="saga-time" dateTime={entry.occurredAt} title={formatDateTime(entry.occurredAt)}>
        {formatOffset(start, entry.occurredAt)}
      </time>
      <Lanes active={entry.participant}>
        <div className="saga-card">
          <div className="saga-card-title">
            <span className="saga-dot" aria-hidden="true" />
            <strong>{entry.kind === 'COMMAND' ? `→ ${stepLabels[entry.step]}` : stepLabels[entry.step]}</strong>
            <span className="saga-kind">{kindLabels[entry.kind]}</span>
          </div>
          {entry.detail && <p className="saga-detail">{entry.detail}</p>}
          <span className="saga-lane-tag">{participantLabels[entry.participant]}</span>
        </div>
      </Lanes>
    </li>
  );
}

/** Renders all three lane cells so the empty ones can draw the lane guide lines. */
function Lanes({ active, children }: { active: Participant; children: ReactNode }) {
  return (
    <>
      {LANES.map((lane) => lane === active
        ? <div key={lane} className={`saga-cell lane-${lane.toLowerCase()}`} role="cell">{children}</div>
        : <div key={lane} className="saga-cell saga-cell-empty" role="cell" aria-hidden="true" />)}
    </>
  );
}

function WaitingRow({ lane, order }: { lane: Participant; order: Order }) {
  const now = useNow();
  const remaining = order.deadlineAt ? new Date(order.deadlineAt).getTime() - now : null;
  return (
    <li className="saga-row kind-waiting" role="row" aria-live="polite">
      <span className="saga-time">now</span>
      <Lanes active={lane}>
        <div className="saga-card">
          <div className="saga-card-title">
            <span className="spinner spinner-small" aria-hidden="true" />
            <strong>
              {order.status === 'COMPENSATING'
                ? 'Waiting for every compensation to finish…'
                : `Waiting for ${participantLabels[lane].toLowerCase()}…`}
            </strong>
          </div>
          {remaining !== null && (
            <p className="saga-detail">
              {order.status === 'COMPENSATING'
                ? (remaining > 0 ? `Unconfirmed compensations are re-sent in ${formatDuration(remaining)}` : 'Re-sending compensations')
                : (remaining > 0 ? `Times out in ${formatDuration(remaining)}` : 'Deadline passed; cancelling shortly')}
            </p>
          )}
        </div>
      </Lanes>
    </li>
  );
}

/** Which lane the saga is currently waiting on, or null when it has finished. */
export function pendingLane(order: Pick<Order, 'status'>): Participant | null {
  switch (order.status) {
    case 'PENDING':
      return 'INVENTORY';
    case 'AWAITING_PAYMENT':
      return 'PAYMENT';
    case 'COMPENSATING':
      return 'ORDER';
    default:
      return null;
  }
}

function useNow(intervalMs = 500): number {
  const [now, setNow] = useState(Date.now);
  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), intervalMs);
    return () => window.clearInterval(timer);
  }, [intervalMs]);
  return now;
}
