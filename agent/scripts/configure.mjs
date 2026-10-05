import {readFile,writeFile} from 'node:fs/promises';
import {randomBytes} from 'node:crypto';
// Imports credentials locally; never prints them or puts them in deployable assets.
const file=process.argv[2];if(!file)throw new Error('Pass the path to the Gemini key file');
const lines=(await readFile(file,'utf8')).replace(/^\uFEFF/,'').split(/\r?\n/).map(x=>x.trim()).filter(Boolean);
const keys=lines.map(x=>x.slice(x.indexOf(':')+1)).filter(x=>/^(AQ\.|AIza)/.test(x));
if(!keys.length)throw new Error('No Gemini keys found');
const admin=randomBytes(24).toString('hex'),app=randomBytes(24).toString('hex'),webhook=randomBytes(24).toString('hex'),pair=randomBytes(18).toString('hex');
await writeFile('.dev.vars',`GEMINI_KEYS='${JSON.stringify(keys)}'\nADMIN_TOKEN=${admin}\nAPP_TOKEN=${app}\nTELEGRAM_WEBHOOK_SECRET=${webhook}\nTELEGRAM_PAIR_CODE=${pair}\n`);
await writeFile('local-settings.json',JSON.stringify({dashboard:'http://localhost:8787',adminCode:admin,appUrl:'http://10.0.2.2:8787',appCode:app},null,2));
console.log(`${keys.length} Gemini keys imported. Local connection codes saved to ignored local-settings.json.`);
