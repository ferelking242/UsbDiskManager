#!/usr/bin/env python3
import os, subprocess, sys

token = os.environ.get("GITHUB_TOKEN", "").strip()
if not token:
    print("GITHUB_TOKEN manquant. Ajoute-le dans les Secrets Replit.")
    sys.exit(1)

url = f"https://{token}@github.com/ferelking242/UsbDiskManager.git"
result = subprocess.run(["git", "push", url, "HEAD:main"], capture_output=True, text=True)

if result.returncode == 0:
    print("Push réussi.")
    print(result.stdout)
else:
    print("Erreur push :")
    print(result.stderr)
    sys.exit(1)
