#!/bin/sh
# 在假 pm/appops/settings/dumpsys 上跑展开后的脚本，检查回执协议是否成立。
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
STUB="$HERE/bin"
export PATH="$STUB:$PATH"
mkdir -p /tmp/gltest/state

run() {
  echo "===== $1"
  sh "$HERE/out/$1" 2>&1 | grep '^R|' || echo "  (无回执)"
}

# 初始状态：无障碍列表为空、通知监听为空
rm -f /tmp/gltest/state/*
echo "null" > /tmp/gltest/state/enabled_accessibility_services
echo "null" > /tmp/gltest/state/enabled_notification_listeners

run grant.sh
echo "--- 写入后的 settings 值"
echo "acc_services=$(cat /tmp/gltest/state/enabled_accessibility_services)"
echo "acc_enabled=$(cat /tmp/gltest/state/accessibility_enabled)"
echo "nl=$(cat /tmp/gltest/state/enabled_notification_listeners)"

run revoke.sh
echo "--- 撤销后的 settings 值"
echo "acc_services=[$(cat /tmp/gltest/state/enabled_accessibility_services)]"
echo "acc_enabled=$(cat /tmp/gltest/state/accessibility_enabled)"
echo "nl=[$(cat /tmp/gltest/state/enabled_notification_listeners)]"

for f in ops-freeze.sh ops-unfreeze.sh ops-uninstall.sh ops-restore.sh ops-forcestop.sh ops-clear.sh ops-battery.sh ops-unbattery.sh ops-appops.sh ops-perm_grant.sh ops-perm_revoke.sh ops-acc_on.sh ops-acc_off.sh ops-nl_on.sh ops-nl_off.sh ops-admin_on.sh ops-admin_off.sh ops-listdisabled.sh ops-listuninstalled.sh; do
  run "$f"
done
