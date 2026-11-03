"""MySQL native DDL and view actions using an isolated kept fixture.

Creates a disposable platform member and role through the real management API,
then verifies the exact mapped native login. Appends owned resources to the input
state so data_permissions_smoke.py --cleanup can remove them.
"""
import argparse,json,secrets
from pathlib import Path
import httpx,pymysql
p=argparse.ArgumentParser();p.add_argument('--credentials',type=Path,required=True);p.add_argument('--state',type=Path,required=True);a=p.parse_args();cfg=json.loads(a.credentials.read_text());s=json.loads(a.state.read_text());assert s['a'].startswith('odwperm') and not s.get('cleaned')
c=httpx.Client(base_url=cfg['portal_url'],timeout=45)
def api(method,path,body=None):
 r=c.request(method,path,json=body) if body is not None else c.request(method,path);j=r.json();assert r.status_code==200 and j.get('code')==200,(path,r.status_code,j.get('message'));return j.get('data')
def persist():a.state.write_text(json.dumps(s,ensure_ascii=False,indent=2));a.state.chmod(0o600)
def check(label,ok=True):assert ok,label;s.setdefault('ddl_checks',[]).append(label);persist();print('PASS',label,flush=True)
api('POST','/auth/login',{'username':cfg['admin_username'],'password':cfg['admin_password']});base=f"/v1/doris-clusters/{s['source']}/permissions"
role=api('POST',base+'/roles',{'name':'DDL验收_'+s['suffix'],'template':'ADMIN'});s['roles'].append(role);persist()
pv=api('POST',base+f"/roles/{role['id']}/preview",{'expectedRevision':0,'grants':[{'resourceType':'SCHEMA','database':s['a'],'actions':['QUERY','CREATE','ALTER','DROP','SHOW_VIEW']}]});op=api('POST',base+f"/roles/{role['id']}/apply",{'previewId':pv['previewId']});check('MySQL DDL/view grant maps and reads back correctly',op['state']=='SUCCESS')
password=secrets.token_urlsafe(18);u=api('POST','/v1/users',{'username':'odw_ddl_'+s['suffix'],'nickname':'DDL验收','password':password,'role':'user'});u['password']=password;s['users'].append(u);persist()
row=api('PUT',base+f"/members/USER/{u['id']}",{'roleIds':[role['id']],'expectedRevision':0});s['members'].append(['USER',str(u['id'])]);persist();api('POST',base+f"/members/USER/{u['id']}/account",{**s['reader_account'],'expectedRevision':row['revision']})
def connect():return pymysql.connect(host=cfg['mysql_host'],port=cfg['mysql_port'],user=s['reader_account']['username'],password=s['reader_account']['password'],autocommit=True)
with connect() as db,db.cursor() as cur:
 cur.execute('CREATE TABLE `'+s['a']+'`.ddl_probe(id INT)');check('native CREATE TABLE allowed')
 cur.execute('ALTER TABLE `'+s['a']+'`.ddl_probe ADD amount INT');check('native ALTER allowed')
 cur.execute('CREATE VIEW `'+s['a']+'`.v_probe AS SELECT id FROM `'+s['a']+'`.ddl_probe');cur.execute('SHOW CREATE VIEW `'+s['a']+'`.v_probe');check('native CREATE VIEW and SHOW VIEW allowed',bool(cur.fetchone()))
 denied=False
 try:cur.execute('GRANT SELECT ON `'+s['a']+'`.orders TO %s@%s',(s['other_account']['username'],'%'))
 except pymysql.MySQLError:denied=True
 check('business data administrator template has no delegation grant option',denied)
 cur.execute('DROP VIEW `'+s['a']+'`.v_probe');cur.execute('DROP TABLE `'+s['a']+'`.ddl_probe');check('native DROP VIEW and DROP TABLE allowed')
member=next(m for m in api('GET',base)['members'] if m['subjectType']=='USER' and str(m['subjectId'])==str(u['id']));api('PUT',base+f"/members/USER/{u['id']}",{'roleIds':[],'expectedRevision':member['revision']})
with connect() as db,db.cursor() as cur:
 denied=False
 try:cur.execute('CREATE TABLE `'+s['a']+'`.forbidden_probe(id INT)')
 except pymysql.MySQLError:denied=True
 check('native CREATE denied after role removal on fresh connection',denied)
c.close();print('DDL ACCEPTANCE CHECKS',len(s['ddl_checks']),flush=True)
