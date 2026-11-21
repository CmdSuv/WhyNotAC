# Faz 2b-3 Raporu: Client'ın dünyası

**Amaç:** Fizik motorunun ihtiyaç duyduğu ilk şey: client'ın çarptığı bloklar. Bunlar sunucunun dünyası
değildir. Client chunk'ları geç alır, kendi koyduğu bloğu önceden tahmin eder, görüş aralığının dışındaki
chunk'ı yok sayar, anti-xray ona başka bloklar gönderebilir. Bu fazda client'ın dünyası, client'ın aldığı
paketlerden ve client'ın okuduğu sırayla yeniden kuruldu. Sunucunun dünyasıyla blok blok
karşılaştırıldı. Eksik analizindeki STATE-2 ("toplanan veri gelecekteki motorları besleyemez") ve STATE-31
("fizik için önemli paketler IGNORED") kalemlerini kapatır.

## Ne yapıldı

### Yakalama
- Modellenen yeni paketler:
  - `level_chunk_with_light`, `forget_level_chunk`, `block_update`, `section_blocks_update`;
  - `block_changed_ack`, `set_chunk_cache_center`, `set_chunk_cache_radius`;
  - `login` ve `respawn` içindeki boyut (anahtar, `minY`, yükseklik) ve chunk yarıçapı.
- **Chunk'lar ağ thread'inde çözülmez.** Kenarda chunk, client'ın aldığı baytlar olarak tutulur: paket
  kimliğinden sonraki her şey, tek bir kopya. Çözme analiz thread'inde yapılır. Oyun görünümü bu baytları
  taşımaz, kenarla yalnız koordinatla uzlaşır. Kenarın olmadığı durumda (ViaVersion eski client)
  oyunun kendi codec'iyle kodlanır.
- Kayıt formatı: 18-26 numaralı yeni etiketler. Eski kayıtlar okunmaya devam eder: eski `Login` ve
  `Respawn`, boyut alanı boş olarak okunur.
- Posta kutusu artık olay değil **birim** sayar: chunk verisinin her 128 baytı 1 birim. Böylece bir chunk
  dalgası da bellek sınırına tabidir. Oturum başına sınır 65.536 birimdir (yaklaşık 8 MB, bir oyuncunun
  ilk chunk dalgasına yeter). Sunucu geneli sınır değişmedi.

### Blok verisi: resmi client'tan
`whynotac-world` 1.21.11'in 29.671 blok durumunu taşır:
- çarpışma şekli, oyunun kendi `VoxelShape` biçiminde: eksen koordinatları, dolu hücreler, kutular ve
  tür (`CubeVoxelShape` ya da diğerleri; hücre bulma yöntemleri farklıdır);
- bayraklar (hava, dinamik şekil, bağlama bağlı çarpışma, ofset, değiştirilebilir, hareketi engeller,
  alet gerekir);
- sürtünme, hız ve zıplama çarpanları, sertlik ve sıvı.

Veri **resmi, değiştirilmemiş client'ın kendisinden** üretilir: `BlockDataDump`, client'ın sınıf yolunda
çalışır ve her üyeyi Mojang'ın eşlemeleriyle okur. Gerçek client CI işi veriyi her çalıştırmada yeniden
üretir ve gönderilenle bayt bayt karşılaştırır.

**Bulgu:** Paper'ın çarpışma yamaları (Moonrise), vanilla'nın `CubeVoxelShape` olarak kurduğu 9.150 blok
durumunu `ArrayVoxelShape` olarak kurar. Koordinatlar, hücreler ve kutular aynıdır. Fark yalnız türdedir
ve küp şekillerin ızgarası her zaman ikinin kuvveti olduğu için hücre bulma sonucu da aynıdır. Veriyi
sunucudan üretseydik client'ınkiyle tür olarak farklı olurdu. `BlockStatesDataTest` bunu sabitler:
sunucuyla geri kalan her şey eşittir, tek izinli fark tam olarak budur.

### Chunk çözücü
Client'ın okuduğu gibi okur. Saklama genişliği client'ın türettiği değerdir, teldeki bayt değil:
- doğrusal paletler 4 bitte saklanır;
- global kimlikler `ceillog2(29671) = 15` bitte saklanır;
- sınır dışı bir palet girdisi geçersiz sayılır (client da onda hata verir);
- bölüm sayısı client'ın boyutundan gelir. Fazla bölüm yok sayılır, eksik bölüm hatadır.

### Replika: client'ın chunk deposu birebir
`ClientChunkCache`:
- `(2r+1)^2` yuvalı halka tampon, `r = max(2, görüş) + 3`, `floorMod` ile indekslenir;
- aralık dışı chunk yok sayılır;
- aynı yuvaya gelen chunk eskisini siler;
- sorgu yalnız güncel merkezin aralığında cevap verir: merkez kayınca chunk gizlenir ama yuvasında kalır;
- yarıçap değişince yeni depo yalnız aralıktakileri tutar;
- giriş her zaman boş dünya verir, yeniden doğuş yalnız boyut değişince.

Tutulmayan chunk'ta ve inşa yüksekliği dışında blok, client'taki gibi `void_air`'dır.

### Belirsizlik ("settled")
- Bir paket, client onu işlediğinde etki eder. Bu, ancak ondan sonra gönderilmiş bir transaction
  onaylanınca bilinir. O zamana kadar değişen blok, yeni chunk ve görüş değişikliğinden etkilenen
  chunk'lar "settled" değildir.
- Client'ın kendi tahminleri (blok koyma, kırma) da öyledir: client sunucunun o konumlar için
  gönderdiklerini `block_changed_ack` ulaşana kadar uygulamaz. Konumu bilinmeyen eşya kullanımları
  (kova, nilüfer) ayrıca bildirilir: onların yerini yalnız fizik bulabilir.
- Olay kaybı (`Gap`) ya da okunamayan bir dünya paketi dünyayı "stale" yapar: chunk yeniden gelene kadar
  blok bilinmiyor sayılır.
- Bir kontrol, settled olmayan bir bloğu asla biliniyor saymaz.

### Bellek: aynı bölüm bir kez
Birbirine yakın oyuncular aynı chunk'ları bayt bayt aynı alır. Sıkıştırılmış bölümler değişmezdir,
içerikle karşılaştırılır ve bütün oyuncular için tek bir havuzda referans sayımıyla tutulur
(`SectionPool`). Bir blok değişince o oyuncunun dünyası bölümün kendi kopyasını alır (copy on write).
Oyuncu çıkınca her şey geri verilir.

## Doğrulama

| Test | Sonuç |
|---|---|
| Birim (`whynotac-world`) | 20 test: halka tampon, aralık, unutma, yarıçap, yeniden doğuş, blok/bölüm güncellemesi, settled ve tahmin kuralları, `Gap`, paylaşım ve serbest bırakma |
| Uygunluk (Paper sınıfları) | Oyunun kendi `PalettedContainer`'ı ile yazılmış bölümler (tek değer, doğrusal, hash map, global; 1-4096 farklı durum) blok blok aynı çözülür. Veri sunucunun kayıt defteriyle eşit (tür farkı dışında) |
| E2E (yalın ve interop) | Bir `setblock` (block_update) ve bir `fill` (section_blocks_update) sonrası 81 chunk'ta **1944/1944 bölüm birebir eşit** |
| Gerçek client | Resmi client'ın dünyası sunucununkiyle **1944/1944** eşit. Gönderilen blok verisi client'ın kayıt defterinin ürettiğiyle bayt bayt aynı |
| Yük (100 bot, gerçek arazi) | Aşağıda |

## Yük testi (100 bot, gerçek arazi, görüş 4)

Ayrıntılar ve ham sonuçlar: `docs/benchmarks/2026-10-08-world/`.

| Ölçüm | Replika belleği (100 oyuncu) |
|---|---|
| Paylaşım yok, her oyuncunun kendi bölümleri | 252,7 MB (oyuncu başına 2,5 MB; görüş 10'da yaklaşık 9 MB) |
| Havuz, değişebilir bölümler | 80 MB |
| Havuz, değişmez ve yeniden paylaşılan bölümler | 3,2 MB (rastgele seed) · **20,0 MB, 8.836 paylaşılan bölüm** (sabit seed, karşılaştırma koşusu) |

İlk karşılaştırma geçersizdi: her koşu rastgele seed ile başka bir dünya üretiyordu. Seed artık
sabit (`LOAD_SEED`), varyantlar aynı araziyi ve aynı mob'ları görüyor.

Sonuçlar, sabit seed ile:
- **Doğruluk:** 0 kayıp olay, 0 sapma, 0 anomali, 0 dinleyici hatası, iki kontrol açıkken 0 vaka ve 0
  reddedilen aday (669.587 client tick'i). Oyuncular çıkınca replika belleği 0'a döndü.
- **Maliyet:** WhyNotAC yaklaşık 0,37 çekirdek harcıyor (Netty +0,20, analiz 0,13, kayıt 0,04), yani
  oyuncu başına saniyede 3,7 ms. Düz dünyada bu 0,15 çekirdekti. (2026-10-09 düzeltmesi: ilk yayımlanan
  değerler, ölçüm aracındaki bir hata yüzünden 2 kat yüksekti; ayrıntı benchmark belgesinde.) Sunucu iki çekirdekte doymuş durumda:
  MSPT 48,4'ten 68,4'e çıktı. Bu bir performans eksiği: hedef, oyuncu başına saniyede 1 ms'nin altı
  (yapılacaklar benchmark belgesinde).

Her iki koşuda bazı botlar vanilla'nın "flying is not enabled" kontrolüyle atıldı (eklentisiz 17,
eklentili 12): botlar fizik yapmadan tepelerde sabit yükseklikte daire çiziyor. Bu WhyNotAC'tan değil,
bot modelinden kaynaklanıyor.

## Sınırlar ve sonraki adımlar
- Etiketler (`update_tags`, örneğin tırmanılabilir bloklar) henüz yakalanmıyor. Fizik fazında
  yapılandırma aşamasından yakalanacak.
- Blok varlıkları (hareket eden piston, shulker kutusu) ve biyomlar (yağmur, riptide için) çözülmüyor.
  Baytlar kayıtta duruyor, fizik ihtiyaç duyunca okunacak.
- Varlıklar (diğer oyuncular, tekneler, hitbox'lar) bir sonraki adım: varlık replikası.
- Anti-xray açık sunucuda replika client'ın aldığı (karıştırılmış) blokları tutar. Bu doğru olandır;
  `/whynotac world` ise o durumda farkları beklenen olarak gösterir.
