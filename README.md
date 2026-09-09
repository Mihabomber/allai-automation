# ALL AI Dola automation

## Поток (итоговый)
1. Android-приложение ждет промпт в ЛС Discord (polling по токену).
   Формат: `PART1:... | PART2:... | прозрачное фото для водяного знака`
2. `DolaAutomationService` (AccessibilityService):
   - открыть DolaAi -> Войти с Google -> выбрать аккаунт[N] на устройстве
   - включить Pro модель
   - вставить: `Generate video Dreamina Seedance 2.5 15 seconds\n{PART1}`
   - OCR ждет статус генерации. При ЛЮБОЙ ошибке — слегка меняет промпт и ретраит.
   - находит видео через OCR -> скачать в MediaStore
   - сохраняет последний кадр (ffmpeg -sseof -1)
   - Удаляет аккаунт (выход / clear app data) -> берет следующий аккаунт
   - PART2 генерирует как Image-to-Video / с `первый кадр = last_frame_part1`
3. Оба mp4 отправляются боту в Discord.
4. Бот (серверная часть, python):
   - склеить part1+part2 (ffmpeg concat)
   - убрать водяной знак Dola (delogo / crop)
   - наложить водяной знак `all ai` + прикрепленное прозрачное PNG, прыгающее по экрану (overlay с формулой x/y через sin/cos от t)
   - вернуть готовое видео в Discord
5. Поверх всех окон — всплывающее окно STOP (FloatingStopService, SYSTEM_ALERT_WINDOW) — останавливает цикл.

## Файлы скелета
- `android/...` — Accessibility + Float stop + OCR helper
- `bot/video_finalize.py` — склейка и водяной знак
