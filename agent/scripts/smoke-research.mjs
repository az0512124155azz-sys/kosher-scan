import {readFile} from 'node:fs/promises';
import {research} from '../src/integrations.mjs';

const [barcode,product_name='',brand='']=process.argv.slice(2);
if(!/^\d{8,14}$/.test(barcode || ''))throw new Error('usage: barcode [name] [brand]');
const source=await readFile(new URL('../.dev.vars',import.meta.url),'utf8');
let keys=source.split(/\r?\n/).find(line=>line.startsWith('GEMINI_KEYS='))?.slice('GEMINI_KEYS='.length).trim() || '[]';
if((keys.startsWith("'")&&keys.endsWith("'"))||(keys.startsWith('"')&&keys.endsWith('"')))keys=keys.slice(1,-1);
const result=await research({barcode,market:'IL',product_name,brand,barcode_photo:'',image_url:''},{GEMINI_KEYS:keys,GEMINI_MODEL:'gemini-2.5-flash'});
console.log(JSON.stringify({barcode,suggestedStatus:result.suggestedStatus,searchUsed:result.searchUsed,evidence:result.evidence.length,grounding:result.grounding.length,imageCertification:Boolean(result.imageCertification)}));
