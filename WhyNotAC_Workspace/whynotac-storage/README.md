# whynotac-storage — Kalıcılık (Faz 4, planlı)

**Sözleşme:** Oyuncu profillerini (özet istatistikler), kanıt kayıtlarını ve karar geçmişini
saklar. Oyun ve ağ thread'lerini asla bloklamaz (asenkron yazma, sınırlı kuyruk).

Hedefler: gömülü varsayılan (tek sunucu), ağ ortamları için harici veritabanı (çok sunucu,
ortak profil), şema sürümleme, kişisel veri için saklama süresi politikası.
