# WhyNotAC

Minecraft Java Edition **1.21.11** (Paper) için tamamen sunucu taraflı anomali tespit sistemi.
Client'a hiçbir şey kurulmaz ve client'ın bilgisayarı okunmaz. Tüm analiz ağ paketleri, bit-tam
fizik ve davranış modelleriyle sunucuda yapılır.

> **Durum: Faz 1 – Faz 17 tamamlandı — Üretime Hazır (`SHADOW` varsayılan, `paper-pvp-practice` / `paper-smp` / `mitigate-pilot` / `ARMED` opt-in).**
> - **21 analiz kontrolü + `design-probe` (`D`):** deterministik `P-A`/`P-M` kontroller (`attack-reach`, `attack-aim`, `use-aim`, `break-time`, `movement`, `velocity-response` v3, `tick-framing`, `client-clock`, `transaction-order` v2, `action-grammar`, `prediction-sequence`, `packet-bounds` v4, `inventory-protocol`, `interact-at-box`, `use-item-state` v2), cezasız istatistiksel `S` kontroller (`click-pattern`, `aim-pattern` v2, `container-timing`, `scaffold-pattern`, `timer-balance`, `ping-spoof` v2) ve Ville eşitsizliğine dayalı `design-probe` yem sondaları.
> - **Sıfır Yanlış-Pozitif Sözleşmesi:** 3 gerçek resmi 1.21.11 istemci kaydında (`414.983` olay, `40.963` hareket tiki -> **%99,94 `EXPLAINED`**, `0 UNEXPLAINED`, `0` yanlış kanıt), **32/32** sentetik benchmark senaryosunda ve **10/10** kalıcı `.wnrec` hile korpusunda `0` FP / `0` FN.
> - **Tam Yığın Mimari:** Netty kenar gözlemcisi (`WIRE`), oturum başına `2 ms` adil analiz bütçesi + `Gap` patlaması telemetrisi, bit-tam 1.21.11 dünya/varlık/envanter/öz-oyuncu/etiket replikaları, bağımsız `SecondVerifier`, SHA-256 `EvidenceCertificate`, `CircuitBreaker`, `FalseBanBudget`, sıcak-yenileme korumalı `BanWaveQueue`, gerçek `fsync` + HMAC-SHA256 zincirli `AuditLedger`, disk-kalıcı dört-göz `ReviewWorkflow`, kalıcı Mahalanobis kovaryanslı `whynotac-ai`, çift-yönlü yankısız `whynotac-velocity` (`whynotac:bridge` + uzak `GDPR_FORGET`), OpenMetrics `/metrics` + `/health`, `alerts-stream.jsonl` + async webhook, `/whynotac reload`, 5 sunucu-türü yapılandırma seti ve CycloneDX 1.5 SBOM.
> - **Varsayılan Güvenlik:** Kutudan çıktığında tüm kontroller `SHADOW` modundadır; hiçbir oyuncuya otomatik kick/ban uygulanmaz ([politika](docs/POLICY.md), [soundness](docs/SOUNDNESS.md), [operatör kılavuzu](docs/OPERATOR-GUIDE.md)).
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
>
> **Bakış (`attack-aim`, P-M):** vanilla client yalnızca tikin başında bakışının değdiği varlığa saldırır. Bakış,
> tikin kendi dönüş paketinden ya da client'ın en son gönderdiği dönüşten okunur; göz pozların göz yüksekliklerinden
> birindedir, bu yüzden eğim de sayılır. Hiçbir olası gözden ve hedefin hiçbir olası kutusuna değmeyen bakışla
> saldırı kanıttır. Bakış hedefe ancak onu kesin durduran bir bloğun ardında değiyorsa da (client'ın seçimi
> outline'a çarptığı bloktan uzaktaki varlığı seçmez; bloklar client'ın saldırı anında tutabileceklerinden,
> outline'lar resmi client'tan) kanıttır. Gerçek client'ın iki saldırısı ve yük testinde 20 botun 1829 saldırısı vaka
> açmadı; E2E'de bakışını çevirip vuran ve taş duvarın arkasındaki lamaya vuran sonda açtı.
>
> **Kullanım bakışı (`use-aim`, P-M):** client eşyayı oyuncunun o anki dönüşüyle kullanır ve bu, tikin bakışıyla bit
> bit aynıdır; bir bloğa da yalnızca tikin seçiminin bulduğu yerden tıklar: imleç bakışın o bloğun outline'ına girdiği
> noktada, girebileceği yüzde, menzilde, araya blok girmeden. Kırdığı blok ve yüz de aynı seçimdendir. Yalnızca sunucu için dönüp nişan alan (inci, iksir, ok),
> bakmadan, havaya, duvarın ardına ya da uzağa blok koyan client'ı yakalar. Gerçek client'ın 10 kullanımı ve blok
> tıklaması vaka açmadı; E2E'de 45° sapmış dönüşle kullanan, imleci bakışından kaydırarak duvara tıklayan ve duvarı arka
> yüzünden kırmaya başlayan sonda açtı, imleci oyunun aritmetiğiyle bit bit hesaplanmış tıklaması ve ön yüzden kırması
> açmadı.
>
> **Kırma süresi (`break-time`, P-M):** vanilla client kırma ilerlemesini tik başına en çok bir kez ve kendi float
> aritmetiğiyle toplar (alet hızı, verimlilik, haste, madencilik yorgunluğu, `block_break_speed`, su altı, yerde olmayış,
> sertlik, doğru alet); `STOP_DESTROY_BLOCK`'u ancak toplam 1'e ulaştığı tik gönderir. Her çarpan client'ın o tikte
> tutabileceği en büyük değeriyle alınır (alet kurallarının etiketleri client'ın sunucudan aldığı etiketlerden; havada
> olduğu yalnızca kendi `onGround` bayrağı bunu kesin söylediğinde); bu üst sınırla bile 1'e ulaşmadan gelen bitiş, hızlı
> kırma (FastBreak) kanıtıdır. Gerçek client'ın elle kırdığı toprak ve çim, zıplarken kazdığı çim ve toprak da vaka
> açmadı (havada 60 tik ÷5 sayılan çimde üst sınır 1.000001: neredeyse tam client'ın kendi toplamı); E2E'de taşı (elle
> 151 tik) başladıktan bir tik sonra bitiren sonda açtı.
>
> **Tahmin numaraları (`prediction-sequence`, P-M):** vanilla client blok değişikliği tahmin ettiği her paketi (kırmaya
> başlama ve bitirme, bloğa ve havaya kullanma) dünyasının sıradaki numarasıyla gönderir; her yeni dünyada (login, başka
> boyuta respawn) 1'den başlar, başka hiçbir eylem numara taşımaz. Bir modülün kendi kurup gönderdiği paket (0 ya da
> tekrar eden numara) bunu bozar. Gerçek client'ın numaraları nether'e geçişte yeniden 1'den başladı, vaka açılmadı;
> E2E'de numaralı bırakma paketi gönderen sonda açtı.
>
> **Elytra ile süzülme (`movement`):** hareket motoru süzülmeyi de client'ın kendi aritmetiğiyle bit bit hesaplar:
> havada yeni bir zıplama basışıyla başlaması (client `START_FALL_FLYING` gönderir), bakış ve eğimle hareketi, sunucunun
> bayrağıyla bitmesi. Oyun burada `Math.cos` kullanır; JVM onu CPU'ya göre farklı hesaplar (x86_64 ile Apple silicon
> float eğimlerin yaklaşık %0,06'sında son bitte ayrılır). İki intrinsic de komut komut aktarıldı ve her float eğimde (2,14 milyar
> girdi) gerçek JVM'lerle (aarch64'ü QEMU altında) bit bit aynı; takipçi client'ın CPU'sunu da görünmeyen bir seçenek
> olarak izler. Havai fişek roketinin itmesi de izlenir: client'ta oyuncuya bağlı roket oyuncudan sonra tick'ler ve
> süzülen oyuncuyu bakışına doğru iter; takipçi bağlı roketleri sayar ve itmeyi client'ın aritmetiğiyle uygular, bağlı
> olup olmadığı ya da tick'leyip tick'lemediği kesin olmayan bir rokette iddiadan çekilir. Gerçek client'ın altı
> süzülmesi (272 tik, biri x86_64'ün kosinüsünün ayrıldığı eğimde, biri roketle: 50 itme izlendi) bit bit eşit;
> sentetik testte daha uzağa süzülme, başlatılamayacak yerde başlatma, paketsiz süzülme ve roket yokken roket hızı kanıt oldu.
>
> **Dünya sınırı (`movement`):** sınırın çarpışması da client'ın kendi kopyasıyla izlenir (`ClientBorder`): sabit ya da
> lerp'li, client'ın tikleriyle adım adım (dünya donmuşken adım atmaz, çarpışan bir önceki boyuttur, şekli tam bloğa
> yuvarlanır). Sınırlar, şekil ve oyuncu kutularıyla çarpışma oyunun kendi `WorldBorder`'ına karşı lerp'in her adımında bit
> bit sınanır. Sınıra yaslanan, küçülen sınırın içinden geçtiği ve büyüyen sınırla ilerleyen oyuncu izlenir; sınırı yok
> sayan ya da lerp'te bir adım önden giden client kanıttır. Gerçek client'ın sabit, büyüyen ve küçülen sınırın
> yanındaki 321 tiki (196'sı sınıra çarpan) bit bit eşit; takipçi hiçbirini açıklanamaz ya da iddiasız bırakmadı.
>
> **Toz kar (`movement`):** şekli oyuncuya bağlıdır: 2.5'ten fazla düşene levha, ayağında deri botla üstünde duran ve
> inmeyene tam blok, kalanlara hiçbir şey; içine giren tutulur, bir sonraki tik botluysa duvarda tırmanır. Botlar
> client'ın envanter replikasından gelir (zırh slotu, envanter indisi, oyuncunun kendi `set_equipment` paketi; yeni
> modellenen bu paket kaydedilir ve kenarda türüne göre uzlaştırılır, Paper eşyalarını gizleyebildiği için). Donma
> client'ın hareketini değiştirmez, yavaşlaması sunucunun hız özniteliğiyle izlenir. Şekil ve içine giriş oyunun
> kendi `PowderSnowBlock`'una karşı bit bit sınanır; botsuz üstünde yürüyen ya da içinde tutulmayan client kanıttır.
> Gerçek client'ın botsuz ve deri botlu toz kar parkurunda (batma, düşüş, üstünde yürüme, tırmanma, donma) 511 tiki
> bit bit eşit; takipçi hiçbirini açıklanamaz ya da iddiasız bırakmadı.
>
> **Sunucunun etiketleri (`update_tags`):** client neye tırmandığını, neyin üstünde durduğunu, nerede yüzdüğünü ve aletinin
> hangi bloğu kazdığını sunucunun gönderdiği etiketlerden bilir; veri paketi bunları değiştirebilir. WhyNotAC paketi
> yapılandırma evresinde de kenardan çözer, kaydeder ve client'ın etiketlerini işlediği sırayla izler (`ClientTags`).
> Hareket motoru vanilla etiketlerini varsayar (`climbable`, `can_glide_through`, `fall_damage_resetting`, `fences`,
> `walls`, `water`, `lava`, `powder_snow_walkable_mobs`): client başka etiketler tutmuş olabildiği sürece hareket iddiası yoktur. Vanilla sunucuda
> (E2E ve gerçek client) bu etiketler motorunkilerle birebir aynıdır.
> Raporlar: [Faz 1](docs/phases/PHASE-1-REPORT.md) · [Faz 2a](docs/phases/PHASE-2A-REPORT.md) · [Faz 2b-1](docs/phases/PHASE-2B-1-REPORT.md) · [Slice 0](docs/phases/SLICE-0-REPORT.md) · [Faz 2b-3](docs/phases/PHASE-2B-3-REPORT.md) · [Faz 2b-5](docs/phases/PHASE-2B-5-REPORT.md) · [Faz 2b-6](docs/phases/PHASE-2B-6-REPORT.md) · [Faz 2b-7](docs/phases/PHASE-2B-7-REPORT.md) · [Faz 2b-8](docs/phases/PHASE-2B-8-REPORT.md)

## Belgeler
- [Kararlar](docs/DECISIONS.md): proje sahibinin kararları (tek koşulsuz kural: dünyanın en iyisi)
- [Kanıt ve eylem politikası](docs/POLICY.md): hangi kanıt hangi eylemi tetikleyebilir (normatif)
- [Kontrollerin Soundness Sözleşmeleri](docs/SOUNDNESS.md): 21 kontrol + `design-probe` bilgi sınırları, varsayımlar ve muafiyetler
- [Operatör ve Canlı Pilot Kılavuzu](docs/OPERATOR-GUIDE.md): Paper, Velocity, `mitigate-pilot`, Prometheus/Grafana ve CLI araçları
- [Çoklu-Sürüm Yükseltme Rehberi](docs/UPGRADE-GUIDE-PROD14.md): `PROD-14` protokol geçiş süreci
- [Mimari ve ilkeler](docs/ARCHITECTURE.md): katmanlar, thread modeli, transaction sandviçi, sıfır yanlış-ban sözleşmesi
- [Yol haritası](docs/ROADMAP.md) · [Faz 7–17 İleri Mühendislik Planı](docs/ROADMAP-PHASE-7-12.md)
- [Derin Zayıflık ve Eksiklik Analizi (2026-11-21)](docs/reviews/2026-11-21-weakness-analysis.md) ([ilk analiz: 2026-10-06](docs/reviews/2026-10-06-weakness-analysis.md))
- [Gizlilik ve veri sahibi talepleri](docs/privacy/README.md): neyin saklandığı, `/whynotac privacy find|forget`
- [Doğrulanmış vanilla 1.21.11 referansı](docs/reference/vanilla-1.21.11.md): decompile edilmiş kaynaktan okunan her sabit
- Katman sözleşmeleri: [physics](whynotac-physics/README.md) · [analysis](whynotac-analysis/README.md) ·
  [ai](whynotac-ai/README.md) · [verdict](whynotac-verdict/README.md) · [storage](whynotac-storage/README.md) · [cheat-corpus](cheat-corpus/README.md)

## Modüller
| Modül | Açıklama |
|---|---|
| `whynotac-math` | Vanilla bit-tam sayısal çekirdek (`PlatformCos` x86_64/AARCH64) ve hassas istatistik primitifleri (bağımlılıksız) |
| `whynotac-protocol` | Platformdan bağımsız paket modeli, `SupportedProtocols` sürüm yönlendiricisi, `NetworkEnvelope` (HMAC), oyuncu zaman çizelgesi |
| `whynotac-recording` | Kayıpsız, çökmeye dayanıklı oturum kaydı (`.wnrec`), takma adlandırma, replay, inspect/dump aracı |
| `whynotac-world` | Client'ın dünyası, varlıkları, envanteri, öz-oyuncu durumu, etiketleri ve blok tahminleri (`DigPredictions`, `ClientWorld`) |
| `whynotac-physics` | Client'ın kendi hareketi, decompile edilmiş 1.21.11 kodundan bit bit (`OnFoot`, `Colliders`, araçlar, elytra, dünya sınırı, toz kar) |
| `whynotac-analysis` | 21 kontrol (`tick-framing`, `client-clock`, `attack-reach`, `attack-aim`, `use-aim`, `break-time`, `prediction-sequence`, `action-grammar`, `movement`, `velocity-response`, `packet-bounds`, `inventory-protocol`, `interact-at-box`, `use-item-state`, `transaction-order`, `click-pattern`, `aim-pattern`, `container-timing`, `scaffold-pattern`, `timer-balance`, `ping-spoof`), `ActionPolicy`, `verify` ve `benchmark` CLI |
| `whynotac-verdict` | Bağımlılık-gruplu birleştirme, `CircuitBreaker`, `FalseBanBudget`, `SecondVerifier`, SHA-256 `EvidenceCertificate` ve `BanWaveQueue` |
| `whynotac-storage` | Hash zincirli + HMAC-SHA256 `AuditLedger`, atomik `SubjectProfileStore` (GDPR `forget`) ve dört-göz `ReviewWorkflow` |
| `whynotac-ai` | 2. nesil çok-değişkenli `BehavioralFeatureExtractor`, Mahalanobis `AccountBaselineModel`, Kelly/GRO `EValueProcess`/`ProbeScheduler` ve cezasız `AiAdvisor` |
| `whynotac-velocity` | Velocity 3.x proxy eşlik eklentisi: HMAC imzalı istemci kenarı beyanı (`NET-01`) ve kaynak-yankısız sunucular arası röle (`NET-22`) |
| `whynotac-paper` | Paper 1.21.11 eklentisi: NMS/Netty paket yakalayıcı, kayıt entegrasyonu, vakalar, OpenMetrics/HTTP, `alerts-stream.jsonl` + webhook, `/whynotac reload` ve uygulama köprüsü |
| `whynotac-conformance` | Gerçek oyun sınıflarına karşı diferansiyel testler (dağıtılmaz) |
| `whynotac-bench` | JMH performans ölçümleri (dağıtılmaz) |

## Derleme ve test
Gereksinim: JDK 21. İlk derlemede paperweight, Paper dev-bundle'ını hazırlar (birkaç dakika).

```bash
./gradlew build                                                # tüm modüller + tüm birim/regresyon/benchmark testleri + CycloneDX SBOM
./gradlew :whynotac-paper:jar :whynotac-velocity:jar           # Paper ve Velocity eklenti JAR'ları
java -jar whynotac-paper/build/libs/whynotac-paper-0.1.0-SNAPSHOT.jar benchmark regression cheat-corpus  # 32 senaryo + 10 .wnrec + 3 gerçek kayıt
tools/e2e/run_e2e.sh /tmp/wnac-e2e                             # gerçek Paper 1.21.11 sunucusuyla canlı E2E
EXTRA_PLUGINS="$(tools/e2e/fetch_interop_plugins.sh /tmp/wnac-plugins)" E2E_EXPECT_PLUGINS="ViaVersion packetevents" \
  tools/e2e/run_e2e.sh /tmp/wnac-e2e                           # ViaVersion + PacketEvents uyumluluk E2E
tools/e2e/run_proxy_e2e.sh /tmp/wnac-proxy-e2e                 # Velocity 3.4.0 + Paper 1.21.11 çift-yönlü imzalı whynotac:bridge E2E
tools/e2e/run_cheat_e2e.sh /tmp/wnac-cheat-e2e                 # 12 canlı ARMED kontrol + CANCEL_IMPOSSIBLE_HIT + SETBACK_POSITION E2E
tools/realclient/run_realclient.sh /tmp/wnac-rc                # resmi Minecraft 1.21.11 istemcisi, Xvfb + XTest
tools/load/run_load.sh /tmp/wnac-load whynotac 100 60          # 100 dürüst bot, canlı Paper + JFR yük testi
./gradlew :whynotac-bench:jmh                                  # JMH mikro-benchmark
```

CI her push ve PR'da 5 işi birlikte çalıştırır: `build`, `live E2E (vanilla)`, `live E2E (interop)`, `live E2E (velocity proxy)` (`run_proxy_e2e.sh` + `run_cheat_e2e.sh`) ve `real client ground truth`.
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
