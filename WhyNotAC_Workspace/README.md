# WhyNotAC

Minecraft Java Edition **1.21.11** (Paper) için tamamen sunucu taraflı anomali tespit sistemi.
Client'a hiçbir şey kurulmaz ve client'ın bilgisayarı okunmaz. Tüm analiz ağ paketleri, bit-tam
fizik ve davranış modelleriyle sunucuda yapılır.

> **Durum: Faz 2a tamamlandı.** Faz 1: paket yakalayıcı ve yüksek hassasiyetli matematik çekirdeği.
> Faz 2a: kayıpsız oturum kaydı, birebir replay ve performans tabanı.
> Sistem şu an tamamen pasif bir gözlemcidir: hiçbir oyuncuya işlem yapmaz.
> Faz 2b-1: zaman çizelgesi ağın kenarında, client'ın gerçekten gönderdiği ve aldığı baytlardan
> kaydedilir. Diğer eklentilerin eklediği, iptal ettiği veya yeniden yazdığı paketler görünür.
> Faz 2b-2: client hakkındaki her bilgi (kimlik, topoloji, protokol, çevirici, Bedrock, marka) güven
> seviyesiyle kaydedilir. Client'ın kendi iddiası hiçbir deterministik kontrolü kapatamaz.
> Gerçek client doğrulaması: resmi, değiştirilmemiş 1.21.11 client'ı CI'da başsız ve gerçek
> klavye/fare girdisiyle oynanır. Kayıt WhyNotAC'ın vanilla modelleriyle karşılaştırılır.
> **Slice 0:** ilk kontrol (tick framing, P-M) uçtan uca çalışıyor: kanıt, kabul edilebilirlik,
> [politika](docs/POLICY.md), SHADOW karar, vaka kaydı, uyarı, dossier ve sunucudan bağımsız `verify`
> ([rapor](docs/phases/SLICE-0-REPORT.md)). İkinci kontrol: client saati (timer, P-M), nedensellikle
> sınırlanmış bir kanıt. Kontroller SHADOW'dadır: hiçbir oyuncuya işlem yapılmaz.
> 100 botluk yük testi: dürüst oyunculara 0 yanlış bulgu, düz dünyada ve gerçek arazide
> ([sonuçlar](docs/benchmarks/2026-10-08-load-100/README.md), [gerçek arazi](docs/benchmarks/2026-10-08-world/README.md)).
> **Faz 2b-3:** client'ın dünyası client'ın aldığı paketlerden yeniden kuruluyor: sunucunun dünyasıyla
> blok blok eşit (E2E ve gerçek client'ta 1944/1944 bölüm). Blok verisi resmi client'tan üretiliyor
> ([rapor](docs/phases/PHASE-2B-3-REPORT.md)).
> **Faz 2b-5:** client'ın gördüğü varlıklar client'ın işlediği sırayla, tick tick yeniden kuruluyor:
> her olası sıra bir aday, yarıçap 0 bit-tam. Her flush ağ kenarında transaction'la çevrili. Bağımsız
> referans client'la 829.571 kontrolde 0 ihlal, sunucunun takibiyle E2E'de 11/11, gerçek client'ta 7/7 eşit.
> Yük testinde 0 yanlış bulgu; ölçülen maliyet hâlâ hedefin üstünde
> ([rapor](docs/phases/PHASE-2B-5-REPORT.md), [yük testi](docs/benchmarks/2026-10-09-entities/README.md)).
> **Faz 2b-6:** varlıkların boyutları (tür, poz, bebek, ölçek, slime boyutu ve zırh askısı bayrakları gibi
> veriler) ve client'ın onları seçip seçemeyeceği, oyunun sınıflarından üretilen tablodan. Her kural
> oyunun kendi `getDimensions`'ıyla 100.000'den fazla durumda karşılaştırıldı. Sunucunun kendi hesabıyla
> E2E'de 16 boyut ve 16 seçilebilirlik, gerçek client'ta 12 boyut ve 12 seçilebilirlik eşit, hiç fark yok
> ([rapor](docs/phases/PHASE-2B-6-REPORT.md)).
> **Faz 2b-7:** client'ın elindeki eşya ve menzili. Hotbar, yedek el, seçili slot ve açık menü client
> sırasıyla izleniyor; menü düzenleri oyunun menü sınıflarından üretiliyor. Client tarafında envanteri
> değiştiren her blok, varlık ve eşya etkileşimi sınıflandırıldı. Tıklamaların hash'leri, sunucunun gönderdiği
> menzil değerlerine çözülüyor. E2E'de ve gerçek client'ta gerçek tıklamalarla 10/10 slot sunucuyla eşit; taşınan
> özel menzilli çubuğun menzili client'ın kendi hash'inden çözüldü ([rapor](docs/phases/PHASE-2B-7-REPORT.md)).
> **Faz 2b-8:** ilk P-A kontrolü, saldırı mesafesi (SHADOW). Client'ın kendi oyuncusu (göz, binek, kamera,
> oyun modu, menzil ve ölçek öznitelikleri) client sırasıyla izleniyor. Bir saldırı ancak her olası göz
> konumu, hedefin her durumu ve isabetteki ile kontroldeki eşyaların her çifti için erişim dışındaysa kanıt
> olur; oyuncuyu bildirimsiz itebilecek bir şey varsa iddia yoktur. Gerçek client 4.70 blokta çubukla ve
> 2.98 blokta kılıçla saldırdı; ikisi de değerlendirildi ve menzil içi bulundu. 3.05 blokta client saldırıyı
> hiç göndermedi. E2E'de 7 bloktan saldırı vaka açtı, menzil içi saldırı açmadı. Kayıtlar artık her UUID'yi
> takma adla tutuyor ([rapor](docs/phases/PHASE-2B-8-REPORT.md)).
> Raporlar: [Faz 1](docs/phases/PHASE-1-REPORT.md) · [Faz 2a](docs/phases/PHASE-2A-REPORT.md) · [Faz 2b-1](docs/phases/PHASE-2B-1-REPORT.md) · [Slice 0](docs/phases/SLICE-0-REPORT.md) · [Faz 2b-3](docs/phases/PHASE-2B-3-REPORT.md) · [Faz 2b-5](docs/phases/PHASE-2B-5-REPORT.md) · [Faz 2b-6](docs/phases/PHASE-2B-6-REPORT.md) · [Faz 2b-7](docs/phases/PHASE-2B-7-REPORT.md) · [Faz 2b-8](docs/phases/PHASE-2B-8-REPORT.md)

## Belgeler
- [Kararlar](docs/DECISIONS.md): proje sahibinin kararları (tek koşulsuz kural: dünyanın en iyisi)
- [Kanıt ve eylem politikası](docs/POLICY.md): hangi kanıt hangi eylemi tetikleyebilir (normatif)
- [Mimari ve ilkeler](docs/ARCHITECTURE.md): katmanlar, thread modeli, transaction sandviçi, sıfır yanlış-ban sözleşmesi
- [Yol haritası](docs/ROADMAP.md): bugünkü durum, sıradaki fazlar ve kapıları, sahibin kararını bekleyenler
- [Gizlilik ve veri sahibi talepleri](docs/privacy/README.md): neyin saklandığı, `/whynotac privacy find|forget`
- [Doğrulanmış vanilla 1.21.11 referansı](docs/reference/vanilla-1.21.11.md): decompile edilmiş kaynaktan okunan her sabit
- Katman sözleşmeleri: [physics](whynotac-physics/README.md) · [analysis](whynotac-analysis/README.md) ·
  [ai](whynotac-ai/README.md) · [verdict](whynotac-verdict/README.md) · [storage](whynotac-storage/README.md)

## Modüller
| Modül | Açıklama |
|---|---|
| `whynotac-math` | Vanilla bit-tam sayısal çekirdek ve hassas analiz primitifleri (bağımlılıksız) |
| `whynotac-protocol` | Platformdan bağımsız paket modeli, oyuncu zaman çizelgesi, transaction/gecikme izleme |
| `whynotac-recording` | Kayıpsız, çökmeye dayanıklı oturum kaydı, takma adlandırma, replay, inspect/dump aracı |
| `whynotac-world` | Client'ın dünyası: blok durumu verisi (resmi client'tan), chunk decoder, client'ın chunk deposunun kopyası ve belirsizliği |
| `whynotac-physics` | Client'ın kendi hareketi, decompile edilmiş koddan bit bit: çarpışma çekirdeği (resmi client'ın koduyla rastgele vakalarda karşılaştırılır) ve yaya hareket adımı, otomatik zıplama dahil (gerçek client'ın parkurdaki her tikiyle karşılaştırılır); `movement` kontrolü bunun üzerinde |
| `whynotac-analysis` | Kontroller, kanıt ve kabul edilebilirlik, eylem politikası (POLICY.md), karar katmanı, `verify` aracı |
| `whynotac-paper` | Paper 1.21.11 eklentisi: NMS/Netty paket yakalayıcı, kayıt entegrasyonu, vakalar ve uyarılar |
| `whynotac-conformance` | Gerçek oyun sınıflarına karşı diferansiyel testler (dağıtılmaz) |
| `whynotac-bench` | JMH performans ölçümleri (dağıtılmaz) |

## Derleme ve test
Gereksinim: JDK 21. İlk derlemede paperweight, Paper dev-bundle'ını hazırlar (birkaç dakika).

```bash
./gradlew build                       # tüm modüller + 434 test
./gradlew :whynotac-paper:jar         # eklenti: whynotac-paper/build/libs/
tools/e2e/run_e2e.sh /tmp/wnac-e2e    # gerçek Paper 1.21.11 sunucusuyla canlı uçtan uca test (111 kontrol)
EXTRA_PLUGINS="$(tools/e2e/fetch_interop_plugins.sh /tmp/wnac-plugins)" E2E_EXPECT_PLUGINS="ViaVersion packetevents" \
  tools/e2e/run_e2e.sh /tmp/wnac-e2e  # aynı test, ViaVersion 5.12.0 + PacketEvents 2.14.0 yanında
tools/realclient/run_realclient.sh /tmp/wnac-rc  # resmi client, Xvfb + XTest (bkz. tools/realclient/README.md)
tools/load/run_load.sh /tmp/wnac-load whynotac 100 60  # 100 dürüst bot, A/B ölçüm (bkz. tools/load/README.md)
./gradlew :whynotac-bench:jmh         # performans ölçümü (sonuç: whynotac-bench/build/jmh/results.json)
```

CI her push'ta derlemeyi, tüm testleri, iki canlı E2E varyantını (yalın, interop) ve gerçek client
oturumunu çalıştırır.
**Tekrarlanabilir derleme:** SNAPSHOT bağımlılıkları (`dev-bundle`, `paper-api`, `velocity-native`)
`gradle/libs.versions.toml` içinde tek bir zaman damgalı yapıya sabitlenmiştir. Sabitlenmemiş bir
SNAPSHOT derlemeyi durdurur. Aynı commit'ten iki temiz derleme bayt bayt aynı eklenti jar'ını
üretir. Dışarıdan indirilen her şey (Paper, ViaVersion, PacketEvents) sha256/sha512 ile doğrulanır.

## Kullanım
`plugins/` klasörüne kopyalayın. Konsol veya `whynotac.inspect` yetkili oyuncu:
- `/whynotac inspect <oyuncu>`: RTT/jitter, transaction muhasebesi, paket sayaçları, son hareket
  paketinin tam değerleri, kesin yaw/pitch farkı ve o yaw'ın float çözünürlüğü, basılı tuşlar,
  anomaliler
- `/whynotac world <oyuncu>`: client'ın dünyasını sunucununkiyle bölüm bölüm karşılaştırır
- `/whynotac entities <oyuncu>`: client'ın varlıklarını sunucunun takibiyle karşılaştırır (küme, kod
  tabanları, boyutlar ve seçilebilirlik), varlık başına client durumunu söyler (kesin, yarıçaplı, aday
  sayısı, bilinmiyor)
- `/whynotac sessions`: canlı oturumlar
- `/whynotac stats`: başlangıçtan beri toplamlar (JSON): anomaliler, sapmalar, kayıp olaylar, vakalar
- `/whynotac cases`, `/whynotac case <id>`: açılan vakalar. Her vakanın bir dossier'si vardır:
  `plugins/WhyNotAC/cases/<id>.json`. `java -jar WhyNotAC.jar verify <dossier>` kontrolü oturum
  kaydı üzerinde sunucudan bağımsız yeniden çalıştırır. Uyarılar `whynotac.alerts`, SHADOW
  kontrollerinki `whynotac.alerts.shadow` yetkisine gider.

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
