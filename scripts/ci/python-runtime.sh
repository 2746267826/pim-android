#!/usr/bin/env bash
# Select a Python 3 executable that actually starts; PATH hits alone are insufficient.
pim_contract_python() {
  local candidate version
  for candidate in python3 python; do
    command -v "$candidate" >/dev/null 2>&1 || continue
    version="$("$candidate" -c 'import sys; print("%d.%d" % sys.version_info[:2])' 2>/dev/null)" || continue
    case "$version" in
      3.*) printf '%s' "$candidate"; return 0 ;;
    esac
  done
  return 3
}

pim_contract_require_python() {
  PIM_CONTRACT_PYTHON="$(pim_contract_python)" || {
    printf '%s\n' "需要可运行的 Python 3 解释器。" >&2
    return 2
  }
  return 0
}
