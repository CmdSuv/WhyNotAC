# WhyNotAC — Mimari

## Tasarım ilkeleri

1. **Kanıt, ceza değil.** Hiçbir tespit katmanı oyuncuyu doğrudan cezalandıramaz. Tespitler
   *kanıt* üretir. Karar yalnızca `whynotac-verdict` katmanında, istatistiksel bir üst sınırla
   verilir.
2. **Bilgi sınırının altında kanıt yoktur.** Verinin kendisinin taşıyamadığı bir hassasiyet
   (ör. float32 yaw'ın ulp'si, client'ın 2e-4 altı hareketi hiç göndermemesi, LpVec3
   kuantizasyonu) asla "sapma" sayılmaz. Her ölçüm, kendi bilgi sınırıyla birlikte taşınır.
3. **Belirsizlik = kanıt yok.** Kaybolan veri (`Gap`), bilinmeyen client durumu ya da
   yorumlanamayan bir pencere, şüphe değil, **sıfırlanmış kanıt** demektir.
4. **Bit-tam replikasyon.** Fizik ve dönüş hesapları client'ın IEEE-754 işlemlerini aynı sırayla
   yapar ve gerçek oyun sınıflarına karşı bit seviyesinde test edilir. "Yaklaşık doğru" bir
   simülatör, yuvarlama hatasını hile sanır.
5. **Fail-open.** WhyNotAC içindeki bir hata hiçbir oyuncuyu etkilemez: gözlem katmanı paketleri
   değiştirmeden iletir, istisna Netty'ye ya da oyun thread'ine sızmaz. Sessiz önlemler (setback,
   hasar iptali, bilgi gizleme) [POLICY.md](POLICY.md)'ye göre ayrı bir katmandan gelir ve oyunun
   kendi mekanizmalarını kullanır (ör. teleport ile setback), paket bozmaz.
6. **Esnek tahmin, katı sıra değil.** Grim tarzı "tek doğru tahmin + paket sırası" yerine fizik
   katmanı, kanıtla tutarlı **tüm hipotezlerin kümesini** (tuş kombinasyonları, knockback
   penceresi, gönderilmemiş mikro hareket) üretir. Gözlem bu kümenin dışına, bilgi sınırının
   ötesinde düştüğünde kanıt oluşur.

## Katmanlar ve bağımlılık grafiği

```
            ┌─────────────────────────── whynotac-paper ───────────────────────────┐
            │  Netty handler (NMS) · paket çevirici · transaction sandviçi · komut │
            └──────────────┬───────────────────────────────────────────────────────┘
                           │ ServerboundPacket / ClientboundPacket (değişmez kayıtlar)
            ┌──────────────▼────────────── whynotac-protocol ──────────────────────┐
            │  PlayerSession · sıralı zaman çizelgesi · SerialMailbox (aktör)      │
            │  TransactionTracker · LatencyEstimator · TransactionPolicy           │
            │  replay oturumları                                                   │
            └──────────────┬───────────────────────────────────────────────────────┘
                           │ TimelineEvent (oyuncu başına toplam sıra)
                           ├──────────────► whynotac-recording: kayıpsız kayıt (.wnrec),
                           │                takma ad, replay, inspect/dump aracı
                           │                (kayıtlar replay ile aynı dinleyicilere geri akar)
   ┌───────────────────────┼─────────────────────────────┬────────────────────────┐
   ▼                       ▼                             ▼                        │
whynotac-physics     whynotac-analysis              whynotac-ai                    │  (planlı)
hipotez kümeli       aim / tıklama / zamanlama      oyuncu profili, anomali skoru  │
fizik motoru         dedektörleri                   (S sınıfı, ceza vermez)        │
   └───────────────────────┴──────────────┬──────────────┘                        │
                                          ▼ Evidence                              │
                                   whynotac-verdict ──► whynotac-storage ◄────────┘
                                   (istatistiksel karar)   (profil/kanıt kalıcılığı)
                                          │
                     tüm katmanlar ──► whynotac-math (bit-tam vanilla sayısal çekirdek)
```

`whynotac-conformance` yalnızca test içerir: `whynotac-math`'ı gerçek 1.21.11 sunucu sınıflarına
(`Mth`, `Vec3`, `LpVec3`, `Entity`) karşı bit-bit karşılaştırır. Hiçbir zaman dağıtılmaz.

## Veri akışı ve thread modeli

Zaman çizelgesi **ağın kenarında**, baytlardan kaydedilir (Faz 2b-1). Oyunun nesne görünümü ise
yalnızca onunla karşılaştırılır:

```
 TCP ─► splitter ─► [decompress] ─► whynotac_wire_in ─► (diğer eklentilerin decoder'ları) ─► decoder ─► … ─► whynotac_interceptor ─► packet_handler
                                     │  vanilla 1.21.11 codec'iyle çöz,                                       │  oyunun gördüğü paketi
                                     │  PlayerSession.onInbound (client'ın GÖNDERDİĞİ)                         │  kenarla uzlaştır
                                     ▼                                                                         ▼
                              SerialMailbox ─► TimelineListener'lar               Reconciler: yalnız bir tarafta olan ─► Divergence olayı

 oyun ─► packet_handler ─► whynotac_interceptor ─► encoder ─► (diğer eklentilerin encoder'ları) ─► whynotac_wire_out ─► [compress] ─► prepender ─► TCP
          sandviç: Ping ─ paket ─ Ping;                                                            çöz, PlayerSession.onOutbound
          oyunun gönderdiğini kenara bildir                                                        (client'ın ALDIĞI), uzlaştır;
                                                                                                   oyunu atlayan fizik paketini
                                                                                                   kenarda sandviçle
```

- **Neden kenar:** ViaVersion, PacketEvents, ProtocolLib ve bunların üzerine kurulu eklentiler
  oyun ile ağ arasında paket ekler, iptal eder veya yeniden yazar. PacketEvents
  `sendPacketSilently` oyunun gönderim yolunu hiç kullanmaz. Client'ın aldığı ama WhyNotAC'nin
  görmediği bir knockback, dürüst bir oyuncunun hareketini imkânsız gösterirdi. Kenarda
  client'ın gönderdiği ve aldığı **tam olarak** görülür.
- **Yerleşim:** Gelen tap `decompress`'in (yoksa `splitter`'ın), giden tap `compress`'in (yoksa
  `prepender`'ın) hemen yanındadır. Paper sıkıştırmayı bir `ConnectionEvent` ile duyurur.
  ViaVersion buna kendini sıkıştırmanın yanına taşıyarak tepki verir. Tap'ler bir event-loop
  görevi sonra yine en dışa döner. Her 5 saniyede bir denetim, araya giren başka eklentileri de
  düzeltir. Hepsi E2E'de gerçek ViaVersion ile doğrulandı.
- **Uzlaştırma (`Reconciler`):** Yalnızca bir tarafta olan her paket açık bir
  `InboundDivergence`/`OutboundDivergence` olayıdır (`WIRE_ONLY`/`GAME_ONLY`). Yeniden
  yazılmış bir paket iki olay üretir. Başka eklentilerin bir süre tutup sonra bıraktığı paketler
  (ViaVersion ve PacketEvents play başında bunu yapar) gecikmedir, sapma sayılmaz. Bir paket,
  ancak akış onu geçtiğinde **ve** 2 saniyelik süre dolduğunda eksik sayılır.
- **Kenar çözülemezse:** Kenar, sunucunun 774 codec'iyle çözülür. Handshake baytlarından okunan
  gerçek protokol başkaysa (ViaVersion'lı eski client) veya bağlantı WhyNotAC'den önce kurulmuşsa,
  oyunun görünümü kullanılır. Bu, bir `Observation` olayıyla açıkça kaydedilir; analiz hangi
  görünüme sahip olduğunu bilir.
- Bir bağlantının tüm handler'ları **tek bir event-loop thread'inde** çalışır. Bu yüzden kenar,
  oyun tarafı ve sandviç aynı çağrı yığınında, kilitsiz ve kesin sırayla işler.

- `sequence` oyuncu başına toplam bir sıradır ve `nanoTime` tutarlı bir saattir.
- Ağ thread'i analiz beklemez: yalnızca damgalar, sayar, kuyruğa ekler.
- `SerialMailbox` sınırlıdır (varsayılan 16 384 olay). Taşma, sessizce veri kaybı değil, açık bir
  `Gap` olayıdır. Gap'i aşan kanıt geçersizdir.
- Dinleyici hataları izole edilir: biri çökerse diğerleri olayı yine görür, bağlantı etkilenmez.
- Analiz havuzu kapanmışsa teslimat çağıran thread'e düşer: kapanışta hiçbir olay (özellikle
  `Disconnected`) kuyrukta kalmaz.

## Client hakkında bilinenler ve güven düzeyleri

Sunucunun bir client hakkında öğrendiği her şey, ne kadar güvenilebileceğiyle birlikte bir
`ClientFact` olayı olarak kaydedilir:

| Güven | Anlamı | Örnekler |
|---|---|---|
| `AUTHENTIC` | Kriptografik ya da yapısal olarak kanıtlı | online mode, Velocity HMAC, Floodgate anahtarı, süreç içi `LocalChannel` (Geyser) |
| `FORMAT_BOUND` | Client'ın hizmet alabilmek için konuşmak zorunda olduğu baytlarla belirli | gerçek protokol sürümü (handshake baytları), çevirici var mı, transaction'ı kim yanıtlıyor |
| `CLAIMED` | Client'ın kendi beyanı | brand, kanal kayıtları, ayarlar, doğrulanmamış iletilen kimlik |

**Kural (her analiz katmanı için bağlayıcı):** `CLAIMED` bir bilgi yalnızca herkese tanınan
hipotezler ekleyebilir. Hiçbir zaman deterministik bir kontrolü kapatamaz ya da kanıtı
affettiremez, çünkü bir hile her şeyi beyan edebilir. Fizik modelini yalnızca `AUTHENTIC` ve
`FORMAT_BOUND` bilgiler seçebilir.

**Kimlik:** `IDENTITY` olgusu sunucu yapılandırmasından belirlenir: `AUTHENTICATED_MOJANG`,
`AUTHENTICATED_BY_PROXY` (Velocity modern + çevrimiçi proxy, ya da Floodgate), `CLAIMED_BY_PROXY`
(legacy BungeeCord: düz metin, port korunmuyorsa herkes her UUID'yi iddia edebilir) ve
`UNAUTHENTICATED` (offline mode). Uzun ömürlü durum (profil, ceza) **yalnızca** `AUTHENTICATED_*`
kimliklere bağlanabilir. Diğerlerinde kanıt oturumla sınırlıdır ve hiçbir hesaba otomatik işlem
yapılmaz. Böyle bir sunucuda eklenti başlangıçta açıkça uyarır.

**Transaction kökeni:** 1.17 öncesi client'larda ping paketi yoktur. ViaBackwards onların yerine
yanıt verir. Bu bağlantılarda `TRANSACTION_ORIGIN = TRANSLATOR` kaydedilir ve gecikme/sandviç
kanıtı bu client hakkında hiçbir şey söylemez.

## Gecikme telafisi: transaction sandviçi

Client, `ClientboundPingPacket`'i oyun thread'inde, diğer tüm paketlerle aynı sırada işler ve
`Pong` ile yanıtlar (decompile ile doğrulandı). Fizik durumunu değiştiren her paket
(knockback, patlama itmesi, teleport, öznitelik, efekt, yetenek, oyun modu, respawn) iki
transaction arasına alınır:

```
sunucu:   ... Ping(a) ─ SetEntityMotion ─ Ping(b) ...
client:   Pong(a) gelene kadar: knockback KESİN uygulanmadı
          Pong(a) … Pong(b) arası: uygulanmış OLABİLİR  → fizik iki hipotezi de taşır
          Pong(b) sonrası: knockback KESİN uygulandı
```

Böylece yüksek ping'li ya da jitter'lı bir oyuncunun "geç" uygulanan knockback'i tahmin edilmez,
**kanıtlanır**. Ping arkasına saklanan bir hile protokol düzeyinde görünür: atlanan transaction
`SKIPPED_TRANSACTION`, iki kez yanıtlanan `DUPLICATE_TRANSACTION`, yanıtlanmayı tamamen bırakan
client `TRANSACTIONS_WITHHELD` anomalisidir (canlı E2E testte ilk ikisi yakalanıyor). Kimlikler
`SecureRandom`'dan gelir. Bu yüzden bir transaction görülmeden yanıtlanamaz ve WhyNotAC'nin ping'leri
sabit bir önekle tanınamaz. Başka bir eklentinin oyunu atlayarak gönderdiği fizik paketi, kenarda
baytlarla kodlanan bir sandviçe alınır. Ayrıca her sunucu
tick'inde bağımsız bir transaction gönderilerek sürekli RTT/jitter izi tutulur
(RFC 6298 SRTT, RFC 3550 jitter).

Gerçek client'ta ölçülen sıra kuralı (resmi 1.21.11 client'ı, `tools/realclient`): oyun fazında
client pong'ları kare başında, tick'in paketlerinden önce gönderir. Bir tick'in ilk paketi ile
`ClientTickEnd` arasında hiç pong yoktur (558 pong'da 0 ihlal). Bu kural, ping'leri ayrı bir thread'den
yanıtlayan client'lara karşı deterministik kanıt adayıdır. Kanıt olarak kullanılmadan önce modlu
client'larda ve çeviricilerin arkasında doğrulanacak (yol haritası).

## Sıfır yanlış-ban yaklaşımı (verdict katmanı için sözleşme)

Normatif sözleşme [POLICY.md](POLICY.md)'dir. Özeti:

- Kişi başına 1e-9 gibi bir yanlış-pozitif oranı replay ile **ölçülemez**. Otomatik ceza bu yüzden
  yalnızca **inşa gereği sağlam** kanıttan (P-A) gelir: vanilla'nın bütün belirsizlikleri sınırlanmış
  haliyle asla üretemeyeceği bir gözlem.
- Temiz kayıtlar yalnızca yanlışlar: bir kontrolün temiz veride tek bir kanıt üretmesi onu SHADOW'a
  geri alır ve bir regresyon testi olur.
- İstatistik ve yapay zekâ e-değer ya da sinyal üretir: inceleme ve bağlam içindir, asla ceza vermez.
- Kanıtlar bağımlılık grubuyla birleştirilir: aynı fiziksel olaydan türeyen iki kontrol çift sayılmaz.
- Her eylem, POLICY.md'deki sınıf × eylem × kimlik katmanı matrisine tabidir. Operatör yasak bir
  hücreyi açamaz.

## Modüller

| Modül | Durum | Sorumluluk |
|---|---|---|
| `whynotac-math` | **Faz 1 ✔** | Vanilla bit-tam sayısal çekirdek (`Mth`, `Vec3`, LpVec3, fare modeli, gönderim kuralları), ULP/tolerans, hatasız dönüşümler, Neumaier toplamı, kaydırmalı Welford momentleri |
| `whynotac-protocol` | **Faz 1 ✔** | 1.21.11 paket modeli, oyuncu zaman çizelgesi, aktör posta kutusu, transaction izleme |
| `whynotac-paper` | **Faz 1 ✔ · 2b-1 ✔** | NMS/Netty paket yakalayıcı, ağ kenarı tap'leri ve uzlaştırma, sandviç (kenarda da), `/whynotac inspect` |
| `whynotac-conformance` | **Faz 1 ✔** | Gerçek oyun sınıflarına karşı diferansiyel testler |
| `whynotac-recording` | **Faz 2a ✔** | Çökmeye dayanıklı kayıt formatı, takma adlandırma, deterministik replay, çevrimdışı araç |
| `whynotac-bench` | **Faz 2a ✔** | JMH performans tabanı (`docs/benchmarks/`) |
| `whynotac-physics` | Faz 2 (planlı) | Katmanlı, hipotez kümeli fizik motoru (bkz. modül README) |
| `whynotac-analysis` | **Slice 0 ✔** | Kontroller (ilk: tick framing), kanıt ve kabul edilebilirlik, eylem politikası, SHADOW karar, `verify` aracı. Aim/tıklama/zamanlama dedektörleri planlı |
| `whynotac-ai` | Faz 3–4 (planlı) | Kişisel profil, hesap ele geçirme / makro tespiti |
| `whynotac-verdict` | Faz 4 (planlı) | Kanıt birleştirme ve ARMED karar (Slice 0'ın SHADOW kararı şimdilik `whynotac-analysis`'te) |
| `whynotac-storage` | Faz 4 (planlı) | Profil ve kanıt kalıcılığı |

## Bilinen sınırlar

- Yalnızca **Paper 1.21.11** (CraftBukkit/NMS sınıfları kullanılır). Spigot ve Folia
  desteklenmez/test edilmedi.
- ViaVersion ile başka sürümden bağlanan client'lar için kenar çözülemez. Oyunun görünümü
  kullanılır ve bu açıkça kaydedilir. Bu client'ların **fizikleri de farklıdır**. Client türü
  tespiti (Faz 2b-2) ayrı modelleme ya da analiz dışı tutma kararını verecek.
- `ChannelInitializeListenerHolder` Paper'ın desteklenmeyen iç API'sidir.
- Ölçülen ek yük: ağ thread'inde paket başına ≈ 115 ns / 112 B (nesne görünümü). Kenar,
  modellenen paket başına ≈ 180–220 ns, modellenmeyen (chunk vb.) başına ≈ 80 ns ekler
  (ayrıntı: `docs/phases/PHASE-2A-REPORT.md`, `docs/phases/PHASE-2B-1-REPORT.md`).
