# Derin Zayıflık Analizi (Faz 2a sonrası)

**Yöntem:** Yorum değil kanıt. Her bulgu ya kodumuzdan, ya decompile edilmiş 1.21.11 kaynağından,
ya da bu analiz için yapılmış bir ölçümden gelir. Ölçüm: ViaVersion 5.12.0 ve PacketEvents 2.14.0
(sha512 doğrulandı) yüklü gerçek bir Paper 1.21.11 sunucusu, yeni `/whynotac pipeline` komutu ve
PacketEvents'in kendi kodunun decompile edilmesi.

**Önce dürüst durum tespiti:** WhyNotAC bugün **hiçbir hileyi tespit etmiyor.** Var olan şey ölçüm
altyapısı: paket yakalama, bit-tam matematik, zaman çizelgesi, kayıt ve replay. İşin en zor
%80–90'ı (fizik motoru, client dünyası, varlık takibi, erişim/aim/tıklama analizleri, karar
katmanı) önümüzde. Aşağıdaki zayıflıkların bir kısmı tam da bu yüzden şimdi düzeltilmeli: üzerine
fizik inşa edilmeden önce.

## Özet tablo

| # | Önem | Zayıflık | Kanıt | Ne zaman |
|---|---|---|---|---|
| 1 | **Kritik** | Client'ın gerçekte aldığı/gönderdiği paketleri değil, sunucunun niyetini görüyoruz. PacketEvents/ProtocolLib ile gönderilen paketler görünmez. | Ölçüm + PacketEvents kodu (§1) | Faz 2b, fizikten önce |
| 2 | **Kritik** | Client türü/sürümü bilinmiyor (ViaVersion, Geyser/Bedrock, eski client'lar) | Pipeline ölçümü: `via-decoder` byte düzeyinde çeviriyor (§2) | Faz 2b |
| 3 | **Kritik** | "Her yerde bit-tam" iddiası yanlış: elytra ve sprint-çarpışma yolları platforma bağlı | `Math.cos`, `Math.acos` (§3) | Fizik ile birlikte |
| 4 | **Yüksek** | Çeviri hatası zaman çizelgesinde **sessiz delik** bırakıyor. Enum eşlemeleri sürüm güncellemesinde sessizce kırılır. | `WhyNotChannelHandler`, `PacketTranslator` (§4) | Hemen |
| 5 | **Yüksek** | Meşru client modları (zoom, sinematik, gamepad, kesirli imleç) aim analizini yanıltır | `MouseTurnModel` varsayımları (§5) | Aim analizinden önce |
| 6 | **Yüksek** | Gerçek client ile hiç doğrulama yapılmadı. Tüm E2E Python sondasına dayanıyor. | `tools/e2e` (§6) | Faz 2b |
| 7 | **Yüksek** | "Sıfır yanlış-ban" ile "yapay zekâ banlasın" vizyonu matematiksel olarak çelişiyor | §7 | Mimari karar |
| 8 | Yüksek | Transaction kimlikleri parmak izi bırakıyor ("WN" öneki) | `TransactionTracker.ID_PREFIX` | Hemen |
| 9 | Yüksek | Kötü niyetli bağlantı başına bellek büyütme, global bütçe yok | §9 | Canlı testten önce |
| 10 | Orta | Ölçek ve gecikme ölçülmedi (tek bot, metrik yok) | §10 | Canlı testten önce |
| 11 | Orta | Hile ile "paket sırasına uyan dürüst client" varsayımı. Blink/tutma tespiti yok. | §11 | Faz 3 |
| 12 | Orta | Derlemeler tekrarlanabilir değil (SNAPSHOT dev-bundle), CI'da E2E yok | §12 | Hemen |
| 13 | Orta | KVKK: takma ad kişisel veridir, silme aracı yok | §13 | Canlı testten önce |
| 14 | Stratejik | Hedef sürüm yaşlanıyor (1.21.11, güncel sürüm 26.3) | §14 | Yol haritası |
| 15 | Stratejik | Hukuki: decompile koddan türetilmiş yeniden uygulama + ticari ürün | §15 | Hukuk görüşü |
| 16 | Stratejik | Etiketli hile verisi yok: tespit oranı ölçülemez | §16 | Faz 3 öncesi |

---

## 1. Kritik: Gözlem noktamız "tel üzerindeki gerçek" değil

**Ölçüm** (ViaVersion + PacketEvents yüklü, `/whynotac pipeline`):

```
 0 FlushConsolidationHandler   1 timeout   2 splitter   3 decompress
 4 via-decoder                 5 FlowControlHandler      6 pe-decoder-packetevents   7 decoder
 8 prepender   9 compress     10 via-encoder            11 pe-encoder-packetevents  12 encoder
13 unbundler  14 hackfix      15 whynotac_interceptor   16 packet_handler
```

WhyNotAC oyuna en yakın uçta, ViaVersion ve PacketEvents ise ağa yakın uçta ve bayt düzeyinde
çalışıyor. **PacketEvents kodundan doğrulandı** (`ProtocolManagerAbstract`):
- `sendPacket()` → `ChannelHelper.writeAndFlush(channel, byteBuf)`: **önceden kodlanmış
  ByteBuf** gönderir. Handler'ımızdan geçer ama biz yalnızca `Packet` nesnelerine baktığımız için
  **görmezden gelinir.**
- `sendPacketSilently()` → `writeAndFlushInContext(channel, ENCODER_NAME, ...)`: PacketEvents'in
  encoder konumundan yazar, handler'ımıza **hiç uğramaz.**
- PacketEvents dinleyicileri giden bir paketi biz gördükten **sonra** iptal edebilir veya
  değiştirebilir. Gelen bir paketi ise biz görmeden **önce** iptal edebilir.

**Sonuç:** Özel knockback eklentileri (PvP sunucularında, yani hedef pazarımızda neredeyse
standart) PacketEvents üzerinden hız paketi gönderdiğinde WhyNotAC bunu görmez. Fizik motoru
yazıldığında bu her vuruşta "knockback'i yok saydı" yanlış-pozitifi demektir. Tersi de mümkün:
iptal edilmiş bir knockback'i "uygulanmış" sanırız.

**Çözüm (Faz 2b'nin 1 numaralı işi):** Ağ ucunda **bayt düzeyinde iki dinleme noktası**.
- Gelen yön: decompress/ViaVersion çevirisinden sonra, PacketEvents ve diğer eklentiler iptal
  etmeden önce.
- Giden yön: tüm eklenti değişikliklerinden sonra, sıkıştırmadan hemen önce.

Paket kimliği bayttan okunur ve yalnızca modellenen türler (sunucunun kendi codec'leriyle) çözülür.
Chunk gibi büyük paketlerin yalnız kimliği okunur. Protokol aşaması geçişleri (config↔play) ve
handler yeniden düzenlemeleri izlenmeli. Test: ViaVersion + PacketEvents + ProtocolLib yüklüyken,
PacketEvents ile sahte knockback gönderen bir test eklentisi; WhyNotAC bu paketi görmeli.

## 2. Kritik: Client türü bilinmiyor

`via-decoder`/`via-encoder` farklı sürümden gelen client'ın baytlarını 1.21.11'e çeviriyor. Bizim
gördüğümüz her şey "1.21.11 gibi" görünüyor, ama örneğin 1.8 PvP client'ının hareket fiziği
**önemli ölçüde farklı**. Eski client'lar `PlayerInput`/`ClientTickEnd` göndermez (ViaVersion bunları sentezler
ya da sentezlemez). Geyser/Floodgate üzerinden gelen Bedrock oyuncularının hareket modeli ise
bambaşkadır.

**Çözüm:** Handshake'teki protokol sürümünü yakalamak (şu an `minecraft:intention` modellenmemiş),
ViaVersion API ile gerçek client sürümünü almak, Floodgate oyuncularını tanımak, client brand'ini
kaydetmek. Kural: **desteklenmeyen client = yalnız gözlem, kanıt yok.**

## 3. Kritik: Bit-tamlık her yolda geçerli değil

Faz 1'de `Math.sin` ile `StrictMath.sin`'in bu JVM'de 65 536 girdinin 2 197'sinde farklı olduğunu
ölçmüştük. Sinüs tablosu float'a yuvarlandığı için bu farkı tolere ediyor, ama hareket kodunda
tablosuz çağrılar var (1.21.11 decompile):
- `LivingEntity.updateFallFlyingMovement`: `Mth.square(Math.cos(pitch))`. **Elytra uçuşu**
  client'ın CPU/JVM'ine göre son bitte farklılaşabilir (x86 ile Apple ARM intrinsics'leri farklı).
- `LocalPlayer.isHorizontalCollisionMinor`: `Math.acos(...) < 0.13962634F`. Eşiğe yakın açılarda
  iki platform **farklı dala** gider ve sprint biri için devam eder, diğeri için durur.

**Çözüm:** Fizik motorunda bu çağrı noktaları ±1 ulp **aralık aritmetiğiyle** taşınmalı, eşik
karşılaştırmaları iki dalı da hipotez kümesine eklemeli. Ayrıca tüm hareket kodunda `Math.*`
transandantal çağrılarının bir envanteri çıkarılıp uygunluk testine bağlanmalı.

## 4. Yüksek: Sessiz delikler ve kırılgan enum eşlemeleri

- `WhyNotChannelHandler.channelRead`: çeviri istisna fırlatırsa paket oyuna iletilir ama
  **zaman çizelgesine hiçbir şey yazılmaz.** Analiz orada bir paket olduğunu bile bilemez. Faz 1
  testim (`translationFailureFailsOpen`) bunu "doğru davranış" olarak kilitlemiş; bu benim
  hatamdı.
- `PacketTranslator` dört yerde `valueOf(nmsEnum.name())` kullanıyor (`PlayerCommand.Action`,
  `PlayerAction.Action`, attribute `Operation`, `Relative`). Bir Paper/Mojang güncellemesi yeni bir
  sabit eklerse o paket türü **her seferinde** düşer, yine sessizce.

**Çözüm (küçük, hemen):** Çeviri hatası yerine açık bir `Unmodeled("translation-failed:<tür>")`
olayı. Bilinmeyen enum değeri bir `UNKNOWN` sabitine eşlenmeli. NMS enum'ları ile model
enum'larının birebir aynı olduğunu doğrulayan bir uygunluk testi, sürüm güncellemesinde CI'ı
kırmızıya çevirir.

## 5. Yüksek: Meşru client davranışları aim analizini yanıltır

`MouseTurnModel` vanilla fareyi modelliyor. Gerçek oyuncular ise şunları kullanıyor:
- **Zoom modları** (OptiFine, Lunar, Zoomify): FOV'u daraltırken hassasiyeti ölçekler ya da
  sinematik kamerayı açar. Kafes yapısı değişir veya tamamen kaybolur.
- **Gamepad modları** (Controlify vb.): imleç sayımı yok, sürekli dönüş.
- **Kesirli imleç** (HiDPI), oturum içinde hassasiyet değiştirme, spyglass.

Bunlar "hiçbir tam sayı sayım bu dönüşü açıklamıyor" sonucunu **masum oyuncu için** üretir.
**Çözüm:** Aim analizinden önce meşru modların bir kataloğu ve her biri için "bu mod kanıt
üretmez" kuralı. Kafes varsayımı kanıt değil, yalnızca bir model seçimi olmalı.

## 6. Yüksek: Gerçek client ile doğrulama yok

Tüm canlı testler benim yazdığım Python sondasıyla yapıldı. Sonda ancak benim protokol anlayışım
kadar doğru. Örneğin ping'leri ağ thread'inde anında yanıtlıyor, gerçek client ise oyun
thread'inde yanıtlar, dolayısıyla RTT'ler gerçekten düşük ölçülüyor. Client'a özgü kod
(`LocalPlayer.aiStep`: sprint kuralları, eşya kullanma yavaşlatması, otomatik zıplama) sunucu
jar'ında yok. Planlanan "vanilla kahin" bu kodu da kapsamıyor.

**Çözüm:** **Gerçek 1.21.11 client'ını başsız çalıştırmak.** Bu ortamda Xvfb ve Mesa (yazılımsal
OpenGL) kurulu, yani mümkün. Client'a betik halinde girdi verip (tuşlar, fare sayımları) her
tick'te kendi konum/hız durumunu kaydettiren bir test ajanı, fizik motoru için mümkün olan **en
güçlü temel gerçek** olur.

## 7. Yüksek (mimari): "Yapay zekâ banlasın" ile "sıfır yanlış-ban" çelişir

Oyuncu-ömrü başına ≤ 1e-9 yanlış-pozitif hedefini **ampirik** olarak kanıtlamak için milyarlarca
temiz oyuncu-ömrü gerekir. Hiçbir ML modeli bu güvenceyi veremez. Dolayısıyla:
- **Kanıtlanabilir kanıt** (fiziksel imkânsızlık: bilinen sınırlarla hiçbir meşru hipotezin
  açıklayamadığı hareket, protokol imkânsızlığı) tek başına karar verebilir.
- **İstatistiksel/ML kanıt** (aim profili, tıklama dağılımı, hesap paylaşımı) **asla tek başına
  ban** üretmemeli; yalnızca izleme, önceliklendirme ve yetkili incelemesi için kullanılmalı.

Bu, orijinal vizyonda düzeltilmesi gereken bir noktadır: yapay zekâ sistemin gözü ve
önceliklendiricisi olur, cezanın tek başına gerekçesi olmaz.

## 8–9. Güvenlik ve dayanıklılık

- **Parmak izi:** Tüm transaction kimlikleri `0x574E....` ("WN") ile başlıyor ve sayaç sırayla
  artıyor. Bir hile client'ı WhyNotAC'yi anında tanıyıp ping'lerine özel davranabilir. Çözüm:
  oturum başına anahtarlı rastgele kimlikler (bir hash set ile takip).
- **Bellek büyütme:** Bağlantı başına üst sınırlar var ama küresel bir bütçe yok. Analiz kuyruğu
  16 384 olay (≈1.8 MB), giriş öncesi kayıt tamponu 4 096 olay (≈460 KB), bekleyen transaction
  30 000 (≈1 MB, cevap vermeyen bir hile 25 dakika boyunca biriktirebilir). Sunucu listesi ping'leri
  bile oturum açıyor. Çözüm: küresel bellek bütçesi, `MAX_PENDING`'i saniyeler mertebesine
  indirmek, durum (status) bağlantılarını hiç izlememek.
- **Bilerek kör etme:** "Gap'i aşan kanıt yok" kuralı doğru, ama bir hile analizi kendi kuyruğunu
  taşıracak kadar yavaşlatabilirse bu kör noktayı istismar edebilir. Bugün olay başına maliyet
  ~100 ns olduğu için pratik değil. Fizik eklendiğinde maliyet artacak. Çözüm: oturum başına analiz
  bütçesi ve gap sıklığının kendisinin bir sinyal olması.

## 10. Ölçek görünmüyor

JMH mikro ölçümleri iyi (paket başına 115 ns). Ama hiç **makro test** yok: 200 botlu bir
sunucuda TPS etkisi, analiz gecikmesi (paketin alınması ile dinleyicinin işlemesi arası),
kuyruk derinliği, gap/saat. Canlı test için bu metrikler bir panoda olmalı. Mevcut sonda
asyncio ile yüzlerce bota ölçeklenebilir.

## 11. Rakip modeli

Transaction sandviçi "client ping'leri sırayla, oyun thread'inde yanıtlar" varsayımına dayanır.
Dürüst client için bu doğrudur (decompile ile doğrulandı). Bir hile ise ping'i anında yanıtlayıp
knockback'i geç uygulayabilir (anti-knockback), ya da yanıtları tutup hareketi sürdürebilir
(blink). Bugün yalnızca **atlanmış** ve **sahte** transaction'ları yakalıyoruz. "Transaction'lar
tutulurken hareket paketlerinin akmaya devam etmesi" vanilla için imkânsızdır. Bu ucuz ve güçlü
bir protokol kontrolü olarak eksik.

## 12. Derleme ve süreç

- Paper dev-bundle'ı `1.21.11-R0.1-SNAPSHOT`: zaman damgasıyla sabitlenmemiş, aynı commit farklı
  günlerde farklı derlenebilir. Çözüm: `1.21.11-R0.1-20260511.115010-91` gibi tam sürüme sabitlemek.
- CI yalnız birim testlerini çalıştırıyor. En gerçekçi test olan E2E (ve ViaVersion/PacketEvents'li
  interop varyantı) her push'ta çalışmalı.
- Tüm iş tek bir PR'da (#1, 12 000+ satır). Gözden geçirilip `main`'e alınması ve sonrasında faz
  başına PR açılması önerilir.
- Mutasyon testi yalnızca elle yapıldı (iki örnek). Matematik modülü için PIT ile otomatikleşebilir.

## 13. KVKK

HMAC takma adı **anonimleştirme değildir.** Anahtarı elinde tutan için kişisel veridir.
Eksikler: oyuncu bilgilendirme metni şablonu, "verilerimi silin" talebi için bir araç (takma ad
hesaplanıp ilgili kayıtlar bulunabilir; bu kolay eklenir), anahtar yedekleme rehberi.

## 14–16. Stratejik riskler

- **Sürüm:** 1.21.11 Aralık 2025 sürümü. Bugün güncel sürüm 26.3, Paper "4 sürüm gerisindesiniz"
  uyarısı veriyor. Mojang 26.x'te obfuscation'ı kaldırdı, bu da NMS bakımını ucuzlatır. Mimari
  (platformdan bağımsız protokol katmanı) çoklu sürüme hazır. Eksik olan sürüm başına çevirici ve
  fizik sabitleri katmanı.
- **Hukuk:** Vanilla davranışını decompile edilmiş koddan birebir yeniden uygulamak anti-cheat
  topluluğunda yaygın bir pratik, ama ticari ve kapalı kaynaklı bir ürün için Mojang EULA/telif
  açısından bir hukuk görüşü alınmalı. Ben avukat değilim. Henüz bir lisans dosyası da yok.
- **Veri:** Tespit oranını ölçmek için etiketli hile verisi gerekir. Açık kaynak hileler (Meteor,
  Wurst, LiquidBounce) izole bir sanal makinede kayda alınabilir. Kapalı kaynak ücretli ghost
  client'lar kötü amaçlı yazılım riski taşır; bunlar ancak sizin kontrolünüzde, tamamen izole
  ortamda değerlendirilmeli.

---

## Önerilen eylem planı

**Hemen (birkaç saat):** #4 sessiz delikler ve enum testleri · #8 rastgele transaction kimlikleri
· #9 bellek bütçeleri ve status bağlantılarını hariç tutma · #12 dev-bundle sabitleme ve CI'da
E2E · #13 silme aracı.

**Faz 2b (fizikten önce):** #1 bayt düzeyinde tel dinleme (interop testleriyle) · #2 client türü
tespiti · #6 başsız gerçek client ile temel gerçek · #10 yük testi ve metrikler.

**Fizik / Faz 3 ile birlikte:** #3 aralık aritmetiği · #5 meşru mod kataloğu · #11 blink/tutma
kontrolü · #7 kanıt sınıflandırması (kanıtlanabilir ve istatistiksel).

**Karar gerektirenler (sizde):** #14 sürüm stratejisi · #15 hukuk görüşü · #16 hile verisi
toplama yöntemi.

---

## Durum (2026-10-06, "Hemen" grubu tamamlandı)

| # | Çözüm | Doğrulama |
|---|---|---|
| 1 | **(2026-10-07)** Zaman çizelgesi ağın kenarında, oyunun kendi codec'leriyle baytlardan kaydediliyor. Gelen tarafta diğer eklentilerin decoder'larından önce, giden tarafta encoder'larından sonra. Oyunun görünümüyle uzlaştırılıyor: eklenen, iptal edilen ve yeniden yazılan paketler `Divergence` olayıdır. Oyunu atlayan fizik paketi kenarda sandviçlenir. Yalnız protokol 774 çözülür. Başka sürümler oyun görünümünde kalır ve #2'ye bağlıdır. Ayrıntı: [Faz 2b-1 raporu](../phases/PHASE-2B-1-REPORT.md). | E2E (iki varyant, gerçek PacketEvents): zaman çizelgesindeki knockback == client'ın aldığı, bit bit. Tam olarak 2 beklenen sapma |
| 2 | **(2026-10-07, kısmen)** `ClientFact` olayları güven düzeyleriyle kaydediliyor: kimlik (yapılandırmadan; uzun ömürlü durum yalnız doğrulanmış kimliklere), topoloji (doğrudan, proxy protokolü, Velocity, BungeeCord, Geyser süreç içi), gerçek protokol (handshake baytları ya da ViaVersion API), çevirici, Bedrock (yalnız Floodgate/Geyser API'si), transaction kökeni (1.17 öncesi = çevirici yanıtlar). Brand, kanal kayıtları ve ayarlar `CLAIMED` olarak, sınırlı ve minimal kaydediliyor. Kural: bir beyan hiçbir kontrolü kapatamaz. **Açık:** çevrilmiş client'lar için ikinci kenar katmanı (çeviricinin arkasında 774 görünümü), proxy üzerinde çeviri tespiti, Velocity eşlik eklentisi. | Birim: kimlik politikası matrisi, beyanların sınırlanması. E2E: tüm olgular doğru güvenle, dil kaydedilmiyor |
| 4 | Çevrilemeyen paket artık açık bir `Untranslatable` olayı (giden ise sandviçlenir). Enum'lar `EnumMapping` ile eşlenir; bilinmeyen değer `UNKNOWN` olur. `ProtocolSurfaceTest`, 66 serverbound + 139 clientbound play paketinin hepsini sınıflandırır ve enum'ların birebir aynı olduğunu doğrular. | Birim + Netty testleri |
| 6 | **(2026-10-07)** Resmi, değiştirilmemiş 1.21.11 client'ı başsız çalışıyor: Xvfb + Mesa yazılım OpenGL, gerçek klavye/fare girdisi XTest ile (GLFW ve client'ın kendi girdi kodundan geçer). Her indirilen dosya Mojang'ın yayımladığı SHA-1 ile doğrulanır. CI'da her push'ta çalışır: [tools/realclient](../../tools/realclient/README.md). **Açık:** modlu client'lar (Fabric + Sodium/Lunar benzeri) ve gamepad, #5 kataloğuyla birlikte. | Gerçek client: 78/78 yaw değişimi tam sayı fare adımlarıyla `MouseTurnModel` üzerinden açıklanıyor. Oyun fazında tick içinde pong yok (558 pong). 0 sapma, brand `vanilla`, ~20 tick/s |
| 8 | Kimlikler `SecureRandom`, `[-32768, 32767]` aralığı diğer eklentilere bırakıldı. Atlanan, iki kez yanıtlanan ve **hiç yanıtlanmayan** (`TRANSACTIONS_WITHHELD`) transaction'lar ayrı ayrı tespit edilir. | E2E: 291 ping, ortak önek yok |
| 9 | Oturum başına ve global olay bütçesi (taşma = açık `Gap`). Status (sunucu listesi) bağlantıları oturum açmadan ayrılır. Executor kapanırken kuyruk kaybolmaz. | Birim testleri + E2E "ping oturum bırakmaz" |
| 7 | **(2026-10-08)** Çelişki [POLICY.md](../POLICY.md) ile çözüldü: otomatik ceza yalnızca inşa gereği sağlam kanıttan (P-A) gelir. İstatistik ve yapay zekâ (D, S) asla ceza vermez. Matris kodda veri olarak duruyor ve operatör yasak bir hücreyi açamaz. | Belgeyle hücre hücre eşlik testi; her hücre için test |
| 10 | **(2026-10-08)** 100 botluk yük testi, A/B ve bileşen ayrıştırması: [sonuçlar](../benchmarks/2026-10-08-load-100/README.md). Dürüst botlara 0 anomali, 0 sapma, 0 kayıp olay, 0 vaka. Maliyet: yaklaşık 2.9 ms CPU/oyuncu/saniye, ana thread etkilenmiyor. **Açık:** maliyeti 1 ms'nin altına indirmek, 500-1000 oyuncu. | `tools/load`, JFR thread CPU'su |
| 12 | SNAPSHOT'lar zaman damgalı yapıya sabitlendi, sabitlenmemiş SNAPSHOT derlemeyi durdurur. Jar bayt bayt tekrarlanabilir. CI her push'ta yalın ve ViaVersion+PacketEvents'li iki canlı E2E çalıştırır. | Temiz iki derleme aynı sha256. E2E 30/30 (iki varyant) |
| 13 | `find`/`forget` aracı, yönetici rehberi ve TR/EN oyuncu bilgilendirme şablonu: [docs/privacy](../privacy/README.md). | Birim testi + E2E: `usercache.json`'daki UUID ile bul ve sil |

Bu sırada bulunan bir hata: `/whynotac inspect`, bir paketin yaw'ını bir sonrakinin farkıyla
yazdırabiliyordu (iki ayrı volatile alan). Artık tek bir değişmez kayıt olarak yayımlanıyor ve
eşzamanlı bir testle korunuyor.

**Açık kalanlar (önerilen sırayla):** Faz 2b: ~~#1 bayt düzeyinde tel dinleme~~ (kapatıldı) · #2 client türü
tespiti · #6 başsız gerçek client · #10 yük testi. Ardından fizik ile birlikte: #3, #5, #11, #7.
#16 için izole VM'de açık kaynak hile kaydı onaylandı.
