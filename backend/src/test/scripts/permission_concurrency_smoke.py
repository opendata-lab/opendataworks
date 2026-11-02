"""Two kept, distinct-source fixtures: concurrent local platform grant saves.

Requires the explicit private backend --platform-env and designated local test DB.
A BEFORE INSERT trigger delays only the two newly created test roles, ensuring
platform transactions overlap. No database global settings or native SQL retries.
Both roles are appended to fixture states for the standard cleanup scripts.
"""
import argparse,json,re
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor
import httpx,pymysql
p=argparse.ArgumentParser()
for key in ['credentials','state-a','state-b','platform-env']:p.add_argument('--'+key,type=Path,required=True)
a=p.parse_args();cfg=json.loads(a.credentials.read_text());states=[json.loads(a.state_a.read_text()),json.loads(a.state_b.read_text())];env=json.loads(a.platform_env.read_text());match=re.fullmatch(r'jdbc:mysql://([^:/]+):(\d+)/([^?]+)(?:\?.*)?',env['SPRING_DATASOURCE_URL']);assert match and match[3]=='odw_split_local_20261029';assert states[0]['source']!=states[1]['source']
client=httpx.Client(base_url=cfg['portal_url'],timeout=60)
def api(method,path,body=None):
 r=client.request(method,path,json=body) if body is not None else client.request(method,path);j=r.json();assert r.status_code==200 and j.get('code')==200,(method,path,r.status_code,j.get('message'));return j.get('data')
api('POST','/auth/login',{'username':cfg['admin_username'],'password':cfg['admin_password']})
def persist(i):
 path=[a.state_a,a.state_b][i];path.write_text(json.dumps(states[i],ensure_ascii=False,indent=2));path.chmod(0o600)
# Reconcile the genuine pre-fix PARTIAL outcome explicitly, without replaying its old preview.
for i,s in enumerate(states):
 base=f"/v1/doris-clusters/{s['source']}/permissions"
 for role in api('GET',base)['roles']:
  if role['status']=='ERROR':
   preview=api('POST',base+f"/roles/{role['id']}/preview",{'expectedRevision':role['revision'],'grants':role['grants']})
   op=api('POST',base+f"/roles/{role['id']}/apply",{'previewId':preview['previewId']});assert op['state']=='SUCCESS',op['message']
   print('PASS explicit recovery of original concurrent save failure',flush=True)
work=[]
for i,s in enumerate(states):
 base=f"/v1/doris-clusters/{s['source']}/permissions";r=api('POST',base+'/roles',{'name':'并发回归_'+s['suffix'],'template':'READONLY'});s['roles'].append(r);persist(i)
 preview=api('POST',base+f"/roles/{r['id']}/preview",{'expectedRevision':0,'grants':[{'resourceType':'TABLE','database':s['a'],'tableName':'orders','actions':['QUERY']}]});work.append((base,r,preview))
platform=pymysql.connect(host=match[1],port=int(match[2]),database=match[3],user=cfg['mysql_user'],password=cfg['mysql_password'],autocommit=True)
trigger='odw_permission_concurrent_'+states[0]['suffix']
def execute(sql):
 with platform.cursor() as c:c.execute(sql)
def apply(item):
 base,role,preview=item;return api('POST',base+f"/roles/{role['id']}/apply",{'previewId':preview['previewId']})
try:
 ids=','.join(str(item[1]['id']) for item in work)
 execute('CREATE TRIGGER `'+trigger+'` BEFORE INSERT ON datasource_access_grant FOR EACH ROW BEGIN IF NEW.role_id IN ('+ids+') THEN DO SLEEP(0.5); END IF; END')
 with ThreadPoolExecutor(max_workers=2) as pool:results=list(pool.map(apply,work))
 assert all(r['state']=='SUCCESS' for r in results),[(r['state'],r['message']) for r in results]
 for i,((base,role,preview),op) in enumerate(zip(work,results)):
  saved=next(r for r in api('GET',base)['roles'] if r['id']==role['id']);assert saved['revision']==1 and saved['status']=='ACTIVE' and saved['grants'][0]['actions']==['QUERY']
  # No native SQL remains to execute on a fresh preview: live ACL equals platform intent.
  verify=api('POST',base+f"/roles/{role['id']}/preview",{'expectedRevision':1,'grants':saved['grants']});assert verify['sql']==[]
  states[i]['concurrency_verified']=True;persist(i)
 print('PASS concurrent cross-source commits under forced overlap and native readback',flush=True)
finally:
 execute('DROP TRIGGER IF EXISTS `'+trigger+'`');platform.close();client.close()
