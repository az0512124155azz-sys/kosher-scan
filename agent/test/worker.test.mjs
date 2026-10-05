import {test, before, after} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {Miniflare} from 'miniflare';
import {normalizeSubmission, validateReview, visibleResult, validateSuggestion} from '../src/policy.mjs';
import {processQueue} from '../src/worker.mjs';
let mf,db;
before(async()=>{
 mf=new Miniflare({modules:true,scriptPath:'src/worker.mjs',compatibilityDate:'2026-07-30',d1Databases:['DB'],bindings:{APP_TOKEN:'local-app-token-00000000000000000000',ADMIN_TOKEN:'local-admin-token-0000000000000000',DAILY_AI_LIMIT:'100',TELEGRAM_WEBHOOK_SECRET:'local-webhook-secret'}});
 db=await mf.getD1Database('DB');for(const file of ['0001.sql','0002.sql'])await db.exec((await readFile('migrations/'+file,'utf8')).split(';').map(x=>x.replace(/\s+/g,' ').trim()).filter(Boolean).join(';\n')+';');
});
after(async()=>{await mf?.dispose();});
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
 assert.equal((await db.prepare('SELECT COUNT(*) as n FROM observations').first()).n,2); // Every scan is forwarded, while research is deduplicated.
 assert.equal((await request('/api/result?barcode=3017620422003&market=GB').then(x=>x.json())).status,'unknown');
 const expires=new Date(Date.now()+86400000*30).toISOString().slice(0,10);
 assert.equal((await request('/api/admin/cases/'+accepted.id+'/review',{status:'kosher',expiresAt:expires,evidenceUrl:'https://www.ok.org/product-search/',details:'חלבי.'},true)).status,200);
 const approved=await request('/api/result?barcode=3017620422003&market=GB').then(x=>x.json());assert.equal(approved.status,'kosher');assert.equal(approved.details,'חלבי.');
 assert.equal((await request('/api/result?barcode=3017620422003&market=IL').then(x=>x.json())).status,'unknown');
 const data=await request('/api/admin/cases',null,true).then(x=>x.json());assert.equal(data.cases[0].barcode,'3017620422003');
 assert.equal((await request('/api/admin/cases/'+accepted.id+'/delete',{},true)).status,200);
 assert.equal((await request('/api/result?barcode=3017620422003&market=GB').then(x=>x.json())).approved,false);
});
test('Telegram secret is required and unpaired chats get no data',async()=>{
 assert.equal((await mf.dispatchFetch('https://example.test/telegram/webhook',{method:'POST',body:'{}'})).status,401);
 const r=await mf.dispatchFetch('https://example.test/telegram/webhook',{method:'POST',headers:{'X-Telegram-Bot-Api-Secret-Token':'local-webhook-secret'},body:JSON.stringify({message:{chat:{id:123,type:'private'},text:'/status 3017620422003'}})});assert.equal(r.status,200);
});
test('a crashed final research attempt leaves a reviewable unknown record',async()=>{
 const id=crypto.randomUUID(),old=Date.now()-240000;
 await db.prepare("INSERT INTO cases(id,barcode,market,created_at,updated_at,phase,attempts) VALUES(?,?,?,?,?,'processing',3)").bind(id,'12345678','IL',old,old).run();
 await processQueue({DB:db,DAILY_AI_LIMIT:'0'});
 const row=await db.prepare('SELECT * FROM cases WHERE id=?').bind(id).first();
 assert.equal(row.phase,'review');assert.equal(row.ai_error,'research_failed');assert.equal(visibleResult(row).approved,false);
});
