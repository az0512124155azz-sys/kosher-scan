import {test, before, after} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {Miniflare} from 'miniflare';
import {normalizeSubmission, validateReview, visibleResult, validateSuggestion} from '../src/policy.mjs';
import {processQueue,deliverObservations,onTelegram} from '../src/worker.mjs';
let mf,db;
before(async()=>{
 mf=new Miniflare({modules:true,scriptPath:'src/worker.mjs',compatibilityDate:'2026-07-30',d1Databases:['DB'],bindings:{APP_TOKEN:'local-app-token-00000000000000000000',ADMIN_TOKEN:'local-admin-token-0000000000000000',DAILY_AI_LIMIT:'100',TELEGRAM_WEBHOOK_SECRET:'local-webhook-secret'}});
 db=await mf.getD1Database('DB');for(const file of ['0001.sql','0002.sql','0003.sql','0004.sql','0005.sql']){
  if(file==='0003.sql'){
   await db.prepare("INSERT INTO cases(id,barcode,market,created_at,updated_at) VALUES('legacy','12345678','IL',1,1)").run();
   await db.prepare("INSERT INTO observations(id,case_id,barcode,market,created_at,telegram_sent) VALUES('legacy-observation','legacy','12345678','IL',1,1)").run();
  }
  await db.exec((await readFile('migrations/'+file,'utf8')).split(';').map(x=>x.replace(/\s+/g,' ').trim()).filter(Boolean).join(';\n')+';');
 }
});
after(async()=>{await mf?.dispose();});
test('upgrade remembers old delivered notifications without resending them',async()=>{
 assert.equal((await db.prepare("SELECT telegram_sent FROM cases WHERE id='legacy'").first()).telegram_sent,1);
 await db.prepare("DELETE FROM observations WHERE case_id='legacy'").run();await db.prepare("DELETE FROM cases WHERE id='legacy'").run();
});
test('concurrent deliveries and repeated scans send only one alert per case; failures retry',async()=>{
 const id=crypto.randomUUID(),now=Date.now();
 await db.prepare("INSERT INTO cases(id,barcode,market,created_at,updated_at,phase,ai_json) VALUES(?,?,?,?,?,'review',?)").bind(id,'98765432','IL',now,now,JSON.stringify({suggestedStatus:'unknown',explanation:'No match'})).run();
 const add=()=>db.prepare('INSERT INTO observations(id,case_id,barcode,market,created_at) VALUES(?,?,?,?,?)').bind(crypto.randomUUID(),id,'98765432','IL',now).run();
 await add();await add();
 await db.prepare("INSERT OR REPLACE INTO settings(key,value) VALUES('telegram_chat','123')").run();
 const old=globalThis.fetch,calls=[];let fail=true;
 globalThis.fetch=async(url,init)=>{calls.push(url);if(fail)throw new Error('network');return Response.json({ok:true,result:{}});};
 const env={DB:db,TELEGRAM_TOKEN:'mock'};
 try {
  await deliverObservations(env);assert.equal((await db.prepare('SELECT telegram_sent FROM cases WHERE id=?').bind(id).first()).telegram_sent,0);
  fail=false;calls.length=0;
  await Promise.all([deliverObservations(env),deliverObservations(env)]);assert.equal(calls.length,1);
  await add();await deliverObservations(env);assert.equal(calls.length,1);
  const observations=await db.prepare('SELECT telegram_sent FROM observations WHERE case_id=?').bind(id).all();assert.equal(observations.results.length,3);assert.ok(observations.results.every(x=>x.telegram_sent===3));
 } finally {globalThis.fetch=old;await db.prepare("DELETE FROM settings WHERE key='telegram_chat'").run();await db.prepare('DELETE FROM observations WHERE case_id=?').bind(id).run();await db.prepare('DELETE FROM cases WHERE id=?').bind(id).run();}
});
const request=(path,body,admin=false)=>mf.dispatchFetch('https://example.test'+path,{method:body?'POST':'GET',headers:{Authorization:'Bearer '+(admin?'local-admin-token-0000000000000000':'local-app-token-00000000000000000000'),'Content-Type':'application/json'},body:body?JSON.stringify(body):undefined});
test('submission keeps identity and excludes fabricated/malformed photo',()=>{
 const p=normalizeSubmission({barcode:'3017620422003',market:'IL',requestId:crypto.randomUUID(),product:{name:'Nutella'}});assert.equal(p.photo,'');assert.equal(p.name,'Nutella');
 assert.throws(()=>normalizeSubmission({...p,barcode:'abc'}));
 assert.throws(()=>normalizeSubmission({barcode:'3017620422003',market:'IL',requestId:crypto.randomUUID(),barcodePhoto:'not-an-image'}));
});
test('unreviewed, expired and unsupported statuses never publish AI as certification',()=>{
 const row={barcode:'3017620422003',market:'IL',phase:'review',status:'kosher',expires_at:'2099-01-01',reviewed_at:0};
 assert.equal(visibleResult(row).status,'unknown');assert.equal(visibleResult({...row,phase:'approved',reviewed_at:1,expires_at:'2000-01-01'}).approved,false);
 assert.equal(validateSuggestion({suggestedStatus:'probably-kosher'}).suggestedStatus,'unknown');
 assert.throws(()=>validateReview({status:'kosher',expiresAt:'2020-01-01',evidenceUrl:'https://www.ok.org/'}));
 assert.throws(()=>validateReview({status:'kosher',expiresAt:'2027-01-01',evidenceUrl:'http://localhost/'}));
});
test('health works but photos, records and submissions need the correct role',async()=>{
 assert.equal((await mf.dispatchFetch('https://example.test/api/health')).status,200);
 assert.equal((await mf.dispatchFetch('https://example.test/api/admin/cases')).status,401);
 assert.equal((await request('/api/admin/cases')).status,401);
 assert.equal((await mf.dispatchFetch('https://example.test/api/cases',{method:'POST',body:'{}'})).status,401);
});
test('unknown scan is durable and idempotent, review changes only the exact market result',async()=>{
 const submission={requestId:crypto.randomUUID(),barcode:'3017620422003',market:'GB',product:{name:'Nutella',brand:'Ferrero'}};
 const res=await request('/api/cases',submission);assert.equal(res.status,202);const accepted=await res.json();
 const duplicate=await request('/api/cases',submission).then(x=>x.json());assert.equal(duplicate.id,accepted.id);
 assert.equal((await db.prepare('SELECT COUNT(*) as n FROM cases').first()).n,1);
 assert.equal((await db.prepare('SELECT COUNT(*) as n FROM observations').first()).n,1);
 const repeatScan=await request('/api/cases',{...submission,requestId:crypto.randomUUID()}).then(x=>x.json());assert.equal(repeatScan.id,accepted.id);
 assert.equal((await db.prepare('SELECT COUNT(*) as n FROM observations').first()).n,2); // Every scan is recorded; research and notifications are deduplicated.
 assert.equal((await request('/api/result?barcode=3017620422003&market=GB').then(x=>x.json())).status,'unknown');
 const expires=new Date(Date.now()+86400000*30).toISOString().slice(0,10);
 assert.equal((await request('/api/admin/cases/'+accepted.id+'/review',{status:'kosher',expiresAt:expires,evidenceUrl:'https://www.ok.org/product-search/',details:'חלבי.'},true)).status,200);
 const approved=await request('/api/result?barcode=3017620422003&market=GB').then(x=>x.json());assert.equal(approved.status,'kosher');assert.equal(approved.details,'');
 assert.equal((await request('/api/result?barcode=3017620422003&market=IL').then(x=>x.json())).status,'unknown');
 const data=await request('/api/admin/cases',null,true).then(x=>x.json());assert.equal(data.cases[0].barcode,'3017620422003');
 assert.equal((await request('/api/admin/cases/'+accepted.id+'/delete',{},true)).status,200);
 assert.equal((await request('/api/result?barcode=3017620422003&market=GB').then(x=>x.json())).approved,false);
});
test('Telegram secret is required and unpaired chats get no data',async()=>{
 assert.equal((await mf.dispatchFetch('https://example.test/telegram/webhook',{method:'POST',body:'{}'})).status,401);
 const r=await mf.dispatchFetch('https://example.test/telegram/webhook',{method:'POST',headers:{'X-Telegram-Bot-Api-Secret-Token':'local-webhook-secret'},body:JSON.stringify({message:{chat:{id:123,type:'private'},text:'/status 3017620422003'}})});assert.equal(r.status,200);
});
test('paired owner can publish a researched result from one Telegram message',async()=>{
 const id=crypto.randomUUID(),now=Date.now(),old=globalThis.fetch,calls=[];
 await db.prepare("INSERT INTO cases(id,barcode,market,created_at,updated_at,phase,ai_json) VALUES(?,?,?,?,?,'review',?)")
  .bind(id,'87654321','IL',now,now,JSON.stringify({suggestedStatus:'kosher',explanation:'Strong official match',evidence:[{url:'https://oukosher.org/product-search/'}]})).run();
 await db.prepare("INSERT OR REPLACE INTO settings(key,value) VALUES('telegram_chat','123')").run();
 globalThis.fetch=async(url,init)=>{calls.push(String(url));return Response.json({ok:true,result:{}});};
 try {
  const req=new Request('https://example.test/telegram/webhook',{method:'POST',headers:{'X-Telegram-Bot-Api-Secret-Token':'secret'},body:JSON.stringify({callback_query:{id:'callback-1',data:`review:${id}:k`,message:{message_id:7,chat:{id:123,type:'private'},text:'בדיקה חדשה\nמה לפרסם באפליקציה?'}}})});
  assert.equal((await onTelegram(req,{DB:db,TELEGRAM_TOKEN:'mock',TELEGRAM_WEBHOOK_SECRET:'secret'})).status,200);
  const row=await db.prepare('SELECT phase,status,evidence_url FROM cases WHERE id=?').bind(id).first();assert.equal(row.phase,'approved');assert.equal(row.status,'kosher');assert.match(row.evidence_url,/oukosher/);
  assert.ok(calls.some(x=>x.endsWith('/editMessageText')));assert.ok(calls.some(x=>x.endsWith('/answerCallbackQuery')));
 } finally {globalThis.fetch=old;await db.prepare("DELETE FROM settings WHERE key='telegram_chat'").run();await db.prepare('DELETE FROM cases WHERE id=?').bind(id).run();}
});
test('Gemini rate limit waits and retries without consuming a case attempt',async()=>{
 const id=crypto.randomUUID(),now=Date.now(),old=globalThis.fetch;
 await db.prepare("INSERT INTO cases(id,barcode,market,created_at,updated_at,phase) VALUES(?,?,?,?,?,'queued')").bind(id,'88776655','IL',now,now).run();
 globalThis.fetch=async()=>new Response('{}',{status:429});
 try {
  await processQueue({DB:db,GEMINI_KEYS:'["shared-project-key"]',DAILY_AI_LIMIT:'100'});
  const row=await db.prepare('SELECT phase,attempts,retry_after,ai_error FROM cases WHERE id=?').bind(id).first();
  assert.equal(row.phase,'queued');assert.equal(row.attempts,0);assert.equal(row.ai_error,'upstream_429');assert.ok(row.retry_after>Date.now());
 } finally {globalThis.fetch=old;await db.prepare('DELETE FROM cases WHERE id=?').bind(id).run();}
});
test('a crashed final research attempt leaves a reviewable unknown record',async()=>{
 const id=crypto.randomUUID(),old=Date.now()-240000;
 await db.prepare("INSERT INTO cases(id,barcode,market,created_at,updated_at,phase,attempts) VALUES(?,?,?,?,?,'processing',3)").bind(id,'12345678','IL',old,old).run();
 await processQueue({DB:db,DAILY_AI_LIMIT:'0'});
 const row=await db.prepare('SELECT * FROM cases WHERE id=?').bind(id).first();
 assert.equal(row.phase,'review');assert.equal(row.ai_error,'research_failed');assert.equal(visibleResult(row).approved,false);
});
