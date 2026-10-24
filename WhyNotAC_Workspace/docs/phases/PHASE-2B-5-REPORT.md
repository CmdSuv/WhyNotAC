# Faz 2b-5 Raporu: Client'ın gördüğü varlıklar

**Amaç:** Reach, hitbox ve vuruş kontrollerinin ihtiyaç duyduğu ikinci şey: client'ın diğer varlıkları
(oyuncular, mob'lar, tekneler) **nerede gördüğü**. Bu, sunucunun bildiği konum değildir. Client varlık
paketlerini geç alır, konumu üç tick boyunca enterpolasyonla yaklaştırır, bazı paketleri hiç uygulamaz
(tick'lenmeyen chunk, profil kaydı olmayan oyuncu). Bu fazda client'ın varlıkları client'ın işlediği
sırayla, tick tick yeniden kuruldu ve sunucunun takibiyle karşılaştırıldı.

## Ne yapıldı

### Yakalama
- Varlık paketleri:
  - `add_entity`, `remove_entities`;
  - `move_entity_pos/pos_rot/rot`;
  - `entity_position_sync`, `teleport_entity` (tüm alanlarıyla), `rotate_head`;
  - `set_passengers`, `move_vehicle`;
  - `set_entity_data`: skaler değerler birebir, gerisi yalnız sayılır.
- Bu fazda eklenenler:
  - `ticking_state` ve `ticking_step`: donmuş oyunda client oyuncular dışındaki varlıkları tick'lemez;
  - `player_info_update` ve `player_info_remove`: client, profilini almadığı bir oyuncu varlığını hiç
    oluşturmaz.
- Kayıt formatına 27-39 numaralı etiketler eklendi. Eski kayıtlar okunmaya devam eder.
- Varlık türü tablosu (157 tür: canlı mı, kaç adımda enterpolasyon) elle yazılmadı. Bir test onu oyunun
  kendi sınıflarından (alanların generic türü ve sınıf hiyerarşisi) üretir ve gönderilenle karşılaştırır.

### Client ne zaman neyi işler (decompile edilmiş 1.21.11)
Client paketleri yalnız kare başında, `PacketProcessor.processQueuedPackets` içinde işler. Kuyruk
boşalana kadar devam eder, sonra karenin tick'lerini koşar. Her tick varlıkları tick'ler
(`ClientLevel.tickEntities`) ve tek bir `ClientTickEnd` ile biter. Pong'lar ping işlenirken gönderilir.
Bundan şu kural çıkar:

> Client her an gönderilen akışın tam bir **önekini** işlemiştir: en az cevapladığı son ping'e kadar
> olanı, en fazla henüz cevaplamadığı ilk ping'den öncesini.

İkisinin arası (pencere) bir tick'ten önce işlenmiş de olabilir, işlenmemiş de.

### Her flush ping ile çevrili
Ağ kenarı artık her flush'ı iki transaction ile çevirir: flush'tan sonraki ilk kareden önce bir tane,
flush'tan hemen önce bir tane. O noktada zaten bir transaction varsa (tick başına olan ya da fizik
sandviçi) yenisi yazılmaz. Client bir flush'ı neredeyse her zaman tek seferde okur, iki cevap aynı iki
tick arasına düşer ve flush'taki her paketin hangi tick'ten önce işlendiği kesin bilinir.

Ping bundle'ların içine ve login'den önceye konmaz. Yapılandırmaya geçişten sonra (yeni login'e kadar)
da konmaz. `observation.flush-transactions` ile kapatılabilir.

E2E'de etkisi açıkça görüldü: çevrelemeden önce replika 10 kez dallandı, sonra 0 kez.

### Replika: aday durumlar
`ClientEntities` (`whynotac-world`) her varlık için bir ya da birkaç **aday** tutar. Her aday, pencerenin
paketleri ile tick'lerin olası bir sırası için client'ın tam olarak tuttuğu durumdur. Kurallar client'ın
kendisinden:
- **Codec:** `VecDeltaCodec` birebir. Göreli hareket tabana göre 1/4096 ızgarada çözülür, sıfır bileşen
  tabanı korur.
- **Enterpolasyon:** `InterpolationHandler` birebir:
  - hedef aynıysa sürer, değilse yeniden başlar (`Objects.equals`: -0.0 ile 0.0 farklıdır);
  - `lerp`/`rotLerp`, `%360`, `setXRot` sınırlaması;
  - adım bitince `previousTickPosition` kalır.
- **Anında konumlama:** 64 bloktan uzak `entity_position_sync` ya da tick'lenmeyen varlık snap edilir
  (enterpolasyon iptal edilmez). Canlıda `absSnapTo` (±3e7 sınırı), diğerlerinde `snapTo`. Türüne göre
  enterpolasyonsuz varlıklar paketle anında taşınır, display'ler snap eder.
- **Kafa:** `lerpHeadTo(y, 3)` ve `rotLerp(1/adım)`.
- **Tick'lenme:** oyuncular her zaman tick'lenir. Diğerleri yalnız chunk'ları client'ın deposunda
  tick'liyorsa tick'lenir: chunk yüklenince başlar, düşürülünce ya da yuvası başka chunk'a geçince
  durur. Görüş daralınca depodan düşen chunk tick'lemeye devam eder. Oyun donmuşsa tick'lenmezler.
- **Oyuncu profili, yolcular, kaldırma:** profili olmayan oyuncu oluşturulmaz. Aynı kimlikle gelen yeni
  varlık eskisini siler. Yolcular `startRiding` kurallarıyla izlenir. Kaldırılan varlığın yolcuları iner.
  Seviye değişimi yalnız boyut değişince olur.

Client'ın kararı bu replikanın modellemediği bir şeye bağlıysa iki sonuç da tutulur. Örnek:
enterpolasyon sırasında varlık dışarıdan taşındıysa hedefin onunla gidip gitmeyeceğini bir çarpışma
testi belirler.

Aynı hedeflere varan adaylar, her üyeyi kapsayan bir **yarıçapla** tek adayda birleşir. Yarıçap 0 olan
aday bit-tam doğrudur. Yarıçap her enterpolasyon adımında en az üçte bir küçülür. 16 adayı aşan varlık,
sunucu onu yeniden ekleyene ya da kaldırana kadar "bilinmiyor"dur.

### Neyin bilinmediği açıkça söylenir
- **Konum bilinmiyor:**
  - yolcular (araçları yerleştirir);
  - oyuncunun kontrol edebileceği araç;
  - uyuyan canlı (yatağa konur);
  - shulker (`setPos` blok merkezine oturtur);
  - dinlenen yarasa ve ölen ejderha (kendini taşır);
  - asılı varlıklar (konumu bloktan yeniden kurulur);
  - display'ler (veriden enterpolasyon süresi).
- **Tümüyle bilinmiyor:** kendi fiziğini çalıştıran canlı olmayan varlıklar (eşya, ok, TNT, düşen blok,
  tekne, vagon, ...). Dünyadan düşüp silinmiş olabilirler.

  Bu liste decompile edilmiş client'ın her `tick`/`aiStep`/`baseTick` geçersiz kılmasından çıkarıldı.
  Diğer bütün canlılar uzaktayken yalnız enterpolasyonla hareket eder.
- **Olay kaybı ve okunamayan paket:** olay kaybı (`Gap`) ya da okunamayan bir varlık paketinden sonra
  varlıklar yeniden eklenene kadar bilinmez.

## Doğrulama

| Test | Sonuç |
|---|---|
| Birim | 22 senaryo: üç adımlı enterpolasyon (elle hesaplanan vanilla değerleriyle bit-tam), enterpolasyonsuz tür, iki ping arasında tick olmayan hareket (kesin), arada tick olan hareket (iki aday, sonra birleşme), sürekli hareket altında yarıçapın küçülmesi, profilsiz oyuncu, değiştirme ve kaldırma, yeniden doğuş, yakın ve uzak senkron, `teleport_entity` (yakın, uzak, göreli; kod tabanına dokunmaz), tick'lenmeyen ve unutulan chunk, daralan görüşte tick'lemeye devam eden chunk, yolcu, kontrol edilebilir araç, uyuyan canlı, olay kaybı, okunamayan paket, yapılandırmaya geçiş, kafa enterpolasyonu, donmuş oyun, cevapsız ping |
| Ağ kenarı | Her flush iki transaction ile çevrili; kenardaki bir transaction (tick başına ya da fizik sandviçi) yenisinin yerini tutar; bundle'ın içine girilmez; login'den önce ve kapalıyken çevreleme yok |
| Simülasyon (sağlamlık) | Bağımsız yazılmış bir referans client: rastgele gecikme, rastgele TCP bölünmesi (paketlerin %50'sine kadar), rastgele kare ve tick zamanlaması, rastgele çarpışma sonuçları, uzak senkronlar ve chunk dışına çıkan mob'lar. 1000 tohum ve 4 ping düzeninde **829.571 kontrolde 0 ihlal**: replikanın olası durumları her tick sonunda client'ın gerçek durumunu kapsadı (yarıçap 0 dediği yerde bit-tam) |
| Simülasyon (gerçekçi) | Her flush çevrili, paketlerin %1'i gecikmeli: **%90,8 bit-tam, %96,2 1e-6 blok içinde**, %0,7 birden çok aday, 0 taşma. Kalan kısım, bir gecikmeden sonra küçülen yarıçaptır |
| Simülasyon (çevrili, bölünmesiz) | Her kontrolde **%100 bit-tam** |
| E2E (gerçek Paper) | AI'sız husk ve köylü, binici, zırh askısı, end kristali, öldürülen inek; mutlak, göreli ve dönüşlü ışınlamalar. `/whynotac entities`: sunucunun oyuncuya gösterdiği her varlık replikada, eksik ya da fazla yok. **Her varlığın kod tabanı sunucununkine 1/4096 ızgarada eşit (6/6)**, yolcu ayrıldı, taşma 0. 81 E2E kontrolü geçiyor |
| Gerçek client (CI, 1.21.11) | Değiştirilmemiş client sunucuya bağlanıp yürüyor. `/whynotac entities`: sunucunun gösterdiği 8 varlığın hepsi replikada, 8/8 kod tabanı 1/4096 ızgarada eşit, 3 dallanma, 0 taşma |

> **Düzeltme (2026-10-14):** Bu tablodaki E2E ve gerçek client testleri "peaceful" zorlukta çalıştı. Bu
> zorlukta düşman mob oluşturulamadığı için husk aslında hiç yoktu ve "6/6" onu içermiyordu. Testler artık
> varlık bölümünden önce zorluğu "easy"ye alıyor. Konum tabanları husk'larla birlikte E2E'de 11/11, gerçek
> client'ta 7/7 eşit ([Faz 2b-6](PHASE-2B-6-REPORT.md)).

## Yük testi (gerçek arazi, cluster)

Ayrıntılar: `docs/benchmarks/2026-10-09-entities/`.

- **Doğruluk:** bütün koşularda 0 kayıp olay, 0 sapma, 0 anomali, 0 dinleyici hatası ve 0 vaka.
- **Flush çevrelemesi (40 bot):**
  - çevrili: 134.897 varlık tick'inde 1.029 dallanma, 0 taşma;
  - çevrelemesiz: 798.816 dallanma, 237 taşma.
- **İlk ölçüm (`5a1c8fe`, 100 bot):**
  - sunucu 2,0 TPS'e düştü (eklentisiz 11,9) ve bir tick 11,7 saniye sürdü;
  - sunucunun iki çekirdeği eklentisiz bile doluydu, Netty'deki ek iş ana thread'i aç bıraktı.
- **Düzeltilen sıcak noktalar (JFR):**
  - chunk tick'leme aramasındaki boxing ve her ping'de bütün geçmişin budanması (ilkel `LongMap`);
  - JDK 21 tür `switch`'inde varlık paketlerinin 25. sırada olması;
  - `MovePlayer`'ın method handle'lı `equals/hashCode`'u;
  - blok güncellemesinde 4096 kutulanmış `HashMap` işlemi.

  Sonuç 9,4 TPS oldu.
- **Maliyet, sunucu doymadan (40 bot):**
  - WhyNotAC yaklaşık 0,27 çekirdek harcıyor: Netty +0,13, analiz 0,13, kayıt 0,02. Bu, oyuncu başına
    saniyede yaklaşık 8 ms.
  - Bu koşuda her oyuncu her tick'te 31 hareket eden oyuncuyu alıyor.
  - Flush çevrelemesinin payı Netty'de +0,024 çekirdek.
- **Ölçüm aracındaki hata:** thread başına CPU 2 kat yüksek hesaplanıyordu. Düzeltildi ve önceki yük
  belgeleri yeni değerlerle güncellendi:
  - düz dünya: 1,5 ms/oyuncu/s;
  - gerçek arazi: 3,7 ms/oyuncu/s.
- **Hedef aynı:** oyuncu başına saniyede 1 ms'nin altı. Kalan sıcak noktalar ve sıradaki adımlar benchmark
  belgesinde.

## Sınırlar ve sonraki adımlar
- **Client'ta başka varlıkları iten şeyler henüz izlenmiyor:** hareket eden piston
  (`PistonMovingBlockEntity`) ve kafasını çıkaran shulker. Bunların yanındaki varlıkların konumuna, bu
  eklenene kadar hiçbir kontrol güvenmemeli. Bunun için replika client'ın dünyasıyla birleştirilecek.
  Enterpolasyondaki çarpışma testi de o zaman dallanmak yerine hesaplanacak.
- **Hitbox'lar:** türe, poza, ölçeğe ve yaşa göre boyutlar `EntityDimensions`'tan, tür tablosu gibi
  oyunun kendisinden üretilecek. Spectator durumu oyuncu listesindeki oyun modundan alınacak. Reach ve
  hitbox kontrolleri bunun üzerine kurulacak.
- **Gerçek client karşılaştırması:** gerçek client'ın varlık konumlarını tick tick kaydetmek
  (`ClientLevel.tickEntities`) ve replikayla karşılaştırmak. Simülasyon bağımsız yazıldı, ama aynı
  okumaya dayanıyor; nihai doğrulayıcı gerçek client olacak.
