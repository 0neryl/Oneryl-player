# Oneryl

Музыкальный плеер для Android: поиск и прослушивание треков из YouTube Music и
SoundCloud, плеер в шторке и на экране блокировки, лайки, история и очередь.
Лайкнутые треки скачиваются на телефон и играют без сети. По желанию можно
войти в YouTube: тогда играют треки 18+ и с VPN реже бывают отказы. Плейлисты
переносятся из Яндекс Музыки по ссылке.

Написан с нуля на Kotlin и Jetpack Compose; звук играет Media3 (ExoPlayer),
треки ищет и находит [NewPipe Extractor](https://github.com/TeamNewPipe/NewPipeExtractor).
Если YouTube отказывает NewPipe (так бывает с адресов VPN), звук берётся через
[InnerTubeX](https://github.com/MetrolistGroup/innertubex) из Metrolist: она
перебирает другие клиенты YouTube и подтверждает запросы BotGuard-токеном,
который выпускает скрытый WebView.

## Установка

Скачайте `Oneryl-<версия>.apk` во вкладке **Releases** и откройте его на
телефоне. Новые версии ставятся поверх старых: лайки, история и очередь
сохраняются.

## Как устроено

- `app/src/main/kotlin/com/oneryl/music/data` — источники: поиск и чарты
  (`MusicCatalog`), получение ссылки на звук (`StreamResolver`), лайки, история
  и очередь в `library.json` (`LibraryStore`).
- `.../playback/youtube` — запасной путь для YouTube: `InnerTubeXSource` и
  выпуск PO-токенов в WebView (`PoTokenWebView`, перенесён из Metrolist).
- `.../playback` — `PlaybackService` (Media3 `MediaSessionService`: фоновое
  воспроизведение, уведомление, кнопка «нравится», восстановление очереди),
  загрузка звука (`PlayerDataSourceFactory`: сначала скачанное, YouTube кусками
  по 1 МБ через кэш, SoundCloud как HLS), скачивание лайкнутых треков
  (`OfflineTracks`, Media3 `DownloadManager`) и `PlayerConnection` для интерфейса.
- `.../ui` — экраны «Главная», «Поиск», «Библиотека», мини-плеер и
  полноэкранный плеер.

## Плейлисты из Яндекс Музыки

«Библиотека» → «Плейлисты» → «Импорт из Яндекс Музыки» и ссылка из кнопки
«Поделиться». Приложение забирает из открытого API Яндекс Музыки названия,
исполнителей и длительность (`YandexMusic`), а каждый трек потом ищет на
YouTube Music и выбирает результат, у которого совпадают название, исполнитель
и длина (`TrackMatcher`). Поиск идёт в фоне по одному треку и переживает
перезапуск (`PlaylistImporter`). Яндекс Музыка отвечает только из России и
соседних стран, поэтому список берётся без VPN, а если YouTube без VPN не
отвечает, поиск ждёт и продолжается сам.

## Вход в YouTube

Вход необязателен и делается в «Библиотеке» на странице Google: пароль
приложение не видит. Оно сохраняет только cookies YouTube, зашифрованные
ключом из хранилища ключей Android, не кладёт их в резервные копии и логи и
отправляет только в YouTube. Аккаунт нужен, когда YouTube отказывает без
него: для треков 18+ и когда адрес VPN не пропускают. Обычные треки по-прежнему
идут без аккаунта. Лучше входить отдельным аккаунтом: сторонние клиенты
нарушают правила YouTube.

## Сборка

Нужны JDK 21 и Android SDK (platform 37).

```bash
./gradlew :app:testDebugUnitTest     # тесты
./gradlew :app:testDebugUnitTest -Ponline   # плюс живые тесты YouTube Music и SoundCloud
./gradlew :app:assembleRelease       # APK в app/build/outputs/apk/release
```

## Выпуск версии

1. Поднимите `oneryl.version` в `gradle.properties` (например, `0.2.0`);
   `versionCode` считается из неё.
2. Запушьте и запустите **Actions → Build → Run workflow** с галочкой
   `release`. CI соберёт APK, проиграет трек на эмуляторе и создаст релиз
   `v<версия>`.

APK подписывается постоянным ключом из секретов репозитория
`ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD` и `ANDROID_KEY_ALIAS`.
Без них релиз не выпускается: APK с другой подписью не встанет поверх старого.

## Лицензия

GPL-3.0, как и NewPipe Extractor, InnerTubeX и код PO-токенов из Metrolist и
NewPipe, на которых держится приложение.
