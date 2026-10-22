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
                           ├──────────────► whynotac-world: client'ın dünyası (chunk'lar, bloklar,
                           │                client'ın chunk deposu, "settled" belirsizliği),
                           │                resmi client'tan üretilmiş blok durumu verisi
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

**Her flush da çevrilidir (Faz 2b-5).** Kenar, client'a giden her flush'ın ilk karesinden önce ve
flush'tan hemen önce birer transaction yazar. Orada zaten bir transaction varsa yenisini yazmaz. Client
bir flush'ı neredeyse her zaman tek seferde okur, bu yüzden iki cevap aynı iki tick'i arasına düşer.
Böylece flush'taki her paketin client'ın hangi tick'inden önce işlendiği kesin bilinir. Bunun
istisnası nadirdir: bir TCP bölünmesi ile bir tick üst üste gelirse olur. O durumda replikalar her
olasılığı taşır. Ping bundle içine, login'den önceye ve yapılandırmaya geçişten sonraya yazılmaz
(`observation.flush-transactions`).

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

## Client'ın dünyası (Faz 2b-3)

Fizik, client'ın çarptığı blokları bilmek zorundadır. Sunucunun dünyası bu değildir: client chunk'ları
geç alır, kendi yerleştirdiği bloğu önceden tahmin eder, görüş aralığının dışındaki chunk'ı yok sayar,
anti-xray ona başka bloklar gönderebilir. Bu yüzden dünya, client'ın aldığı paketlerden ve client'ın
okuduğu sırayla yeniden kurulur:

- **Yakalama:** chunk paketi kenarda client'ın aldığı baytlar olarak tutulur. Ağ thread'inde çözülmez,
  tek bir kopya alınır. Blok ve bölüm güncellemeleri, unutulan chunk'lar, görüş merkezi/yarıçapı,
  `block_changed_ack` ve giriş/yeniden doğuştaki boyut modellenir ve kayda girer. Posta kutusu
  sınırları chunk baytlarını da sayar (128 bayt = 1 birim), bellek sınırı korunur.
- **Veri:** her blok durumu için çarpışma şekli oyunun kendi biçiminde (eksen koordinatları, dolu
  hücreler, kutular, `CubeVoxelShape`/`ArrayVoxelShape` türü), sürtünme, hız ve zıplama çarpanları,
  sertlik ve sıvı. Veri **resmi client'ın kendisinden** üretilir (`BlockDataDump`, Mojang eşlemeleriyle).
  Paper'ın çarpışma yamaları vanilla'nın `CubeVoxelShape` kurduğu 9.150 durumu `ArrayVoxelShape`
  olarak kurar; geri kalan her şey aynıdır ve bir test tam olarak bunu sabitler.
- **Replika:** `ClientChunkCache` birebir: `(2r+1)^2` yuvalı halka tampon, `r = max(2, görüş) + 3`,
  aralık dışı chunk yok sayılır, aynı yuvaya gelen chunk eskisini siler, sorgu yalnız güncel merkezin
  aralığında cevaplanır, yeniden doğuş yalnız boyut değişince dünyayı siler.
- **Belirsizlik:** bir blok, son değişikliğinden sonra gönderilmiş bir transaction onaylanana kadar
  "settled" değildir (client eski ya da yeni durumu tutuyor olabilir). Client'ın kendi tahminleri,
  sunucunun `block_changed_ack`'i client'a ulaşana kadar belirsizdir. Olay kaybı (`Gap`) ya da
  okunamayan bir dünya paketi dünyayı "stale" yapar. Bir kontrol, settled olmayan bir bloğu ancak
  tutabileceği her durum (bekleyen her değişiklikten önceki ve şimdiki, `possibleStates`) onun için aynıysa
  biliniyor sayar: hareket için aynı çarpışma geometrisi, bayraklar, sürtünme, hız ve zıplama çarpanları, sıvı
  yok; adımın adıyla okuduğu bloklar yalnızca aynı durum olarak (`OnFoot.sameForMovement`). Çimenin yayıldığı
  toprak, nemi değişen tarla, mesafesi değişen yaprak böyledir; delik, çiğnenen tarla, eriyen buz, büyüyen
  sarmaşık değildir.
- **Doğrulama:** `/whynotac world <oyuncu>` replikayı sunucunun dünyasıyla bölüm bölüm (4096 blok
  durumunun 64 bit özeti) karşılaştırır. E2E ve gerçek client'ta 1944/1944 bölüm birebir eşittir.

## Client'ın gördüğü varlıklar (Faz 2b-5)

Reach ve hitbox kontrolleri, diğer varlıkların client'taki konumunu bilmek zorundadır. Bu konum
sunucununki değildir. Client paketleri kare başında işler, konumu üç tick boyunca enterpolasyonla
yaklaştırır, bazı paketleri hiç uygulamaz. Model şudur:

- **Önek kuralı:** client her an gönderilen akışın bir önekini işlemiştir. Bu önek en az cevapladığı son
  ping'e kadardır, en fazla cevaplamadığı ilk ping'den öncesidir. `ClientTickEnd` tick'leri sayar.
- **Adaylar:** her varlık için, pencerenin paketleri ile tick'lerin her olası sırasına bir aday düşer.
  Kurallar decompile edilmiş client'tan alınmıştır: `VecDeltaCodec`, `InterpolationHandler`, snap,
  kafa enterpolasyonu, chunk'a bağlı tick'lenme, donma, yolcular, oyuncu profili. Aynı hedeflere
  varan adaylar, her üyeyi kapsayan bir yarıçapla birleşir. Yarıçap 0 bit-tam demektir.
- **Bilinmeyen açıkça söylenir:** yolcular, kontrol edilebilir araç, kendi fiziğini çalıştıran türler,
  olay kaybı. Bir kontrol bilinmeyen ya da yarıçaplı bir konumu asla kesin saymaz.
- **Doğrulama:** `/whynotac entities <oyuncu>` replikanın varlık kümesini ve kod tabanlarını sunucunun
  takibiyle karşılaştırır (E2E'de 11/11 eşit). Bağımsız bir referans client'la yapılan simülasyonda
  829.571 kontrolde 0 ihlal çıktı.

## Varlıkların boyutları ve seçilebilirlik (Faz 2b-6)

Kutunun boyutu türe, poza, bebek bayrağına, ölçek özelliğine ve bazı türlerde verilerine bağlıdır
(`LivingEntity.getDimensions`).
- **Tablo:** `EntitySizes` boyut tablosunu taşır. Tablo oyunun sınıflarından üretilir
  (`EntitySizesDataTest`). Boyutu belirleyen metotları geçersiz kılan her sınıf kayıtlı bir listeyle
  karşılaştırılır.
- **Veriye bağlı boyutlar:**
  - slime ve phantom boyutu, pufferfish şişmesi, salmon türü;
  - zırh askısı bayrakları;
  - kazan ya da çıkan warden, kazan sniffer;
  - interaction genişliği ve yüksekliği, area effect cloud yarıçapı.
  Veri indeksleri, ilk değerleri ve sabitler tabloya oyunun sınıflarından yazılır.
- **Oyunla karşılaştırma:** `EntitySizesGameTest` her kuralı oyunun kendi `getDimensions`'ıyla karşılaştırır:
  - her tür, her poz, bebek ve yetişkin;
  - aralığın içinde ve dışında ölçekler;
  - verinin alabileceği değerler.
  Varlıklar oyunun sınıflarıdır: dünyası olmadan oluşturulur ve client tarafı bir dünya nesnesi taşır.
- **Seçilebilirlik:** client'ın nişangâhı bir varlığı ancak `isPickable()` ise ve varlık spectator
  değilse seçebilir.
  - Kural türün sınıfından tabloya yazılır ve oyunla karşılaştırılır.
  - Spectator bilgisi oyuncu listesinden, client sırasıyla izlenir. Bir oyuncunun PlayerInfo'su varlık
    dururken kaldırılırsa client eski nesneyi tutar; bu durumda değer "bilinmiyor" sayılır.
  - Mermilerin seçilebilirliği sunucunun gönderdiği bir etikete bağlıdır, iddia edilmez.
- **Replika:** bebek bayrağını, ölçeği ve türün boyut verisini client sırasıyla izler. Her olası durum bir
  boyut ve bir seçilebilirlik taşır.
- **Bilinmeyen:** client tarafı animasyonla değişen boyutlar (kutup ayısı, shulker), konumun etrafında
  olmayan kutular (asılı varlıklar, wind charge) ve sırası belirsiz değiştiricili ölçekler için boyut
  iddia edilmez.
- **Doğrulama:** `/whynotac entities` boyutları ve seçilebilirliği sunucunun kendi hesabıyla karşılaştırır.

## Client'ın elindeki eşya (Faz 2b-7)

Saldırı menzili client'ın elindeki eşyaya bağlıdır: `minecraft:attack_range` bileşeni olan bir eşya kendi
menziliyle vurur. İsabet tikin başında `getActiveItem()` ile, kontrol saldırı anında ana elle yapılır.
- **Replika (`ClientInventory`):** hotbar, yedek el, seçili slot ve açık menü, client sırasıyla.
  - Sunucunun paketleri flush parantezlerinin yerleştirdiği yerde, client'ın tıklamaları, seçimleri,
    yaratıcı düzenlemeleri, bırakmaları ve eşya kullanımları gönderdiği yerde etkili olur.
  - İşleyiciler `ClientPacketListener`'ınkiler: konteyner 0 her zaman envanter menüsü, binek menüsü
    yalnızca client'ta at ya da nautilus varsa açılır, yeniden doğma yeni ve boş bir oyuncudur.
- **Menü düzenleri (`MenuLayouts`):** her menü türünün hangi slotunun oyuncunun hotbar'ı olduğu, oyunun menü
  sınıflarından üretilir (`MenuLayoutsDataTest`).
- **Eşya kullanımı (`VanillaUseEffects`):** client tarafında envanteri değiştiren her blok, varlık ve eşya
  etkileşimi sınıflandırıldı.
  - Çoğu yalnızca küçültür ya da menzili olmayan bir yığına dönüştürür; o slotun menzil kümesine "yok"
    eklenir.
  - Zırh takası, yamadan gelen kalıntı ve allay'in geri verdiği eşya slotu bilinmez kılar.
- **Hash çözümleme:** tıklamadaki hashlenmiş yığının menzili, sunucunun gönderdiği tam yığınlardan aynı
  hash'le çözülür. Hash client'ın kendi hesabıdır (CRC32C).
- **Sorgular:** şimdiki ana elin menzil kümesi, isabet anındaki ana elin kümesi (son tik sonundan beri seçilen
  her slot) ve yedek elin kümesi.
- **Doğrulama:** `/whynotac inventory` replikayı sunucunun envanteriyle turlarla karşılaştırır.

## Kendi oyuncu ve saldırı mesafesi (Faz 2b-8)

- **Replika (`ClientSelf`):** saldırının isabet ve kontrol adımlarının okuduğu her şey, client sırasıyla.
  - Göz: son gönderilen konumun 2·10⁻⁴ yakını (`sendPosition` eşiği). Oyuncuyu yanıt göndermeden hareket
    ettiren bir paket o gönderimden sonra işlenmiş olabiliyorsa göz bilinmez: binek değişimi, yeni
    oyuncu, blok geri alması, kendi kimliğine ışınlama ya da senkron, okunamayan paket.
  - Gözün yüksekliği: duruş zarfının tamamı (0.2'den 1.62·ölçek'e). Ölçek önbellektedir, bir tik sonra
    yenilenir; o ana kadar eski değer de olası kalır.
  - Binek, kamera (`set_camera`), oyuncu listesindeki kendi oyun modu (önbelleğe alınan girdi) ve
    `MultiPlayerGameMode` modu, `entity_interaction_range` ve ölçek, her önekteki değer kümesiyle.
- **Kontrol (`AttackReachCheck`, P-A, SHADOW):** saldırı, olası her göz konumu, hedefin her durumu ve
  isabetteki ile kontroldeki eşyaların her çifti için erişim dışındaysa kanıttır (`AttackReach`).
  - Hedefin kutusu `ClientEntities`'in her durumundan gelir. Bir varlığın kutusu yeni bir ölçeği ancak bir
    sonraki tikinin sonunda alır; o ana kadar boyut bilinmez sayılır.
  - Oyuncuyu kendi tikinden sonra itebilen şeylerin yanında iddia yoktur: piston blokları, shulker
    kutuları (`ClientWorld.mayMoveEntitiesIn`) ve shulker'lar (`ClientEntities.mayHaveNear`).
  - İddia yok: yaratıcı ve izleyici mod, bilinmeyen göz, bilinmeyen menzil, herhangi bir durumunda
    bilinmeyen ya da seçilemeyen hedef.
- **Denetim (`tools/realclient/ReachAudit.java`):** bir kayıttaki her saldırıyı kontrolle aynı şekilde
  değerlendirir: menzil içi (mesafe ve izin verilen), menzil dışı ya da iddia yok (nedeniyle).

## Modüller

| Modül | Durum | Sorumluluk |
|---|---|---|
| `whynotac-math` | **Faz 1 ✔** | Vanilla bit-tam sayısal çekirdek (`Mth`, `Vec3`, LpVec3, fare modeli, gönderim kuralları), ULP/tolerans, hatasız dönüşümler, Neumaier toplamı, kaydırmalı Welford momentleri |
| `whynotac-protocol` | **Faz 1 ✔** | 1.21.11 paket modeli, oyuncu zaman çizelgesi, aktör posta kutusu, transaction izleme |
| `whynotac-paper` | **Faz 1 ✔ · 2b-1 ✔** | NMS/Netty paket yakalayıcı, ağ kenarı tap'leri ve uzlaştırma, sandviç (kenarda da), `/whynotac inspect` |
| `whynotac-conformance` | **Faz 1 ✔** | Gerçek oyun sınıflarına karşı diferansiyel testler |
| `whynotac-recording` | **Faz 2a ✔** | Çökmeye dayanıklı kayıt formatı, takma adlandırma, deterministik replay, çevrimdışı araç |
| `whynotac-bench` | **Faz 2a ✔** | JMH performans tabanı (`docs/benchmarks/`) |
| `whynotac-world` | **Faz 2b-3 ✔ · 2b-5 ✔ · 2b-6 ✔ · 2b-7 ✔ · 2b-8 ✔** | Client'ın dünyası: 29.671 blok durumu (çarpışma şekli oyunun kendi VoxelShape biçiminde, sürtünme, sıvı...), chunk decoder, client'ın chunk deposunun birebir kopyası, değişikliklerin ve client tahminlerinin belirsizliği. Client'ın gördüğü varlıklar: client sırasıyla, tick tick, aday durumlar ve yarıçaplarla; boyutları ve seçilebilirlikleri oyunun kendi tablosundan. Client'ın elindeki eşya ve menzili, kendi oyuncusunun gözü, modu ve öznitelikleri |
| `whynotac-physics` | **Faz 3 (yaya) ✔** | Client'ın çarpışma kodu ve yaya hareket adımı, bit bit (bkz. modül README) |
| `whynotac-analysis` | **Slice 0 ✔ · 2b-8 ✔ · Faz 3 ✔** | Kontroller (tick framing, client saati, saldırı mesafesi, yaya hareket), kanıt ve kabul edilebilirlik, eylem politikası, SHADOW karar, `verify` aracı. Aim/tıklama/zamanlama dedektörleri planlı |
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
