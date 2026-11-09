"""Variant: task cancellation (uvicorn cancels the StreamingResponse task on disconnect)."""
import asyncio, sys
sys.path.insert(0, r'C:/Users/hp/Desktop/LianYu-PC/backend-agent')
from app.gateway import ModelGateway
from app.resilience import breaker_registry

PROVIDER = 'repro-cancel'
BASE = 'https://upstream.example/v1'

async def fake_stream_once(self, base_url, api_key, payload, tool_sink=None):
    for i in range(100):
        await asyncio.sleep(0.01)
        yield f'd{i}'

async def consume(gw):
    async for _ in gw.stream_text(base_url=BASE, api_key='k', model='m',
                                  messages=[{'role': 'user', 'content': 'hi'}],
                                  temperature=0.7, max_tokens=10, provider=PROVIDER):
        pass

async def main():
    gw = ModelGateway.__new__(ModelGateway); gw._client = None
    gw._stream_once = lambda *a, **k: fake_stream_once(gw, *a, **k)
    b = breaker_registry().resolve(PROVIDER, BASE)
    for i in range(5):
        t = asyncio.create_task(consume(gw))
        await asyncio.sleep(0.05)
        t.cancel()
        try:
            await t
        except asyncio.CancelledError:
            pass
    print('after 5 cancellations -> state =', getattr(getattr(b, '_state', None), 'value', None))
    print('window =', list(getattr(b, '_results', [])))
    print('permit =', await b.try_acquire())

asyncio.run(main())
