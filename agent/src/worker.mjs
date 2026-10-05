import {clean, markets, normalizeSubmission, validateReview, visibleResult} from './policy.mjs';
import {research, notifyCase, telegram} from './integrations.mjs';

const response = (data, status = 200) => Response.json(data, {status,headers:{'Cache-Control':'no-store','X-Content-Type-Options':'nosniff'}});
const eq = (a,b) => { if (!a || !b || a.length !== b.length) return false; let n=0; for(let i=0;i<a.length;i++) n|=a.charCodeAt(i)^b.charCodeAt(i); return n===0; };
const auth = (req, token) => eq(req.headers.get('Authorization'), `Bearer ${token || ''}`) && Boolean(token);
async function body(req) {
  const reader=req.body?.getReader(); if(!reader) throw new Error('empty_body');
  let bytes=0, chunks=[];
  for(;;) {const {done,value}=await reader.read(); if(done)break; bytes+=value.length; if(bytes>320000){await reader.cancel();throw new Error('body_too_large');} chunks.push(value);}
  const all=new Uint8Array(bytes);let offset=0;for(const x of chunks){all.set(x,offset);offset+=x.length;}
  return JSON.parse(new TextDecoder().decode(all));
}
const getSetting = async (env,key) => (await env.DB.prepare('SELECT value FROM settings WHERE key=?').bind(key).first())?.value || '';
async function setting(env,key,value) { await env.DB.prepare('INSERT INTO settings(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value').bind(key,value).run(); }
async function audit(env,id,action) {await env.DB.prepare('INSERT INTO audit(case_id,action,created_at) VALUES(?,?,?)').bind(id,action,Date.now()).run();}
async function latest(env,code,market) {return env.DB.prepare('SELECT * FROM cases WHERE barcode=? AND market=? ORDER BY updated_at DESC LIMIT 1').bind(code,market).first();}

async function submit(env, data) {
  const p = normalizeSubmission(data), now=Date.now();
  const receipt=await env.DB.prepare('SELECT case_id FROM receipts WHERE request_id=?').bind(p.requestId).first();
  if(receipt) return {id:receipt.case_id,duplicate:true};
  const day=new Date().toISOString().slice(0,10);
  const quota=await env.DB.prepare('INSERT INTO usage(day,submissions) VALUES(?,1) ON CONFLICT(day) DO UPDATE SET submissions=submissions+1 WHERE submissions<500 RETURNING submissions').bind(day).first();
  if(!quota)throw new Error('daily_submission_limit');
  const previous=await latest(env,p.barcode,p.market);
  const observation=id=>env.DB.prepare('INSERT OR IGNORE INTO observations(id,case_id,barcode,market,product_name,brand,image_url,barcode_photo,created_at) VALUES(?,?,?,?,?,?,?,?,?)')
    .bind(p.requestId,id,p.barcode,p.market,p.name,p.brand,p.imageUrl,p.photo,now);
  if(previous && (visibleResult(previous).approved || ['queued','processing','review'].includes(previous.phase))) {
    await env.DB.batch([
      env.DB.prepare('INSERT OR IGNORE INTO receipts(request_id,case_id) VALUES(?,?)').bind(p.requestId,previous.id),
      observation(previous.id),
      env.DB.prepare("UPDATE cases SET seen_count=seen_count+1, barcode_photo=CASE WHEN barcode_photo='' THEN ? ELSE barcode_photo END, updated_at=? WHERE id=?").bind(p.photo,now,previous.id)
    ]);
    return {id:previous.id,duplicate:true};
  }
  const id=crypto.randomUUID();
  await env.DB.batch([
    env.DB.prepare('INSERT INTO cases(id,barcode,market,product_name,brand,image_url,barcode_photo,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?)').bind(id,p.barcode,p.market,p.name,p.brand,p.imageUrl,p.photo,now,now),
    env.DB.prepare('INSERT INTO receipts(request_id,case_id) VALUES(?,?)').bind(p.requestId,id),
    observation(id)
  ]);
  return {id,duplicate:false};
}
async function review(env,id,input) {
  const row=await env.DB.prepare('SELECT id FROM cases WHERE id=?').bind(id).first();if(!row)throw new Error('not_found');
  const p=validateReview(input), now=Date.now();
  await env.DB.prepare('UPDATE cases SET status=?,details=?,evidence_url=?,expires_at=?,phase=?,reviewed_at=?,updated_at=? WHERE id=?')
    .bind(p.status,p.details,p.evidence,p.expires,p.status==='unknown'?'closed':'approved',now,now,id).run();
  await audit(env,id,`review:${p.status}`);
}
export async function processQueue(env) {
  // Start delivery and research together so an HTTP waitUntil stays within 30 seconds.
  await Promise.all([deliverObservations(env), researchCases(env)]);
  await env.DB.prepare("UPDATE cases SET barcode_photo='' WHERE created_at<? AND barcode_photo<>''").bind(Date.now()-Number(env.PHOTO_RETENTION_DAYS || 30)*86400000).run();
  await env.DB.prepare("UPDATE observations SET barcode_photo='' WHERE created_at<? AND barcode_photo<>''").bind(Date.now()-Number(env.PHOTO_RETENTION_DAYS || 30)*86400000).run();
}
async function deliverObservations(env) {
  const chat=await getSetting(env,'telegram_chat');
  if(chat && env.TELEGRAM_TOKEN) {
    await env.DB.prepare('UPDATE observations SET telegram_sent=0 WHERE telegram_sent=2 AND delivery_at<?').bind(Date.now()-120000).run();
    const {results:unsent}=await env.DB.prepare('SELECT id FROM observations WHERE telegram_sent=0 ORDER BY created_at LIMIT 2').all();
    await Promise.all(unsent.map(async item => {
      const row=await env.DB.prepare('UPDATE observations SET telegram_sent=2,delivery_at=? WHERE id=? AND telegram_sent=0 RETURNING *').bind(Date.now(),item.id).first();
      if(!row)return;
      try {if(await notifyCase({...row,id:row.case_id},env,chat))await env.DB.prepare('UPDATE observations SET telegram_sent=1 WHERE id=?').bind(row.id).run();}
      catch {await env.DB.prepare('UPDATE observations SET telegram_sent=0 WHERE id=?').bind(row.id).run();}
    }));
  }
}
async function researchCases(env) {
  // Durable leases survive isolate restarts. A crashed job is retried, never auto-approved.
  await env.DB.prepare("UPDATE cases SET phase='review',ai_error='research_failed' WHERE phase='processing' AND updated_at<? AND attempts>=3").bind(Date.now()-180000).run();
  await env.DB.prepare("UPDATE cases SET phase='queued' WHERE phase='processing' AND updated_at<? AND attempts<3").bind(Date.now()-180000).run();
  const {results}=await env.DB.prepare("SELECT id FROM cases WHERE phase='queued' AND attempts<3 ORDER BY created_at LIMIT 2").all();
  await Promise.all(results.map(async item => {
    const row=await env.DB.prepare("UPDATE cases SET phase='processing',updated_at=?,attempts=attempts+1 WHERE id=? AND phase='queued' RETURNING *").bind(Date.now(),item.id).first();
    if(!row)return;
    const day=new Date().toISOString().slice(0,10), max=Math.max(0,Math.min(1000,Number(env.DAILY_AI_LIMIT || 100)));
    try {
      if(max===0)throw new Error('daily_ai_limit');
      const quota=await env.DB.prepare('INSERT INTO usage(day,ai_calls) VALUES(?,1) ON CONFLICT(day) DO UPDATE SET ai_calls=ai_calls+1 WHERE ai_calls<? RETURNING ai_calls').bind(day,max).first();
      if(!quota || max===0)throw new Error('daily_ai_limit');
      const suggestion=await research(row,env);
      await env.DB.prepare("UPDATE cases SET phase='review',ai_json=?,ai_error='',updated_at=? WHERE id=? AND phase='processing'").bind(JSON.stringify(suggestion),Date.now(),row.id).run();
      await audit(env,row.id,'ai_researched');
    } catch(e) {
      const reason=/^(gemini_not_configured|daily_ai_limit|upstream_\d+|upstream_too_large)$/.test(e.message)?e.message:'research_failed';
      await env.DB.prepare('UPDATE cases SET phase=?,ai_error=?,updated_at=? WHERE id=? AND phase=?')
        .bind(row.attempts>=3 || reason==='daily_ai_limit' || reason==='gemini_not_configured'?'review':'queued',reason,Date.now(),row.id,'processing').run();
    }
  }));
}
async function onTelegram(req,env) {
  if(!eq(req.headers.get('X-Telegram-Bot-Api-Secret-Token'),env.TELEGRAM_WEBHOOK_SECRET))return response({error:'unauthorized'},401);
  const update=await body(req), msg=update.message;
  if(!msg || msg.chat?.type!=='private')return response({ok:true});
  const chat=String(msg.chat.id), owner=await getSetting(env,'telegram_chat');
  const text=clean(msg.text,200);
  if(text.startsWith('/connect ') && eq(text.slice(9),env.TELEGRAM_PAIR_CODE) && (!owner || owner===chat)) {
    await setting(env,'telegram_chat',chat);
    await telegram(env,'sendMessage',{chat_id:chat,text:'החיבור הושלם. בדיקות של מוצרים לא ידועים יגיעו לכאן עם הברקוד ופרטי המוצר.'});
  } else if(owner===chat && /^\/status \d{8,14}$/.test(text)) {
    const row=await latest(env,text.slice(8),'IL');
    const result=visibleResult(row);
    await telegram(env,'sendMessage',{chat_id:chat,text:result.approved?({'kosher':'כשר','not_kosher':'לא כשר'}[result.status] || 'לא ידוע'):'עדיין אין תשובה מאומתת. אפשר לבדוק את הבקשה בדשבורד.'});
  }
  // Other chats cannot receive product photos or act as administrators.
  return response({ok:true});
}
export default {
  async fetch(req,env,ctx) {
    const url=new URL(req.url), path=url.pathname;
    try {
      if(path==='/api/health')return response({ok:true,version:'1.6.0'});
      if(path==='/telegram/webhook' && req.method==='POST')return await onTelegram(req,env);
      if(path.startsWith('/api/admin/')) {
        if(!auth(req,env.ADMIN_TOKEN))return response({error:'unauthorized'},401);
        if(path==='/api/admin/overview' && req.method==='GET') {
          const phases=await env.DB.prepare('SELECT phase,COUNT(*) AS count FROM cases GROUP BY phase').all();
          const usage=await env.DB.prepare('SELECT * FROM usage WHERE day=?').bind(new Date().toISOString().slice(0,10)).first();
          return response({phases:phases.results,usage:usage || {ai_calls:0},geminiConfigured:Boolean(env.GEMINI_KEYS),telegramConfigured:Boolean(env.TELEGRAM_TOKEN),telegramConnected:Boolean(await getSetting(env,'telegram_chat')),appToken:env.APP_TOKEN || '',pairCode:env.TELEGRAM_PAIR_CODE || '',aiLimit:Number(env.DAILY_AI_LIMIT || 100)});
        }
        if(path==='/api/admin/cases' && req.method==='GET') {
          const cursor=Number(url.searchParams.get('before') || Date.now()+1);
          const rows=await env.DB.prepare('SELECT id,barcode,market,product_name,brand,image_url,phase,ai_json,ai_error,status,details,evidence_url,expires_at,created_at,updated_at,seen_count FROM cases WHERE updated_at<? ORDER BY updated_at DESC LIMIT 100').bind(cursor).all();
          return response({cases:rows.results});
        }
        const image=path.match(/^\/api\/admin\/cases\/([a-f0-9-]{36})\/photo$/);
        if(image && req.method==='GET') {
          const row=await env.DB.prepare('SELECT barcode_photo FROM cases WHERE id=?').bind(image[1]).first();
          if(!row?.barcode_photo)return response({error:'not_found'},404);
          return new Response(Uint8Array.from(atob(row.barcode_photo),c=>c.charCodeAt(0)),{headers:{'Content-Type':'image/jpeg','Cache-Control':'no-store','X-Content-Type-Options':'nosniff'}});
        }
        const match=path.match(/^\/api\/admin\/cases\/([a-f0-9-]{36})\/(review|retry|delete)$/);
        if(match && req.method==='POST') {
          if(match[2]==='review')await review(env,match[1],await body(req));
          else if(match[2]==='retry') {
            await env.DB.prepare("UPDATE cases SET phase='queued',attempts=0,ai_error='',status='unknown',expires_at='',reviewed_at=NULL,updated_at=? WHERE id=?").bind(Date.now(),match[1]).run();
            ctx.waitUntil(processQueue(env));
          } else {
            await env.DB.batch([env.DB.prepare('DELETE FROM receipts WHERE case_id=?').bind(match[1]),env.DB.prepare('DELETE FROM observations WHERE case_id=?').bind(match[1]),env.DB.prepare('DELETE FROM cases WHERE id=?').bind(match[1])]);
            await audit(env,match[1],'deleted');
          }
          return response({ok:true});
        }
        if(path==='/api/admin/telegram/connect' && req.method==='POST') {
          const result=await telegram(env,'setWebhook',{url:url.origin+'/telegram/webhook',secret_token:env.TELEGRAM_WEBHOOK_SECRET,allowed_updates:['message'],drop_pending_updates:false});
          return response({ok:Boolean(result)});
        }
        return response({error:'not_found'},404);
      }
      if(path.startsWith('/api/')) {
        if(!auth(req,env.APP_TOKEN))return response({error:'unauthorized'},401);
        if(path==='/api/cases' && req.method==='POST') {
          const item=await submit(env,await body(req));ctx.waitUntil(processQueue(env));return response(item,202);
        }
        if(path==='/api/result' && req.method==='GET') {
          const code=url.searchParams.get('barcode'), market=url.searchParams.get('market');
          if(!/^\d{8,14}$/.test(code || '') || !markets.has(market))return response({error:'invalid_query'},400);
          return response(visibleResult(await latest(env,code,market)));
        }
        return response({error:'not_found'},404);
      }
      const asset=await env.ASSETS.fetch(req);
      const headers=new Headers(asset.headers);
      headers.set('Content-Security-Policy',"default-src 'self'; img-src 'self' blob: https://*.openfoodfacts.org; script-src 'self'; style-src 'self' 'unsafe-inline'; frame-ancestors 'none'; base-uri 'none'");
      headers.set('X-Content-Type-Options','nosniff');headers.set('Referrer-Policy','no-referrer');
      return new Response(asset.body,{status:asset.status,headers});
    } catch(e) {
      const allowed=['invalid_submission','invalid_photo','invalid_status','evidence_and_current_expiry_required','not_found','body_too_large','daily_submission_limit'];
      return response({error:allowed.includes(e.message)?e.message:'request_failed'}, e.message==='daily_submission_limit'?429:allowed.includes(e.message) || e instanceof SyntaxError?400:503);
    }
  },
  async scheduled(event,env,ctx) {ctx.waitUntil(processQueue(env));}
};
