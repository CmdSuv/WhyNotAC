# whynotac-ai — Kişisel profil, ardışık e-değeri ve vaka önceliklendirme

**Sözleşme:** Oyuncu başına ham paket saklamadan birleştirilebilir ([`RunningMoments.merge`](../whynotac-math)) özet istatistiklerden (`BehavioralFeatureExtractor` + `AccountBaselineModel`) davranış profili tutar, randomize deney sondalarını ([`ProbeScheduler`]) Ville eşitsizliğini koruyan negatif-olmayan test martingali ([`EValueProcess`]) ile değerlendirir ve vaka inceleme sırasını [`AiAdvisor`] üzerinden puanlar (`0..100`).

> [!IMPORTANT]
> [`docs/POLICY.md`](../docs/POLICY.md) uyarınca strictly **cezasızdır (non-punitive)**: yalnızca `S` / `D` sınıfı öncelik puanı ve inceleme ek bilgisi üretir; hiçbir zaman `NEUTRALIZE`, `KICK` veya `BAN` tetiklemez. Temiz oturumlar bağlantı kapanışında profille birleştirilirken, `P-A` veya `P-M` bulgusu almış oturumlar profil zehirlenmesini önlemek için dışlanır; `/whynotac privacy forget <uuid> --yes` tüm profil ve sonda durumunu siler.

## Sınıflar

- `BehavioralFeatureExtractor` (`AI-01`): 4 genel oturum serisi (`click_interval_ticks`, `aim_angular_step_deg`, `tick_delta_ms`, `horizontal_step_blocks`) ile 4 savaş-koşullu / çapraz özelliği (`combat_aim_step_deg`, `combat_aim_jerk_deg`, `attack_snap_ratio`, `container_click_interval_ticks`) çevrimiçi kaydırmalı momentlerle çıkarır.
- `AccountBaselineModel` (`AI-02`): oturum özetlerini `RunningMoments#merge` ile uzun dönem hesaba yuvarlar; hem ortalama kaymasını (`zMean`) hem de aynı ortalamada varyans çökmesini / robotik düzenli ritmi (`zSpread`) birlikte değerlendirerek hesap devri / makro profil değişimini ölçer.
- `EValueProcess` & `ProbeScheduler` (`AI-04`, `AI-05`): savaş sırasında `DECOY_LOS_CHECK`, `RANDOMIZED_HITBOX_OFFSET` ve `TARGET_SWAP_CADENCE` modlarına göre vanilla `3.0` blok erişim sınırı içinde (`2.05..2.70` blok) ve kamera görüş açısı arkasında (`|yawOffset| >= 130°`) rastgele geometrili sonda planlar; ikili sonuçları $P_{H_0}(\sup_t E_t \ge 1/\alpha) \le \alpha$ güvenlik sınırını koruyan test martingalinde biriktirir.
- `AiAdvisor`: geçmiş baz çizgisi olmadan da oturum içi çapraz anomali riskini (`IntrinsicRiskAssessment`: `low-click-variance`, `zero-jerk-combat-aim`, `attack-snap-acceleration`, `rapid-container-cadence`) hesaplar; Paper üzerinde `/whynotac ai <player>` ve `/whynotac cases` `[ai=...]` çıktılarıyla sunulur.
