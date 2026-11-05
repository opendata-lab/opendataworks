"""Extend kept MySQL fixtures with immediate-dual-write and expired-password tests.

Run after data_permissions_smoke.py --keep. Inputs and state are private JSON files.
--platform-env is the exact local backend environment, needed only to inject a
role-specific failing trigger in a disposable local platform database. No runtime
is started or restarted. The trigger and limited manager are removed in finally.
"""
import argparse
import json
import re
import secrets
from pathlib import Path
import httpx
import pymysql

parser=argparse.ArgumentParser()
for name in ['credentials','state','platform-env']:parser.add_argument('--'+name,type=Path,required=True)
a=parser.parse_args();cfg=json.loads(a.credentials.read_text());s=json.loads(a.state.read_text());env=json.loads(a.platform_env.read_text())
assert s.get('accepted') and not s.get('cleaned'),'requires kept accepted fixtures'
match=re.fullmatch(r'jdbc:mysql://([^:/]+):(\d+)/([^?]+)(?:\?.*)?',env['SPRING_DATASOURCE_URL'])
assert match and match[3]=='odw_split_local_20261029','fault injection restricted to designated local test database'
admin=httpx.Client(base_url=cfg['portal_url'],timeout=60)
def api(method,path,body=None,ok=True):
 r=admin.request(method,path,json=body) if body is not None else admin.request(method,path);j=r.json()
 if ok:assert r.status_code==200 and j.get('code')==200,(method,path,r.status_code,j.get('message'))
 return j.get('data') if ok else (r.status_code,j)
api('POST','/auth/login',{'username':cfg['admin_username'],'password':cfg['admin_password']})
dba=pymysql.connect(host=cfg['mysql_host'],port=cfg['mysql_port'],user=cfg['mysql_user'],password=cfg['mysql_password'],autocommit=True,cursorclass=pymysql.cursors.DictCursor)
platform=pymysql.connect(host=match[1],port=int(match[2]),database=match[3],user=cfg['mysql_user'],password=cfg['mysql_password'],autocommit=True,cursorclass=pymysql.cursors.DictCursor)
def execute(c,sql,params=None):
 with c.cursor() as cur:cur.execute(sql,params);return cur.fetchall() if cur.description else cur.rowcount
def persist():a.state.write_text(json.dumps(s,ensure_ascii=False,indent=2));a.state.chmod(0o600)
def check(label,condition):
 assert condition,label
 s.setdefault('failure_checks',[]).append(label);persist();print('PASS',label,flush=True)
prefix=f"/v1/doris-clusters/{s['source']}/permissions"
def overview():return api('GET',prefix)
def role_now(role):return next(r for r in overview()['roles'] if r['id']==role['id'])
def preview(role,grants):return api('POST',prefix+f"/roles/{role['id']}/preview",{'expectedRevision':role_now(role)['revision'],'grants':grants})
def apply(role,p):return api('POST',prefix+f"/roles/{role['id']}/apply",{'previewId':p['previewId']})
def member(kind,subject):return next(m for m in overview()['members'] if m['subjectType']==kind and str(m['subjectId'])==str(subject))
def bind(kind,subject,account):return api('POST',prefix+f'/members/{kind}/{subject}/account',{**account,'expectedRevision':member(kind,subject)['revision']})
def assign(user,roles):return api('PUT',prefix+f"/members/USER/{user['id']}",{'roleIds':[r['id'] for r in roles],'expectedRevision':member('USER',user['id'])['revision']})
def query(user,database,allowed):
 with httpx.Client(base_url=cfg['portal_url'],timeout=30) as c:
  r=c.post('/auth/login',json={'username':user['username'],'password':user['password']});assert r.json()['code']==200
  j=c.post('/v1/data-query/execute',json={'clusterId':s['source'],'database':database,'sql':'SELECT COUNT(*) AS n FROM orders','limit':5}).json();result=(j.get('data') or {}).get('resultSets') or []
  assert bool(j.get('code')==200 and result and all(r['status']=='SUCCESS' for r in result))==allowed,(database,j.get('message'))
limited='odwp_limited_'+s['suffix'];password=secrets.token_urlsafe(24);trigger='odw_permission_fault_'+s['suffix']
try:
 # Rebind pre-V56 fixtures to persist verified user@host, and enforce Agent capability checks.
 for kind,subject,account in [('USER',s['users'][0]['id'],s['reader_account']),('USER',s['users'][1]['id'],s['other_account']),('SERVICE',s['service']['id'],s['agent_account'])]:bind(kind,subject,account)
 check('existing members and readonly Agent pass verified-identity binding',True)
 role=api('POST',prefix+'/roles',{'name':'故障恢复_'+s['suffix'],'template':'READONLY'});s['roles'].append(role);persist()
 grants=[{'resourceType':'TABLE','database':s['a'],'tableName':'orders','actions':['QUERY']}]
 p=preview(role,grants)
 execute(dba,'CREATE USER %s@%s IDENTIFIED BY %s',(limited,'%',password));execute(dba,'GRANT CREATE ROLE ON *.* TO %s@%s',(limited,'%'))
 for table in ['user','db','role_edges','default_roles','tables_priv','columns_priv']:execute(dba,'GRANT SELECT ON mysql.'+table+' TO %s@%s',(limited,'%'))
 api('PUT',prefix+'/management',{'username':limited,'password':password})
 op=apply(role,p);check('CREATE ROLE succeeds but business GRANT denied reports PARTIAL',op['state']=='PARTIAL' and op['steps'][0]['state']=='SUCCESS' and op['steps'][-1]['state']=='FAILED')
 check('retry of partial preview never replays SQL',apply(role,p)['state']=='PARTIAL')
 query(s['users'][1],s['a'],True);check('unrelated role remains usable after native grant failure',True)
 api('PUT',prefix+'/management',{'username':cfg['mysql_user'],'password':cfg['mysql_password']})
 op=apply(role,preview(role,grants));check('explicit fresh preview reconciles partial native role',op['state']=='SUCCESS')
 assign(s['users'][0],s['roles'][:2]+[role])
 target=[{'resourceType':'TABLE','database':s['b'],'tableName':'orders','actions':['QUERY']}];p=preview(role,target)
 execute(platform,'CREATE TRIGGER `'+trigger+'` BEFORE UPDATE ON datasource_access_role FOR EACH ROW BEGIN IF NEW.id='+str(role['id'])+" AND NEW.status='ACTIVE' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='isolated permission smoke injected save failure'; END IF; END")
 try:op=apply(role,p)
 finally:execute(platform,'DROP TRIGGER IF EXISTS `'+trigger+'`')
 check('native success and platform commit failure reports PARTIAL',op['state']=='PARTIAL' and all(step['state']=='SUCCESS' for step in op['steps']))
 query(s['users'][0],s['a'],False);query(s['users'][1],s['a'],True);check('only member with inconsistent role is protected',True)
 op=apply(role,preview(role,target));check('fresh preview commits observed native state and releases member',op['state']=='SUCCESS');query(s['users'][0],s['a'],True)
 assign(s['users'][0],s['roles'][:2])
 # DBA password rotation must not prevent native membership revocation.
 old=s['other_account']['password'];execute(dba,'ALTER USER %s@%s IDENTIFIED BY %s',(s['other_account']['username'],'%',secrets.token_urlsafe(24)))
 try:
  api('DELETE',prefix+f"/members/USER/{s['users'][1]['id']}")
  remaining=execute(dba,'SELECT FROM_USER FROM mysql.role_edges WHERE TO_USER=%s',(s['other_account']['username'],))
  check('member removal revokes native roles even when stored password has expired',not remaining)
 finally:execute(dba,'ALTER USER %s@%s IDENTIFIED BY %s',(s['other_account']['username'],'%',old))
 api('PUT',prefix+f"/members/USER/{s['users'][1]['id']}",{'roleIds':[s['roles'][0]['id']],'expectedRevision':0});bind('USER',s['users'][1]['id'],s['other_account']);query(s['users'][1],s['a'],True)
 check('removed member can be explicitly rebound',True)
 print('FAILURE ACCEPTANCE CHECKS',len(s['failure_checks']),flush=True)
finally:
 execute(platform,'DROP TRIGGER IF EXISTS `'+trigger+'`')
 try:api('PUT',prefix+'/management',{'username':cfg['mysql_user'],'password':cfg['mysql_password']})
 finally:execute(dba,'DROP USER IF EXISTS %s@%s',(limited,'%'));admin.close();dba.close();platform.close()
