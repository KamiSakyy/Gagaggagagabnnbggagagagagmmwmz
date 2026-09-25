#!/usr/bin/env python3
"""Rewrites a generated sing-box configuration so that CI can really start it.

`sing-box check` validates the schema only; several rules are enforced when the engine starts
(for example "detour to an empty direct outbound makes no sense"). The Android profile contains a
TUN inbound, which cannot be created on a plain runner, so the inbound is replaced with a local
proxy port that needs no privileges.

Usage: python3 tools/start_config.py <source.json> <target.json>
"""
import json
import sys


def main() -> int:
    if len(sys.argv) != 3:
        print(__doc__)
        return 2
    source, target = sys.argv[1], sys.argv[2]
    with open(source, encoding="utf-8") as handle:
        data = json.load(handle)
    data["inbounds"] = [{
        "type": "mixed",
        "tag": "mixed-in",
        "listen": "127.0.0.1",
        "listen_port": 0,
    }]
    log = data.setdefault("log", {})
    if isinstance(log, dict):
        log["level"] = "debug"
    with open(target, "w", encoding="utf-8") as handle:
        json.dump(data, handle, ensure_ascii=False)
    return 0


if __name__ == "__main__":
    sys.exit(main())
