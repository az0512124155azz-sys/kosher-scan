import {readFile,writeFile} from 'node:fs/promises';
// Run only after deploying. The app connection code is intentionally public and
// limited to submission/result access. Never publish administrator/provider keys.
const url=new URL(process.argv[2]);
if(url.protocol!=='https:' || url.username || url.password || url.pathname!=='/' || url.search || url.hash)throw new Error('An HTTPS service origin is required');
const vars=await readFile('.dev.vars','utf8');
const code=vars.match(/^APP_TOKEN=([a-f0-9]{48})\s*$/m)?.[1];
if(!code)throw new Error('Missing locally configured app code');
const r=await fetch(new URL('/api/result?barcode=12345678&market=IL',url),{headers:{Authorization:'Bearer '+code},signal:AbortSignal.timeout(10000)});
if(!r.ok)throw new Error('The deployed service did not accept the app connection');
await writeFile('connection.json',JSON.stringify({enabled:true,url:url.href,appCode:code},null,2)+'\n');
console.log('Public app routing prepared. Commit connection.json to main to activate all installed clients.');
