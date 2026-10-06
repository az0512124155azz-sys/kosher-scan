import {readFile} from 'node:fs/promises';

const settings=JSON.parse(await readFile(new URL('../local-settings.json',import.meta.url),'utf8'));
const base=(settings.dashboard || '').replace(/\/$/,'');
const response=await fetch(base+'/api/admin/telegram/connect',{method:'POST',headers:{Authorization:`Bearer ${settings.adminCode}`}});
if(!response.ok)throw new Error(`webhook_refresh_${response.status}`);
const result=await response.json();
if(!result.ok)throw new Error('webhook_refresh_failed');
console.log('Telegram webhook refreshed.');
