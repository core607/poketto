"""HTTP client executed in independent Linux network namespaces by the proxy gate."""
import http.cookiejar
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request

base, action = sys.argv[1:3]
jar = http.cookiejar.CookieJar()
http = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPCookieProcessor(jar))
with http.open(base + "/api/auth/csrf", timeout=10) as response:
    csrf = json.load(response)


def login(username, index=0, password="wrong-fixture-password"):
    headers = {
        csrf["headerName"]: csrf["token"],
        "Origin": "http://gateway",
        "Content-Type": "application/x-www-form-urlencoded",
        "X-Forwarded-For": f"198.51.100.{index % 200 + 1}, 203.0.113.10",
        "Forwarded": f"for=198.51.100.{index % 200 + 1};proto=https",
        "X-Forwarded-Proto": "https",
        "X-Forwarded-Port": "7777",
    }
    body = urllib.parse.urlencode({"username": username, "password": password}).encode()
    try:
        with http.open(urllib.request.Request(base + "/api/auth/login", body, headers), timeout=15) as response:
            return response.status
    except urllib.error.HTTPError as error:
        return error.code


if action == "probe":
    status = login("owner", password=os.environ["FIXTURE_PASSWORD"])
    if status != 204:
        raise AssertionError(f"probe login returned {status}")
    request = urllib.request.Request(base + "/api/auth/forwarding-probe", headers={
        "X-Forwarded-For": "198.51.100.250", "X-Forwarded-Port": "7777", "X-Forwarded-Proto": "https",
    })
    with http.open(request, timeout=10) as response:
        address, port, secure = response.read(256).decode().splitlines()
    print(json.dumps({"address": address, "port": int(port), "secure": secure == "true"}))
else:
    count, prefix, same = int(sys.argv[3]), sys.argv[4], sys.argv[5] == "same"
    print(json.dumps({"statuses": [login(prefix if same else f"{prefix}-{i}", i) for i in range(count)]}))
