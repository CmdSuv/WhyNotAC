# Regresyon kümesi (`VAL-10`)

Dürüst oturumların kayıtları. `RegressionReplayTest` (`whynotac-analysis`) her derlemede, gönderilen her kontrolü taze
replikaların arkasında bunların her birinde yeniden oynatır ve hiçbir şey bulmamasını ister: ne kanıt ne de kabul
edilebilirlik kapısının reddedeceği bir aday. Aynı oturuma oturumun ortasında yarım blok yukarı adım konunca hareket
kontrolü onu bulmalıdır; kapı boşuna geçmez.

Canlı CI oturumları her çalıştırmada farklıdır (zamanlama, varlıklar, interpolasyon). Bir kontrolün bir oturumda
yaptığı hata sonraki çalıştırmada görünmeyebilir; burada kalıcı olarak yakalanır.

| Kayıt | Kaynak | Neden burada |
|---|---|---|
| `2026-10-24-realclient-local.wnrec` | `tools/realclient`, yerel çalıştırma | Hareket, tekne, elytra, toz kar, dünya sınırı geliştirmesinin ana oturumu |
| `2026-10-28-ci-c28207e-boat-mount-interpolating.wnrec` | CI, c28207e (pull_request çalıştırması) | Tekneye interpolasyonun üç adımı kalmışken binildi; `fromEntity` hedefi gönderir. Düzeltmesiz `BoatRide` tick 11856'da yanlış bulgu verir |
| `2026-10-28-ci-397905e.wnrec` | CI, 397905e | Yeni doğruluk ajanının ilk oturumu (tikten sonra hâlâ interpolasyonda iki tekne tiki) |

## Ekleme kuralı

- Yalnızca dürüst oturumlar: vanilla client, sentetik hesap (`tools/realclient`'in çevrimdışı `RealProbe`'u), yerel
  sunucu. Gerçek bir oyuncunun kaydı buraya girmez (KVKK, [docs/privacy](../docs/privacy/README.md)).
- Bir kontrolün hatasını ortaya çıkaran her CI oturumu (indirilen `real-client` artifact'ının
  `plugins/WhyNotAC/recordings/` altı) düzeltmesiyle birlikte eklenir; tabloya kaynağı ve nedeni yazılır.
- Kapı, düzeltme geri alınınca o kayıtta kırılmalıdır (eklerken bir kez denenir).
