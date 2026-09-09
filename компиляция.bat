@echo off
rem Компиляция проекта: проверяет все .ncode без запуска
rem Пути в отчёте — от корня проекта, дальше строка и ошибка
chcp 65001 >nul
cd /d "%~dp0"
setlocal EnableDelayedExpansion
set ROOT=%CD%
set ВСЕГО=0
set ПЛОХИХ=0
for /r %%f in (*.ncode) do (
  set F=%%f
  set REL=!F:%ROOT%\=!
  set /a ВСЕГО+=1
  java -Dfile.encoding=UTF-8 -jar "ncode.jar" --проверить "!REL!" || set /a ПЛОХИХ+=1
)
echo Проверено файлов: %ВСЕГО%, с ошибками: %ПЛОХИХ%
exit /b %ПЛОХИХ%
