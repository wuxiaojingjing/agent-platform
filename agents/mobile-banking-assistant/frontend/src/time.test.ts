import { describe, expect, it } from 'vitest'
import { formatOperationsTime, operationsTimeMillis } from './time'

describe('operations time', () => {
  it('treats legacy numeric values as epoch seconds and displays Shanghai time', () => {
    expect(operationsTimeMillis(1785600565.275)).toBe(1785600565275)
    expect(formatOperationsTime(1785600565.275)).toBe('00:09:25')
  })

  it('displays ISO timestamps and epoch milliseconds with the same timezone', () => {
    expect(formatOperationsTime('2026-08-01T16:09:25.275Z')).toBe('00:09:25')
    expect(formatOperationsTime(1785600565275)).toBe('00:09:25')
  })

  it('does not show an invalid date', () => {
    expect(formatOperationsTime('not-a-time')).toBe('—')
  })
})
