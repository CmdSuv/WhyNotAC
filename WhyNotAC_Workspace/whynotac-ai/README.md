# whynotac-ai — Kişisel profil, ardışık e-değeri ve vaka önceliklendirme

**Sözleşme:** Oyuncu başına ham paket saklamadan birleştirilebilir ([`RunningMoments.merge`](../whynotac-math)) özet istatistiklerden (`BehavioralFeatureExtractor` + `AccountBaselineModel`) davranış profili tutar, randomize deney sondalarını ([`ProbeScheduler`]) Ville eşitsizliğini koruyan negatif-olmayan test martingali ([`EValueProcess`]) ile değerlendirir ve vaka inceleme sırasını [`AiAdvisor`] üzerinden puanlar (`0..100`).

> [!IMPORTANT]
> [`docs/POLICY.md`](../docs/POLICY.md) uyarınca strictly **cezasızdır (non-punitive)**: yalnızca `S` / `D` sınıfı öncelik puanı ve inceleme ek bilgisi üretir; hiçbir zaman `NEUTRALIZE`, `KICK` veya `BAN` tetiklemez. Temiz oturumlar bağlantı kapanışında profille birleştirilirken, `P-A` veya `P-M` bulgusu almış oturumlar profil zehirlenmesini önlemek için dışlanır; `/whynotac privacy forget <uuid> --yes` tüm profil ve sonda durumunu siler.

## Sınıflar

- `BehavioralFeatureExtractor` (`AI-01`): `click_interval_ticks`, `aim_angular_step_deg`, `tick_delta_ms` ve `horizontal_step_blocks` serilerinin çevrimiçi kaydırmalı momentlerini çıkarır.
- `AccountBaselineModel` (`AI-02`): oturum özetlerini `RunningMoments#merge` ile uzun dönem hesaba yuvarlar, standart-hata küçültmeli z-skoruyla hesap devri / makro profil değişimini ölçer (en zayıf özellik katkısıyla açıklanabilir).
- `EValueProcess` & `ProbeScheduler` (`AI-04`, `AI-05`): savaş sırasında rastgele gecikmeli sonda zamanlar ve ikili sonuçları $P_{H_0}(\sup_t E_t \ge 1/\alpha) \le \alpha$ güvenlik sınırını koruyan test martingalinde biriktirir.
- `AiAdvisor`: `TimelineListener` + `VerdictEngine.CaseListener` cephesi; Paper üzerinde `/whynotac ai <player>` ve `/whynotac cases` `[ai=...]` çıktılarıyla sunulur.
