#!/usr/bin/env python3
"""Test fixture only. Replaces privilege drop and Unix socket for restricted CI hosts.
Never copied into sandbox images; cannot be selected through irv configuration.
"""
import sys,pathlib,asyncio,os
sys.path.insert(0,str(pathlib.Path(__file__).resolve().parents[1]/'agent'))
from agent import Agent,RUNTIME
from aiohttp import web
async def start_test_api(self):
 app=web.Application(client_max_size=3*1024*1024);app.router.add_route('*','/{op}',self.local_api)
 self.local_runner=web.AppRunner(app);await self.local_runner.setup()
 site=web.TCPSite(self.local_runner,'127.0.0.1',0);await site.start()
 (RUNTIME/'test-port').write_text(str(site._server.sockets[0].getsockname()[1]))
Agent.child_args=lambda self:{}
Agent.start_local_api=start_test_api
asyncio.run(Agent().run())
