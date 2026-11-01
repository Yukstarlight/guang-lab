# 光 · 实验室 —— 单动作脚本（动作名与包名由 App 填入，均已校验字符集）
# 回执：R|<分组>|<条目>|<ok|fail>|<详情>

A='unbattery'
P='com.test.app'
E=''

case "$A" in

  freeze)
    o=$(pm disable-user --user 0 "$P" 2>&1)
    if pm list packages -d --user 0 2>/dev/null | grep -qxF "package:$P"; then
      echo "R|freeze|冻结|ok|"
    else
      echo "R|freeze|冻结|fail|$(echo "$o" | head -n 1 | tr -d '|')"
    fi
    ;;

  unfreeze)
    o=$(pm enable "$P" 2>&1)
    r=$?
    if [ $r -eq 0 ]; then
      echo "R|freeze|解冻|ok|"
    else
      echo "R|freeze|解冻|fail|$(echo "$o" | head -n 1 | tr -d '|')"
    fi
    ;;

  uninstall)
    o=$(pm uninstall --user 0 "$P" 2>&1)
    case "$o" in
      *Success*) echo "R|canta|卸载|ok|" ;;
      *) echo "R|canta|卸载|fail|$(echo "$o" | head -n 1 | tr -d '|')" ;;
    esac
    ;;

  restore)
    o=$(cmd package install-existing --user 0 "$P" 2>&1)
    case "$o" in
      *installed* | *Success*) echo "R|canta|恢复|ok|" ;;
      *) echo "R|canta|恢复|fail|$(echo "$o" | head -n 1 | tr -d '|')" ;;
    esac
    ;;

  forcestop)
    am force-stop "$P" >/dev/null 2>&1
    r=$?
    if [ $r -eq 0 ]; then
      echo "R|act|强制停止|ok|"
    else
      echo "R|act|强制停止|fail|exit=$r"
    fi
    ;;

  clear)
    o=$(pm clear "$P" 2>&1)
    case "$o" in
      *Success*) echo "R|act|清除数据|ok|" ;;
      *) echo "R|act|清除数据|fail|$(echo "$o" | head -n 1 | tr -d '|')" ;;
    esac
    ;;

  start)
    o=$(monkey -p "$P" -c android.intent.category.LAUNCHER 1 2>&1)
    if echo "$o" | grep -q "Events injected"; then
      echo "R|act|启动应用|ok|"
    else
      echo "R|act|启动应用|fail|$(echo "$o" | head -n 1 | tr -d '|')"
    fi
    ;;

  battery)
    cmd deviceidle whitelist +"$P" >/dev/null 2>&1
    dumpsys deviceidle whitelist +"$P" >/dev/null 2>&1
    if dumpsys deviceidle whitelist 2>/dev/null | grep -qF "$P"; then
      echo "R|batt|免电池优化|ok|"
    else
      echo "R|batt|免电池优化|fail|"
    fi
    ;;

  unbattery)
    cmd deviceidle whitelist -"$P" >/dev/null 2>&1
    dumpsys deviceidle whitelist -"$P" >/dev/null 2>&1
    if dumpsys deviceidle whitelist 2>/dev/null | grep -qF "$P"; then
      echo "R|batt|免电池优化|fail|"
    else
      echo "R|batt|免电池优化|ok|"
    fi
    ;;

  appops)
    o=$(appops set "$P" "$E" allow 2>&1)
    v=$(appops get "$P" "$E" 2>/dev/null | head -n 1)
    case "$v" in
      *allow*) echo "R|op|$E|ok|" ;;
      *) echo "R|op|$E|fail|$(echo "$o" | head -n 1 | tr -d '|')" ;;
    esac
    ;;

  perm_grant)
    o=$(pm grant "$P" "$E" 2>&1)
    r=$?
    if [ $r -eq 0 ]; then
      echo "R|perm|$E|ok|"
    else
      echo "R|perm|$E|fail|$(echo "$o" | head -n 1 | tr -d '|')"
    fi
    ;;

  perm_revoke)
    o=$(pm revoke "$P" "$E" 2>&1)
    r=$?
    if [ $r -eq 0 ]; then
      echo "R|perm|$E|ok|"
    else
      echo "R|perm|$E|fail|$(echo "$o" | head -n 1 | tr -d '|')"
    fi
    ;;

  acc_on)
    t="$P/$E"
    c=$(settings get secure enabled_accessibility_services)
    case ":$c:" in
      *":$t:"*) ;;
      "::" | ":null:") settings put secure enabled_accessibility_services "$t" ;;
      *) settings put secure enabled_accessibility_services "$c:$t" ;;
    esac
    settings put secure accessibility_enabled 1
    n=$(settings get secure enabled_accessibility_services)
    case ":$n:" in
      *":$t:"*) echo "R|acc|无障碍|ok|" ;;
      *) echo "R|acc|无障碍|fail|写入后回读未命中" ;;
    esac
    ;;

  acc_off)
    t="$P/$E"
    c=$(settings get secure enabled_accessibility_services)
    n=""
    rest="$c"
    while [ -n "$rest" ]; do
      case "$rest" in
        *:*) item="${rest%%:*}"; rest="${rest#*:}" ;;
        *) item="$rest"; rest="" ;;
      esac
      if [ "$item" = "$t" ]; then continue; fi
      if [ -n "$item" ] && [ "$item" != "null" ]; then n="${n:+$n:}$item"; fi
    done
    settings put secure enabled_accessibility_services "$n"
    if [ -z "$n" ]; then settings put secure accessibility_enabled 0; fi
    n2=$(settings get secure enabled_accessibility_services)
    case ":$n2:" in
      *":$t:"*) echo "R|acc|无障碍|fail|仍在列表中" ;;
      *) echo "R|acc|无障碍|ok|" ;;
    esac
    ;;

  nl_on)
    t="$P/$E"
    o=$(cmd notification allow_listener "$t" 2>&1)
    c=$(settings get secure enabled_notification_listeners)
    case ":$c:" in
      *":$t:"*) ;;
      "::" | ":null:") settings put secure enabled_notification_listeners "$t" ;;
      *) settings put secure enabled_notification_listeners "$c:$t" ;;
    esac
    n=$(settings get secure enabled_notification_listeners)
    case ":$n:" in
      *":$t:"*) echo "R|nl|通知使用权|ok|" ;;
      *) echo "R|nl|通知使用权|fail|$(echo "$o" | head -n 1 | tr -d '|')" ;;
    esac
    ;;

  nl_off)
    t="$P/$E"
    cmd notification disallow_listener "$t" >/dev/null 2>&1
    c=$(settings get secure enabled_notification_listeners)
    n=""
    rest="$c"
    while [ -n "$rest" ]; do
      case "$rest" in
        *:*) item="${rest%%:*}"; rest="${rest#*:}" ;;
        *) item="$rest"; rest="" ;;
      esac
      if [ "$item" = "$t" ]; then continue; fi
      if [ -n "$item" ] && [ "$item" != "null" ]; then n="${n:+$n:}$item"; fi
    done
    settings put secure enabled_notification_listeners "$n"
    n2=$(settings get secure enabled_notification_listeners)
    case ":$n2:" in
      *":$t:"*) echo "R|nl|通知使用权|fail|仍在列表中" ;;
      *) echo "R|nl|通知使用权|ok|" ;;
    esac
    ;;

  admin_on)
    o=$(dpm set-active-admin "$P/$E" 2>&1)
    if dumpsys device_policy 2>/dev/null | grep -qF "$P/$E"; then
      echo "R|admin|设备管理员|ok|"
    else
      echo "R|admin|设备管理员|fail|$(echo "$o" | head -n 1 | tr -d '|')"
    fi
    ;;

  admin_off)
    o=$(dpm remove-active-admin "$P/$E" 2>&1)
    r=$?
    if [ $r -eq 0 ]; then
      echo "R|admin|设备管理员|ok|"
    else
      echo "R|admin|设备管理员|fail|$(echo "$o" | head -n 1 | tr -d '|')"
    fi
    ;;

  listdisabled)
    pm list packages -d --user 0 2>/dev/null | sed 's/^package://'
    ;;

  listuninstalled)
    pm list packages -u --user 0 2>/dev/null | sed 's/^package://'
    ;;

  *)
    echo "R|sys|未知动作|fail|$A"
    ;;
esac

exit 0
