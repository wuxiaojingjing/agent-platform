import { afterEach, describe, expect, it, vi } from 'vitest'
import { api } from './api'

describe('chat structured actions', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('sends event, runtime reference and state version with the chat request', async () => {
    const fetch = vi.fn().mockResolvedValue(new Response(JSON.stringify({ actions: [] }), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    }))
    vi.stubGlobal('fetch', fetch)

    await api.chat('继续', 'session-1', {
      userId: 'user-1',
      spaceId: 'tenant-1',
      channel: 'MOBILE_BANK',
    }, 'home', '', {
      event: 'REVIEW_ACCEPT',
      ref: 'task-1',
      version: 7,
    })

    const [, init] = fetch.mock.calls[0]
    expect(JSON.parse(String(init?.body))).toMatchObject({
      query: '继续',
      action: { event: 'REVIEW_ACCEPT', ref: 'task-1', version: 7 },
    })
  })

  it('parses ordered SSE card events even when network chunks split a frame', async () => {
    const encoder = new TextEncoder()
    const payload = [
      'id:0\nevent:TURN_STARTED\ndata:{"sequence":0,"type":"TURN_STARTED"}\n\n',
      'id:1\nevent:CARD_AVAILABLE\ndata:{"sequence":1,"type":"CARD_AVAILABLE","component":"TASK_PROGRESS","itemIndex":0,"itemCount":1}\n\n',
      'id:2\nevent:TURN_COMPLETED\ndata:{"sequence":2,"type":"TURN_COMPLETED"}\n\n',
    ].join('')
    const stream = new ReadableStream({
      start(controller) {
        controller.enqueue(encoder.encode(payload.slice(0, 57)))
        controller.enqueue(encoder.encode(payload.slice(57, 143)))
        controller.enqueue(encoder.encode(payload.slice(143)))
        controller.close()
      },
    })
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(stream, {
      status: 200, headers: { 'Content-Type': 'text/event-stream' },
    })))
    const events: string[] = []

    await api.chatStream('测试', 'session-1', {
      userId: 'user-1', spaceId: 'tenant-1', channel: 'MOBILE_BANK',
    }, 'home', '', undefined, (event) => events.push(
      `${event.sequence}:${event.type}:${event.itemIndex ?? '-'}:${event.itemCount ?? '-'}`,
    ))

    expect(events).toEqual([
      '0:TURN_STARTED:-:-', '1:CARD_AVAILABLE:0:1', '2:TURN_COMPLETED:-:-',
    ])
  })
})
