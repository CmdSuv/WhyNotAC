# WhyNotAC Operatör ve Canlı Pilot Kılavuzu (`1.21.11`)

Bu kılavuz, WhyNotAC'yi tekli **Paper `1.21.11`** veya **Velocity `3.4.0` + Paper `1.21.11`** ağında güvenli `SHADOW` gözlemi veya korumalı `mitigate-pilot` profiliyle devreye almak için hazırlanmıştır.

---

## 1. Hızlı Kurulum (Tekli Paper `1.21.11`)

1. Eklenti JAR'ını üretin veya `whynotac-paper/build/libs/whynotac-paper-*.jar` dosyasını sunucunun `plugins/WhyNotAC.jar` konumuna kopyalayın.
2. İlk pilot haftası için [`deploy/configs/paper-shadow/config.yml`](../deploy/configs/paper-shadow/config.yml) şablonunu `plugins/WhyNotAC/config.yml` olarak yerleştirin:
   - Tüm kontroller `SHADOW` (`armed-checks: []`) kalır; otomatik kick/ban/etkisizleştirme kapalıdır.
   - `recording.enabled: true` takma-adlı `.wnrec` kayıtları tutar (`max-age-days: 14`, `max-total-megabytes: 4096`).
   - `observability.metrics-http-port: 9108` yalnızca `127.0.0.1:9108` üzerinde OpenMetrics (`/metrics`) ve JSON sağlık (`/health`) uçlarını açar.
3. Sunucuyu başlatıp konsoldan doğrulayın:
   - `/whynotac health` -> `GREEN`
   - `/whynotac verdict` -> `armedChecks=[]`
   - `/whynotac stats` -> `listenerErrors=0`, `lostEvents=0`

---

## 2. Çok-Sunuculu Velocity + Paper Kurulumu (`NET-01` / `NET-22`)

1. Velocity proxy'ye `whynotac-velocity/build/libs/whynotac-velocity-*.jar` eklentisini koyun.
2. Velocity'yi bir kez başlatın; `plugins/whynotac-velocity/network-secret.key` (32 bayt HMAC-SHA256 anahtarı) oluşur.
3. Aynı `network-secret.key` dosyasını tüm arka uç Paper sunucularında `plugins/WhyNotAC/network-secret.key` yoluna kopyalayın (**dosyayı herkese açık tutmayın**).
4. Arka uç Paper sunucularında [`deploy/configs/paper-velocity-backend/config.yml`](../deploy/configs/paper-velocity-backend/config.yml) şablonunu (`network-bridge.enabled: true`) kullanın ve Paper `config/paper-global.yml` içinde `proxies.velocity.enabled: true` ayarının açık olduğundan emin olun.
5. Doğrulama:
   - Velocity konsolunda: `wnacproxy status` (`sentEdges >= 1`, `rejected=0`)
   - Paper konsolunda: `whynotac network` (`valid >= 1`, `rejected=0`)

---

## 3. Offline Mod + Giriş Eklentisi Kimlik Katmanı

Offline-mode sunucularda [`identity.trust-auth-plugins: true`](../deploy/configs/paper-shadow/config.yml) ayarı `AuthMe`, `nLogin`, `LibreLogin` ve `OpeNLogin` eklentilerini algılar:
- Oyuncu şifresini girene dek kimlik `UNAUTHENTICATED` kalır (otomatik kalıcı profil/ban açılmaz).
- Giriş doğrulandığı andan itibaren oturum `AUTH_PLUGIN_CONFIRMED` katmanına yükselir ([`POLICY.md`](POLICY.md)).

---

## 4. Aşamalı Pilot -> `mitigate-pilot` -> `ARMED` Geçişi

1. **1–2 Hafta `SHADOW` Gözlemi:**
   - Hiçbir dürüst oyuncuda yanlış pozitif açılmadığını doğrulayın (`/whynotac cases`, `/whynotac stats` -> `rejectedCandidates`).
   - Herhangi bir şüpheli vaka düşerse çevrimdışı inceleyin:
     ```bash
     java -jar WhyNotAC.jar audit plugins/WhyNotAC/storage
     java -jar WhyNotAC.jar verify plugins/WhyNotAC/cases/<case-id>.json
     java -jar WhyNotAC.jar explain plugins/WhyNotAC/cases/<case-id>.json
     ```
2. **Yumuşak Etkisizleştirme Pilotu (`mitigate-pilot`):**
   - [`deploy/configs/paper-mitigate-pilot/config.yml`](../deploy/configs/paper-mitigate-pilot/config.yml) şablonu `attack-reach`, `attack-aim`, `break-time`, `use-aim`, `movement` ve `design-probe` kontrollerini `ARMED` + `mitigations: true` + `hide-information: true` ile açar; **`auto-kick: false` ve `auto-ban: false`** korur.
   - `ClickPatternCheck`, `AimPatternCheck`, `ContainerTimingCheck` (`S`) ve `DesignProbeEngine` (`D`) sınıfsal olarak asla ban, kick veya etkisizleştirme tetiklemez (`ActionPolicy`).
3. **İki-Gözlü İnceleme & Acil Durdurma:**
   - İnceleme iş akışı: `/whynotac review <case-prefix> pass1|confirm|appeal|uphold|overturn [not]` (`overturn` yanlış ban bütçesini kaydeder, bekleyen ban dalgasını iptal eder ve proxy üzerinden diğer sunuculara duyurur).
   - Acil kapatma: `/whynotac killswitch on` (tüm yaptırımı anında `SHADOW`'a çeker).

---

## 5. Komut ve Çevrimdışı Araç Referansı

### Sunucu / Yetkili Komutları (`/whynotac ...`)
| Komut | İşlev |
|---|---|
| `/whynotac inspect <oyuncu>` | Canlı RTT, transaction sayacı, görünüm (`WIRE`/`GAME`), rotasyon ve anomali özeti |
| `/whynotac pipeline <oyuncu>` | Netty handler sırası (`whynotac_wire_in`, `whynotac_wire_out`, `whynotac_interceptor`) |
| `/whynotac world|entities|inventory|self <oyuncu>` | Replikaların Paper sunucu durumuyla canlı karşılaştırması |
| `/whynotac ai <oyuncu>` | Cezasız davranışsal sapma özellikleri, AI öncelik puanı ve e-süreci özeti |
| `/whynotac cases` / `/whynotac case <önek>` | Son açılan vakalar ve dosya bilgisi |
| `/whynotac review <önek> <pass1|confirm|appeal|uphold|overturn>` | İki farklı yetkili gerektiren inceleme/itiraz süreci |
| `/whynotac health` / `/whynotac metrics` / `/whynotac stats` | Sağlık (`GREEN`/`DEGRADED`/`CRITICAL`), OpenMetrics ve JSON telemetri |
| `/whynotac verdict` / `/whynotac killswitch [on|off]` / `/whynotac circuit [reset]` | Karar politikası durumu, devre kesici ve ban bütçesi yönetimi |
| `/whynotac audit` / `/whynotac network` / `/whynotac sessions` | İmzalı hash zinciri doğrulama, proxy köprü sayaçları, aktif oturumlar |
| `/whynotac privacy <find|forget> <uuid>` | GDPR/KVKK veri bulma ve kalıcı silme talebi |

### Çevrimdışı JAR CLI (`java -jar WhyNotAC.jar ...`)
- `verify <dossier.json>` — Kayıtlı `.wnrec` üzerinden bağımsız replikada bulguyu tekrar üretir ve parmak izini doğrular.
- `explain <dossier.json>` — İnsan tarafından okunabilir inceleme ve itiraz özetini basar.
- `audit <storage-dir>` — `plugins/WhyNotAC/storage` dizinindeki HMAC/hash zincirli denetim defterinin bütünlüğünü doğrular.
- `inspect <file.wnrec>` / `dump <file.wnrec>` / `find ...` / `forget ...` — Ham kayıt ve mahremiyet araçları ([`docs/privacy`](privacy/README.md)).

---

## 6. Prometheus & Alerting Kurulumu

[`deploy/monitoring/prometheus.yml`](../deploy/monitoring/prometheus.yml) ve [`deploy/monitoring/alerts.yml`](../deploy/monitoring/alerts.yml) dosyaları, `observability.metrics-http-port: 9108` üzerinden doğrudan gerçek `MetricsExporter` metriklerini (`whynotac_health_status`, `whynotac_lost_events_total`, `whynotac_listener_errors_total`, `whynotac_transaction_loop_mean_wall_micros`, `whynotac_check_min_margin`) izler.
