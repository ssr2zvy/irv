#!/usr/bin/env python3
"""Reassemble the irv JAR after extracting both distribution ZIPs."""
from pathlib import Path
import hashlib
import os
import sys

EXPECTED = "9f39b4619a1e0f36f62a7ceaf6ad2f2dc8d1232e27f87c0e912242b7593e1371"

def main():
    folder = Path(__file__).resolve().parent / "dist"
    target = folder / "irv.jar"
    if target.exists():
        if hashlib.sha256(target.read_bytes()).hexdigest() == EXPECTED:
            print("irv.jar is already complete and verified. Continue with README.md.")
            return 0
        print("An existing dist/irv.jar differs from this release. Move it aside first.", file=sys.stderr)
        return 1
    parts = [folder / "irv.jar.part1", folder / "irv.jar.part2"]
    missing = [str(p) for p in parts if not p.is_file()]
    if missing:
        print("Extract BOTH irv1.zip and irv2.zip into the SAME destination first. Missing: " + ", ".join(missing), file=sys.stderr)
        return 1
    temporary = folder / "irv.jar.assembling"
    try:
        checksum = hashlib.sha256()
        with temporary.open("xb") as out:
            for part in parts:
                with part.open("rb") as stream:
                    while True:
                        block = stream.read(1024 * 1024)
                        if not block:
                            break
                        checksum.update(block)
                        out.write(block)
        if checksum.hexdigest() != EXPECTED:
            temporary.unlink()
            print("Checksum failed. Re-download and extract both matching parts.", file=sys.stderr)
            return 1
        os.replace(temporary, target)
    except FileExistsError:
        print("dist/irv.jar.assembling already exists. Check for an interrupted or active assembly before removing it and retrying.", file=sys.stderr)
        return 1
    print("Reassembled and SHA-256 verified dist/irv.jar. Continue with README.md.")
    print("You may now delete dist/irv.jar.part1 and dist/irv.jar.part2 to reclaim space.")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
