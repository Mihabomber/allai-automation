# 🤖 ALL AI — Dola automation

![build](https://github.com/Mihabomber/allai-automation/actions/workflows/build-apk.yml/badge.svg)

Автоматизация DolaAi на Android: Discord ЛС → генерация PART1/PART2 → склейка → замена водяного знака → возврат в Discord.

## 📥 Скачать APK
**Actions → последний успешный run → Artifact `app-debug`** → установить на телефон.

## 📲 Настройка (важно, иначе вылет/не работает)
1. Установи APK.
2. Открой **ALL AI** → кнопка **1** → разреши *«Поверх других приложений»*.
3. Кнопка **2** → Спец. возможности → включи **ALL AI / DolaAutomation**.
4. Кнопка **3** → появится плавающая **■ STOP BOT**.

## 🔁 Как работает
```
Discord ЛС [PART1]/[PART2] + png-лого
  → DolaAi: Google-login → Pro → Generate (Seedance 2.5, 15s)
  → OCR контроль → скачивание → последний кадр → PART2
  → bot_video_finalize.py: concat + delogo Dola + прыгающий «all ai»
```

## 📁 Структура
```
app/src/main/java/com/allai/automation/
  MainActivity.kt            — экран настройки, больше не падает
  DolaAutomationService.kt   — Accessibility-автоклики
  FloatingStopService.kt     — плавающий STOP
  DiscordPoller.kt           — парсинг [PART1]/[PART2]
bot_video_finalize.py        — склейка и водяной знак
```

## 🛠 Сборка
Пуш в `main` → GitHub Actions собирает `app-debug.apk` автоматически.
