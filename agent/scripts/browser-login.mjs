import {readFile} from 'node:fs/promises';import {execFileSync} from 'node:child_process';
const settings=JSON.parse(await readFile('local-settings.json','utf8'));
const cli='node_modules/agent-browser/bin/agent-browser.js';
execFileSync(process.execPath,[cli,'fill','@e7',settings.adminCode],{stdio:'pipe'});
execFileSync(process.execPath,[cli,'click','@e6'],{stdio:'pipe'});
console.log('Dashboard login submitted without printing credentials.');
