# App Lock (Android 5.0+ Uyumlu Uygulama Kilitleme Uygulaması)

Bu proje, eski/düşük donanımlı Android cihazlar (minSdk 21 / Android 5.0
Lollipop) için optimize edilmiş, tamamen çevrimdışı çalışan bir uygulama
kilitleme (App Lock) uygulamasıdır.

## Nasıl Açılır

1. Android Studio'yu aç → **Open** → bu klasörü (`AppLock/`) seç.
2. Gradle senkronizasyonunun bitmesini bekle.
3. `app` modülünü minSdk 21+ bir cihaz/emülatörde çalıştır.

## Mimari Özeti

| Katman | Dosya | Görev |
|---|---|---|
| UI - Liste | `MainActivity.kt`, `AppListAdapter.kt` | Yüklü uygulamaları listeler, kilit açma/kapama toggle'ı |
| UI - Kurulum | `SetupPinActivity.kt` | İlk açılışta 4 haneli PIN belirleme |
| UI - Kilit Ekranı | `LockScreenActivity.kt`, `PatternLockView.kt` | PIN veya Desen ile kilit açma ekranı |
| Arka plan izleme | `AppLockService.kt` | Foreground Service; `UsageStatsManager` ile ön plandaki uygulamayı tespit eder |
| Kalıcılık | `PrefsHelper.kt` | SharedPreferences; PIN/desen SHA-256 + tuz (salt) ile hash'lenerek saklanır (açık metin **asla** saklanmaz) |
| Boot | `BootReceiver.kt` | Cihaz yeniden başladığında servisi otomatik ayağa kaldırır |
| İzinler | `PermissionUtils.kt` | `SYSTEM_ALERT_WINDOW` ve `PACKAGE_USAGE_STATS` özel izinlerinin kontrolü |

## Gerekli İzinler ve Neden Gerekli Oldukları

- **`SYSTEM_ALERT_WINDOW`**: Kilit ekranının diğer uygulamaların üzerinde tam
  ekran gösterilebilmesi için. Kullanıcı, `Settings.ACTION_MANAGE_OVERLAY_PERMISSION`
  ekranından elle onaylamalıdır (API 23+).
- **`PACKAGE_USAGE_STATS`**: Hangi uygulamanın ön planda olduğunu tespit
  etmek için. `Settings.ACTION_USAGE_ACCESS_SETTINGS` ekranından elle
  onaylanır — normal izin akışıyla otomatik verilmez.
- **`RECEIVE_BOOT_COMPLETED`**: Cihaz yeniden başladığında koruma servisinin
  otomatik olarak yeniden başlaması için.
- **`FOREGROUND_SERVICE`** / **`FOREGROUND_SERVICE_SPECIAL_USE`**: İzleme
  servisinin arka planda güvenilir şekilde çalışabilmesi için (Android 8+ ve
  Android 14 gereksinimleri).

## Performans Notları (Eski Cihaz Uyumluluğu)

- Uygulama listesi ana thread'i bloklamadan arka planda (`kotlin.concurrent.thread`)
  yüklenir.
- Ön plan uygulaması tespiti, ağır olan `queryUsageStats` yerine hafif
  `UsageEvents` (`queryEvents`) API'si ile 800ms aralıklarla yapılır.
- Kilit ekranı ve desen çizimi native `Canvas` ile yapılır; harici ağır
  kütüphane bağımlılığı yoktur.
- Uygulama tamamen çevrimdışı çalışır; hiçbir ağ izni istenmez.

## Bilinen Sınırlamalar

- `UsageStatsManager` tabanlı izleme, kök (root) erişimi olmayan cihazlarda
  en pratik yöntemdir; alternatif olarak `AccessibilityService` de
  kullanılabilir ancak Play Store politikaları gereği onay süreci daha
  zordur. Bu proje `UsageStatsManager` yöntemini tercih eder.
- Donanım "Son Uygulamalar" (Recent Apps) ekranından uygulamanın önizlemesi
  kilitlenmeden önce kısa süreliğine görünebilir; bu, işletim sisteminin
  kendi davranışıdır ve üçüncü parti uygulamalarca tam olarak engellenemez.
