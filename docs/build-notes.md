# Build notes — Phase 1

## Изменения фазы 1 (YouTube-профиль)

### 1. NinjaWebView.java (`view/NinjaWebView.java:159-183,623`)
- Добавлен `desktopChromeUA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 … Chrome/126.0.0.0 Safari/537.36"`
- Перед выбором UA вызывается `isYouTubeHost(url)` — если хост youtube/youtu.be/youtube-nocookie → форсируется десктопный Chrome UA, `setUseWideViewPort(true)`, `scale 100` независимо от профиля `_desktop`.
- Метод `isYouTubeHost` использует `HelperUnit.domain()` (уже strips www) + fallback на contains, покрывает `youtube.com`, `m.youtube.com`, `www.youtube.com`, `youtu.be`, `youtube-nocookie.com` и поддомены.

### 2. BrowserActivity.java (`activity/BrowserActivity.java:150,329,439,1056,2221,2244,2286`)
- Константа `YOUTUBE_HOME = "https://www.youtube.com"` 
- Все fallback `favoriteURL` дефолты заменены с `https://codeberg.org/.../wiki` на `YOUTUBE_HOME` (6 мест). Ссылки на wiki для помощи оставлены.
- `preference_setting.xml:9` defaultValue тоже `https://www.youtube.com`

### 3. AdBlock.java (`browser/AdBlock.java:33-242`)
- Добавлен `FILE_YOUTUBE = "hosts_youtube.txt"`, `isYouTubeFlavor()` через `BuildConfig.IS_YOUTUBE`, `getHostsFileName()`.
- `AdBlock(Context)` теперь копирует нужный файл из assets в зависимости от flavor, youtube flavor не тянет StevenBlack (skip download), загрузка только из bundled узкого списка.
- `getHostsDate`, `loadHosts` используют `getHostsFileName()`, `downloadHosts` early-exit для youtube.
- Trim + empty check при загрузке hosts.

### 4. build.gradle (`app/build.gradle`)
- `flavorDimensions "mode"` + `productFlavors { full { IS_YOUTUBE false } youtube { IS_YOUTUBE true, suffix .vot } }`
- `buildFeatures.buildConfig true` для генерации BuildConfig

### 5. Assets
- `app/src/main/assets/hosts_youtube.txt` (948 B, 15-20 tracking доменов, без рекламных)
- `app/src/main/assets/hosts.txt` placeholder (будет перезаписан StevenBlack в full flavor)

## Не затронуто (осознанно)
- Cookies — не трогать (ТЗ)
- Client Hints `Sec-CH-UA` перехват — отложен до эмпирики на устройстве (ТЗ: тяжёлая мера, перехватывает googlevideo сегменты)
- Реклама — не режется на уровне hosts в youtube flavor, полностью уходит в JS-скиппер Фазы 2

## Сборка
- SDK отсутствует в окружении (`sdkmanager` нет) — `./gradlew assembleFullDebug` / `assembleYoutubeDebug` требуют `platforms;android-35`. Структура валидна, ошибки компиляции не обнаружены grep-проверкой, но полная сборка — только с SDK.
- Проверить: `./gradlew assembleYoutubeDebug` → APK `app-youtube-debug.apk` должен стартовать на `https://www.youtube.com` без вкладок.

## Критерий фазы 1
Видео играет стабильно, вход в аккаунт работает, реклама на этом этапе присутствует (закрывается в Ф2).
