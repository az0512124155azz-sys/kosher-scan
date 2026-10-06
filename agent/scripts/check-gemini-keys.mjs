import {readFile} from 'node:fs/promises';

const source=await readFile(new URL('../.dev.vars',import.meta.url),'utf8');
let raw=source.split(/\r?\n/).find(line=>line.startsWith('GEMINI_KEYS='))?.slice('GEMINI_KEYS='.length).trim() || '[]';
if((raw.startsWith("'") && raw.endsWith("'")) || (raw.startsWith('"') && raw.endsWith('"')))raw=raw.slice(1,-1);
const keys=JSON.parse(raw),results=[];
for(let i=0;i<keys.length;i++){
  try {const response=await fetch(`https://generativelanguage.googleapis.com/v1beta/models?key=${encodeURIComponent(keys[i])}`);results.push({number:i+1,valid:response.ok,status:response.status});}
  catch {results.push({number:i+1,valid:false,status:'network_error'});}
}
console.log(JSON.stringify({count:keys.length,results}));
