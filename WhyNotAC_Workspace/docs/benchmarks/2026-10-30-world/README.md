# Yük testi: gerçek arazi, bütün kontroller, 2026-10-30

`LOAD_WARMUP=120 LOAD_LEVEL_TYPE=normal tools/load/run_load.sh <dir> <variant> 100 60`: 100 bot, cluster
yerleşimi, görüş 4, sabit seed (`LOAD_SEED`, varsayılan 20261008), Paper 1.21.11, 4 çekirdekli bulut makinesi.
Sunucu CPU 0-1'de, botlar CPU 2-3'te. 2026-10-08 ölçümüyle aynı düzenek; o günden beri eklenenler: saldırı
mesafesi ve nişanı, blok nişanı, kırma süresi, tahmin sırası, eylem dilbilgisi ve hareket kontrolü (binekler
dahil), envanter, etiket ve öz-oyuncu replikaları. Ham sonuçlar bu klasördeki JSON dosyalarındadır:
`100-baseline.json` (eklentisiz), `100-whynotac-before.json` ve `100-whynotac-after.json` (aşağıdaki iki düzeltmeden
önce ve sonra).

## Yanlış pozitif

| Koşu | Vaka | Bot yapıntısı (blok arkasından vuruş) | Kayıp olay | Sapma | Anomali | Dinleyici hatası |
|---|---|---|---|---|---|---|
| önce | 4 | 10 | 0 | 0 | 0 | 0 |
| sonra | 0 | 10 | 0 | 0 | 0 | 0 |

İlk koşudaki 4 vaka (2 `attack-aim`, 1 `attack-reach`, 1 `attack-aim`) WhyNotAC'ın değil botların hatasıydı: gerçek
arazinin kendi inekleri (20 ve 210 numaralı varlıklar, sahnelenenler değil) dolaşır, bot da ineği sunucunun son
koyduğu yerden vuruyordu. Vanilla client bir varlığı her konuma üç tikte lerp eder ve oradan seçer: hareket eden
ineğe o vuruş vanilla bir saldırı değildir, kontroller bunu doğru buldu. Bot artık yalnızca yarım saniyedir
yerinden oynamamış bir ineğe vurur (`load_bots.py`). Blok arkasından vuruşlar bilinen bot yapıntısıdır (botlar
seçim yapmadan vurur; gerçek arazide yapraklar ve tepeler araya girer) ve ayrı sayılır.

## Maliyet

Thread başına CPU, JFR `jdk.ThreadCPULoad`; JSON'daki `threadCpuCores` ham hâliyle 2 kat yüksektir (2026-10-08'deki
düzeltmeye bakın), aşağıdaki değerler düzeltilmiştir.

| Varyant | Netty I/O | Ana thread | WhyNotAC analiz | Kayıt I/O | Süreç CPU | TPS |
|---|---|---|---|---|---|---|
| baseline | 0,148 | 0,233 | - | - | 1,42 | 18,0 |
| whynotac (önce) | 0,268 | 0,191 | 0,172 | 0,023 | 1,97 | 7,2 |
| whynotac (sonra) | 0,270 | 0,182 | 0,185 | 0,029 | 1,96 | 10,2 |

WhyNotAC'ın thread'lerdeki payı yaklaşık **0,33 çekirdek**: Netty +0,12, analiz 0,18, kayıt 0,03. 100 oyuncu için
oyuncu başına saniyede yaklaşık **3,3 ms**; süreç düzeyinde (JIT ve GC dahil) +0,54 çekirdek, yani 5,4 ms. 2026-10-08'de
(yalnız iki kontrol) thread payı 3,7 ms'ydi (düzeltilmiş değer): kontroller ve replikalar çoğaldı, maliyet artmadı. Hedef
(oyuncu başına < 1 ms/s) uzakta. Sunucu iki çekirdekte doymuş olduğu için ek CPU doğrudan tick süresine yansır (TPS 18'den
7-10'a). İki koşu arasındaki TPS farkı yükün gürültüsüdür, düzeltmelerin değil.

### Analiz thread'i (JFR örnekleri, sonra; bir örnek birden çok bileşende sayılabilir)

| Bileşen | Pay |
|---|---|
| Varlık replikası (`ClientEntities`; tik başına her varlığın adımı %24 civarı) | %29,7 |
| Kayıt kodlaması (`RecordingListener`, `EventCodec`) | %15,6 |
| Hareket kontrolü (`MovementCheck`, `MovementTracker`) | %14,4 |
| Envanter replikası | %5,2 |
| `attack-aim` | %5,2 |
| `action-grammar` | %5,0 |
| `use-aim` | %3,7 |
| Dünya replikası | %3,3 |
| Oturum ekleri (`PlayerSession.attachment`) | %2,4 |

### Bu ölçümde yapılan iki düzeltme

- `ClientTags.revision` her cevaplanan ping'de değişiyordu, hareket kontrolü bu yüzden `EngineTags.mayDiffer`'ı neredeyse
  her olayda yeniden soruyordu (analiz thread'inin %2,7'si). Artık yalnızca bir cevap değişebildiğinde değişir:
  pencerenin sonunun geçtiği bir sürüm ya da birkaç sürüm arasında kesin olanın ilerlemesi. Sonra: %0.
- `ClientInventory` her tik sonunda ve her cevaplanan ping'de aday durumları `LinkedHashSet`'e koyup karmalıyordu
  (`State` ve `Stack` `hashCode`, %3,9 ve %2,6). Tek durum artık karmalanmaz, birden çoğu yine sırasıyla tekilleştirilir.
  Sonra: %0.

İkisinin toplam etkisi (%6 civarı) bu düzenekte koşudan koşuya gürültünün içinde kalıyor.

## Tekrar oynatmayla ölçülen kesintiler

Yük testi koşudan koşuya gürültülü olduğu için kesintiler kayıtların tekrar oynatılmasıyla karşılaştırıldı:
`java -cp WhyNotAC.jar tools/load/ReplayCost.java <kayıt klasörü> 6 20 all`. Bu yük testinin ilk 20 kaydı belleğe
okunur, taze replikalara ve eklentinin bağladığı bütün kontrollere tek thread'de 6 tur oynatılır. İlk iki tur JIT
ısınmasıdır, sonra thread CPU süresi istemci tiki başına ölçülür (142.526 tik). Ağ ve sunucu bu ölçüme girmez. Kayıtlar
oturumların tamamını kapsar. Isınma dakikalarındaki blok değişiklikleri de buna dahildir; dünya replikası bu yüzden canlı
ölçümdeki payından ağır çıkar.

| Derleme | En iyi tur (ns/tik) | Ortalama (ns/tik) | Bulgu |
|---|---|---|---|
| 3bfd554 | 27.988 / 27.240 | 29.733 / 30.599 | 24 |
| ilk üç kesintiyle (25fee6e) | 22.156 / 23.354 | 22.829 / 24.396 | 24 |
| ilk üç kesintiyle (25fee6e), ikinci ölçüm | 22.313 / 20.995 | 25.125 / 23.344 | 24 |
| dördüyle | 19.563 / 19.448 | 20.765 / 20.461 | 24 |

Her satırda iki ayrı koşu var; ilk iki satır ile son iki satır ayrı oturumlarda ölçüldü. İlk üç kesinti maliyeti
%17-22, olay dağıtımı bir %9-15 daha düşürdü. 3bfd554 ile dördü aynı oturumda: 30.014'e karşı 19.628 ns/tik, %35 daha
az. Bulgular değişmedi: 24'ün hepsi `attack-aim`'dir, botun
blok arkasından vuruşudur. Yüz kaydın tamamında 115 bulgu çıkar, hepsi aynı yapıntıdır. Profil (JFR, aynı oynatma):

- Blok değişikliği (`Section.with`, kapsayıcı %18,3 → %6,2). Her değişiklik bölümün 4096 bloğunu açıp yeniden
  paketliyordu. Artık bölüm `Section.of`'un kendi paketlediği bir bölümse ve yeni durumların hepsi paletinde varsa
  yalnızca değişen değerler yeniden yazılır. Sonuç tam paketlemenin vereceği bölümle aynıdır: değer değer ve eşitlikte
  (`SectionTest`, 6.000 rastgele adım; mutasyonlar öldürüldü). Paketten gelen bir bölümün ilk değişikliği, palet
  büyümesi ve tekdüze olan sonuç eski yoldan gider.
- Oturum ekleri (`PlayerSession.attachment`, `HashMap` araması %7 civarı → dizi okuması). Anahtarlar
  `PlayerSession.AttachmentKey`'dir; her biri her oturumda bir yuvadır.
- Chunk çözümü: bölüm verisi bayt bayt değil, big-endian `VarHandle` ile uzun uzun okunur.
- Olay dağıtımı (`SessionRegistry.dispatch`'in kendi payı %14). Olayların %49'u varlık hareketidir: `MoveEntity`,
  `RotateHead`, `SetEntityMotion`, `EntityPositionSync`. Bu dört paket her dinleyiciye gidiyordu. Artık yalnızca onları
  okuyan dinleyicilere gider (`TimelineListener.readsEntityMotion`): varlık ve öz-oyuncu replikaları, hareket kontrolü,
  kayıt ve tanılama. Dünya, envanter ve etiket replikaları ile sekiz kontrol okumadıklarını bildirir. Bu bildirim kodla
  denetlendi: bu dinleyicilerin hiçbiri bu paketleri okumuyor, hiçbiri de her olayda iş yapmıyor (kabul edilebilirlik
  izleyicisi yalnızca gözlem, olgu, boşluk ve çevrilemeyen paketleri okur). Tekrar oynatma kapısı bunu sınar
  (`RegressionReplayTest`): her korpus oturumu, hilesi eklenmiş hâliyle, bir kez süzerek bir kez her dinleyiciye her
  olayı vererek oynatılır, bulgular birebir aynı çıkar. Bir okuyucuyu yanlışlıkla okumuyor diye bildirmek kapıyı kırar
  (hareket kontrolü ve varlık replikası için denendi). Öz-oyuncu replikasının hareket paketlerini okuması korpusta
  sınanmıyor; o okumaya devam eder. Yüz kaydın tamamında bulgular aynı kaldı: 115 `attack-aim`.

Denenip alınmayan: dinlenen varlık izleri. Tek adaylı, bekleyen işi olmayan ve tiki yalnızca `ticked`'i değiştiren bir iz
tik döngüsünden çıkarılıyor, `ticked` gerektiğinde son tikin penceresinden çözülüyordu. İzlerin %60'ı dinlendi. Ancak
yalnız replikalarla ölçülen kazanç %0-7 arasında, gürültü düzeyinde kaldı. Varlık replikasının maliyeti tik döngüsünde
değil, giden paketlerin işlenmesinde. Doğruluğa dokunan bir karmaşıklık bu kazanca değmedi.

## Canlı doğrulama

Tekrar oynatmadaki kazanç yük testinde aynı düzenekle denendi. 100 botla sunucu iki çekirdeğinin tavanındadır: thread
CPU'su thread'ler arasında paylaşılır ve TPS'e bağlıdır. Aynı derleme (3bfd554) bir koşuda TPS 10,2, bir sonrakinde 6,7
verdi. Bu yüzden toplam etki 100 botla ölçülemez. 40 botla ise eski (3bfd554) ve yeni (5409a1f, işlem kimliği
kesintisinden önce) derleme sırayla ikişer kez koşuldu. Ham sonuçlar `40-3bfd554-*.json` ve `40-5409a1f-*.json`'dadır.
Tablodaki değerler ham `threadCpuCores`'tur, 2 kat yüksektir; giden olay sayısı bütün koşu içindir:

| Koşu | TPS | Analiz (ham) | Analiz örnekleri | Giden olay | Analiz / milyon giden olay |
|---|---|---|---|---|---|
| yeni 1 | 15,2 | 0,177 | 315 | 1,60 M | 0,110 |
| eski 1 | 15,8 | 0,179 | 325 | 1,24 M | 0,144 |
| yeni 2 | 16,1 | 0,140 | 190 | 1,37 M | 0,102 |
| eski 2 | 14,5 | 0,182 | 338 | 1,46 M | 0,124 |

Olay başına analiz maliyeti yeni derlemede %20 civarı daha düşük. Kaba bir ölçü bu: CPU 60 saniyelik pencerenin, olay
sayısı bütün koşunun. Yönü tekrar oynatmayla aynı, büyüklüğü daha küçük. Bütün koşularda vaka 0, kayıp olay 0, sapma 0,
dinleyici hatası 0 (bot yapıntıları ayrı). Canlı profil kesintileri bileşen düzeyinde doğruluyor: oturum ekleri %4,8 →
%0,8; dağıtım döngüsünün kendi payı %3,4'ten ilk sıraların dışına düştü. Blok değişikliği ısınmadan sonra canlı profilde
zaten görünmüyordu: o kazanç oyuncunun yeni araziye girdiği anlar içindir.

Canlı profil iki yeni sıcak nokta gösterdi, ikisi de düzeltildi:

- Hareket kontrolü (`MovementTracker.aboutSelf`, `ridePacket`: %6 + %3). Etraftaki her varlığın hareket paketi takım,
  binek ve öz-oyuncu desen `switch`'lerini varsayılana kadar dolaşıyordu (Java 21 desen `switch`'inin türlerini tek tek
  dener). Başka bir varlığa ait bu paketler artık `switch`'lerden önce, onların vereceği sonuçla ayrılır.
- Netty'de işlem kimlikleri. Her ping paylaşılan `SecureRandom`'dan bir kimlik çekiyordu; her ağ thread'i onun kilidini
  alıyordu. Bu maliyet CPU örneklerinde değil, kilit beklemelerinde görünür. 02347db'nin commit mesajında yazan
  "Netty örneklerinin %10'u" yanlıştır: o örnekler kimlik aramalarıydı. Kimlikler artık 64'lük bloklarla çekilir. Kaynak
  aynı kriptografik kaynaktır ve hiçbir kimlik gönderilmeden önce görülmez. Ayır, gönder, cevapla döngüsü dört thread'de
  işlem başına 2.917 ns'den 482 ns'ye indi.

Kilit beklemeleri (JFR `jdk.JavaMonitorEnter`, eşik 10 ms, 60 s pencere, 40 bot; JFR'nin kendi thread'i hariç). Bu
beklemeler CPU harcamaz ama thread'i durdurur. Sunucu thread'inde beklemek tik süresine eklenir:

| Koşu | Netty, `SecureRandom` | Sunucu thread'i, `PlayerSession` | Analiz |
|---|---|---|---|
| 3bfd554 (1) | 5 kez, 77 ms | 1 kez, 18 ms | 1 kez, 11 ms (`SecureRandom`) |
| 3bfd554 (2) | 9 kez, 140 ms | 1 kez, 18 ms | 8 kez, 166 ms (vaka açılışı, `SecureRandom`) |
| 5409a1f (1) | 3 kez, 36 ms (+2 kez, 26 ms, iç kilit) | 2 kez, 26 ms | 1 kez, 15 ms (`SecureRandom`) |
| 5409a1f (2) | 0 | 2 kez, 23 ms | 0 |
| 02347db (bloklarla kimlik) | 1 kez, 10 ms | 3 kez, 38 ms | 0 |
| 4c1e2fc (işlem kilidi) | 0 | 0 | 2 kez, 36 ms (vaka açılışı) |

Sunucu thread'i her tik her oyuncu için bir ping kimliği ayırır. Bunun için oturumun kilidini alıyordu. Ağ thread'leri
oturumun her paketini işlerken aynı kilidi tutar. Sunucunun iki çekirdeği doyduğunda kilidi tutan ağ thread'i işlemciden
düşürülebiliyor, bu da sunucu thread'ini 10-18 ms bekletiyordu. İşlem izleyicisinin artık kendi kilidi var
(4c1e2fc). Bu kilit oturumun kilidi içinde alınır, hiçbir zaman onun dışından değil. Kimlik ayırmak yalnızca bu kilidi
alır: bir oturumun paketleri işlenirken kimlik ayrılabilir (`PlayerSessionTest`; metot yeniden `synchronized` yapılınca
test başarısız olur). Kalan analiz beklemeleri yalnızca vaka açılırken görülür (`ShadowVerdict.accept`, kabul
edilebilirlik izleyicisi); burada bunlar botların blok arkasından vuruşlarıdır.

## Sonuç ve sıradaki adımlar (görev #27)

- Doğruluk: bütün kontroller gerçek arazide 100 ve 40 dürüst botla 0 vaka (bot yapıntıları ayrı), 0 kayıp, 0 sapma,
  0 dinleyici hatası.
- Performans (40 bot, cluster yerleşimi, gerçek arazi; ham değerlerin yarısı; Netty'nin WhyNotAC payı JFR
  örneklerinden; oyuncu başına ms/s):

| Derleme | Analiz | Kayıt | Netty (WhyNotAC payı) | Toplam |
|---|---|---|---|---|
| 3bfd554 (1) | 2,23 | 0,21 | 1,09 | 3,54 |
| 3bfd554 (2) | 2,27 | 0,38 | 1,35 | 3,99 |
| 4c1e2fc | 1,88 | 0,28 | 1,03 | 3,19 |
| 0b507d3 (yeniden gönderilen chunk'lar başvuruyla) | 1,70 | 0,15 | 0,94 | 2,79 |
| 0d4e10b (ping takibi ilkel dizilerde, varlık izleri dinlenir) | 1,58 | 0,18 | 0,78 | 2,54 |

  Kesintiler %10-20 kazandırdı ve sunucu thread'inin kilit beklemelerini kaldırdı. Hedef (< 1 ms/s) hâlâ 3 kat uzakta.
  Kalan maliyet tek tük sıcak noktalarda değil, tasarımda:
  1. Varlık replikası (analizin %24'ü): her varlık paketi bir işlem olarak sıraya girer. Cevaplanan her ping'te o
     işlemler aday durumların her birine uygulanır (`onAcknowledged`, `applyUpTo`); her tik her etkin iz bir adım
     atar (`onTick`). Kalabalıkta oyuncu başına olay sayısı gördüğü varlık sayısıyla büyür.
  2. Ağ kenarı (oyuncu başına yaklaşık 1 ms/s): her paket kenarda bayt düzeyinde bir kez daha çözülür ve oyunun
     gönderdiğiyle uzlaştırılır (`WireObserver`, `Reconciler`). Bu, başka bir eklentinin paketi değiştirmesine karşı
     replikaların doğruluğunun güvencesidir; kapsamını daraltmak kanıt gücünü azaltır.
  3. Kayıt kodlaması (analizin %11'i). Yeniden gönderilen chunk'lar artık başvuruyla yazılır; aşağıya bakın.
  4. Ping defterleri: yaklaşık on dinleyici (replikalar, hareket takipçisi, üç kontrolün `MoveReplies`'i) her ping'in
     sırasını ayrı ayrı tutar. Oturum başına tek bir defter paylaşılabilir.

## Kayıtta yeniden gönderilen chunk'lar (2026-10-31)

Kayıt kodlaması tekrar oynatma ölçümüne girmiyordu; ayrıca ölçüldü. Ölçüm, aynı yük testinin ilk 20 kaydının
3.658.363 olayı ve 142.526 istemci tiki (7.126 oyuncu saniyesi) üzerinde yapıldı. Kayıtlar `RecordingWriter` ile
yeniden yazıldı; sıkıştırma kayıt I/O thread'indeki gibi `Deflater(BEST_SPEED)` ve CRC32C ile ölçüldü. Thread CPU süresi
tek çekirdekte (`taskset`) ölçüldü ve oyuncu saniyesine bölündü.

- Ham baytların %90'ı chunk paketleri (10.212 chunk, 444 MB). Bunların %68'i (6.970 chunk, 300 MB), aynı kayıtta
  daha önce bayt bayt aynısıyla gönderilmiş bir chunk'tır: oyuncu bir chunk'a geri döndüğünde sunucu onu yeniden
  gönderir. Bu tekrarların hepsi son 256 farklı gövdenin içindedir. Pencere 32 iken %61, 128 iken %67 yakalanır.
- Biçim değişikliği: gövdesi olan her chunk, kayıttaki sıra numarasıyla saklanır (`CB_LEVEL_CHUNK_STORED`). Son
  `CHUNK_WINDOW` = 512 gövdeden birinin SHA-256'sı ile aynı olan bir chunk, yalnızca o numarayı taşır
  (`CB_LEVEL_CHUNK_REPEAT`). Okuyucu en fazla 512 gövde tutar. Başvurduğu gövde bozuk bir blokta kaldıysa ya da yazıcı
  o bloğu atmışsa, başvuru başka bir chunk'ın baytları olarak okunmaz, tek olaylık açık bir `Gap` olarak okunur.
  Yazıcı attığı bloğun gövdelerini unutur ve onları yeniden saklar. Eski chunk kaydı okunmaya devam eder; biçim sürümü
  değişmedi, çünkü yeni etiketleri bilmeyen eski bir okuyucu onları bilinmeyen paket olarak atlar.

Üç ayrı JVM'de sırayla, oyuncu saniyesi başına µs:

| Derleme | Kodlama (analiz thread'i) | Sıkıştırma (kayıt I/O) | Diskte |
|---|---|---|---|
| önce | 52-54 | 539-553 | 111,3 MB |
| sonra | 90-94 | 270-287 | 63,8 MB |

Net olarak oyuncu başına saniyede yaklaşık 0,23 ms kazanıldı ve disk %43 küçüldü. SHA-256 bu işlemcide
(SHA-NI) gerçek gövdelerde yaklaşık 1 GB/s hızla çalışır ve analiz thread'ine 39 µs ekler. Sıkıştırma ise
141 MB/s hızla çalışır. Tekrar yazılmayan her gövde bu sıkıştırma maliyetinden kurtulur. 20 kaydın 3.658.363 olayı
yeni biçimde yazıldı ve geri okundu: hepsi aynı, 6.970 chunk daha önceki gövdeden çözüldü, 0 fark.
Botlar dar bir alanda dolaştığı için tekrar oranı burada yüksektir. Bir yere geri dönmeden keşfe çıkan bir
oyuncuda oran daha düşük olur. O durumda maliyet, SHA-256'nın chunk başına 40 µs'si kadar artar.

Canlı koşu (40 bot, aynı düzen, 2026-10-31, `40-0b507d3.json`): kayıt I/O thread'i oyuncu başına saniyede 0,28 ms'den
0,15 ms'ye indi. Bu, yukarıdaki yeniden yazma ölçümünün yarıya inme beklentisiyle tutarlıdır. Analiz ve Netty'deki düşüş
koşudan koşuya gürültünün içindedir. 0 vaka (bot yapıntıları ayrı), 0 kayıp, 0 dinleyici hatası. Bu koşunun 40 kaydının
hepsi tam okundu: 2.101.285 olay, 0 boşluk; 6.852 chunk'ın 2.201'i önceki bir gövdeden çözüldü. Koşu kısa olduğu için
botlar daha az geri döndü, tekrar oranı bu yüzden daha düşük (%32).
