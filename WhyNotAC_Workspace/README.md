# WhyNotAC

Minecraft Java Edition **1.21.11** (Paper) için tamamen sunucu taraflı anomali tespit sistemi.
Client'a hiçbir şey kurulmaz ve client'ın bilgisayarı okunmaz. Tüm analiz ağ paketleri, bit-tam
fizik ve davranış modelleriyle sunucuda yapılır.

> **Durum: Faz 1 tamamlandı** (paket yakalayıcı + yüksek hassasiyetli matematik çekirdeği).
> Faz 1 tamamen pasif bir gözlemcidir: hiçbir oyuncuya işlem yapmaz.
> Rapor: [`docs/phases/PHASE-1-REPORT.md`](docs/phases/PHASE-1-REPORT.md)

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
| `whynotac-paper` | Paper 1.21.11 eklentisi: NMS/Netty paket yakalayıcı |
| `whynotac-conformance` | Gerçek oyun sınıflarına karşı diferansiyel testler (dağıtılmaz) |

## Derleme ve test
Gereksinim: JDK 21. İlk derlemede paperweight, Paper dev-bundle'ını hazırlar (birkaç dakika).

```bash
./gradlew build                       # tüm modüller + 79 test
./gradlew :whynotac-paper:jar         # eklenti: whynotac-paper/build/libs/
tools/e2e/run_e2e.sh /tmp/wnac-e2e    # gerçek Paper 1.21.11 sunucusuyla canlı uçtan uca test
```

## Kullanım (Faz 1)
`plugins/` klasörüne kopyalayın. Konsol veya `whynotac.inspect` yetkili oyuncu:
- `/whynotac inspect <oyuncu>`: RTT/jitter, transaction muhasebesi, paket sayaçları, son hareket
  paketinin tam değerleri, kesin yaw/pitch farkı ve o yaw'ın float çözünürlüğü, basılı tuşlar,
  anomaliler
- `/whynotac sessions`: canlı oturumlar
