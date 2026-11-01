// 把 res/raw 里的三个脚本按 App 内同一套占位符替换规则展开，供本地 shell 行为验证。
import fs from 'fs';
import path from 'path';

const here = path.dirname(
  decodeURIComponent(new URL(import.meta.url).pathname).replace(/^\/([A-Za-z]:)/, '$1')
);
const raw = path.join(here, '..', 'app', 'src', 'main', 'res', 'raw');
const out = path.join(here, 'out');
fs.mkdirSync(out, { recursive: true });

const PKG = 'com.test.app';
const perms = ['android.permission.CAMERA', 'android.permission.RECORD_AUDIO'];
const ops = ['SYSTEM_ALERT_WINDOW', 'GET_USAGE_STATS'];

let g = fs.readFileSync(path.join(raw, 'grant.sh'), 'utf8')
  .replace('__PKG__', PKG)
  .replace('__PERMS__', perms.map(p => `g '${p}'`).join('\n'))
  .replace('__OPS__', ops.map(o => `s '${o}'`).join('\n'))
  .replace('__ACC__', `a '${PKG}.MyAccService'`)
  .replace('__NL__', `l '${PKG}.MyListener'`)
  .replace('__ADMIN__', `d '${PKG}.Admin'`);
fs.writeFileSync(path.join(out, 'grant.sh'), g);

let r = fs.readFileSync(path.join(raw, 'revoke.sh'), 'utf8')
  .replace('__PKG__', PKG)
  .replace('__PERMS__', perms.map(p => `p '${p}'`).join('\n'))
  .replace('__OPS__', ops.map(o => `s '${o}'`).join('\n'))
  .replace('__ACC__', `a '${PKG}.MyAccService'`)
  .replace('__NL__', `l '${PKG}.MyListener'`)
  .replace('__ADMIN__', `d '${PKG}.Admin'`);
fs.writeFileSync(path.join(out, 'revoke.sh'), r);

const opsTpl = fs.readFileSync(path.join(raw, 'ops.sh'), 'utf8');
const cases = [
  ['freeze', ''],
  ['unfreeze', ''],
  ['uninstall', ''],
  ['restore', ''],
  ['forcestop', ''],
  ['clear', ''],
  ['battery', ''],
  ['unbattery', ''],
  ['appops', 'SYSTEM_ALERT_WINDOW'],
  ['perm_grant', 'android.permission.CAMERA'],
  ['perm_revoke', 'android.permission.CAMERA'],
  ['acc_on', `${PKG}.MyAccService`],
  ['acc_off', `${PKG}.MyAccService`],
  ['nl_on', `${PKG}.MyListener`],
  ['nl_off', `${PKG}.MyListener`],
  ['admin_on', `${PKG}.Admin`],
  ['admin_off', `${PKG}.Admin`],
  ['listdisabled', ''],
  ['listuninstalled', '']
];
for (const [a, e] of cases) {
  fs.writeFileSync(
    path.join(out, `ops-${a}.sh`),
    opsTpl.replace('__A__', a).replace('__P__', PKG).replace('__E__', e)
  );
}
console.log('wrote', fs.readdirSync(out).length, 'scripts to', out);
