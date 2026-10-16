# whynotac-analysis — Kontroller ve davranış dedektörleri

**Sözleşme:** Zaman çizelgesi ve fizik kanıtları üzerinde çalışan, her biri bir **istatistik ve
bilgi sınırı** ile birlikte `Evidence` üreten dedektörler. Cezalandırmaz.

## Mevcut kontroller (hepsi SHADOW)

| Kimlik | Sınıf | Ne kanıtlar |
|---|---|---|
| `tick-framing` | P-M | Bir client tick'inde vanilla'nın gönderebileceğinden fazla hareket paketi |
| `client-clock` | P-M | Client'ın kendi saatinin izin verdiğinden fazla tick |
| `attack-reach` | P-A | Hiçbir vanilla client'ın erişemeyeceği bir varlığa saldırı |
| `movement` | P-M | Yaya hareket: hiçbir vanilla client'ın gönderemeyeceği konum, bayrak ya da koşma/girdi paketi |

### `movement` (`MovementCheck`, `movement/MovementTracker`)

- **Hipotez kümesi:** client'ın kendi oyuncusu tick tick, olabileceği durumların kümesi olarak izlenir
  (`Hypothesis`: fizik durumu, oyuncunun geri kalanı, client'ın en son gönderdikleri, sunucunun göremediği
  seçenekler). Her durum `OnFoot` ile bir tick ilerletilir; yalnızca client'ın gönderdiğinin aynısını gönderen
  durumlar kalır: konum bit bit, bayraklar, koşma ve girdi paketleri, 2·10⁻⁴ eşiği ve 20 tikte bir hatırlatma.
- **Tick'ler:** her `ClientTickEnd` bir tick'i kapatır. Client paketleri yalnızca tick'ler arasında işler ve
  transaction'ı işlediği anda yanıtlar: tick'ten önce yanıtlanan bir transaction'dan önce gönderilen paket
  uygulanmıştır, ilk yanıtsızdan sonra gönderilen uygulanmamıştır, aradakiler için her önek bir hipotezdir.
- **Sunucunun gönderdikleri:** ışınlama, `SetEntityMotion` (set), patlama itmesi (add), öznitelikler, açlık
  (`SetHealth`), kendi bayrak ve duruş yankısı (`SetEntityData`), yetenekler, oyun modu, efektler, dünya sınırı,
  binek ve kamera.
- **Efektler:** adımın okuduğu dördü client'ın tuttuğu güçle izlenir (`OnFoot.Effects`): zıplama gücü
  (zıplamada ve otomatik zıplamanın erişiminde), yavaş düşüş (yükselmezken yerçekimi en çok 0.01, düşüş mesafesi
  sıfırlanır), havada süzülme (yerçekiminin yerine) ve körlük (koşulamaz). Client bir efekti kendisi bitirmez:
  süresini sayar ama sunucu kaldırana kadar tutar, bu yüzden varlık ve güç yalnızca sunucunun paketlerinden
  gelir. Hız ve yavaşlık özniteliğe sunucu üzerinden etki eder; yunus lütfu yalnızca suda (adımın dışında).
- **Hız değiştiricileri (`SpeedAttribute`, `ModifierTable`):** client değiştiricileri işlem başına fastutil
  `Object2ObjectOpenHashMap` içinde tutar ve değeri o tablonun sırasıyla çarpar; sıra değerin son bitleridir.
  Tablo birebir kopyalanır (32 yuva, `HashCommon.mix(Identifier.hashCode())`, doğrusal yoklama, `shiftKeys`
  silmesi, son yuvadan geriye yineleme): hız ve yavaşlık efektleri, eklentilerin değiştiricileri ve koşma
  birlikte kesin. `ModifierTableTest` gerçek fastutil tablosuyla 2000 rastgele ekleme/silme dizisinde aynı sırayı
  ister. Tabloyu büyütecek (25.) değiştirici ve bilinmeyen işlem iddia dışıdır.
- **Görünmeyen seçenekler:** çift W penceresi (0..10) ve otomatik zıplama maske olarak taşınır, yalnızca
  okunduklarında dallanır; maskeleri dışında aynı durumlar birleşir.
- **Eşya kullanımı (`Using`):** client'ın kendi kullanımı (`LocalPlayer.startedUsingItem`), elindeki yığının
  `use_effects`'iyle (`ItemPrototypes`: oyunun tablosu; varsayılan 0.2 yavaşlatma, koşma başlamaz; mızraklar koşar,
  yavaşlamaz). Tuş eylemleri oyuncunun tick'inden önce: `UseItem` kullanımı başlatabilir ya da başlatmayabilir
  (kullanımı olmayan eşya, bekleme süresi, tokken yemek), ikisi de izlenir; bırakma durdurur. Tick başında
  `updatingUsingItem`: el aynı eşyayı tutuyorsa sürer (etkileri elin şimdiki yığınından), tutmuyorsa durur;
  client o tick elleriyle bir şey yaptıysa (seçim, tık, bırakma, etkileşim) durmuş da olabilir. Sunucunun
  canlı varlık bayrakları client'ın kullanımını eşitler (`onSyncedDataUpdated`); bayrakla başlayan kullanım
  elin o anki yığınını alır. Bitiş olayı (9) kullanımı bitirir (aç karnına yemek iddiayı bitirir: client'ın kendi
  açlığı koşmayı sunucudan önce belirler). Çapa her olası kullanımı dener: hiçbiri ya da ellerdeki her yığın.
  İddia yok: blok değiştirebilen kullanım (kova, nilüfer), kendi kendine bitebilen kullanım (fırça, kese),
  bırakılan trident (riptide oyuncuyu client'ta iter), bloğa kullanım (`UseItemOn`).
- **İddia yok (küme düşer):** OnFoot'un kapsamı dışı (sıvı, iskele...), itebilecek ya da
  çarpışan bir varlık yakında (canlılar, tekneler, vagonlar), yerleşmemiş blok, piston, dünya sınırı yakın,
  hız dışında bir öznitelik, uçma, binek, başka kamera, sunucunun döndürmesi, okunamayan paket.
- **Çapalar:** yeni oyuncu (giriş ya da varlık verisi tutmayan yeniden doğuş: client'ın varsayılanları) ve
  duran oyuncu: 12 tik tuşsuz, en az 3 tik arayla bit bit aynı iki konum paketi, arada gönderim yok, sonda yerde.
  Arada 0.003'ten hızlı her yatay hareket gönderim eşiğini aşardı; durum dünyadan hesaplanır ve bir tik daha
  çalıştırılarak doğrulanır.
- **Doğrulama:** `tools/realclient/MovementTruth.java` takipçiyi gerçek client oturumunun kaydı üzerinde
  çalıştırır: açıklanamayan tick 0 olmalı, açıklanan ve hareket paketi gönderen her tick'te client'ın kendi
  durumu hipotezlerden biri olmalı. Son oturum (otomatik zıplama açık; efekt, eşya kullanımı
  ve tırmanma bacaklarıyla): 3659 tikin 3237'si iddia edildi, 0 açıklanamayan, 1686/1686 tick'te client'ın durumu
  kümede. Efekt modellerinin bilinçli
  hataları (zıplama gücü, yavaş düşüş, havada süzülme, körlük) takipçide de açıklanamayan tik ya da kümede
  bulunmayan client durumu verir. `MovementCheckTest` aynı adımı koşan sentetik bir
  client'la (hız değiştiricileri gerçek fastutil tablosunda): dürüst hareket, belirsiz pencerede savrulma, çift
  W, hız ve yavaşlık efektiyle koşma, zıplama gücü, yavaş düşüş, havada süzülme, körlük, yemek (sonuna kadar),
  kılıcın arkasından kalkan ve bırakması, mızrakla koşma ve çapadan önce kaldırılmış kalkanla yürüme açıklanır;
  0.01 blok fazla hareket, havada asılı kalma, uygulanmayan savrulma, yok sayılan yavaşlık, verilmemiş zıplama
  gücüyle zıplama, körken koşma ve yerken tam hızla yürüme kanıttır.
- **Maliyet:** gerçek client oturumunda tick başına ortalama 1.33 hipotez, ≈ 60 µs.

## Planlanan aileler

- **Aim:** `MouseTurnModel` tersine çevirme ile imleç sayımı uzayına geçiş (hassasiyet tahmini,
  çok-kareli float birikimini hesaba katan kafes uyumu), açısal hız/ivme/jerk dağılımları,
  hedefe kilitlenme ve aşırı-düzeltme imzaları, "mükemmel rastgelelik" testleri
  (entropi, otokorelasyon, spektral düzlük).
- **Tıklama:** saldırı/swing aralıklarının dağılımı (kaydırmalı Welford momentleri, basıklık,
  çarpıklık), donanım makrosu periyodikliği, insan kas sınırları.
- **Zamanlama:** `ClientTickEnd` çerçevelemesiyle Timer/Blink, transaction tutma, paket
  patlamaları.
- **Etkileşim:** erişim/hitbox (bit-tam `viewVector` ışın izleme), görüş hattı, envanter zamanlaması.

Sinematik kamera, dürbün, kesirli imleç ve büyük-yaw çözünürlük kaybı gibi bilinen meşru
istisnalar, dedektör kanıt üretmeden **önce** dışlanır.
