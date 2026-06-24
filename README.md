# cordova-plugin-ima-tv

Cordova plugin для показа **интерстициальной рекламы** через Google IMA SDK на **Android TV**.

## Установка

```bash
cordova plugin add /path/to/cordova-plugin-ima-tv
```

Или из npm (после публикации):

```bash
cordova plugin add cordova-plugin-ima-tv
```

### Требования

| Зависимость | Версия |
|---|---|
| cordova-android | ≥ 12.0.0 |
| Android minSdk | 23 |
| IMA SDK | 3.39.0 (подключается автоматически через Gradle) |

---

## Использование

### 1. Инициализация

Вызовите один раз при старте экрана/активности. Передайте VAST URL вашей рекламы.

```javascript
IMAPlugin.initialize(
    'https://pubads.g.doubleclick.net/gampad/ads?...', // VAST ad tag URL
    function (eventName, data) {
        console.log('IMA event:', eventName, data);

        switch (eventName) {
            case 'initialized':
                console.log('SDK готов');
                break;
            case 'adStarted':
                console.log('Реклама началась');
                break;
            case 'adCompleted':
            case 'adSkipped':
                console.log('Реклама завершена, продолжаем');
                startContent();
                break;
            case 'allAdsCompleted':
                IMAPlugin.destroy();
                break;
        }
    },
    function (errorMessage) {
        console.error('IMA error:', errorMessage);
        startContent(); // показываем контент даже при ошибке рекламы
    }
);
```

### 2. Показ рекламы

```javascript
IMAPlugin.showAd(function (err) {
    console.error('Ошибка запроса рекламы:', err);
    startContent();
});
```

### 3. Уничтожение

```javascript
IMAPlugin.destroy();
```

---

## Список событий

| Событие | Описание |
|---|---|
| `initialized` | SDK инициализирован, можно вызывать `showAd()` |
| `adStarted` | Воспроизведение рекламы началось |
| `adPaused` | Реклама поставлена на паузу |
| `adResumed` | Воспроизведение возобновлено |
| `adSkipped` | Пользователь пропустил рекламу |
| `adCompleted` | Реклама воспроизведена полностью |
| `allAdsCompleted` | Все рекламные блоки завершены |
| `error` | Ошибка (второй аргумент — сообщение) |

---

## Особенности Android TV

- **Кнопка Skip**: SDK автоматически фокусирует её при доступности (через D-pad).
- **"Почему эта реклама?" (VAST icon)**: Плагин обрабатывает события `ICON_TAPPED` и `ICON_FALLBACK_IMAGE_CLOSED` — при нажатии на иконку открывается модальный диалог, реклама ставится на паузу и возобновляется после закрытия.
- **Кнопка "Learn More"**: Отключена SDK для TV-устройств автоматически.

---

## Тестовые VAST URL (Google)

```
// Preroll skippable
https://pubads.g.doubleclick.net/gampad/ads?iu=/21775744923/external/single_preroll_skippable&sz=640x480&ciu_szs=300x250%2C728x90&gdfp_req=1&output=vast&unviewed_position_start=1&env=vp&correlator=

// Non-skippable
https://pubads.g.doubleclick.net/gampad/ads?iu=/21775744923/external/single_preroll&sz=640x480&ciu_szs=300x250%2C728x90&gdfp_req=1&output=vast&unviewed_position_start=1&env=vp&correlator=
```

---

## Лицензия

Apache 2.0
