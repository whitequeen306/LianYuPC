"""Repro: client disconnect (generator aclose / task cancel) is counted as an upstream failure."""
import asyncio, sys
sys.path.insert(0, r'C:/Users/hp/Desktop/LianYu-PC/backend-agent')

from app.gateway import ModelGateway
from app.resilience import breaker_registry

PROVIDER = 'repro'
BASE = 'https://upstream.example/v1'

async def fake_stream_once(self, base_url, api_key, payload, tool_sink=None):
    # mimics a real SSE stream that keeps producing deltas
    for i in range(50):
        await asyncio.sleep(0.01)
        yield f'delta{i}'

async def one_disconnect(gw):
    agen = gw.stream_text(base_url=BASE, api_key='k', model='m',
                          messages=[{'role': 'user', 'content': 'hi'}],
                          temperature=0.7, max_tokens=10, provider=PROVIDER)
    got = 0
    async for _ in agen:
        got += 1
        break            # <- client closes the SSE connection after first delta
    await agen.aclose()  # what Starlette does when the client goes away
    return got

async def main():
    gw = ModelGateway.__new__(ModelGateway)
    gw._client = None
    gw._stream_once = lambda *a, **k: fake_stream_once(gw, *a, **k)
    b = breaker_registry().resolve(PROVIDER, BASE)
    # service allowed_calls: stream_text calls breaker.try_acquire() which for CLOSED always returns True
    allowed = True
    for i in range(5):
        await one_disconnect(gw)
    st = getattr(b, '_state', None)
    results = list(getattr(b, '_results', []))
    print('after 5 client disconnects -> state =', getattr(st, 'value', st))
    print('window  =', results)
    # now simulate another user hitting the same upstream
    ok = await b.try_acquire()
    print('permit for next user =', ok)
    if not ok:
        print('=> next user is fast-failed with GatewayRejectedError (智能体暂时繁忙)')

asyncio.run(main())
