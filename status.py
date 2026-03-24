#!/usr/bin/env python3
"""Minimal status server for the USB OTG Disk Manager Android project."""
from http.server import HTTPServer, BaseHTTPRequestHandler
import os

HTML = """<!DOCTYPE html>
<html lang="fr">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>USB OTG Disk Manager</title>
<style>
  * { margin: 0; padding: 0; box-sizing: border-box; }
  body { font-family: 'Segoe UI', sans-serif; background: #0d1117; color: #e6edf3; min-height: 100vh; display: flex; align-items: center; justify-content: center; }
  .card { background: #161b22; border: 1px solid #30363d; border-radius: 12px; padding: 40px 48px; max-width: 680px; width: 90%; }
  .logo { font-size: 48px; margin-bottom: 16px; }
  h1 { font-size: 26px; font-weight: 700; color: #58a6ff; margin-bottom: 8px; }
  .badge { display: inline-block; background: #238636; color: #fff; font-size: 12px; font-weight: 600; padding: 3px 10px; border-radius: 20px; margin-bottom: 24px; }
  .info { background: #0d1117; border: 1px solid #30363d; border-radius: 8px; padding: 16px 20px; margin-bottom: 16px; }
  .info h3 { font-size: 13px; color: #8b949e; text-transform: uppercase; letter-spacing: 0.5px; margin-bottom: 10px; }
  .info p, .info li { font-size: 14px; color: #c9d1d9; line-height: 1.8; }
  .info ul { padding-left: 18px; }
  .build { background: #1f2428; border-left: 3px solid #58a6ff; border-radius: 0 8px 8px 0; padding: 14px 18px; font-family: monospace; font-size: 13px; color: #79c0ff; margin-top: 16px; }
  .note { font-size: 13px; color: #8b949e; margin-top: 20px; text-align: center; }
</style>
</head>
<body>
<div class="card">
  <div class="logo">💾</div>
  <h1>USB OTG Disk Manager</h1>
  <span class="badge">Kotlin · Android · 100%</span>
  <div class="info">
    <h3>Architecture</h3>
    <ul>
      <li>Clean Architecture + MVVM</li>
      <li>Jetpack Compose Material 3</li>
      <li>Hilt Dependency Injection</li>
      <li>Coroutines + StateFlow</li>
      <li>Multi-module: :core :usb :storage :app</li>
    </ul>
  </div>
  <div class="info">
    <h3>Modules</h3>
    <ul>
      <li>:app — UI, Navigation, DI</li>
      <li>:core — Models, Base classes</li>
      <li>:usb — USB OTG device detection (libaums)</li>
      <li>:storage — File operations via SAF</li>
    </ul>
  </div>
  <div class="info">
    <h3>Build & Release</h3>
    <p>APK signé arm64-v8a → GitHub Actions → Release automatique</p>
  </div>
  <div class="build">
    $ ./gradlew assembleRelease
    # CI: .github/workflows/build.yml
  </div>
  <p class="note">Ce projet est 100% Kotlin · Aucun Node.js / TypeScript</p>
</div>
</body>
</html>"""

class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.end_headers()
        self.wfile.write(HTML.encode())
    def log_message(self, format, *args):
        pass

port = int(os.environ.get("PORT", 3000))
print(f"USB OTG Disk Manager — projet Android 100% Kotlin")
print(f"Status page: http://localhost:{port}")
print(f"Build: ./gradlew assembleRelease")
print(f"CI/CD: .github/workflows/build.yml → GitHub Actions")
HTTPServer(("0.0.0.0", port), Handler).serve_forever()
