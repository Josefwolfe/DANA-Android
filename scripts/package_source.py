#!/usr/bin/env python3
"""Generate a deterministic, secret-free source delivery ZIP from this project tree."""
from __future__ import annotations

from pathlib import Path
import hashlib
import zipfile

root = Path(__file__).resolve().parents[1]
output = root.parent / "DANA-Android-v1.9.1-Offline-WakeFix-FULL-SOURCE.zip"
excluded_names = {".git", ".gradle", ".idea", "build", "__pycache__", ".DS_Store",
                  "local.properties", ".env", "gradle-wrapper.jar", "*.keystore", "*.jks", "google-services.json"}

def ignored(rel: Path) -> bool:
    return any(part in excluded_names or part.startswith(".env.") or part.endswith((".keystore", ".jks", ".pyc"))
               for part in rel.parts)

files = sorted((x for x in root.rglob("*") if x.is_file() and not ignored(x.relative_to(root))
                and x.name != "SHA256SUMS.txt"), key=lambda x: x.relative_to(root).as_posix())
sums = [(hashlib.sha256(file.read_bytes()).hexdigest(), file.relative_to(root).as_posix()) for file in files]
(root / "SHA256SUMS.txt").write_text("\n".join(f"{digest}  {path}" for digest, path in sums) + "\n", encoding="utf-8")
files.append(root / "SHA256SUMS.txt")
with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
    for file in files:
        rel = file.relative_to(root).as_posix()
        info = zipfile.ZipInfo("DANA-Android/" + rel, date_time=(2026, 10, 10, 0, 0, 0))
        info.compress_type = zipfile.ZIP_DEFLATED
        info.external_attr = (0o755 if file.suffix in {".sh", ".py"} or file.name == "gradlew"
                              else 0o644) << 16
        z.writestr(info, file.read_bytes(), compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)
with zipfile.ZipFile(output) as z:
    assert z.testzip() is None
    assert "DANA-Android/app/src/main/AndroidManifest.xml" in z.namelist()
sha = hashlib.sha256(output.read_bytes()).hexdigest()
(output.with_name(output.name + ".sha256")).write_text(f"{sha}  {output.name}\n", encoding="ascii")
print(f"SOURCE_ZIP={output}\nFILES={len(files)}\nBYTES={output.stat().st_size}\nSHA256={sha}\nZIP_INTEGRITY=PASS")
