# Extract irv — two-part distribution

Download **both `irv1.zip` and `irv2.zip`** from the same release. Each is an ordinary ZIP. Part 1 contains the source, documentation, and first half of the runnable JAR. Part 2 contains the second half. Both include these instructions and the same assembly script.

## Extract and assemble

1. Put both ZIPs in one folder. Choose a new, empty destination for this release.
2. Extract `irv1.zip` into that destination.
3. Extract `irv2.zip` into the **same destination**, merging its `irv` folder with the first one. Do not replace/delete the entire first `irv` folder. Identical instruction/script files may be overwritten.
4. Run the included assembly script with Python 3. It verifies the complete JAR's SHA-256 before creating `irv/dist/irv.jar`.
5. Open `irv/README.md` and follow setup. No credentials are included.

### macOS / Linux terminal

From the folder containing both downloads:

```sh
mkdir irv-unpacked
unzip -o irv1.zip -d irv-unpacked
unzip -o irv2.zip -d irv-unpacked
python3 irv-unpacked/irv/assemble.py
cd irv-unpacked/irv
```

Use a fresh destination name if `irv-unpacked` already exists. Terminal extraction avoids Finder replacing one complete folder with the other.

### Windows PowerShell

From the folder containing both downloads:

```powershell
New-Item -ItemType Directory -Path irv-unpacked
Expand-Archive -LiteralPath .\irv1.zip -DestinationPath .\irv-unpacked -Force
Expand-Archive -LiteralPath .\irv2.zip -DestinationPath .\irv-unpacked -Force
py -3 .\irv-unpacked\irv\assemble.py
Set-Location .\irv-unpacked\irv
```

If Python is installed as `python` instead, use `python .\irv-unpacked\irv\assemble.py`. Python 3 is also used by the normal secret-generation setup.

## Completion and troubleshooting

The script prints **Reassembled and SHA-256 verified dist/irv.jar** when complete. Re-running is safe if the verified JAR already exists. A missing-part message means the ZIPs were extracted into different folders or one download is missing. A checksum error means the pieces do not match or are corrupt; download both again.

After successful assembly, `irv/dist/irv.jar.part1` and `irv/dist/irv.jar.part2` can be deleted to recover their disk space. Keep the final `irv.jar`. Allow roughly 150 MB of temporary disk space for the downloads, extracted parts, and assembled JAR.

These archives preserve every file from the updated `irv.zip`; only the JAR's packaging changes. Application setup, behavior, and previously documented validation limits remain the same.

Expected JAR SHA-256: `9f39b4619a1e0f36f62a7ceaf6ad2f2dc8d1232e27f87c0e912242b7593e1371`
