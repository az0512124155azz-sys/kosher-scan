import {readFile} from 'node:fs/promises';
import {execFileSync} from 'node:child_process';
const allowed=new Set(['GEMINI_KEYS','ADMIN_TOKEN','APP_TOKEN','TELEGRAM_TOKEN','TELEGRAM_WEBHOOK_SECRET','TELEGRAM_PAIR_CODE']);
const values={};
for(const line of (await readFile('.dev.vars','utf8')).split(/\r?\n/)) {
 const match=line.match(/^([A-Z_]+)=(.*)$/);if(!match || !allowed.has(match[1]))continue;
 let value=match[2].trim();if(value.startsWith("'") && value.endsWith("'"))value=value.slice(1,-1);
 if(value)values[match[1]]=value;
}
if(!values.GEMINI_KEYS || !values.ADMIN_TOKEN || !values.APP_TOKEN)throw new Error('Required local secrets are missing');
execFileSync(process.execPath,['node_modules/wrangler/bin/wrangler.js','secret','bulk'],{input:JSON.stringify(values),stdio:['pipe','inherit','inherit']});
