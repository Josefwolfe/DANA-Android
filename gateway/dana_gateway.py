#!/usr/bin/env python3
"""Optional self-hosted DANA intent gateway. Python stdlib only; TLS is terminated by a reverse proxy.

The Android app sends just one unknown utterance, never contact databases or saved history.
Protect this gateway using DANA_GATEWAY_TOKEN; never expose the local HTTP port to the Internet.
"""
from __future__ import annotations

import hmac
import json
import os
import re
import time
from collections import defaultdict
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib import request

ALLOWED = frozenset({"TIME", "BATTERY", "OPEN_CAMERA", "OPEN_CONTACTS", "OPEN_APP",
    "CALL", "SMS_DRAFT", "FLASH_ON", "FLASH_OFF", "VOLUME_UP", "VOLUME_DOWN"})
NEEDS_TARGET = frozenset({"OPEN_APP", "CALL", "SMS_DRAFT"})
NEGATED = re.compile(r"(?:^|\s)(?:نکن|نکنید|نزن|نزنید|نگیر|نگیرید|نفرست|نفرستید|نذار|نگذار|نبند|نمی\s*خوام|نمی\s*خواهم)(?:$|\s)")
RATE_LIMIT = 20  # requests per local forwarding peer, per minute; upstream firewall required.
_hits: dict[str, list[float]] = defaultdict(list)


def clean_text(value: object, max_length: int) -> str | None:
    if not isinstance(value, str):
        return None
    cleaned = value.strip()
    if not cleaned or len(cleaned) > max_length or any(ord(c) < 32 for c in cleaned):
        return None
    return cleaned


def is_negated(text: str) -> bool:
    normalized = text.translate(str.maketrans("يك", "یک")).replace("\u200c", " ")
    quoted_sms = re.match(
        r"^(?:(?:سلام دانا|هی دانا|دانا) )?(?:به|برای) .{1,80}? "
        r"(?:بگو|بنویس|خبر بده|پیام بده|پیام بفرست|اس ام اس بفرست)(?: که)? (.+)$",
        normalized,
    )
    if quoted_sms:
        return False  # e.g. «به رضا بگو چراغ رو روشن نکن» is a message, not a cancellation.
    return bool(NEGATED.search(normalized))


def sanitize_response(data: dict) -> dict:
    if not isinstance(data, dict) or data.get("needs_clarification") is True:
        return {"needs_clarification": True}
    action = str(data.get("action", "")).upper()
    if action not in ALLOWED:
        return {"needs_clarification": True}
    result = {"action": action}
    if action in NEEDS_TARGET:
        target = clean_text(data.get("target"), 80)
        if not target:
            return {"needs_clarification": True}
        result["target"] = target
    if action == "SMS_DRAFT":
        message = data.get("message")
        if message is not None:
            body = clean_text(message, 450)
            if body is None:
                return {"needs_clarification": True}
            result["message"] = body
    return result


SYSTEM_PROMPT = """You interpret one spoken Persian Android voice command. You must return ONLY one JSON object.
Allowed action values: TIME, BATTERY, OPEN_CAMERA, OPEN_CONTACTS, OPEN_APP, CALL, SMS_DRAFT,
FLASH_ON, FLASH_OFF, VOLUME_UP, VOLUME_DOWN.
For CALL, OPEN_APP, SMS_DRAFT add a nonempty target (contact name or app name).
For SMS_DRAFT optionally include the exact intended message, preserving negatives inside the message.
If the user is negating the instruction, the intent is ambiguous, it requires unsupported capabilities,
or it is a general question, return {"needs_clarification":true}.
NEVER claim that an action was executed. This is classification, not task execution.
Examples:
"رضا رو بگیر" -> {"action":"CALL","target":"رضا"}
"یه زنگ به رضا بزن" -> {"action":"CALL","target":"رضا"}
"به مریم بگو من دیر می‌رسم" -> {"action":"SMS_DRAFT","target":"مریم","message":"من دیر می‌رسم"}
"چراغ قوه رو روشن نکن" -> {"needs_clarification":true}
"""


def infer(text: str, api_key: str, model: str, timeout: float = 8.0) -> dict:
    if is_negated(text):
        return {"needs_clarification": True}
    body = json.dumps({
        "model": model,
        "messages": [{"role": "system", "content": SYSTEM_PROMPT},
                     {"role": "user", "content": text}],
        "response_format": {"type": "json_object"},
        "max_tokens": 150,
        "store": False,
    }, ensure_ascii=False).encode("utf-8")
    req = request.Request("https://api.openai.com/v1/chat/completions", body,
        headers={"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"}, method="POST")
    with request.urlopen(req, timeout=timeout) as response:
        raw = response.read(32_769)
    if len(raw) > 32_768:
        return {"needs_clarification": True}
    result = json.loads(raw.decode("utf-8"))
    content = result["choices"][0]["message"]["content"]
    return sanitize_response(json.loads(content))


class GatewayHandler(BaseHTTPRequestHandler):
    server_version = "DANA-gateway/1.0"

    def log_message(self, format: str, *args: object) -> None:
        # Don't write spoken text, API credentials, or authorization headers to logs.
        pass

    def send_json(self, code: int, data: dict) -> None:
        encoded = json.dumps(data, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(encoded)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.end_headers()
        self.wfile.write(encoded)

    def do_GET(self) -> None:
        if self.path == "/health":
            self.send_json(200, {"status": "ready"})
        else:
            self.send_json(404, {"error": "not found"})

    def do_POST(self) -> None:
        if self.path != "/dana/intent":
            return self.send_json(404, {"error": "not found"})
        secret = os.environ["DANA_GATEWAY_TOKEN"]
        authorization = self.headers.get("Authorization", "")
        if not hmac.compare_digest(authorization, f"Bearer {secret}"):
            return self.send_json(401, {"error": "unauthorized"})
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            return self.send_json(400, {"error": "invalid length"})
        if not 0 < length <= 2048:
            return self.send_json(413, {"error": "request too large"})
        ip = self.client_address[0]
        now = time.monotonic()
        entries = _hits[ip]
        entries[:] = [t for t in entries if now - t < 60]
        if len(entries) >= RATE_LIMIT:
            return self.send_json(429, {"error": "too many requests"})
        entries.append(now)
        try:
            parsed = json.loads(self.rfile.read(length))
            if not isinstance(parsed, dict):
                return self.send_json(400, {"error": "invalid request"})
            text = clean_text(parsed.get("text"), 500)
            if parsed.get("language") != "fa" or not text:
                return self.send_json(400, {"error": "invalid request"})
            response = infer(text, os.environ["OPENAI_API_KEY"], os.environ["DANA_LLM_MODEL"])
            return self.send_json(200, response)
        except (KeyError, ValueError, TypeError):
            return self.send_json(400, {"error": "invalid request"})
        except Exception:
            # Never expose model/provider exception text or credentials to callers.
            return self.send_json(503, {"error": "provider unavailable"})


def main() -> None:
    if len(os.environ.get("DANA_GATEWAY_TOKEN", "")) < 24:
        raise SystemExit("Set a random DANA_GATEWAY_TOKEN (at least 24 characters) in server environment")
    if not os.environ.get("OPENAI_API_KEY") or not os.environ.get("DANA_LLM_MODEL"):
        raise SystemExit("Set OPENAI_API_KEY and DANA_LLM_MODEL in server environment")
    host = os.environ.get("DANA_BIND", "127.0.0.1")
    port = int(os.environ.get("DANA_PORT", "8080"))
    print(f"DANA gateway listening on {host}:{port}; use an HTTPS reverse proxy for phone access")
    ThreadingHTTPServer((host, port), GatewayHandler).serve_forever()


if __name__ == "__main__":
    main()
