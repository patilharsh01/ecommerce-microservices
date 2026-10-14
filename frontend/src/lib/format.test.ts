import { formatDuration, formatMoney, formatOffset } from './format';

describe('formatMoney', () => {
  it('uses Indian digit grouping', () => {
    expect(formatMoney(123456.5)).toBe('₹1,23,456.50');
    expect(formatMoney(10000000)).toBe('₹1,00,00,000.00');
  });
});

describe('formatDuration', () => {
  it.each([
    [0, '0 ms'],
    [850, '850 ms'],
    [2400, '2.4 s'],
    [42_000, '42 s'],
    [65_000, '1 m 05 s'],
  ])('%i ms -> %s', (milliseconds, expected) => {
    expect(formatDuration(milliseconds)).toBe(expected);
  });
});

describe('formatOffset', () => {
  it('is relative to the first timeline entry', () => {
    expect(formatOffset('2026-01-01T10:00:00.000Z', '2026-01-01T10:00:01.250Z')).toBe('+1.3 s');
  });
});
