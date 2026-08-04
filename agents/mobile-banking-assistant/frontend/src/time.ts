export type OperationsTimestamp = string | number

const OPERATIONS_TIME_ZONE = 'Asia/Shanghai'
const TIME_FORMATTER = new Intl.DateTimeFormat('zh-CN', {
  timeZone: OPERATIONS_TIME_ZONE,
  hourCycle: 'h23',
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
})

/** Accepts ISO-8601, epoch milliseconds, and the legacy backend's epoch seconds. */
export function operationsTimeMillis(value: OperationsTimestamp): number {
  const numeric = typeof value === 'number'
    ? value
    : /^-?\d+(\.\d+)?$/.test(value.trim()) ? Number(value) : Number.NaN
  if (Number.isFinite(numeric)) {
    return Math.abs(numeric) < 100_000_000_000 ? numeric * 1000 : numeric
  }
  const parsed = Date.parse(String(value))
  return Number.isFinite(parsed) ? parsed : Number.NaN
}

export function formatOperationsTime(value: OperationsTimestamp): string {
  const milliseconds = operationsTimeMillis(value)
  if (!Number.isFinite(milliseconds)) return '—'
  const parts = TIME_FORMATTER.formatToParts(new Date(milliseconds))
  const read = (type: Intl.DateTimeFormatPartTypes) =>
    parts.find((part) => part.type === type)?.value ?? '00'
  return `${read('hour')}:${read('minute')}:${read('second')}`
}
