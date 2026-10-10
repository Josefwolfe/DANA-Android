#!/usr/bin/env python3
"""Download the official pinned Gradle wrapper JAR, verifying its published SHA-256.

The JAR is intentionally NOT reimplemented or bundled without verification.
Requires internet the first time only; subsequent invocations run fully locally.
"""
from pathlib import Path
import hashlib
import os
import sys
import tempfile
import urllib.request

VERSION = "8.9"
EXPECTED = "498495120a03b9a6ab5d155f5de3c8f0d986a449153702fb80fc80e134484f17"
URL = f"https://services.gradle.org/distributions/gradle-{VERSION}-wrapper.jar"
TARGET = Path(__file__).resolve().parents[1] / "gradle/wrapper/gradle-wrapper.jar"


def validate(path):
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    if digest != EXPECTED:
        raise ValueError(f"Gradle wrapper checksum mismatch: {digest}")


def main():
    if TARGET.exists():
        validate(TARGET)
        print("Verified Gradle wrapper JAR: " + str(TARGET))
        return 0
    TARGET.parent.mkdir(parents=True, exist_ok=True)
    fd, tmp = tempfile.mkstemp(prefix="dana-wrapper-", suffix=".jar", dir=TARGET.parent)
    os.close(fd)
    temp = Path(tmp)
    try:
        req = urllib.request.Request(URL, headers={"User-Agent": "DANA-build-bootstrap/1.0"})
        with urllib.request.urlopen(req, timeout=45) as response, temp.open("wb") as out:
            if response.url.split('/')[2] not in {'services.gradle.org', 'downloads.gradle.org', 'github.com', 'release-assets.githubusercontent.com'}:
                raise RuntimeError("Unexpected wrapper download host")
            total = 0
            while True:
                chunk = response.read(64 * 1024)
                if not chunk:
                    break
                total += len(chunk)
                if total > 2 * 1024 * 1024:
                    raise RuntimeError("Wrapper JAR unexpectedly large")
                out.write(chunk)
        validate(temp)
        os.replace(temp, TARGET)
        print("Official Gradle wrapper JAR downloaded and SHA-256 verified")
        return 0
    except Exception as exc:
        print(f"ERROR fetching verified Gradle wrapper: {exc}", file=sys.stderr)
        print("If offline, install Gradle 8.9 and run 'gradle wrapper --gradle-version 8.9'.", file=sys.stderr)
        return 1
    finally:
        if temp.exists():
            temp.unlink()


if __name__ == '__main__':
    raise SystemExit(main())
