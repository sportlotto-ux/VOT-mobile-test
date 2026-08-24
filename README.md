# VOT Browser — YouTube с закадровым переводом Яндекса

> Форк [FOSS Browser](https://codeberg.org/Gaukler_Faun/FOSS_Browser) (AGPL-3.0), заточенный под YouTube. Открываешь видео — смотришь с русской озвучкой VOT, без прероллов, с фоновым воспроизведением.

[![Build](https://github.com/sportlotto-ux/VOT-mobile-test/actions/workflows/build.yml/badge.svg)](https://github.com/sportlotto-ux/VOT-mobile-test/actions)
License: AGPL-3.0 (форк) + MIT (VOT) — см. [NOTICE.md](NOTICE.md)

---

## Что это

Android WebView-браузер с инжектом юзерскрипта [voice-over-translation](https://github.com/ilyhalight/voice-over-translation) (1.11.8, MIT):
- кнопка перевода прямо в плеере YouTube
- дрифт ≤0.15s после перемоток
- автопропуск прероллов (JS-скиппер) + скрытие баннеров
- фоновое воспроизведение с MediaSession (управление с нотификации/гарнитуры)
- вход в аккаунт сохранён (подписки, 18+), cookies не режутся

Распространение: **GitHub Releases** (APK `app-youtube-debug` / `release`). F-Droid-ветка планируется с `SCRIPT_AUTOUPDATE=false`.

---

## Отличия от апстрима

| Область | FOSS Browser | VOT Browser (`youtube` flavor) |
|---|---|---|
| Домашняя | `codeberg.org/.../wiki` | `https://www.youtube.com` |
| UA для youtube.com | как у профиля | форсирован десктоп Chrome 126 (`NinjaWebView.isYouTubeHost`) |
| AdBlock | StevenBlack 93k (ежедневное обновление) | узкий трекинг-лист `hosts_youtube.txt` (аналитика, без рекламных — прероллы закрывает скиппер) |
| Флейворы | один APK | `full` (как был) / `youtube` (`.vot`, `IS_YOUTUBE=true`) |
| Инжект | нет | `NinjaWebViewClient.onPageStarted` → `GmShim` + `NativeHttpBridge` (вайтлист, `GM_*` полифилы) |
| Скиппер | нет | `skipping-rules.js` (селекторы `.ytp-ad-*`) — обновляется через манифест |

`full` флейвор остаётся 1-в-1 как апстрим (полный hosts, без VOT).

---

## Установка

1. Скачай APK из Releases (`app-youtube-debug.apk` или `release`).
2. Разреши установку из неизвестных источников.
3. Открой `youtube.com` — проверь что перевод появляется.

Сборка из исходников:

```bash
./gradlew assembleYoutubeDebug   # VOT
./gradlew assembleFullDebug      # оригинал
# или :app:assembleYoutubeRelease
```

Требует JDK 17, Android SDK `platforms;android-35` + `build-tools;35.0.0`, Gradle 8.11.1.

---

## Обновление VOT-скрипта

- **GitHub Releases канал (по умолчанию):** скрипты `vot.user.js` + `skipping-rules.js` обновляются без пересборки APK через подписанный манифест (ed25519, `version` монотонный, downgrade-защита). Источник — `raw.githubusercontent.com/ilyhalight/.../dist/vot.user.js` (проверяется `sha256` + подпись). F-Droid-ветка — статичные `assets/vot/*`.
- Текущий бандл: `app/src/main/assets/vot/vot.user.js` v1.11.8.
- Селекторы скиппера вынесены в `skipping-rules.js` и тоже обновляются через манифест.

---

## ⚠️ Риск бана аккаунта

Скиппер делает активный автоклик/промотку (`video.playbackRate=10`) под залогиненным Google-аккаунтом — поведенчески отличимо от пассивной блокировки. YouTube может связать паттерн с аккаунтом.

> **Вход в аккаунт при включённом скиппере — на свой риск.** Для параноидального режима используй без логина. Подробности — ТЗ Фаза 2.4.

---

## Приватность

- `hosts_youtube.txt` — только аналитика/телеметрия, не рекламные домены. Часть телеметрии YouTube всё равно уходит (осознанный компромисс MVP).
- `DNT:1`, `Sec-GPC:1`, `X-Requested-With` — как в апстриме.
- FOSS Browser сам данные не собирает. См. [PRIVACY.md](PRIVACY.md).

---

## Разработка

- Фаза 0: клон, инвентаризация `GM_*` — см. [docs/phase0-notes.md](docs/phase0-notes.md)
- Фаза 1: YouTube-профиль — см. [docs/build-notes.md](docs/build-notes.md)
- Фаза 2: инжектор + `GmShim` + `NativeHttpBridge` + скиппер
- Фаза 3: FGS `mediaPlayback` + единый нативный тик

Исходники новых модулей (план): `app/src/main/java/de/baumann/browser/vot/` и `app/src/main/assets/vot/`.

---

## Лицензии

- Код браузера — **AGPL-3.0** (как апстрим). См. [LICENSE.md](LICENSE.md).
- VOT юзерскрипт, полифилы — **MIT** (ilyhalight).
- Модификации форка распространяются под AGPL-3.0. См. [NOTICE.md](NOTICE.md).
- Иконка/графика — из апстрима + Material.

---

## Апстрим

- Codeberg: https://codeberg.org/Gaukler_Faun/FOSS_Browser
- Issues апстрима — отдельно, баги форка — в Issues этого репо.
