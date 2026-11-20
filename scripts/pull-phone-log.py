# Copyright 2026 Tobi1chi
# SPDX-License-Identifier: Apache-2.0
"""Fetch the OpenRayneo app log from the phone over Wi-Fi (no USB).

On the phone: 설정 → DSH Go 연결 → 기록 동기화 켜기 → "동기화 주소와 전용 토큰 복사".
Paste that text (two lines: address, "Authorization: Bearer …") into analysis/phone-sync.txt once,
or pass it with --setup. Then:  uv run python scripts/pull-phone-log.py
The log is saved under analysis/phone-logs/ and its last lines are printed.
"""
import argparse
import datetime
import pathlib
import sys
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent
CONFIG = ROOT / "analysis" / "phone-sync.txt"
OUT = ROOT / "analysis" / "phone-logs"


def read_config() -> tuple[str, str]:
    lines = [l.strip() for l in CONFIG.read_text(encoding="utf-8").splitlines() if l.strip()]
    address = next(l for l in lines if l.startswith("http")).rstrip("/")
    token = next(l for l in lines if l.lower().startswith("authorization:")).split(":", 1)[1].strip()
    return address, token


def main() -> None:
    sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--setup", help="the copied address+token text (saved to analysis/phone-sync.txt)")
    parser.add_argument("--bytes", type=int, default=2_000_000, help="newest bytes to fetch (1000-2000000)")
    parser.add_argument("--tail", type=int, default=40, help="lines to print")
    args = parser.parse_args()
    if args.setup:
        CONFIG.parent.mkdir(parents=True, exist_ok=True)
        CONFIG.write_text(args.setup.replace("\\n", "\n") + "\n", encoding="utf-8")
    if not CONFIG.exists():
        sys.exit(f"{CONFIG} is missing: paste the phone's sync address and token there (see --help)")
    address, token = read_config()
    request = urllib.request.Request(f"{address}/v1/sync/logs?bytes={args.bytes}", headers={"Authorization": token})
    with urllib.request.urlopen(request, timeout=15) as response:
        text = response.read().decode("utf-8")
    OUT.mkdir(parents=True, exist_ok=True)
    path = OUT / f"app-{datetime.datetime.now():%Y%m%d-%H%M%S}.log"
    path.write_text(text, encoding="utf-8")
    print("\n".join(text.splitlines()[-args.tail:]))
    print(f"\n{len(text.encode('utf-8'))} bytes -> {path}")


if __name__ == "__main__":
    main()
