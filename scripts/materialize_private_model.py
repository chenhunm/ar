#!/usr/bin/env python3
"""Reassemble the authorized personal avatar without network access."""
import hashlib
import json
import os
from pathlib import Path
import re
import sys
import tempfile

def materialize(root):
    source = root / "private-model"
    metadata = json.loads((source / "manifest.json").read_text(encoding="utf-8"))
    size = metadata["bytes"]
    entries = metadata["parts"]
    if not (0 < size <= 64 * 1024 * 1024 and 0 < len(entries) <= 128):
        raise ValueError("Invalid archive budget")
    destination = root / "app/src/main/assets/models/user-default.7z"
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(dir=destination.parent, delete=False) as output:
            temporary = Path(output.name)
            digest = hashlib.sha256()
            written = 0
            for index, entry in enumerate(entries):
                name = entry["name"]
                if not re.fullmatch(r"user-default\.7z\.part[0-9]{3}", name) or name != f"user-default.7z.part{index:03d}":
                    raise ValueError("Invalid chunk name/order")
                part = source / "parts" / name
                if part.is_symlink() or not part.is_file():
                    raise ValueError(f"Missing avatar chunk {index}")
                content = part.read_bytes()
                git_hash = hashlib.sha1(b"blob " + str(len(content)).encode() + b"\0" + content).hexdigest()
                if len(content) != entry["bytes"] or git_hash != entry["git_sha"]:
                    raise ValueError(f"Avatar chunk {index} checksum mismatch")
                written += len(content)
                if written > size:
                    raise ValueError("Avatar size exceeded")
                digest.update(content)
                output.write(content)
            if written != size or digest.hexdigest() != metadata["sha256"]:
                raise ValueError("Reassembled avatar checksum mismatch")
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, destination)
        temporary = None
        return destination
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)

if __name__ == "__main__":
    root = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else Path(__file__).resolve().parents[1]
    result = materialize(root)
    print(f"Verified personal avatar: {result.name}, {result.stat().st_size} bytes")
