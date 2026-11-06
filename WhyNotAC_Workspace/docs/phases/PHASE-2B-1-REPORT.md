# Faz 2b-1 Raporu — Ağ Kenarında Tel Gerçeği

**Durum:** tamamlandı · **Hedef:** Paper 1.21.11 (protokol 774) · **Kapatılan zayıflık:** #1 (kritik)

## 1. Sorun

Zayıflık analizinin 1. bulgusu ölçümle doğrulanmıştı. WhyNotAC paketleri oyunun `packet_handler`'ının
hemen önünde, **nesne** olarak görüyordu. Arada çalışan eklentiler ise baytlarla oynuyor:

- ViaVersion başka sürümdeki client'ların baytlarını çevirir.
- PacketEvents (ve onu kullanan eklentiler) paket iptal eder, yeniden yazar, ekler.
  `sendPacketSilently` önceden kodlanmış baytları oyunun gönderim yolunun **altından** yazar.

Sonuç: Bir eklentinin gönderdiği knockback client'a ulaşır, fizik modelimiz ise onu hiç bilmez.
Dürüst bir oyuncunun yükselişi "sebepsiz" görünür. Bu, sıfır yanlış-ban hedefini doğrudan tehdit
eden bir körlüktü.

## 2. Çözüm

| Bileşen | İçerik |
|---|---|
| `wire/WireTaps` | İki Netty handler'ı. `whynotac_wire_in` → `decompress` (yoksa `splitter`) yanında, diğer tüm eklentilerin decoder'larından **önce**. `whynotac_wire_out` → `compress` (yoksa `prepender`) yanında, diğer tüm eklentilerin encoder'larından **sonra**. Fail-open: her çerçeve değişmeden ve tam bir kez iletilir. |
| `wire/WireProtocols` | Oyunun kendi 1.21.11 `StreamCodec`'leri, durum (handshake, login, configuration, play) ve yön başına, id → tip tablosuyla. Play tabloları sunucunun registry'lerine bağlanır. |
| `wire/WireObserver` | Bağlantı başına kenar durumu. Kendi protokol durum makineleri (terminal paketlerle: `intention`, `login_acknowledged`, `finish_configuration`, `configuration_acknowledged`, `login_finished`, `start_configuration`). Yalnızca modellenen paketleri çözer (diğerleri yalnızca id ile). Zaman çizelgesini kenardan yazar. |
| `wire/Reconciler` | Kenar ile oyunun görünümünü sırayla eşler. Yalnızca bir tarafta olan her paket açık bir olaydır. |
| `protocol`: yeni olaylar | `Observation` (hangi görünüm, client'ın gerçek protokolü), `InboundDivergence` / `OutboundDivergence` (`WIRE_ONLY` / `GAME_ONLY`). Kayıt formatında etiket 8–10 (yalnız ekleme). |
| `whynotac-interop-probe` (test-only, yeni) | Gerçek sunucuda knockback'i üç yoldan gönderen eklenti: oyunun yolu, oyunun altından enjekte, oyunun gönderip alttan iptal edilen. PacketEvents kuruluysa onun gerçek API'siyle, değilse aynı konumdan ham Netty ile. Asla dağıtılmaz. |

### Neden "kenar", neden vanilla codec

Kenarda görülen, client'ın gönderdiği ve aldığı baytların ta kendisidir (şifre çözülmüş ve
sıkıştırma açılmış). Bu baytları elle yazılmış bir ayrıştırıcıyla değil, **oyunun kendi codec'iyle**
çözüyoruz. Böylece her bayt client ve sunucu nasıl okuyorsa öyle okunur. Fazla bayt bırakan bir
paket (oyun da reddeder) "çevrilemedi" işaretine dönüşür, sessizce yutulmaz.

### Yerleşim ve ViaVersion

Paper sıkıştırmayı `ConnectionEvent.COMPRESSION_THRESHOLD_SET` ile duyurur. ViaVersion'ın
kodunu decompile ederek doğruladık: ViaVersion bu olayda kendini senkron olarak
`decompress`/`compress`'in hemen yanına taşır. Biz olayı görünce yerleşimi **bir event-loop
görevi sonraya** erteliyoruz. Böylece herkes tepkisini verdikten sonra tap'ler yine en dışa
döner. Gerçek ViaVersion 5.12.0 ile E2E'de ölçüldü: 1 onarım, son sıra
`decompress → whynotac_wire_in → via-decoder` ve `compress → whynotac_wire_out → via-encoder`.
Her 5 saniyede bir denetim, sonradan araya giren başka eklentileri de düzeltir.

### Uzlaştırma

- **Gelen:** referans kenardır (client'ın gönderdiği). Oyun bir paketi işlediğinde eşleşir.
  Kenarda olup oyunun hiç işlemediği paket `WIRE_ONLY`'dir (bir eklenti iptal etti). Oyunun işleyip
  client'ın hiç göndermediği paket `GAME_ONLY`'dir (bir eklenti uydurdu).
- **Giden:** referans oyundur (oyunun gönderdiği). Kenar gördüğünde eşleşir. Oyunun gönderip
  client'a ulaşmayan paket `GAME_ONLY`'dir (alttan iptal). Client'a ulaşıp oyunun göndermediği
  paket `WIRE_ONLY`'dir (alttan enjekte).
- **Gecikme sapma değildir:** ViaVersion ve PacketEvents play başında paketleri bir süre tutup
  sonra bırakıyor. İlk sürüm bunları 24 çift sahte sapma olarak raporladı (gerçek sunucuda
  bulundu, §5). `Reconciler` herhangi bir bekleyen kaydı eşleyebilir. Bir kayıt ancak (a) ondan
  sonra duyurulan bir kayıt eşleştiğinde **ve** (b) 2 saniyelik süre dolduğunda eksik sayılır.
  Sınırlar: en çok 4 096 bekleyen kayıt, eşleme başına en çok 256 karşılaştırma.
- **Yeniden yazılan paket** iki olaydır: orijinal `GAME_ONLY`, client'ın aldığı `WIRE_ONLY`.
  Zaman çizelgesinde **client'ın aldığı** değer vardır.
- **Kayıplı alanlar:** knockback telde LpVec3 ile nicemlenir. Karşılaştırmada oyunun değeri
  bit-tam `LpVec3.quantize` ile telin biçimine getirilir.

### Kenarda sandviç

Oyunu atlayan bir fizik paketi (ör. PacketEvents ile enjekte knockback) oyun tarafındaki sandviçe
giremez. Kenar bu durumda client'ın 774 codec'iyle bir Ping'i baytlarla kodlayıp paketin önüne ve
arkasına yazar. Gerçek sunucuda doğrulandı: client'ın kendi sırasında enjekte knockback iki
transaction arasındadır.

### Kenar çözülemediğinde

Kenar yalnızca protokol 774'ü çözer. Handshake baytlarından okunan gerçek protokol farklıysa
(ViaVersion arkasındaki 1.8 client), tap'ler bağlantıdan çıkar ve oyunun görünümü kullanılır.
WhyNotAC'den önce kurulmuş bağlantılarda da (geç yükleme) aynısı olur. Her iki durum da
`Observation` olayıyla kayda geçer. Bir sunucu listesi ping'i tap'leri hemen ayırır.

## 3. Testler

**Birim ve Netty testleri:** toplam 139 test, hepsi geçiyor (önceki adımda 118). Kenar paketi gerçek
vanilla `PacketDecoder`/`PacketEncoder`/`PacketBundleUnpacker` ve 1.21.11 play codec'leriyle kurulmuş
bir `EmbeddedChannel` üzerinde test ediliyor:

| Senaryo | Beklenen ve doğrulanan |
|---|---|
| Uyumlu akış (gelen/giden) | Kenardan kayıt, sapma yok. Knockback nicemlenmiş değeriyle |
| Gelen paket oyundan önce iptal | Zaman çizelgesinde var (client gönderdi) + `WIRE_ONLY` |
| Oyunun uydurduğu gelen paket | Zaman çizelgesinde yok + `GAME_ONLY` |
| PacketEvents `sendPacketSilently` benzeri | Zaman çizelgesinde var + `WIRE_ONLY` + kenarda Ping–paket–Ping |
| PacketEvents `sendPacket` benzeri (kuyruktan bayt) | Aynı |
| Oyunun knockback'i alttan iptal | Zaman çizelgesinde **yok**, `GAME_ONLY` |
| Knockback yeniden yazıldı | Client'ın aldığı değer + `WIRE_ONLY`, `GAME_ONLY` |
| Tutulup sonra bırakılan paketler | Sapma yok |
| Bundle | İki görünümde de düzleştirilmiş, sapma yok |
| Handshake 774 / 47 / status | Kenar / oyun görünümü + tap'ler çıkar / tap'ler çıkar |
| ViaVersion benzeri yer değiştirme + sıkıştırma | Tap'ler en dışa döner, kenar doğru çözmeye devam eder |
| Denetim | Dışarıya giren handler düzeltilir, ikinci denetim no-op |

Test gücü mutasyonla kontrol edildi. "Enjekte paketi beklenen say" mutasyonunu 3 test, "iptali
raporlama" mutasyonunu 2 test yakaladı.

**Canlı E2E (gerçek Paper 1.21.11):** 39 kontrol (önce 30), CI'da iki varyant. Yeni kontroller:

- Zaman çizelgesi kenardan kaydediliyor (`Observation` WIRE/WIRE/774).
- **Zaman çizelgesindeki knockback == client'ın aldığı knockback, bit bit ve aynı sırada.**
  Sonda, client'ın LpVec3 çözümünü işlem işlem taklit eden bağımsız bir Python çözücü var.
- Oyun yolundan gelen knockback (0.3) ve oyunun altından enjekte edilen (0.5) client'a ve zaman
  çizelgesine ulaştı. Alttan iptal edilen (0.7) ikisinde de yok.
- Tam olarak iki sapma var: enjekte (`WIRE_ONLY` 0.5) ve iptal (`GAME_ONLY` 0.7). Başka hiçbir
  sapma yok (ViaVersion + PacketEvents kuruluyken de).
- Client'ın aldığı her knockback, client'ın kendi sırasında iki transaction arasında. Enjekte
  edilen de, kenarda.
- Interop varyantında enjeksiyon ve iptal **gerçek PacketEvents API'siyle** yapıldı.

## 4. Performans (JMH, `docs/benchmarks/2026-10-07-edge.json`)

| Yol | Süre | Ayırma |
|---|---|---|
| Gelen hareket: çöz + çevir + kaydet + uzlaştır | ≈ 300 ns | 312 B |
| Giden knockback: çöz + nicemle + kaydet + uzlaştır | ≈ 337 ns | 496 B |
| Giden modellenmeyen (8 KiB chunk): yalnız id | ≈ 79 ns | 136 B |
| (Karşılaştırma) nesne görünümü, uçtan uca | ≈ 119 ns | 112 B |

Kenar, modellenen paket başına ≈ 180–220 ns, modellenmeyen başına ≈ 80 ns ekler. 100 oyuncu ×
(20 gelen + ≈100 giden paket/s) ≈ 12 000 paket/s, yani bir çekirdeğin ≈ %0.2'si. Daha
iyileştirilebilir (bekleyen kayıt nesneleri, `List.of`), ama şu an darboğaz değil.

## 5. Gerçek sunucuda bulunan ve düzeltilen hatalar

1. **ViaVersion/PacketEvents'in play başındaki tamponlaması** 24 çift sahte sapma üretti. İlk
   tasarım "yazma dönünce görülmemişse iptal" diyordu. Gerçek dünyada yanlıştı, `Reconciler`'ın
   gecikmeye dayanıklı kuralıyla değiştirildi ve testle sabitlendi.
2. **Hermetik olmayan E2E:** Superflat dünyada `spawn-monsters=false` olmasına rağmen bir slime
   sondayı öldürdü. Ölüm kaydedilen dünyada kaldı ve sonraki koşuda sonda ölü başladı (knockback
   almadı). Artık her koşu temiz dünya ve `difficulty=peaceful` ile başlıyor. Yan bilgi: slime'ın
   gerçek dövüş knockback'i de kenarda bit-tam doğru kaydedilmişti.
3. **JSON dökümü:** Bir kaydın `type` adlı alanı (`GameEvent.type`, `Unmodeled.type`), JSON'daki
   `type` ayırıcısıyla çakışıyordu (yinelenen anahtar). Artık `typeId` olarak yazılıyor.
4. Bu fazdan hemen önce: `/whynotac inspect`, bir paketin yaw'ını sonraki paketin farkıyla
   yazdırabiliyordu (bkz. ilgili commit).

## 6. Sınırlar ve sonraki adım

- Kenar yalnız 774'ü çözer. Başka sürümdeki client'lar oyun görünümündedir ve fizikleri
  farklıdır. **Faz 2b-2: client türü tespiti** (handshake protokolü, ViaVersion API,
  Floodgate/Geyser, brand) bu client'lar için modelleme ya da analiz dışı tutma kararını verecek.
  Bir client'ın kendi beyanı (brand vb.) hiçbir zaman muafiyet sebebi olmayacak.
- Bundle sınırları zaman çizelgesine yazılmıyor (iki görünüm de düzleştiriyor). Client bir
  bundle'ı tek tick'te uygular; fizik motoru bunu kullanacaksa `BundleBoundary` modeli eklenecek.
- **Kapatıldı (format sürüm 2):** Eskiden bilinmeyen bir kaydı atlayan okuyucu, o kayıtta tanımlanan
  metin tablosu girdilerini kaçırıyor ve sonraki tüm metin referansları kayıyordu. Sürüm 2'de her
  tanım kendi indisini taşır (`0, indis, utf8`). Atlanan bir kayıt yalnızca kendi içinde tanımlanan
  metinlere mal olur. Bunlara yapılan referanslar açıkça `whynotac:unknown-recorded-string/<indis>`
  olarak okunur, asla tahmin edilmez. Sürüm 1 kayıtları okunmaya devam eder (golden test iki sürümü
  de kilitliyor).
