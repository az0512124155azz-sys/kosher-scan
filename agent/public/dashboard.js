'use strict';
const $=id=>document.getElementById(id), phaseLabels={queued:'ממתינה לסוכן',processing:'בבדיקה',review:'ממתינה להחלטה',approved:'מאומתת',closed:'סגורה'}, countries={IL:'ישראל',GB:'בריטניה',OTHER:'אחרת'};
let token=sessionStorage.getItem('adminToken') || '', cases=[], selected=null, photoUrl='';
function notify(text){$('notice').textContent=text;$('notice').hidden=false;}
async function api(path,options={}) {
 const r=await fetch(path,{...options,headers:{Authorization:'Bearer '+token,'Content-Type':'application/json',...options.headers}});
 if(!r.ok){if(r.status===401){token='';sessionStorage.removeItem('adminToken');showLogin();}throw new Error(r.status===401?'קוד הניהול אינו תקין.':'לא ניתן להשלים את הפעולה. בדוק את השדות ונסה שוב.');}
 return r;
}
function showLogin(){$('login').hidden=false;$('workspace').hidden=true;$('refresh').hidden=true;$('logout').hidden=true;}
async function load(){
 try {
  const [overview,res]=await Promise.all([api('/api/admin/overview').then(r=>r.json()),api('/api/admin/cases').then(r=>r.json())]);
  const counts=Object.fromEntries(overview.phases.map(x=>[x.phase,x.count]));
  $('queuedCount').textContent=(counts.queued || 0)+(counts.processing || 0);$('reviewCount').textContent=counts.review || 0;$('approvedCount').textContent=counts.approved || 0;
  $('usageCount').textContent=overview.usage.ai_calls || 0;$('usageLimit').textContent='מתוך '+overview.aiLimit+' בדיקות ליום';$('navCount').textContent=counts.review || '';
  $('serviceUrl').value=location.origin;$('appCode').value=overview.appToken;$('pairCommand').value='/connect '+overview.pairCode;
  $('telegramState').textContent=overview.telegramConnected?'השיחה שלך מחוברת לבוט.':overview.telegramConfigured?'הבוט מוגדר. נותר לחבר את השיחה שלך.':'יש ליצור בוט ב־BotFather ולהגדיר את המפתח שלו בשירות.';
  $('connectTelegram').disabled=!overview.telegramConfigured;$('geminiState').textContent=overview.geminiConfigured?'✓ Gemini מחובר ומוכן לבדיקות':'Gemini עדיין אינו מוגדר';
  cases=res.cases;$('more').hidden=cases.length<100;draw();$('login').hidden=true;$('workspace').hidden=false;$('refresh').hidden=false;$('logout').hidden=false;
 } catch(e){notify(e.message);}
}
function draw(){
 const q=$('search').value.toLowerCase(), filter=$('filter').value;
 const shown=cases.filter(x=>(filter==='all'||x.phase===filter)&&(x.product_name+' '+x.brand+' '+x.barcode).toLowerCase().includes(q));
 $('rows').replaceChildren();$('empty').hidden=shown.length>0;
 for(const row of shown){
  const tr=document.createElement('tr');const name=document.createElement('td');name.textContent=row.product_name || 'מוצר לא מזוהה';const brand=document.createElement('small');brand.textContent=row.brand;name.append(brand);tr.append(name);
  const barcode=document.createElement('td');barcode.className='code';barcode.textContent=row.barcode;tr.append(barcode);
  const market=document.createElement('td');market.textContent=countries[row.market];tr.append(market);
  const phase=document.createElement('td');const badge=document.createElement('span');badge.className='badge '+row.phase;badge.textContent=phaseLabels[row.phase] || row.phase;phase.append(badge);tr.append(phase);
  const count=document.createElement('td');count.textContent=row.seen_count;tr.append(count);
  const action=document.createElement('td');const button=document.createElement('button');button.className='secondary';button.textContent='פתיחת הבדיקה ←';button.onclick=()=>openDetail(row);action.append(button);tr.append(action);$('rows').append(tr);
 }
}
async function openDetail(row){
 selected=row;$('detailName').textContent=row.product_name || 'מוצר לא מזוהה';$('detailMeta').textContent=`${row.barcode} · ${countries[row.market]} · ${row.seen_count} סריקות`;
 let ai={};try{ai=JSON.parse(row.ai_json);}catch{}
 $('aiExplanation').textContent=ai.explanation || (row.ai_error?'הסוכן לא הצליח להשלים את הבדיקה. אפשר לנסות שוב.':'הסוכן עדיין לא החזיר מידע.');
 $('aiNote').textContent='הצעת הסוכן: '+({kosher:'כשר',not_kosher:'לא כשר',unknown:'לא ידוע'}[ai.suggestedStatus] || 'לא ידוע')+' · נדרשת בדיקה לפני פרסום';
 $('aiSources').replaceChildren();
 $('searchSuggestions').replaceChildren();
 if(ai.searchSuggestions){const frame=document.createElement('iframe');frame.title='הצעות החיפוש של Google';frame.setAttribute('sandbox','allow-popups allow-popups-to-escape-sandbox');frame.style.width='100%';frame.style.border='0';frame.srcdoc='<meta http-equiv="Content-Security-Policy" content="default-src \'none\'; style-src \'unsafe-inline\'; img-src https:; base-uri \'none\'">'+ai.searchSuggestions;$('searchSuggestions').append(frame);}
 for(const evidence of [...(ai.evidence || []),...(ai.grounding || [])]){try{const url=new URL(evidence.url);if(url.protocol!=='https:')continue;const a=document.createElement('a');a.href=url.href;a.target='_blank';a.rel='noopener noreferrer';a.textContent=evidence.title || url.hostname;$('aiSources').append(a);}catch{}}
 $('verdict').value=row.status;$('expiry').value=row.expires_at;$('evidenceUrl').value=row.evidence_url;$('details').value=row.details;
 if(photoUrl){URL.revokeObjectURL(photoUrl);photoUrl='';}$('barcodePhoto').hidden=true;$('photoCaption').textContent='אין צילום שמור לברקוד זה';
 $('productPhoto').hidden=true;if(/^https:\/\/(?:[^/]+\.)?openfoodfacts\.org\//.test(row.image_url)){$('productPhoto').src=row.image_url;$('productPhoto').hidden=false;}
 $('detail').showModal();
 try{const r=await api('/api/admin/cases/'+row.id+'/photo');const blob=await r.blob();if(selected?.id!==row.id)return;photoUrl=URL.createObjectURL(blob);$('barcodePhoto').src=photoUrl;$('barcodePhoto').hidden=false;$('photoCaption').textContent='צילום הברקוד שנשלח מהאפליקציה';}catch{/* No photo is normal for manual input/expired retention. */}
}
$('loginForm').onsubmit=async e=>{e.preventDefault();token=$('adminCode').value.trim();sessionStorage.setItem('adminToken',token);$('adminCode').value='';await load();};
$('logout').onclick=()=>{token='';sessionStorage.removeItem('adminToken');showLogin();};$('refresh').onclick=load;$('search').oninput=draw;$('filter').onchange=draw;
document.querySelectorAll('[data-tab]').forEach(button=>button.onclick=()=>{document.querySelectorAll('[data-tab]').forEach(x=>x.classList.toggle('active',x===button));$('casesView').hidden=button.dataset.tab!=='cases';$('connectionView').hidden=button.dataset.tab!=='connection';});
$('closeDetail').onclick=()=>{$('detail').close();selected=null;if(photoUrl)URL.revokeObjectURL(photoUrl);photoUrl='';};
$('reviewForm').onsubmit=async e=>{e.preventDefault();if(!selected)return;try{await api('/api/admin/cases/'+selected.id+'/review',{method:'POST',body:JSON.stringify({status:$('verdict').value,expiresAt:$('expiry').value,evidenceUrl:$('evidenceUrl').value,details:$('details').value})});$('closeDetail').click();notify('התשובה נשמרה. תשובה מאומתת זמינה כעת לאפליקציה.');await load();}catch(e){notify(e.message);}};
$('retryCase').onclick=async()=>{if(!selected)return;try{await api('/api/admin/cases/'+selected.id+'/retry',{method:'POST',body:'{}'});$('closeDetail').click();await load();notify('הבדיקה נשלחה שוב לסוכן.');}catch(e){notify(e.message);}};
$('deleteCase').onclick=async()=>{if(!selected || !confirm('למחוק את הבדיקה ואת הצילום השמור?'))return;try{await api('/api/admin/cases/'+selected.id+'/delete',{method:'POST',body:'{}'});$('closeDetail').click();await load();}catch(e){notify(e.message);}};
$('copyApp').onclick=async()=>{try{await navigator.clipboard.writeText($('appCode').value);notify('קוד החיבור הועתק.');}catch{notify('בחר והעתק את הקוד מהשדה.');}};
$('copyPair').onclick=async()=>{try{await navigator.clipboard.writeText($('pairCommand').value);notify('הפקודה הועתקה.');}catch{notify('בחר והעתק את הפקודה מהשדה.');}};
$('connectTelegram').onclick=async()=>{try{await api('/api/admin/telegram/connect',{method:'POST',body:'{}'});notify('החיבור הופעל. כעת שלח לבוט את פקודת החיבור.');}catch(e){notify(e.message);}};
$('more').onclick=async()=>{try{const before=Math.min(...cases.map(x=>x.updated_at));const data=await api('/api/admin/cases?before='+before).then(r=>r.json());cases.push(...data.cases);$('more').hidden=data.cases.length<100;draw();}catch(e){notify(e.message);}};
if(token)load();else showLogin();
