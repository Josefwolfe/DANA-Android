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

ALLOWED = frozenset({
    "TIME", "BATTERY", "TODAY_DATE", "HELP", "OPEN_CAMERA", "OPEN_CONTACTS", "OPEN_CALENDAR",
    "OPEN_APP", "CALL", "SMS_DRAFT", "FLASH_ON", "FLASH_OFF", "VOLUME_UP", "VOLUME_DOWN",
    "MUSIC_RANDOM", "MUSIC_SEARCH", "MEDIA_PAUSE", "MEDIA_RESUME", "MEDIA_NEXT",
    "MEDIA_PREVIOUS", "MEDIA_STOP", "WIFI_ON", "WIFI_OFF", "BLUETOOTH_ON", "BLUETOOTH_OFF",
    "WEB_SEARCH", "YOUTUBE_SEARCH", "NAVIGATE", "SET_TIMER", "SET_ALARM", "CHAT_ANSWER",
})
NEEDS_TARGET = frozenset({"OPEN_APP", "CALL", "SMS_DRAFT", "MUSIC_SEARCH", "WEB_SEARCH",
                          "YOUTUBE_SEARCH", "NAVIGATE"})
NEGATED = re.compile(r"(?:^|\s)(?:نکن|نکنید|نزن|نزنید|نگیر|نگیرید|نفرست|نفرستید|نذار|نگذار|نبند|نمی\s*خوام|نمی\s*خواهم)(?:$|\s)")
RATE_LIMIT = 20  # requests per local forwarding peer, per minute; upstream firewall required.
_hits: dict[str, list[float]] = defaultdict(list)


def clean_text(value: object, max_length: int) -> str | None:
    if not isinstance(value, str):
        return None
    if any(ord(c) < 32 or ord(c) == 127 for c in value):
        return None
    cleaned = value.strip()
    if not cleaned or len(cleaned) > max_length:
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
        max_len = 80 if action in {"OPEN_APP", "CALL", "SMS_DRAFT"} else 120
        target = clean_text(data.get("target"), max_len)
        if not target:
            return {"needs_clarification": True}
        result["target"] = target
    if action == "CHAT_ANSWER":
        answer = clean_text(data.get("answer"), 450)
        if not answer:
            return {"needs_clarification": True}
        result["answer"] = answer
    if action == "SMS_DRAFT":
        message = data.get("message")
        if message is not None:
            body = clean_text(message, 450)
            if body is None:
                return {"needs_clarification": True}
            result["message"] = body
    if action in {"SET_ALARM", "SET_TIMER"}:
        label = data.get("message")
        if label is not None:
            clean_label = clean_text(label, 160)
            if not clean_label:
                return {"needs_clarification": True}
            result["message"] = clean_label
    if action == "SET_TIMER":
        minutes = data.get("minutes")
        if type(minutes) is not int or not 1 <= minutes <= 1440:
            return {"needs_clarification": True}
        result["minutes"] = minutes
    if action == "SET_ALARM":
        hour, minute = data.get("hour"), data.get("minute")
        if type(hour) is not int or type(minute) is not int or not (0 <= hour <= 23 and 0 <= minute <= 59):
            return {"needs_clarification": True}
        result["hour"] = hour
        result["minute"] = minute
        day_offset = data.get("day_offset")
        if day_offset is not None:
            if type(day_offset) is not int or not 0 <= day_offset <= 7:
                return {"needs_clarification": True}
            result["day_offset"] = day_offset
    return result


SYSTEM_PROMPT = """You interpret one Persian Android voice command. Return ONLY one JSON object.
You are an intent classifier, NOT a phone operator or general assistant.
Allowed actions: TIME, BATTERY, TODAY_DATE, HELP, OPEN_CAMERA, OPEN_CONTACTS, OPEN_CALENDAR,
OPEN_APP, CALL, SMS_DRAFT, FLASH_ON, FLASH_OFF, VOLUME_UP, VOLUME_DOWN,
MUSIC_RANDOM, MUSIC_SEARCH, MEDIA_PAUSE, MEDIA_RESUME, MEDIA_NEXT, MEDIA_PREVIOUS, MEDIA_STOP,
WIFI_ON, WIFI_OFF, BLUETOOTH_ON, BLUETOOTH_OFF, WEB_SEARCH, YOUTUBE_SEARCH, NAVIGATE,
SET_TIMER, SET_ALARM, CHAT_ANSWER.
CHAT_ANSWER is for ordinary general-knowledge questions only, not device operations. Return a short
accurate Persian answer (max 450 characters) in field answer. If uncertain or asked for live/current
facts you cannot verify, say so. Never claim to have checked the phone, Internet, or private data.
For OPEN_APP, CALL, SMS_DRAFT, MUSIC_SEARCH, WEB_SEARCH, YOUTUBE_SEARCH and NAVIGATE,
return a nonempty target (app name, contact name, track search text, query, destination).
For SMS_DRAFT include optional message, preserving exact intended message text.
For SET_TIMER include integer minutes in 1..1440.
For SET_ALARM include integer hour 0..23, minute 0..59 and optionally day_offset 0..7.
Use day_offset ONLY for an explicit today/tomorrow/number-of-days reference; NEVER guess the date.
For alarms with ambiguous daypart, weekday, or Persian calendar date, return needs_clarification.
For WIFI and BLUETOOTH return the intent; Android may require manual confirmation.
If the device command is negated, ambiguous, unsupported, or unsafe, return
{"needs_clarification":true}. General knowledge questions can use CHAT_ANSWER.
Never invent an Android action or say it was executed.
Examples:
"یه آهنگ پخش کن" -> {"action":"MUSIC_RANDOM"}
"آهنگ شادمهر رو پخش کن" -> {"action":"MUSIC_SEARCH","target":"شادمهر"}
"آهنگ بعدی" -> {"action":"MEDIA_NEXT"}
"بلوتوث رو خاموش کن" -> {"action":"BLUETOOTH_OFF"}
"فردا ساعت ۸ صبح بیدارم کن" -> {"action":"SET_ALARM","hour":8,"minute":0,"day_offset":1}
"تایمر پنج دقیقه" -> {"action":"SET_TIMER","minutes":5}
"پایتخت فرانسه کجاست؟" -> {"action":"CHAT_ANSWER","answer":"پایتخت فرانسه پاریس است."}
"به مریم بگو من دیر می‌رسم" -> {"action":"SMS_DRAFT","target":"مریم","message":"من دیر می‌رسم"}
"چراغ قوه رو روشن نکن" -> {"needs_clarification":true}
"سهم فلان رو بخر" -> {"needs_clarification":true}
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
