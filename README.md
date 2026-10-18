# Oneryl

Музыкальный плеер для Android: поиск и прослушивание треков из YouTube Music и
SoundCloud, плеер в шторке и на экране блокировки, лайки, история и очередь.

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
  загрузка звука (`PlayerDataSourceFactory`: YouTube кусками по 1 МБ через кэш,
  SoundCloud как HLS) и `PlayerConnection` для интерфейса.
- `.../ui` — экраны «Главная», «Поиск», «Библиотека», мини-плеер и
  полноэкранный плеер.

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
