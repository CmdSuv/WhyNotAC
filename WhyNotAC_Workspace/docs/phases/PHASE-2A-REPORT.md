# Faz 2a Raporu — Oturum Kaydı, Birebir Yeniden Oynatma ve Performans Tabanı

**Durum:** tamamlandı, onay bekliyor · **Hedef:** Paper 1.21.11 (protokol 774)

## 1. Neden bu modül önce geldi

"Temiz oyuncuyu asla banlamama" iddiası ancak gerçek temiz oyun verisi üzerinde **ölçülebilir**.
Fizik motoru da kayıtlı oturumlar üzerinde geliştirilip test edilmelidir. Bu faz, canlı test
başladığı gün her oturumu kaydedecek, kaydı yeniden oynatacak ve maliyetini bilecek altyapıyı
kurar. Kayıtla bulunan her yanlış-pozitif kalıcı bir regresyon testine dönüşür.

## 2. Teslim edilenler

| Bileşen | İçerik |
|---|---|
| `whynotac-recording` (yeni) | Sürümlü, çökmeye dayanıklı blok formatı; her olay/paket tipi için bit-tam kodek; takma adlandırma (HMAC-SHA256); tek I/O thread'i ve bayt bütçesi; yaş/boyut saklama politikası; hasara dayanıklı okuyucu; deterministik replay; `inspect`/`dump` aracı |
| `whynotac-protocol` | Replay oturumları (`openReplay` / `deliver`) + **gerçek bir veri kaybı hatasının düzeltilmesi** (bkz. §6) |
| `whynotac-paper` | `config.yml` (kayıt varsayılan **kapalı**), sunucuya özel gizli anahtar, kayıtlar sonlanana kadar sınırlı kapanış beklemesi, eklenti jar'ı aynı zamanda çevrimdışı araç |
| `whynotac-bench` (yeni) | JMH mikro-ölçümleri (GC profilleyici ile) |
| `tools/e2e` | Canlı testte kaydın kayıpsız olduğunun kanıtı (20 kontrol) |

Testler: **104 otomatik test** (Faz 1'de 79), hepsi geçiyor. Canlı uçtan uca test: **20/20**.

## 3. Format (sürüm 1) ve arkasındaki mantık

```
başlık : 89 'W''N''R''E''C' 0D 0A | u16 sürüm | varint protokol | oyun sürümü | WhyNotAC sürümü
         | 16B kayıt kimliği | 16B takma ad | i64 başlangıç (ms) | u32 CRC32C(başlık)
blok*  : "WNBK" | u32 sıkıştırılmış | u32 ham | u64 taban sıra | u64 taban ns | u64 taban tick
         | u32 olay sayısı | u64 öncesinde kaybolan | u32 CRC32C(alanlar+yük) | zlib yük
yük    : kayıt*, kayıt = varint etiket | varint uzunluk | gövde
```

- **Bit-tam:** double/float'lar ham IEEE-754 bitleriyle yazılır. NaN yükü ve ±0 korunur. Yokluk
  (`NaN` konum) kanonik değilse açıkça yazılır.
- **Kompaktlık:** Sıra numarası, zaman ve client tick blok içinde delta kodlanır. İşaretli
  değerler ZigZag ile (`0,−1,1,−2 → 0,1,2,3`), sonra LEB128 varint ile yazılır. Bu sayede tipik
  bir tick'teki 50 ms'lik zaman farkı 4 bayta sığar. Tekrarlayan metinler blok başına bir tabloya
  alınır. Sonuç: **saatlik oyun ≈ 1.17 MB, olay başına 3.42 bayt** (yalnız oyuncu trafiği).
- **Bütünlük:** Her blok kendi CRC32C'sini taşır (donanım hızlandırmalı, 32 bit; rastgele hasarı
  kaçırma olasılığı ≈ 2⁻³²). Başlığın her baytının bozulmasının fark edildiği tek tek test edildi.
- **Kendi kendine yeten bloklar:** Delta ve metin tablosu her blokta sıfırlanır. Böylece bozuk bir
  blok yalnızca **kendi olaylarına** mal olur: okuyucu bir sonraki `WNBK` işaretine
  senkronlanır ve kaybı açık bir `Gap` olarak bildirir.
- **İleri uyumluluk:** Her kayıt uzunluk önekli olduğundan, gelecekteki bir olay türünü bilmeyen
  bir okuyucu onu atlar, sırasını korur ve yerine 1 olaylık bir `Gap` koyar.
- **Çökme güvenliği:** Dosya `.wnrec.part` olarak yazılır ve son kayıt diske kalıcı olarak
  yazıldıktan (`force`) sonra atomik olarak `.wnrec`'e taşınır. Bloklar en geç 64 KB'da veya 5
  saniyede mühürlenir, dolayısıyla bir çökme en fazla son ~5 saniyeyi kaybettirir.
- **Format donduruldu:** Sabit bir zaman çizelgesinin baytları golden test ile kilitlendi.
  Formatı yanlışlıkla değiştiren her değişiklik testi kırar. Eski kayıtlar kanıttır ve okunabilir
  kalmalıdır.

**Kayıp semantiği (sıfır yanlış-ban ilkesi):** Okuyucu hiçbir zaman veri uydurmaz. Doğrulanamayan
her şey atılır ve yerine konumu ve büyüklüğü belli bir `Gap` konur. Analiz katmanı bir Gap'i aşan
kanıt kuramaz.

## 4. Gizlilik

- IP adresi ve oyuncu adı **hiç yazılmaz**. UUID, sunucuya özel 32 baytlık gizli anahtarla
  `HMAC-SHA256(anahtar, uuid)`'nin ilk 128 biti (sürüm-8 UUID) ile değiştirilir.
- Aynı sunucuda aynı oyuncu hep aynı takma adı alır (profil oluşturmak için). Anahtar olmadan
  geri çevrilemez, hatta tahmin edilen bir UUID ile doğrulanamaz bile. Anahtarı silmek, geçmiş
  kayıtları gelecektekilerden koparır.
- Sohbet gibi modellenmemiş paketler yalnızca **tür adıyla** kaydedilir, içerikleri değil.
- Giriş yapmayan bağlantılar (sunucu listesi ping'leri, başarısız girişler) dosya oluşturmaz.
- Testte gerçek isim, UUID ve IP için hem çözülmüş kayıt hem ham dosya tarandı: hiçbiri yok.
- Kayıt varsayılan olarak **kapalıdır**. Canlı testte açmadan önce oyunculara bildirilmesi
  gerekir (KVKK açısından sunucu kurallarına bir madde yeterli).

## 5. Performans tabanı (JMH 1.37, 4 çekirdek Xeon 2.1 GHz, JDK 21)

| Ölçüm | Süre | Ayırma |
|---|---|---|
| NMS → model çeviri (hareket paketi) | 10.1 ns | 48 B |
| Oturuma alma (sıra, tick, kuyruk, dağıtım) | 104.6 ns | 64 B |
| **Ağ thread'inde paket başına toplam ek yük** | **115.4 ns** | **112 B** |
| Olay kaydı (kodlama + amortize sıkıştırma) | 110.8 ± 5.0 ns | 156 B |
| `inputVector` / `viewVector` | 12.7 / 8.1 ns | 40 B |
| LpVec3 kuantizasyon | 70.1 ns | 40 B |
| Fare tersine çevirme (tam ikili arama) | 440.8 ns | 32 B |
| `RunningMoments.add` | 8.9 ns | 0 B |

Yorum: Saniyede ~100 paket gönderen bir oyuncu, ağ thread'ine saniyede ≈ 11.5 µs ek yük getirir.
**1 000 oyuncu ≈ tek bir çekirdeğin %1.2'si.** Ham sonuçlar `docs/benchmarks/` altında
saklandı. Gelecekteki her değişiklik bu tabana göre kıyaslanacak. Paylaşımlı bir konteynerde
ölçüldüğü için mutlak değerler değil, büyüklük mertebeleri esas alınmalı.

## 6. Testlerin yakaladığı hatalar

1. **Gerçek veri kaybı (üretimde kayıtları bozardı):** `SerialMailbox` olayları 256'lık
   gruplarla işleyip her grubun sonunda kendini yeniden zamanlıyordu. Analiz havuzu kapanınca bu
   yeniden zamanlama reddediliyor ve kuyrukta bekleyen **tüm olaylar sessizce kayboluyordu**,
   `Disconnected` dahil. Sunucu kapanışında açık olan her kayıt `.part` olarak kalırdı.
   Regresyon testi eski kodda 10 001 olaydan yalnızca **4 914**'ünü teslim etti. Düzeltmeyle
   (havuz yoksa çağıran thread'de teslim) 10 001/10 001.
2. **Okuyucuda çift sayım:** İlk tasarım, bloklar arası her sıra atlamasını kayıp sayıyordu. Oysa
   canlı oturum düşürdüğü olayların numarasını tüketir ve kendisi bir `Gap` kaydeder. Artık yalnızca
   bozuk blok ya da yazarın bildirdiği kayıt varsa sentetik Gap üretiliyor.
3. **Kayıp işaretinin konumu:** Girişten önceki tampon taşarsa kayıp işareti bloğun başına
   düşüyordu (replay'de yanlış yerde görünürdü). Düzeltme: kayıp bildirilmeden önce açık blok
   mühürleniyor, işaret tam yerinde duruyor.
4. **Kendi hatam:** Golden testin ilk sürümüne hex değerini elle uydurmuş ve testi totolojik
   yazmıştım. Fark edip düzelttim: baytlar gerçek koddan üretildi, elle doğrulandı
   (`04`=Bound etiketi, `22`=34 bayt, `3ff8…`=1.5) ve donduruldu.

## 7. Test süreci

- **Özellik testleri:** Rastgele ve kasıtlı uç değerlerle (NaN yükleri, ±0, ±∞, MIN/MAX,
  Unicode, 3 000 karakterlik metin) 100 000 olayın tamamı bit-bit geri döndü. Kapsam testi her
  sealed alt türün üretildiğini doğrular, yani yeni bir paket türü test edilmeden eklenemez.
- **Kesilme:** Bir kaydın **her bayt konumunda** kesilmesi denendi. Her seferinde tam bir önek
  okundu, hiçbir sahte olay çıkmadı ve hiçbir kesik dosya "tam" sayılmadı.
- **Bozulma:** Bir bloğun yükünde tek bir bit çevrildi. Yalnızca o blok kayboldu, yerine doğru
  büyüklükte bir Gap geldi, sonraki bloklar eksiksiz okundu.
- **Geri basınç:** Bütçe aşılınca bloklar reddedilir, yazar hiç bloklanmaz, kayıp son kayıtta bile
  doğru bildirilir.
- **Deterministik replay:** Gerçek `PlayerSession` ile 4 thread'li havuzda ~100 000 olaylık canlı
  oturum (transaction, atlama ve anomaliler dahil) diske kaydedildi ve yeniden oynatıldı. Takma ad
  dışında olay olay bit-bit aynı çıktı.
- **Canlı E2E (20/20):** Gerçek Paper 1.21.11 sunucusunda kayıt açık. Sonda client'ın gönderdiği
  240 dönüş paketinin hepsi aynı float bitleriyle ve aynı sırada, 280 `ClientTickEnd`'in hepsi,
  7 tuş değişikliği ve iki kasıtlı ihlal kayıtta bulundu. Kimlik takma adlı, gerçek isim dosyada
  hiçbir yerde yok, kayıt `Disconnected` ile tamamlanmış.

## 8. Açık sınırlar

- **Kayıt boyutu ortama bağlı:** Yalnız oyuncu ≈ 1.2 MB/saat. E2E'deki gibi yakında 77 hayvan
  olan bir alanda ≈ 16 MB/saat (olayların %62'si diğer varlıkların hareket paketleri). Varsayılan
  2 GB üst sınır, kalabalık bir canlı testte ~125 oyuncu-saatlik veri demektir; `config.yml`'den
  artırılabilir. Gerekirse kayıt kapsamı oyuncunun kendisini etkileyen paketlerle daraltılabilir.
  Şimdilik "kayıt = canlı zaman çizelgesinin aynısı" ilkesini koruyorum, çünkü ileride erişim/aim
  analizi diğer varlıkların konumuna ihtiyaç duyacak.
- Okuyucu dosyayı belleğe tamamen yükler (tipik kayıtlar MB mertebesinde). Çok büyük dosyalar için
  akış tabanlı okuma ileride eklenebilir.
- Dünya verisi (chunk/blok paketleri) henüz modellenmediği için kayıtlara girmiyor. Faz 2b'nin
  dünya replikasıyla birlikte formata eklenecek. Format bunun için ileriye uyumlu tasarlandı.
- Gizli anahtar `plugins/WhyNotAC/recording-secret.key` dosyasında durur. Yedeklenmeli ve gizli
  tutulmalı.

## 9. Önerilen Faz 2b: client dünyası + vanilla fizik kahini

1. **Client'ın gördüğü dünya:** chunk ve blok güncelleme paketlerinden, transaction onaylı bir
   replika. Bu, kayıt formatına da eklenecek, böylece fizik kayıtlar üzerinde çevrimdışı
   çalışabilecek.
2. **Vanilla kahin:** oyunun kendi `LivingEntity.travel` / `Entity.move` / çarpışma kodunu gerçek
   bir sunucu dünyasında senaryolarla çalıştırıp temel gerçek izleri üretmek. Fizik motoru bu izlere
   karşı bit-bit test edilecek.
3. **Fizik katmanı 1:** düz zeminde yürüme, sprint, zıplama, hipotez kümesi ve kayıtlar üzerinde
   replay ile doğrulama.
