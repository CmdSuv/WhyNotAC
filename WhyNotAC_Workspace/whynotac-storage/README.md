# whynotac-storage — Değiştirilemez denetim defteri, profil kalıcılığı ve dört-göz inceleme

**Sözleşme:** Kanıtları ve karar sertifikalarını değiştirilemez biçimde kalıcılaştırır (`STO-01`–`STO-03`, `PROD-13`), dört-göz inceleme/itiraz akışını (`ADM-02`, `VER-15`) uygular ve GDPR unutma taleplerine (`forget`) uyumludur:

- **`AuditLedger` (`STO-01`, `STO-02`, `PROD-13`):** Yalnızca eklenen (`audit-ledger.jsonl`) denetim defteri; her kayıt bir önceki kaydın SHA-256 özetine (`prevHash`), aktif derleme sürümüne (`buildTag`) ve gizli sunucu anahtarlı (`audit-secret.key`, `0600`) HMAC-SHA256 etiketine bağlanır. Oyuncu kimlikleri HMAC takma adı olarak tutulduğundan `forget` silmeleri geçmiş hash zincirini bozmaz.
- **`SubjectProfileStore` (`STO-03`):** Oyuncu başına atomik JSON profili (`profiles/<uuid>.json`), kontrol istatistiklerini ve yeniden başlatmalar arası `FalseBanBudget` olay geçmişini tutar; `/whynotac privacy forget` ile tamamen silinir.
- **`ReviewWorkflow` (`ADM-02`, `VER-15`):** İki farklı inceleyici gerektiren dört-göz onay/itiraz durum makinesi ve sade dilde vaka açıklamaları üretir.
- **`StorageManager`:** Tüm disk yazımlarını analiz ve ana sunucu iş parçacıklarını bloklamadan tek bir asenkron yazar iş parçacığı üzerinden sırayla yürütür.
