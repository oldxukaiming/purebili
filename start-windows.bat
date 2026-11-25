@echo off
cd /d "%~dp0desktop"
if not exist "dist\index.html" (
  echo Building frontend...
  call npm run build
)
start "" /b node server.mjs
