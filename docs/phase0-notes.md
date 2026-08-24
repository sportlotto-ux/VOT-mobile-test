# Фаза 0 — разведка и сборка базы

Дата: 2026-08-23
База: https://codeberg.org/Gaukler_Faun/FOSS_Browser (commit depth=1, ~2026-08-23, versionCode 158 versionName 23)
VOT: https://raw.githubusercontent.com/ilyhalight/voice-over-translation/master/dist/vot.user.js @ 1.11.8 (etag e93406...)
Канал распространения: GitHub Releases (SCRIPT_AUTOUPDATE=true)

---

## 1. Клонирование и сборка

### Клонирование
```bash
git clone --depth 1 https://codeberg.org/Gaukler_Faun/FOSS_Browser.git
# 340 объектов, 10.6 MiB, OK
```
Путь: `/home/user/podman/triada/workspace/vot-browser/FOSS_Browser`

### Структура проекта
- `app/src/main/java/de/baumann/browser/` — 9 пакетов, 58 Java-файлов (совпадает с ТЗ ~58)
  - `activity/` — BrowserActivity, Settings_*, ActivityCustomSearches
  - `browser/` — AdBlock, NinjaWebChromeClient, NinjaWebViewClient, WebAppInterface, BrowserController и т.д.
  - `view/` — NinjaWebView, адаптеры, UI
  - `database/`, `fragment/`, `unit/`, `objects/`, `preferences/`
- `app/build.gradle`: compileSdk 35, minSdk 26, targetSdk 36, AGP 8.9.3, Gradle 8.11.1, Java 1.8 compat
- Уже есть `@JavascriptInterface` мост: `view.NinjaWebView` → `browser.WebAppInterface` (`AndroidInterface`), сейчас только `processBlob(base64, mime, fileName)`

### Сборка APK как есть
- JDK в окружении: OpenJDK 25.0.4 (Red_Hat-25.0.4.0.7-2), совместим с Gradle 8.11.1 (цель 1.8) — должно собираться, но официально AGP 8.9.3 тестировался на JDK 17/21, JDK 25 — риск несовместимости.
- Gradle wrapper скачивается: `https://services.gradle.org/distributions/gradle-8.11.1-all.zip` (~126 МБ) — в окружении без Android SDK скачивание занимает >120с, прервано таймаутом.
- **Android SDK отсутствует** (`ANDROID_HOME`, `ANDROID_SDK_ROOT` не заданы, `sdkmanager` не найден, `/opt/android*` нет). Без SDK (`platforms;android-35`, `build-tools`, `platform-tools`) сборка невозможна локально.
- Попытка `./gradlew tasks` зависает на скачивании дистрибутива Gradle, далее упадет с `SDK location not found`.
- **Вывод гейта:** формально «собрать APK как есть» не выполнено в этом окружении — требуется установка `cmdline-tools` + `sdkmanager --install "platforms;android-35" "build-tools;35.0.0"`. Структура проекта валидна, `gradlew` исполняемый, `settings.gradle` + `build.gradle` корректны. На машине с SDK сборка должна пройти (`./gradlew assembleDebug`).
- **Действие до Фазы 1:** установить Android SDK или собирать в Docker-образе `cirrusci/android-sdk:35` / через `sdkmanager`. Иначе эмпирическая проверка `youtube.com` в WebView невозможна.

### Проверка youtube.com в WebView (ожидаемая, без устройства)
- Код `NinjaWebView.initPreferences(url)` включает JS (`_javascript=true` по умолчанию для профилей), DOM storage, cookies — предпосылки для плеера есть.
- `NinjaWebViewClient.shouldInterceptRequest` режет только по `AdBlock.isAd()` — без эвристик YouTube.
- `BrowserActivity` стартует с `overViewTab` (bookmarks/history) или последней вкладкой; прямой хардкод youtube.com отсутствует — будет добавлен в Фазе 1.
- **Эмпирика требуется на устройстве:** открыть `https://www.youtube.com/watch?v=jNQXAC9IVRw` (десктопный UA vs мобильный) — проверить, играет ли `<video>` в WebView без модификации. По коду мобильный UA = `WebSettings.getDefaultUserAgent()`, десктопный = `Mozilla/5.0 (X11; Linux ...)` переключается профилем `_desktop`.

---

## 2. User-Agent и Client Hints

### Текущая логика UA (NinjaWebView.java:120-145)
```java
String mobile = WebSettings.getDefaultUserAgent(context);
String desktop = "Mozilla/5.0 (X11; Linux " + System.getProperty("os.arch") + ")";
if (sp.getBoolean(profile+"_desktop", false)) ua = desktop else ua = mobile;
```
- Переключатель `_desktop` — пер-профильный, хранится в SharedPreferences. Для `youtube.com` в Фазе 1 нужен принудительный десктопный UA независимо от профиля.
- `getRequestHeaders()` добавляет `DNT:1, Sec-GPC:1, X-Requested-With: com.duckduckgo.mobile.android, Referer, Save-Data` — уже есть перехват заголовков, но не для Client Hints.

### Client Hints
- В ТЗ: сначала эмпирика, потом код. `shouldInterceptRequest` перехватывает ВСЕ сабресурсы включая `*.googlevideo.com` (видеосегменты) — дорого и рискованно.
- **Рекомендация:** в Фазе 0/начале Ф1 на тестовом устройстве с `chrome://inspect` проверить, ломается ли плеер при рассогласовании `User-Agent: desktop` vs `Sec-CH-UA: "Android WebView"` (дефолт). Если не ломается — перехват не делать. Если ломается — добавлять заголовки `Sec-CH-UA`, `Sec-CH-UA-Mobile`, `Sec-CH-UA-Platform` через `shouldInterceptRequest` только для `youtube.com`/`googlevideo.com`, с обходом кэша и без модификации body.
- **Заметка:** `WebSettings` не дает API для Client Hints напрямую — только через `shouldInterceptRequest`/`WebViewCompat`.

---

## 3. Инвентаризация GM_* API (час работы) — КЛЮЧЕВОЙ РЕЗУЛЬТАТ

### Источник
- Каноничный апстрим VOT: `ilyhalight/voice-over-translation`, файл `dist/vot.user.js`, скачан 2026-08-23, версия **1.11.8**, 27731 строк, SHA etag `e93406140e7d2a93d3e458618c4c27f7ce6bd06a93f459c8518d75b4042c8d15` (raw.githubusercontent).
- GreasyFork/SleazyFork — зеркало того же файла (`@downloadURL`/`@updateURL` указывают на raw.githubusercontent). Gist-полифилы: `gm-addstyle-polyfill.js`, `gm-info-polyfill.js`.

### Заголовок // @grant (9 канонических + 5 GM4)
```
// @grant GM_addStyle
// @grant GM_deleteValue
// @grant GM_getValue
// @grant GM_info
// @grant GM_listValues
// @grant GM_notification
// @grant GM_setValue
// @grant GM_xmlhttpRequest
// @grant GM.deleteValue
// @grant GM.getValue
// @grant GM.getValues
// @grant GM.listValues
// @grant GM.notification
// @grant GM.setValue
// @grant GM.xmlHttpRequest
// @grant window.focus
// @require https://gist.githubusercontent.com/ilyhalight/6eb5bb4dffc7ca9e3c57d6933e2452f3/.../gm-addstyle-polyfill.js
```

### Фактический вызов кода (grep -o, uniq -c)
| Символ | Кол-во | Примечание |
|---|---|---|
| `GM_fetch` (внутренняя обертка) | 32 | обертка над GM_xhr/fetch |
| `GM_xmlhttpRequest` (callback-стиль) | 15 | основной транспорт |
| `GM_info` | 12 | `scriptHandler`, `script.version`, `script.name`, `version` |
| `GM.getValue` (GM4 promise) | 5 | |
| `GM.xmlHttpRequest` (GM4 promise) | 4 | фолбек если callback нет |
| `GM_setValue` / `GM.setValue` | 3+3 | |
| `GM_notification` / `GM.notification` | 3/1 | тосты |
| `GM_listValues` / `GM.listValues` | 3/3 | |
| `GM.getValues` | 3 | батч-чтение GM4 |
| `GM_getValue` legacy | 3 | |
| `GM_deleteValue` / `GM.deleteValue` | 3/3 | |
| `GM_addStyle` | 3 | via `globalThis.GM_addStyle` с фолбеком на `document.createElement('style')` |
| `GM_addValueChangeListener` / `GM_removeValueChangeListener` | 2/2 | `globalThis.GM_addValueChangeListener`, `gm.addValueChangeListener` |
| `window.focus` | 1 | grant only |
| `unsafeWindow` | 0 | **не используется** |
| `GM_registerMenuCommand` | 0 | **не используется** — меню Tampermonkey не нужен, нативный UI Фазы 2.7 не требуется |
| `GM_openInTab`, `GM_setClipboard`, `GM_getResource*` | 0 | не используются |

### Деталь по каждому API — что должен покрыть шим GmShim

#### GM_xmlhttpRequest / GM.xmlHttpRequest (критично)
- Контракт — **только колбэки**, не Promise наружу (ТЗ 2.3.0). Внутри `vot.user.js` уже есть `gmXhrFetch()` который оборачивает оба стиля:
  ```js
  GM_xmlhttpRequest({method, url, headers, data, responseType:"blob", timeout,
    onload(resp){ resp.status, resp.statusText, resp.responseHeaders, resp.response, resp.finalUrl },
    onerror, ontimeout, onabort })
  // и GM.xmlHttpRequest({method,url,headers,data,responseType:"blob"}) → Promise<resp>
  ```
- Поля `resp` читаемые кодом: `status`, `statusText`, `response` (Blob), `responseHeaders` (строка), `finalUrl`. В шиме Tampermonkey `responseText`/`readyState` тоже ожидаются — проверить в `gmXhrFetch` `parseResponseHeaders`.
- `onprogress` **не используется** в 1.11.8 (grep 0) — в MVP не реализовывать, задокументировать как ограничение.
- Текущая логика: пытается `callbackGmXhr`, если нет — `promiseGmXhr`, если оба фейлятся — fallback на native `fetch`. В WebView нативный `fetch` к `api.browser.yandex.ru` упадет по CORS → шим обязан предоставить хотя бы один из двух.
- **Требование к мосту:** `@JavascriptInterface nativeFetch(id, url, method, headersJson, body)` + `__votGm.deliver(id, json, kind)` через `evaluateJavascript` (async, без блокировки JS-потока).

#### GM_getValue / GM_setValue / GM_deleteValue / GM_listValues / GM.getValue* (хранение настроек)
- Обертка `votStorage` детектит `typeof GM_getValue === "function"` и `typeof GM === "object"`:
  - legacy sync: `GM_getValue(name, def)`, `GM_setValue(name, value)`, `GM_deleteValue(name)`, `GM_listValues()`
  - GM4 async: `await GM.getValue(name, def)`, `await GM.setValue(name, value)`, `await GM.getValues({k:def})`
- Хранятся: громкости, `translationService`, `detectService`, `countryCode`, `account`, `locale` и т.д.
- Шим должен дать **синхронный** возврат в той же строке JS: `let v = GM_getValue('volume', 50)` — значит канал JS→Java через `@JavascriptInterface` блокирующий возврат (не `evaluateJavascript`). Бэкенд — `SharedPreferences`. Для GM4-ветки — Promise-обертка, но тоже поверх синхронного Java-хранилища.

#### GM_addValueChangeListener / GM_removeValueChangeListener
- Используется: `votStorage.addValueChangeListener("account", (key, old, new)=>{})` в `ChaimuPlayer` логике (строка 22630). Требуется, чтобы шим прокидывал изменения SharedPreferences обратно в JS через `evaluateJavascript` (listener registry).
- Оба варианта: `globalThis.GM_addValueChangeListener` и `gm.addValueChangeListener`.

#### GM_addStyle
- 2 точки: `loadFontsGMAddStyle(cssText)` и `GM_addStyle` для `drive.google.com` фикса. Реализация пустая в WebView без Tampermonkey → фолбек `document.createElement('style')`. Полифил из gist:
  ```js
  if (typeof GM_addStyle=='undefined') this.GM_addStyle=(css)=>{head.appendChild(style); return style;}
  ```
- Шим может не проксировать в Java — достаточно JS-полифила перед инжектом.

#### GM_notification / GM.notification
- `if (typeof GM_notification==="function") GM_notification(details)` — показывает тост при ошибках. В WebView заменить на `Android Toast` через мост или `console.warn` + нативный Snackbar. Не критично для перевода, но без полифила упадет `typeof` check — дать заглушку `()=>{}`.

#### GM_info
- Читается: `GM_info.scriptHandler`, `GM_info.version`, `GM_info.script.version`, `GM_info.script.name`, `GM_info.scriptMetaStr`. Используется для логов и `isProxyOnlyExtension` детекта.
- Полифил из gist (обязателен, т.к. `GM_info` не существует в WebView):
  ```js
  if (typeof GM_info=='undefined') this.GM_info={downloadMode:"native", isIncognito:false, script:{}, scriptHandler:"Other (Polyfill)", version:"0.0.0"}
  ```
- В шиме перед инжектом объявить `window.GM_info` с `script.version="1.11.8"` и `scriptHandler="VOTBrowser"`.

#### window.focus, GM_info.scriptHandler детект
- `scriptHandler` используется для определения `isProxyOnlyExtension` — если `GM_info.scriptHandler` установлен и нет `GM_xmlhttpRequest`, включает прокси-режим. Шим должен выставить консистентный `scriptHandler`.

### Итоговый список для шима GmShim.java + GmShim.js (покрывает ВЕСЬ заголовок)
- `GM_xmlhttpRequest` (callback, onload/onerror/ontimeout/onabort, blob)
- `GM.xmlHttpRequest` (promise)
- `GM_getValue` / `GM.getValue` / `GM.getValues` (sync/async)
- `GM_setValue` / `GM.setValue`
- `GM_deleteValue` / `GM.deleteValue`
- `GM_listValues` / `GM.listValues`
- `GM_addValueChangeListener` / `GM_removeValueChangeListener` (оба неймспейса)
- `GM_addStyle` (JS-полифил)
- `GM_notification` / `GM.notification` (заглушка → Toast)
- `GM_info` (полифил объекта)
- `window.focus` (no-op)
- **Не нужны:** `unsafeWindow`, `GM_registerMenuCommand`, `GM_openInTab`, `GM_setClipboard`, `GM_getResource*`, `onprogress` (не используется)

---

## 4. Публичный API паузы синка vot.js

### Вопрос ТЗ 0.п4: есть ли `window.vot.pauseSync()/resumeSync()`?
**Ответ: НЕТ.**

- Греп по 27731 строкам: `pauseSync`, `resumeSync`, `votTick`, `votHandover`, `votResync`, `window.vot` — 0 совпадений (кроме `globalThis.__votKeyboardNavInitialized`).
- Синхронизация реализована классами `BasePlayer` → `AudioPlayer` / `ChaimuPlayer`, методы `lipSync(mode)`, `handleVideoEvent(event)`, `syncPlay()`, `play()`, `pause()`. Подписка на события `<video>`: `timeupdate`, `playing`, `pause`, `seeked`, `waiting`, `ended` (массив `videoLipSyncEvents`).
- Никакого глобального `window.vot` объекта не экспортируется. `VOTTranslationHandler` — внутренний `class`, не `window`.
- **Вывод:** решение по гонке «скиппер ↔ VOT» из ТЗ 2.4 (3 варианта) — публичный API **отсутствует** → выбирать между (2) monkey-patch внутренней функции до инициализации VOT или (3) принять дребезг как ограничение MVP.
- **Рекомендация:** вариант 3 для MVP (задокументировать), вариант 2 — для последующей итерации: патчить `AudioPlayer.prototype.lipSync` / `handleVideoEvent` до `new VOTTranslationHandler()` — но хрупко (имя класса минимизировано в сборке).

### Протокол хендшейка для Фазы 3
- Так как публичного API нет, протокол из ТЗ 3.3.1 (HANDOVER с `votHandover('native'/'js')`) требует, чтобы инжект-скрипт предоставил чистые функции `votTick()`/`votResync()` без собственного `setInterval`. Это потребует **обернуть** юзерскрипт: остановить его `setInterval` (если есть) при `onStop` и передать управление нативному тику. Текущий `vot.user.js` не имеет `setInterval` для синка — синк событийный, значит нативный тик из Фазы 3 будет конкурировать только по `playbackRate` промотки скиппера (10x) — конфликт уже описан в 2.4.

---

## 5. @connect хосты — вайтлист для NativeHttpBridge

```
yandex.ru, *.yandex.net, disk.yandex.*, api.browser.yandex.ru (подразумевается yandex.ru)
timeweb.cloud, raw.githubusercontent.com, vimeo.com, toil.cc, onrender.com,
workers.dev, eu.cc, cloudflare-dns.com, porntn.com, youtube.com, googlevideo.com,
*.yandex.* (?), plus из vot.user.js: toil.cc воркеры
```
- Дополнительно из кода: `yandex.ru`, `yandex.net`, все поддомены `disk.yandex.*`, `translate.yandex.ru` (icon), `api.browser.yandex.ru` — фактический endpoint перевода.
- **Минимальный вайтлист для моста (ТЗ 2.3):**
  ```
  api.browser.yandex.ru
  *.greasyfork.org        // если загрузка скрипта через greasyfork (сейчас raw.githubusercontent)
  raw.githubusercontent.com
  toil.cc
  *.workers.dev
  *.onrender.com
  eu.cc
  timeweb.cloud
  cloudflare-dns.com
  ```
- Схема только `https`, методы `GET/POST`, таймаут 10s, матчинг с точкой `host.equals(domain) || host.endsWith("."+domain)`.

---

## 6. Supply-chain целостность

- `@require` полифил: `https://gist.githubusercontent.com/ilyhalight/6eb5bb4dffc7ca9e3c57d6933e2452f3/.../gm-addstyle-polyfill.js`
- `@downloadURL`/`@updateURL`: `https://raw.githubusercontent.com/ilyhalight/voice-over-translation/master/dist/vot.user.js`
- Для подписанного манифеста (ТЗ 2.1) каноничный источник — `raw.githubusercontent`, но в схеме с ed25519 рантайм тянет `manifest.json` с контролируемого репозитория мейнтейнера, а не напрямую с GitHub. Пока заготовлен локальный кэш `assets/vot/vot.user.js` (1.11.8).
- Версия `1.11.8` — монотонный `version` в манифесте будет `8` или `11108`.

---

## 7. Критерии гейта Фазы 0

| Критерий | Статус |
|---|---|
| Видео играет в WebView без модификации | ⏳ требует устройства + SDK |
| UA/Client Hints эмпирика | ⏳ требует устройства |
| Список GM_* полный, сверен с исходником | ✅ выполнен (см. раздел 3) |
| pauseSync API проверен | ✅ отсутствует, задокументировано |
| Файл `/docs/phase0-notes.md` создан | ✅ этот файл |
| APK собран как есть | ❌ заблокировано отсутствием Android SDK |

---

## 8. Что передать в Фазу 1 и 2

- **Фаза 1:** править `view/NinjaWebView.java` (десктоп UA для `youtube.com|m.youtube.com|youtu.be`), `activity/BrowserActivity.java` (homepage), `browser/AdBlock.java` (усеченный список для `youtube` flavor), `AndroidManifest.xml` (если нужен `usesCleartextTraffic` — нет, только https).
- **Фаза 2:** шим покрывает 14 грантов (таблица выше), мост `@JavascriptInterface` расширить в `browser/WebAppInterface.java` или новый `vot/NativeHttpBridge.java` + `vot/GmShim.java`/`GmShim.js`, пре-инжект полифилов `GM_info`+`GM_addStyle` до `evaluateJavascript(vot.user.js)`, точка инжекта `NinjaWebViewClient.onPageStarted/onPageFinished`.
- **Фаза 2.4 скиппер:** селекторы `.ytp-ad-skip-button-modern` и т.д. — вынести в `assets/vot/skipping-rules.js`, дребезг принять как ограничение (публичного API нет).

---

## 9. Зависимости для сборки (рекомендация)

```bash
# JDK 17 или 21 (AGP 8.9.3 официально поддерживает 17)
sdk install java 17.0.11-tem

# Android cmdline-tools
mkdir -p $ANDROID_HOME/cmdline-tools
wget https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip -d $ANDROID_HOME/cmdline-tools ...

sdkmanager --install "platforms;android-35" "build-tools;35.0.0" "platform-tools"
./gradlew assembleDebug
```

WebView на тестовом устройстве: проверить `chrome://version` или `WebView.getCurrentWebViewPackage()` — зафиксировать в матрице тестирования Фазы 3.

---

## 10. Открытые вопросы (для README/рисков)

- `GM_addValueChangeListener` — реально ли нужен кросс-вкладковый синк аккаунта в WebView с одним профилем? Если игнорировать — потеря синхронизации настроек между вкладками, но не критично.
- `GM_notification` — заменить на `Snackbar` или `Toast`.
- Троттлинг `evaluateJavascript` в фоне — замерить на Pixel vs MIUI (ТЗ 3.4).
