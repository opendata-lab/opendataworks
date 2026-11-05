"""Verify real Portal MCP using explicit test identities and kept native ACL fixtures.

Run with DataAgent's prepared Python environment (httpx + mcp). Identity headers
remain the existing local contract; this is not trusted delegated-user acceptance.
A short-lived test token is issued for the fixture SERVICE account,
never printed and revoked at exit, and no runtime is started/restarted.
"""
import argparse,asyncio,json
from pathlib import Path
import httpx
from mcp import ClientSession
from mcp.client.streamable_http import streamablehttp_client
p=argparse.ArgumentParser();p.add_argument('--credentials',type=Path,required=True);p.add_argument('--state',type=Path,required=True);p.add_argument('--engine',choices=['mysql','postgresql'],required=True);p.add_argument('--dataagent-url',default='http://127.0.0.1:8900');p.add_argument('--mcp-url',default='http://127.0.0.1:8082/api/mcp/');a=p.parse_args()
s=json.loads(a.state.read_text());cfg=json.loads(a.credentials.read_text());assert s.get('accepted') and not s.get('cleaned')
admin=httpx.Client(base_url=cfg['portal_url'],timeout=45)
def api(method,path,body=None):
 r=admin.request(method,path,json=body) if body is not None else admin.request(method,path);j=r.json();assert r.status_code==200 and j.get('code')==200,(path,r.status_code,j.get('message'));return j.get('data')
api('POST','/auth/login',{'username':cfg['admin_username'],'password':cfg['admin_password']})
base=f"/v1/doris-clusters/{s['source']}/permissions"
def agent():return next(m for m in api('GET',base)['members'] if m['subjectType']=='SERVICE' and str(m['subjectId'])==str(s['service']['id']))
def assign(ids):return api('PUT',base+f"/members/SERVICE/{s['service']['id']}",{'roleIds':ids,'expectedRevision':agent()['revision']})
async def query(user,db,table,allowed):
 headers={'Authorization':'Bearer '+token['token'],'X-Agent-User-Id':'local:'+user['username']}
 async with streamablehttp_client(a.mcp_url,headers=headers) as (read,write,_):
  async with ClientSession(read,write) as c:
   await c.initialize();r=await c.call_tool('odw_query_readonly',{'database':db,'sql':'SELECT COUNT(*) AS n,SUM(amount) AS total FROM '+table,'preferredEngine':a.engine})
   assert bool(r.isError)!=allowed,(db,table,'unexpected authorization result')
   if allowed:assert r.structuredContent['rows']==[{'n':2,'total':30.0}],r.structuredContent['rows']
   label=f"{a.engine} {user['username']} {db}.{table} "+('allowed' if allowed else 'denied');s.setdefault('mcp_checks',[]).append(label);print('PASS',label,flush=True)
async def main():
 await query(s['users'][0],s['a'],'orders',True);await query(s['users'][0],s['b'],'orders',True)
 await query(s['users'][1],s['a'],'v_orders' if a.engine=='postgresql' else 'orders',True)
 await query(s['users'][1],s['a'] if a.engine=='postgresql' else s['b'],'orders',False)
original=agent()['roleIds']
token=api('POST',f"/v1/users/{s['service']['id']}/tokens",{'name':'MCP验收临时凭据','expiresInDays':1})
try:
 # Rebind verifies latest native account capability checks and persists its identity.
 api('POST',base+f"/members/SERVICE/{s['service']['id']}/account",{**s['agent_account'],'expectedRevision':agent()['revision']})
 if a.engine=='postgresql':assign(list(dict.fromkeys(original+[s['roles'][3]['id']])))
 asyncio.run(main());a.state.write_text(json.dumps(s,ensure_ascii=False,indent=2));a.state.chmod(0o600)
finally:
 api('DELETE',f"/v1/users/{s['service']['id']}/tokens/{token['id']}")
 if a.engine=='postgresql':assign(original)
 admin.close()
