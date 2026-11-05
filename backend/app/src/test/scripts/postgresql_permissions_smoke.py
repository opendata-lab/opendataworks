"""PostgreSQL16 native role acceptance against an externally prepared disposable database.
JSON credentials: portal_url/admin_username/admin_password and postgres:{host,port,dbname,user,password}.
No runtime startup/restart. --keep supports subsequent browser/MCP tests, --cleanup removes recorded fixtures.
"""
import argparse,json,secrets
from pathlib import Path
import httpx,psycopg
from psycopg import sql as pg
p=argparse.ArgumentParser();p.add_argument('--credentials',type=Path,required=True);p.add_argument('--state',type=Path,required=True);p.add_argument('--keep',action='store_true');p.add_argument('--cleanup',action='store_true');args=p.parse_args()
cfg=json.loads(args.credentials.read_text());s=json.loads(args.state.read_text()) if args.cleanup else {'roles':[],'users':[],'members':[],'schemas':[],'native_users':[],'checks':[]}
def persist():args.state.write_text(json.dumps(s,ensure_ascii=False,indent=2));args.state.chmod(0o600)
def check(name,ok):assert ok,name;s['checks'].append(name);persist();print('PASS',name,flush=True)
a=httpx.Client(base_url=cfg['portal_url'],timeout=60)
def api(c,method,path,body=None,ok=True):
 r=c.request(method,path,json=body) if body is not None else c.request(method,path);j=r.json()
 if ok:assert r.status_code==200 and j.get('code')==200,(path,r.status_code,j.get('message'))
 return j.get('data') if ok else (r.status_code,j)
api(a,'POST','/auth/login',{'username':cfg['admin_username'],'password':cfg['admin_password']})
db=psycopg.connect(**cfg['postgres'],autocommit=True)
def execute(stmt,params=None):return db.execute(stmt,params)
def base():return f"/v1/doris-clusters/{s['source']}/permissions"
def overview():return api(a,'GET',base())
def member(t,id):return next(m for m in overview()['members'] if m['subjectType']==t and str(m['subjectId'])==str(id))
def assign(t,id,roles):
 old=next((m for m in overview()['members'] if m['subjectType']==t and str(m['subjectId'])==str(id)),None)
 row=api(a,'PUT',base()+f'/members/{t}/{id}',{'expectedRevision':old['revision'] if old else 0,'roleIds':[r['id'] for r in roles]})
 if [t,str(id)] not in s['members']:s['members'].append([t,str(id)]);persist()
 return row
def bind(t,id,account):return api(a,'POST',base()+f'/members/{t}/{id}/account',{**account,'expectedRevision':member(t,id)['revision']})
def grant(role,grants):
 r=next(x for x in overview()['roles'] if x['id']==role['id']);preview=api(a,'POST',base()+f"/roles/{role['id']}/preview",{'expectedRevision':r['revision'],'grants':grants})
 op=api(a,'POST',base()+f"/roles/{role['id']}/apply",{'previewId':preview['previewId']});check('native role verified '+role['name'],op['state']=='SUCCESS');return preview
def query(client,namespace,statement,allowed=True):
 status,j=api(client,'POST','/v1/data-query/execute',{'clusterId':s['source'],'database':namespace,'sql':statement,'limit':20},ok=False);d=j.get('data') or {};rs=d.get('resultSets') or [];good=status==200 and j.get('code')==200 and rs and all(r['status']=='SUCCESS' for r in rs);assert bool(good)==allowed,(statement,status,j.get('message'),[(r.get('status'),r.get('message')) for r in rs]);return d

def cleanup():
 if s.get('source') and not s.get('source_removed'):
  for t,id in reversed(s['members']):api(a,'DELETE',base()+f'/members/{t}/{id}')
  s['members']=[];persist()
  for r in reversed(s['roles']):
   op=api(a,'DELETE',base()+f"/roles/{r['id']}");assert op['state']=='SUCCESS',op
  s['roles']=[];persist();fixture=api(a,'GET',f"/v1/doris-clusters/{s['source']}");fixture['feHost']='removed-pg-'+s['suffix']+'.invalid';api(a,'PUT',f"/v1/doris-clusters/{s['source']}",fixture);api(a,'DELETE',f"/v1/doris-clusters/{s['source']}");s['source_removed']=True;persist()
 for user in s['users']:api(a,'DELETE',f"/v1/users/{user['id']}")
 s['users']=[];persist()
 for name in s['schemas']:execute(pg.SQL('DROP SCHEMA IF EXISTS {} CASCADE').format(pg.Identifier(name)))
 for name in s['native_users']:execute(pg.SQL('DROP ROLE IF EXISTS {}').format(pg.Identifier(name)))
 s['cleaned']=True;persist();print('CLEANUP complete',flush=True)
try:
 if args.cleanup:cleanup();raise SystemExit(0)
 suffix=secrets.token_hex(3);s['suffix']=suffix
 for key in ['a','b','denied']:
  schema='odw_pg_'+key+'_'+suffix;s[key]=schema;s['schemas'].append(schema);persist();execute(pg.SQL('CREATE SCHEMA {}').format(pg.Identifier(schema)));execute(pg.SQL('CREATE TABLE {}.orders(id SERIAL PRIMARY KEY,amount NUMERIC(12,2))').format(pg.Identifier(schema)));execute(pg.SQL('INSERT INTO {}.orders(amount) VALUES(10),(20)').format(pg.Identifier(schema)))
 execute(pg.SQL('CREATE VIEW {}.v_orders AS SELECT id,amount FROM {}.orders').format(pg.Identifier(s['a']),pg.Identifier(s['a'])))
 for who in ['reader','view','agent']:
  account={'username':'odwpg_'+who+'_'+suffix,'password':secrets.token_urlsafe(24)};s[who+'_account']=account;s['native_users'].append(account['username']);persist();execute(pg.SQL('CREATE ROLE {} LOGIN NOINHERIT PASSWORD {}').format(pg.Identifier(account['username']),pg.Literal(account['password'])))
 source=api(a,'POST','/v1/doris-clusters',{'clusterName':'PG权限验收_'+suffix,'sourceType':'POSTGRESQL','feHost':cfg['postgres']['host'],'fePort':cfg['postgres']['port'],'databaseName':cfg['postgres']['dbname'],'username':cfg['postgres']['user'],'password':cfg['postgres']['password'],'isDefault':0,'autoSync':0,'status':'active'});s['source']=source['id'];persist()
 for label,template in [('A当前对象只读','READONLY'),('B表只读','READONLY'),('A序列写入','READWRITE'),('仅视图','READONLY')]:
  r=api(a,'POST',base()+'/roles',{'name':label+'_'+suffix,'template':template});s['roles'].append(r);persist()
 ra,rb,rw,rv=s['roles'];ga=[{'resourceType':'SCHEMA','database':s['a'],'actions':['QUERY']}];gb=[{'resourceType':'TABLE','database':s['b'],'tableName':'orders','actions':['QUERY']}];gw=[{'resourceType':'TABLE','database':s['a'],'tableName':'orders','actions':['WRITE']}];gv=[{'resourceType':'VIEW','database':s['a'],'tableName':'v_orders','actions':['QUERY']}]
 preview=grant(ra,ga);check('PG schema preview explains current objects',any('未来' in w for w in preview['warnings']));grant(rb,gb);grant(rw,gw);grant(rv,gv)
 check('PG ALTER remains ownership-controlled',api(a,'POST',base()+f"/roles/{rw['id']}/preview",{'expectedRevision':1,'grants':[{'resourceType':'TABLE','database':s['a'],'tableName':'orders','actions':['ALTER']}]},ok=False)[0]==400)
 for i in [1,2]:
  password=secrets.token_urlsafe(18);u=api(a,'POST','/v1/users',{'username':'odw_pg_user'+str(i)+'_'+suffix,'nickname':'PG权限验收'+str(i),'password':password,'role':'user'});u['password']=password;s['users'].append(u);persist()
 first,view_user=s['users'];assign('USER',first['id'],[ra,rb]);bind('USER',first['id'],s['reader_account']);client=httpx.Client(base_url=cfg['portal_url'],timeout=60);api(client,'POST','/auth/login',{'username':first['username'],'password':first['password']})
 data=query(client,s['a'],f'SELECT COUNT(*) AS n FROM "{s["a"]}".orders a JOIN "{s["b"]}".orders b ON a.id=b.id');check('PG one connection inherits multiple roles across schemas',data['rows']==[{'n':2}]);query(client,s['denied'],'SELECT * FROM orders',False);check('PG unauthorized schema denied',True)
 execute(pg.SQL('CREATE TABLE {}.future_table(id INT)').format(pg.Identifier(s['a'])));query(client,s['a'],'SELECT * FROM future_table',False);check('PG current-object schema scope does not implicitly authorize future objects',True)
 assign('USER',first['id'],[ra,rb,rw]);creds={**cfg['postgres'],'user':s['reader_account']['username'],'password':s['reader_account']['password']}
 with psycopg.connect(**creds,autocommit=True) as userdb:
  row=userdb.execute(pg.SQL('INSERT INTO {}.orders(amount) VALUES(50) RETURNING id').format(pg.Identifier(s['a']))).fetchone();check('PG SERIAL insert includes required sequence USAGE',row[0]==3);userdb.execute(pg.SQL('UPDATE {}.orders SET amount=51 WHERE id=3').format(pg.Identifier(s['a'])));userdb.execute(pg.SQL('DELETE FROM {}.orders WHERE id=3').format(pg.Identifier(s['a'])))
 assign('USER',first['id'],[ra,rb]);check('PG remove write role preserves read roles',query(client,s['a'],'SELECT COUNT(*) AS n FROM orders')['rows']==[{'n':2}])
 create_role=api(a,'POST',base()+'/roles',{'name':'Schema创建_'+suffix,'template':'ADMIN'});s['roles'].append(create_role);persist();grant(create_role,[{'resourceType':'SCHEMA','database':s['a'],'actions':['CREATE']}]);assign('USER',first['id'],[ra,rb,create_role])
 with psycopg.connect(**creds,autocommit=True) as userdb:
  userdb.execute(pg.SQL('CREATE TABLE {}.owned_probe(id INT)').format(pg.Identifier(s['a'])))
 assign('USER',first['id'],[ra,rb])
 with psycopg.connect(**creds,autocommit=True) as userdb:
  denied=False
  try:userdb.execute(pg.SQL('CREATE TABLE {}.forbidden_probe(id INT)').format(pg.Identifier(s['a'])))
  except psycopg.errors.InsufficientPrivilege:denied=True
  check('PG CREATE role revoke blocks creating new objects',denied)
  userdb.execute(pg.SQL('ALTER TABLE {}.owned_probe ADD amount INT').format(pg.Identifier(s['a'])));userdb.execute(pg.SQL('DROP TABLE {}.owned_probe').format(pg.Identifier(s['a'])))
 check('PG creator retains ownership after CREATE grant revoke, no ownership transfer implied',True)
 assign('USER',view_user['id'],[rv]);bind('USER',view_user['id'],s['view_account']);vc=httpx.Client(base_url=cfg['portal_url'],timeout=60);api(vc,'POST','/auth/login',{'username':view_user['username'],'password':view_user['password']});query(vc,s['a'],'SELECT COUNT(*) AS n FROM v_orders');query(vc,s['a'],'SELECT COUNT(*) AS n FROM orders',False);check('PG view-only user can query view but not base table',True)
 service=api(a,'POST','/v1/users',{'accountType':'SERVICE','nickname':'PG验收服务_'+suffix,'enabled':True});s['service']=service;s['users'].append(service);persist()
 assign('SERVICE',service['id'],[ra,rb]);bad=api(a,'POST',base()+f"/members/SERVICE/{service['id']}/account",{'username':cfg['postgres']['user'],'password':cfg['postgres']['password'],'expectedRevision':member('SERVICE',service['id'])['revision']},ok=False);check('PG privileged agent account rejected',bad[0]==400)
 bind('SERVICE',service['id'],s['agent_account']);check('PG read-only agent account binding succeeds',True)
 grant(rb,[]);query(client,s['b'],'SELECT * FROM orders',False);query(client,s['a'],'SELECT * FROM orders');check('PG revoke one role resource preserves other namespace',True);grant(rb,gb)
 for schema in [s['a'],s['b']]:api(a,'POST',f"/v1/tables/sync-metadata/database/{schema}?clusterId={s['source']}")
 s['accepted']=True;persist();print('ACCEPTANCE CHECKS',len(s['checks']),flush=True)
 if not args.keep:cleanup()
except BaseException as error:
 persist()
 if not isinstance(error,SystemExit) or error.code:print('State retained:',str(args.state),flush=True)
 raise
finally:a.close();db.close()
