# WhyNotAC Deployment & Pilot Stack (`deploy/`)

Bu dizin, tekli Paper `1.21.11` sunucuları ve çok-sunuculu **Velocity + Paper + Redis Pub/Sub + Prometheus + Grafana** pilot kurulumları için hazır şablonlar içerir.

## Dizindeki Bileşenler

- [`docker-compose.yml`](docker-compose.yml) — Tek komutla Paper `1.21.11`, Velocity, Redis `7-alpine` (`network-bridge.redis-uri` küme otobüsü), Prometheus ve Grafana başlatan pilot yığını.
- [`configs/paper-shadow/config.yml`](configs/paper-shadow/config.yml) — Sıfır yaptırımlı ilk gözlem profili (`SHADOW`, kayıt açık).
- [`configs/paper-mitigate-pilot/config.yml`](configs/paper-mitigate-pilot/config.yml) — Yumuşak etkisizleştirme pilotu (`mitigations: true`, `auto-ban: false`).
- [`configs/paper-pvp-practice/config.yml`](configs/paper-pvp-practice/config.yml) — Rekabetçi PvP / Practice ayarı (sıkı korelasyon + oklüzyon + aktif sondalar).
- [`configs/paper-smp/config.yml`](configs/paper-smp/config.yml) — Büyük SMP / Survival dünyaları için düşük ana-thread maliyetli koruma profili.
- [`configs/paper-velocity-backend/config.yml`](configs/paper-velocity-backend/config.yml) — Velocity + Redis küme köprüsü etkin arka uç profili.
- [`monitoring/prometheus.yml`](monitoring/prometheus.yml), [`monitoring/alerts.yml`](monitoring/alerts.yml), [`monitoring/grafana-dashboard.json`](monitoring/grafana-dashboard.json) — OpenMetrics (`/metrics`) toplama, uyarı kuralları ve Grafana panosu (23 kontrol, toplu WAL SQLite, Redis Pub/Sub, bütçe ve gap patlaması metrikleri dâhil).

## Hızlı Başlangıç (Docker Compose Pilotu)

```bash
cd WhyNotAC_Workspace
./gradlew :whynotac-paper:jar :whynotac-velocity:jar
mkdir -p deploy/runtime/paper-plugins deploy/runtime/velocity-plugins
cp whynotac-paper/build/libs/whynotac-paper-*.jar deploy/runtime/paper-plugins/WhyNotAC.jar
cp whynotac-velocity/build/libs/whynotac-velocity-*.jar deploy/runtime/velocity-plugins/WhyNotAC-Velocity.jar

export WHYNOTAC_REDIS_PASSWORD="strong-random-redis-password"
export GRAFANA_PASSWORD="strong-random-grafana-password"
docker compose -f deploy/docker-compose.yml up -d
```

## Üretim Güvenlik Kontrol Listesi (`WhyNotAC.jar doctor`)

Canlıya geçmeden önce veri dizinini çevrimdışı denetleyin:

```bash
java -jar whynotac-paper/build/libs/whynotac-paper-0.1.0-SNAPSHOT.jar doctor plugins/WhyNotAC
```

- `observability.dashboard-enabled: true` açılacaksa mutlaka güçlü bir `observability.metrics-auth-token` tanımlayın (boş token ile `/dashboard` ve `/api/status` `403` ile reddedilir).
- `*.key` dosyalarının izinleri `0600` (`rw-------`) ve uzunluğu `>= 32` bayt olmalıdır.
- Prometheus / Grafana / `/metrics` portlarını yalnızca `127.0.0.1` veya özel VPN/iç ağ arayüzüne bağlayın.
