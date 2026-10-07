# WhyNotAC

Minecraft Java Edition **1.21.11** (Paper) için tamamen sunucu taraflı anomali tespit sistemi.
Client'a hiçbir şey kurulmaz ve client'ın bilgisayarı okunmaz. Tüm analiz ağ paketleri, bit-tam
fizik ve davranış modelleriyle sunucuda yapılır.

> **Durum: Faz 2a tamamlandı.** Faz 1: paket yakalayıcı ve yüksek hassasiyetli matematik çekirdeği.
> Faz 2a: kayıpsız oturum kaydı, birebir replay ve performans tabanı.
> Sistem şu an tamamen pasif bir gözlemcidir: hiçbir oyuncuya işlem yapmaz.
> Faz 2b-1: zaman çizelgesi ağın kenarında, client'ın gerçekten gönderdiği ve aldığı baytlardan
> kaydedilir. Diğer eklentilerin eklediği, iptal ettiği veya yeniden yazdığı paketler görünür.
> Raporlar: [Faz 1](docs/phases/PHASE-1-REPORT.md) · [Faz 2a](docs/phases/PHASE-2A-REPORT.md) · [Faz 2b-1](docs/phases/PHASE-2B-1-REPORT.md)

## Belgeler
- [Mimari ve ilkeler](docs/ARCHITECTURE.md): katmanlar, thread modeli, transaction sandviçi, sıfır yanlış-ban sözleşmesi
- [Doğrulanmış vanilla 1.21.11 referansı](docs/reference/vanilla-1.21.11.md): decompile edilmiş kaynaktan okunan her sabit
- Planlı katmanlar: [physics](whynotac-physics/README.md) · [analysis](whynotac-analysis/README.md) ·
  [ai](whynotac-ai/README.md) · [verdict](whynotac-verdict/README.md) · [storage](whynotac-storage/README.md)

## Modüller
| Modül | Açıklama |
|---|---|
| `whynotac-math` | Vanilla bit-tam sayısal çekirdek ve hassas analiz primitifleri (bağımlılıksız) |
| `whynotac-protocol` | Platformdan bağımsız paket modeli, oyuncu zaman çizelgesi, transaction/gecikme izleme |
| `whynotac-recording` | Kayıpsız, çökmeye dayanıklı oturum kaydı, takma adlandırma, replay, inspect/dump aracı |
| `whynotac-paper` | Paper 1.21.11 eklentisi: NMS/Netty paket yakalayıcı, kayıt entegrasyonu |
| `whynotac-conformance` | Gerçek oyun sınıflarına karşı diferansiyel testler (dağıtılmaz) |
| `whynotac-bench` | JMH performans ölçümleri (dağıtılmaz) |

## Derleme ve test
Gereksinim: JDK 21. İlk derlemede paperweight, Paper dev-bundle'ını hazırlar (birkaç dakika).

```bash
./gradlew build                       # tüm modüller + 139 test
./gradlew :whynotac-paper:jar         # eklenti: whynotac-paper/build/libs/
tools/e2e/run_e2e.sh /tmp/wnac-e2e    # gerçek Paper 1.21.11 sunucusuyla canlı uçtan uca test (39 kontrol)
EXTRA_PLUGINS="$(tools/e2e/fetch_interop_plugins.sh /tmp/wnac-plugins)" E2E_EXPECT_PLUGINS="ViaVersion packetevents" \
  tools/e2e/run_e2e.sh /tmp/wnac-e2e  # aynı test, ViaVersion 5.12.0 + PacketEvents 2.14.0 yanında
./gradlew :whynotac-bench:jmh         # performans ölçümü (sonuç: whynotac-bench/build/jmh/results.json)
```

CI her push'ta derlemeyi, tüm testleri ve iki canlı E2E varyantını (yalın, interop) çalıştırır.
**Tekrarlanabilir derleme:** SNAPSHOT bağımlılıkları (`dev-bundle`, `paper-api`, `velocity-native`)
`gradle/libs.versions.toml` içinde tek bir zaman damgalı yapıya sabitlenmiştir. Sabitlenmemiş bir
SNAPSHOT derlemeyi durdurur. Aynı commit'ten iki temiz derleme bayt bayt aynı eklenti jar'ını
üretir. Dışarıdan indirilen her şey (Paper, ViaVersion, PacketEvents) sha256/sha512 ile doğrulanır.

## Kullanım
`plugins/` klasörüne kopyalayın. Konsol veya `whynotac.inspect` yetkili oyuncu:
- `/whynotac inspect <oyuncu>`: RTT/jitter, transaction muhasebesi, paket sayaçları, son hareket
  paketinin tam değerleri, kesin yaw/pitch farkı ve o yaw'ın float çözünürlüğü, basılı tuşlar,
  anomaliler
- `/whynotac sessions`: canlı oturumlar

### Oturum kaydı
`plugins/WhyNotAC/config.yml` içinde `recording.enabled: true` (varsayılan kapalı; açmadan önce
oyuncuları bilgilendirin). Kayıtlar `plugins/WhyNotAC/recordings/<tarih>/*.wnrec` altına
takma adlı olarak yazılır. IP ve isim içermez. Çevrimdışı inceleme için eklenti jar'ı aynı zamanda
bir araçtır:

```bash
java -jar WhyNotAC.jar inspect kayit.wnrec   # bütünlük raporu, sayaçlar, gecikme, anomaliler (JSON)
java -jar WhyNotAC.jar dump    kayit.wnrec   # her olay bir JSON satırı, float'lar ham bitleriyle
java -jar WhyNotAC.jar find    plugins/WhyNotAC/recordings plugins/WhyNotAC/recording-secret.key <uuid>
java -jar WhyNotAC.jar forget  plugins/WhyNotAC/recordings plugins/WhyNotAC/recording-secret.key <uuid> --yes
```

`find`/`forget` KVKK/GDPR erişim ve silme talepleri içindir (`--yes` olmadan `forget` yalnızca
neyi sileceğini listeler). Yönetici rehberi ve oyuncu bilgilendirme metni şablonu (TR/EN):
[docs/privacy](docs/privacy/README.md).
