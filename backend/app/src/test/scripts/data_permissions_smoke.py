"""Real MySQL/HTTP acceptance. Prepare runtime externally; never starts/restarts services.

Run with --credentials /path/to/private.json --state /path/to/private-state.json.
The credential JSON supplies portal_url, admin_username, admin_password, mysql_host,
mysql_port, mysql_user, mysql_password, source_host, source_port. Use an isolated
source endpoint and disposable native schemas/accounts. --keep preserves resources
for browser/model verification; --cleanup removes only IDs/names recorded in state.
"""
import argparse
import json
import secrets
from pathlib import Path
import httpx
import pymysql

parser=argparse.ArgumentParser();parser.add_argument('--credentials',type=Path,required=True);parser.add_argument('--state',type=Path,required=True);parser.add_argument('--keep',action='store_true');parser.add_argument('--cleanup',action='store_true');args=parser.parse_args()
config=json.loads(args.credentials.read_text());state=json.loads(args.state.read_text()) if args.cleanup else {'roles':[],'users':[],'members':[],'schemas':[],'native_users':[],'checks':[]}
def persist():
 args.state.write_text(json.dumps(state,ensure_ascii=False,indent=2));args.state.chmod(0o600)
def check(label,condition):
 assert condition,label
 state['checks'].append(label);persist();print('PASS',label,flush=True)
def request(client,method,url,body=None,success=True):
 r=client.request(method,url,json=body) if body is not None else client.request(method,url)
 data=r.json()
 if success:assert r.status_code==200 and data.get('code')==200,(method,url,r.status_code,data.get('message'))
 return data.get('data') if success else (r.status_code,data)
admin=httpx.Client(base_url=config['portal_url'],timeout=60)
request(admin,'POST','/auth/login',{'username':config['admin_username'],'password':config['admin_password']})
dba=pymysql.connect(host=config['mysql_host'],port=config['mysql_port'],user=config['mysql_user'],password=config['mysql_password'],autocommit=True,cursorclass=pymysql.cursors.DictCursor)
def native(sql,params=None):
 with dba.cursor() as c:c.execute(sql,params);return c.fetchall() if c.description else c.rowcount
def prefix():return f"/v1/doris-clusters/{state['source']}/permissions"
def cleanup():
 if state.get('source') and not state.get('source_removed'):
  for kind,subject in reversed(state['members']):request(admin,'DELETE',prefix()+f'/members/{kind}/{subject}')
  state['members']=[];persist()
  for role in reversed(state['roles']):
   op=request(admin,'DELETE',prefix()+f"/roles/{role['id']}");assert op['state']=='SUCCESS',op
  state['roles']=[];persist()
  fixture=request(admin,'GET',f"/v1/doris-clusters/{state['source']}");fixture['feHost']='removed-'+state['suffix']+'.invalid';request(admin,'PUT',f"/v1/doris-clusters/{state['source']}",fixture)
  request(admin,'DELETE',f"/v1/doris-clusters/{state['source']}")
  state['source_removed']=True;persist()
 for user in state['users']:request(admin,'DELETE',f"/v1/users/{user['id']}")
 state['users']=[];persist()
 for name in state['native_users']:native('DROP USER IF EXISTS %s@%s',(name,'%'))
 for db in state['schemas']:native('DROP DATABASE IF EXISTS `'+db+'`')
 state['cleaned']=True;persist();print('CLEANUP complete',flush=True)
def refresh():return request(admin,'GET',prefix())
def member(kind,subject):return next(m for m in refresh()['members'] if m['subjectType']==kind and str(m['subjectId'])==str(subject))
def assign(kind,subject,roles):
 old=next((m for m in refresh()['members'] if m['subjectType']==kind and str(m['subjectId'])==str(subject)),None)
 row=request(admin,'PUT',prefix()+f'/members/{kind}/{subject}',{'roleIds':[r['id'] for r in roles],'expectedRevision':old['revision'] if old else 0})
 if [kind,str(subject)] not in state['members']:state['members'].append([kind,str(subject)]);persist()
 return row
def bind(kind,subject,username,password):
 old=member(kind,subject)
 return request(admin,'POST',prefix()+f'/members/{kind}/{subject}/account',{'username':username,'password':password,'expectedRevision':old['revision']})
def grant(role,grants):
 current=next(r for r in refresh()['roles'] if r['id']==role['id'])
 preview=request(admin,'POST',prefix()+f"/roles/{role['id']}/preview",{'expectedRevision':current['revision'],'grants':grants})
 if current['status']=='DRAFT':check('preview does not create native role '+role['name'],not native('SELECT User FROM mysql.user WHERE User=%s',(role['nativeName'],)))
 op=request(admin,'POST',prefix()+f"/roles/{role['id']}/apply",{'previewId':preview['previewId']});check('native grant verified '+role['name'],op['state']=='SUCCESS')
 repeated=request(admin,'POST',prefix()+f"/roles/{role['id']}/apply",{'previewId':preview['previewId']});check('apply retry returns same completed operation',repeated['operationId']==op['operationId'] and repeated['state']=='SUCCESS')
 return preview

def sql(client,database,statement,allowed=True):
 j=request(client,'POST','/v1/data-query/execute',{'clusterId':state['source'],'database':database,'sql':statement,'limit':20},success=False)
 status,body=j;data=body.get('data') or {};results=data.get('resultSets') or []
 ok=status==200 and body.get('code')==200 and results and all(r['status']=='SUCCESS' for r in results)
 assert bool(ok)==allowed,(statement,status,body.get('message'),[(r.get('status'),r.get('message')) for r in results])
 return data

try:
 if args.cleanup:cleanup();raise SystemExit(0)
 suffix=secrets.token_hex(3);state['suffix']=suffix
 for letter in ['a','b','denied','pattern']:
  db=('odwperm_a_'+suffix) if letter=='pattern' else ('odwperm'+letter+suffix);state[letter]=db;state['schemas'].append(db);persist();native('CREATE DATABASE `'+db+'`');native('CREATE TABLE `'+db+'`.orders(id INT PRIMARY KEY, amount DECIMAL(12,2))');native('INSERT INTO `'+db+'`.orders VALUES (1,10),(2,20)')
 near=state['pattern'].replace('_','X');state['near']=near;state['schemas'].append(near);persist();native('CREATE DATABASE `'+near+'`');native('CREATE TABLE `'+near+'`.orders(id INT PRIMARY KEY)')
 for who in ['reader','other','agent']:
  name='odwp_'+who+'_'+suffix;password=secrets.token_urlsafe(24);state[who+'_account']={'username':name,'password':password};state['native_users'].append(name);persist();native('CREATE USER %s@%s IDENTIFIED BY %s',(name,'%',password))
 source=request(admin,'POST','/v1/doris-clusters',{'clusterName':'权限验收_'+suffix,'sourceType':'MYSQL','feHost':config['source_host'],'fePort':config['source_port'],'username':config['mysql_user'],'password':config['mysql_password'],'isDefault':0,'autoSync':0,'status':'active'});state['source']=source['id'];persist()
 check('source overview has role templates as capabilities, no shared role logins',refresh()['roles']==[])
 saved=request(admin,'PUT',prefix()+'/management',{'username':config['mysql_user'],'password':config['mysql_password']});check('management credential never returned',saved.get('configured') and 'password' not in saved)
 resources=request(admin,'GET',prefix()+'/resources?database='+state['a']);check('management resource selection sees actual table',any(o['tableName']=='orders' and o['resourceType']=='TABLE' for o in resources['objects']))
 for label in ['A只读','B只读','A写入']:
  role=request(admin,'POST',prefix()+'/roles',{'name':label+'_'+suffix,'template':'READWRITE' if label=='A写入' else 'READONLY'});state['roles'].append(role);persist()
 ra,rb,rw=state['roles'];ga=[{'resourceType':'SCHEMA','database':state['a'],'actions':['QUERY']}];gb=[{'resourceType':'TABLE','database':state['b'],'tableName':'orders','actions':['QUERY']}];gw=[{'resourceType':'SCHEMA','database':state['a'],'actions':['WRITE']}]
 grant(ra,ga);grant(rb,gb);grant(rw,gw)
 native('CREATE TABLE `'+state['a']+'`.future_table(id INT PRIMARY KEY)')
 r=request(admin,'POST',prefix()+f"/roles/{ra['id']}/preview",{'expectedRevision':0,'grants':ga},success=False);check('stale role revision rejected',r[0]==409)
 for i in [1,2]:
  password=secrets.token_urlsafe(18);user=request(admin,'POST','/v1/users',{'username':'odw_smoke_'+str(i)+'_'+suffix,'nickname':'权限验收用户'+str(i),'password':password,'role':'user','enabled':True});user['password']=password;state['users'].append(user);persist();check('user API does not return password hash','passwordHash' not in user)
 first,other=state['users'];u=httpx.Client(base_url=config['portal_url'],timeout=60);request(u,'POST','/auth/login',{'username':first['username'],'password':first['password']});check('ordinary login carries user role',request(u,'GET','/auth/me')['role']=='user')
 check('ordinary user cannot manage users',request(u,'GET','/v1/users',success=False)[0]==403)
 check('ordinary user cannot manage datasource permissions',request(u,'GET',prefix(),success=False)[0]==403)
 for method,path in [('POST','/v1/doris-clusters'),('PUT',f"/v1/doris-clusters/{state['source']}"),('DELETE',f"/v1/doris-clusters/{state['source']}"),('POST',f"/v1/doris-clusters/{state['source']}/default"),('POST',f"/v1/doris-clusters/{state['source']}/test")]:
  check('ordinary user cannot mutate source '+method+' '+path,request(u,method,path,{},success=False)[0]==403)
 row=assign('USER',first['id'],[ra,rb]);check('member may receive two roles before binding account',row['status']=='NEEDS_ACCOUNT' and len(row['roleIds'])==2)
 sql(u,state['a'],'SELECT COUNT(*) AS n FROM orders',False);check('missing account does not fall back to source manager',True)
 creds=state['reader_account'];row=bind('USER',first['id'],creds['username'],creds['password']);check('member has one account and two active roles',row['status']=='ACTIVE' and len(row['roleIds'])==2)
 d=sql(u,state['a'],f"SELECT COUNT(*) AS n FROM `{state['a']}`.orders a JOIN `{state['b']}`.orders b ON a.id=b.id");check('one connection joins two schemas',d['rows']==[{'n':2}]);sql(u,state['a'],'SELECT COUNT(*) FROM future_table');check('schema role includes objects created after grant',True)
 sql(u,state['denied'],'SELECT * FROM orders',False);sql(u,state['near'],'SELECT * FROM orders',False);check('unauthorized schema and underscore lookalike both denied',True)
 grant(ra,[]);sql(u,state['a'],'SELECT * FROM orders',False);sql(u,state['b'],'SELECT * FROM orders');check('role grant revoke is effective while other role remains',True);grant(ra,ga)
 assign('USER',first['id'],[rb]);sql(u,state['a'],'SELECT * FROM orders',False);sql(u,state['b'],'SELECT * FROM orders');check('removing one membership preserves remaining schema role',True)
 assign('USER',first['id'],[ra,rb,rw]);dbu=pymysql.connect(host=config['mysql_host'],port=config['mysql_port'],user=creds['username'],password=creds['password'],autocommit=True)
 with dbu.cursor() as cur:
  cur.execute('INSERT INTO `'+state['a']+'`.orders VALUES(3,30)');cur.execute('UPDATE `'+state['a']+'`.orders SET amount=31 WHERE id=3');cur.execute('DELETE FROM `'+state['a']+'`.orders WHERE id=3')
 dbu.close();check('WRITE template enforces actual INSERT UPDATE DELETE',True);assign('USER',first['id'],[ra,rb])
 assign('USER',other['id'],[ra]);creds2=state['other_account'];bind('USER',other['id'],creds2['username'],creds2['password'])
 ddl= request(admin,'POST',prefix()+'/roles',{'name':'对象管理_'+suffix,'template':'ADMIN'});state['roles'].append(ddl);persist()
 grant(ddl,[{'resourceType':'SCHEMA','database':state['a'],'actions':['CREATE','ALTER','DROP','SHOW_VIEW']}]);assign('USER',first['id'],[ra,rb,ddl])
 with pymysql.connect(host=config['mysql_host'],port=config['mysql_port'],user=creds['username'],password=creds['password'],autocommit=True) as conn,conn.cursor() as cur:
  cur.execute('CREATE TABLE `'+state['a']+'`.ddl_probe(id INT)');cur.execute('ALTER TABLE `'+state['a']+'`.ddl_probe ADD amount INT');cur.execute('CREATE VIEW `'+state['a']+'`.v_probe AS SELECT id FROM `'+state['a']+'`.ddl_probe');cur.execute('SHOW CREATE VIEW `'+state['a']+'`.v_probe');assert cur.fetchone();cur.execute('DROP VIEW `'+state['a']+'`.v_probe');cur.execute('DROP TABLE `'+state['a']+'`.ddl_probe')
 check('native CREATE ALTER DROP CREATE VIEW and SHOW VIEW work with managed role',True)
 assign('USER',first['id'],[ra,rb])
 with pymysql.connect(host=config['mysql_host'],port=config['mysql_port'],user=creds['username'],password=creds['password'],autocommit=True) as conn,conn.cursor() as cur:
  denied=False
  try:cur.execute('CREATE TABLE `'+state['a']+'`.ddl_forbidden(id INT)')
  except pymysql.MySQLError:denied=True
  check('removing DDL role blocks CREATE on new connection',denied)
 u2=httpx.Client(base_url=config['portal_url'],timeout=60);request(u2,'POST','/auth/login',{'username':other['username'],'password':other['password']});sql(u2,state['a'],'SELECT * FROM orders');sql(u2,state['b'],'SELECT * FROM orders',False);check('second user has different effective table scope',True)
 request(admin,'PATCH',f"/v1/users/{first['id']}",{'enabled':False});check('disable invalidates an existing session',request(u,'GET','/auth/me',success=False)[0]==401)
 request(admin,'PATCH',f"/v1/users/{first['id']}",{'enabled':True});check('reenabling does not revive old session',request(u,'GET','/auth/me',success=False)[0]==401)
 request(u,'POST','/auth/login',{'username':first['username'],'password':first['password']});first['password']=secrets.token_urlsafe(18);request(admin,'POST',f"/v1/users/{first['id']}/reset-password",{'password':first['password']});persist();check('reset password invalidates existing session',request(u,'GET','/auth/me',success=False)[0]==401);request(u,'POST','/auth/login',{'username':first['username'],'password':first['password']})
 request(admin,'PATCH',f"/v1/users/{first['id']}",{'role':'admin'});check('platform role promotion is immediate for an existing session',request(u,'GET','/v1/users') is not None);request(admin,'PATCH',f"/v1/users/{first['id']}",{'role':'user'});check('platform role downgrade immediately removes admin access',request(u,'GET','/v1/users',success=False)[0]==403)
 check('current administrator cannot disable themselves',request(admin,'PATCH','/v1/users/1',{'enabled':False},success=False)[0]==400)
 service=request(admin,'POST','/v1/users',{'accountType':'SERVICE','nickname':'权限验收服务_'+suffix,'enabled':True});state['service']=service;state['users'].append(service);persist()
 assign('SERVICE',service['id'],[rw]);check('service may receive an explicitly granted native write role',True)
 assign('SERVICE',service['id'],[ra,rb]);ac=state['agent_account'];bind('SERVICE',service['id'],ac['username'],ac['password']);check('agent read-only account receives two read roles',True)
 pattern_role=request(admin,'POST',prefix()+'/roles',{'name':'精确表权限_'+suffix,'template':'READONLY'});state['roles'].append(pattern_role);persist()
 bad=request(admin,'POST',prefix()+f"/roles/{pattern_role['id']}/preview",{'expectedRevision':0,'grants':[{'resourceType':'SCHEMA','database':state['pattern'],'actions':['QUERY']}]},success=False);check('affected MySQL schema scope explicitly rejected before grant',bad[0]==400 and 'UNSUPPORTED_ACTION' in str(bad[1]))
 grant(pattern_role,[{'resourceType':'TABLE','database':state['pattern'],'tableName':'orders','actions':['QUERY']}]);assign('USER',first['id'],[ra,rb,pattern_role]);sql(u,state['pattern'],'SELECT COUNT(*) FROM orders');sql(u,state['near'],'SELECT COUNT(*) FROM orders',False);check('underscore table scope works without lookalike schema exposure',True);assign('USER',first['id'],[ra,rb])
 for db in [state['a'],state['b']]:
  out=request(admin,'POST',f"/v1/tables/sync-metadata/database/{db}?clusterId={state['source']}");check('metadata collection '+db,str(out.get('status')).upper() in ['SUCCESS','COMPLETED'])
 state['accepted']=True;persist();print('ACCEPTANCE CHECKS',len(state['checks']),flush=True)
 if not args.keep:cleanup()
except BaseException as error:
 persist()
 if not isinstance(error,SystemExit) or error.code:print('State retained for diagnosis/cleanup:',str(args.state),flush=True)
 raise
finally:
 admin.close();dba.close()
