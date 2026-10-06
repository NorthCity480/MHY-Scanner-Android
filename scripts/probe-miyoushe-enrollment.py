#!/usr/bin/env python3
"""Optional unauthenticated availability probe. Never prints tickets or signs in an account."""
import json
import urllib.parse
import urllib.request
import uuid

BASE = "https://passport-api.mihoyo.com/account/ma-cn-passport/app/"
HEADERS = {
    "Content-Type": "application/json", "Accept": "application/json",
    "x-rpc-app_id": "dw9y09jqjpxc", "x-rpc-device_id": str(uuid.uuid4()),
}

def post(operation, body):
    request = urllib.request.Request(BASE + operation, data=json.dumps(body).encode(), headers=HEADERS)
    with urllib.request.urlopen(request, timeout=12) as response:
        result = json.load(response)
    if result.get("retcode") != 0:
        raise RuntimeError("official API retcode=" + str(result.get("retcode")))
    return result["data"]

def main():
    data = post("createQRLogin", {})
    target = urllib.parse.urlparse(data["url"])
    assert target.hostname == "user.mihoyo.com"
    assert target.path == "/login-platform/mobile.html"
    assert target.fragment == "/login/qr"
    assert urllib.parse.parse_qs(target.query).get("token_types") == ["1"]
    print("PASS: Miyoushe passport created a community QR (not a game QR)")
    status = post("queryQRLoginStatus", {"ticket": data["ticket"]})
    assert status["status"] == "Created"
    print("PASS: status=Created; no account was scanned, imported or logged in")

if __name__ == "__main__":
    main()
