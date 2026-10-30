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
| üç kesintiyle | 22.156 / 23.354 | 22.829 / 24.396 | 24 |

Tabloda iki ayrı koşu var. Kesintiler maliyeti %17-22 düşürdü. Bulgular değişmedi: 24'ün hepsi `attack-aim`'dir, botun
blok arkasından vuruşudur. Yüz kaydın tamamında 115 bulgu çıkar, hepsi aynı yapıntıdır. Profil (JFR, aynı oynatma):

- Blok değişikliği (`Section.with`, kapsayıcı %18,3 → %6,2). Her değişiklik bölümün 4096 bloğunu açıp yeniden
  paketliyordu. Artık bölüm `Section.of`'un kendi paketlediği bir bölümse ve yeni durumların hepsi paletinde varsa
  yalnızca değişen değerler yeniden yazılır. Sonuç tam paketlemenin vereceği bölümle aynıdır: değer değer ve eşitlikte
  (`SectionTest`, 6.000 rastgele adım; mutasyonlar öldürüldü). Paketten gelen bir bölümün ilk değişikliği, palet
  büyümesi ve tekdüze olan sonuç eski yoldan gider.
- Oturum ekleri (`PlayerSession.attachment`, `HashMap` araması %7 civarı → dizi okuması). Anahtarlar
  `PlayerSession.AttachmentKey`'dir; her biri her oturumda bir yuvadır.
- Chunk çözümü: bölüm verisi bayt bayt değil, big-endian `VarHandle` ile uzun uzun okunur.

Denenip alınmayan: dinlenen varlık izleri. Tek adaylı, bekleyen işi olmayan ve tiki yalnızca `ticked`'i değiştiren bir iz
tik döngüsünden çıkarılıyor, `ticked` gerektiğinde son tikin penceresinden çözülüyordu. İzlerin %60'ı dinlendi. Ancak
yalnız replikalarla ölçülen kazanç %0-7 arasında, gürültü düzeyinde kaldı. Varlık replikasının maliyeti tik döngüsünde
değil, giden paketlerin işlenmesinde. Doğruluğa dokunan bir karmaşıklık bu kazanca değmedi.

## Sonuç ve sıradaki adımlar (görev #27)

- Doğruluk: bütün kontroller gerçek arazide 100 dürüst botla 0 vaka (bot yapıntıları ayrı), 0 kayıp, 0 sapma.
- Performans: oyuncu başına 3,3 ms/s; hedefin 3 katı. Yukarıdaki üç kesinti analiz thread'ini tekrar oynatmada %17-22
  hafifletti; canlı payı bir sonraki yük testi gösterecek. Kalan kazanç profile göre şu sırayla aranacak:
  1. Olay dağıtımı: her olay her dinleyiciye gider (tekrar oynatmada `SessionRegistry.dispatch`'in kendi payı %14).
     Dinleyicilerin hangi olayları okuduğunu bildirmesi ve yalnızca onları alması gerekir. Bir dinleyicinin okuduğu bir
     olayı kaçırması replikayı bozar, bu yüzden her dinleyici için tam ve testle kanıtlanmış bir bildirim şarttır.
  2. Netty: kenar tap'lerinde yalnızca replikaların ve kontrollerin okuduğu paketleri çözmek, ping'i tick'in kendi
     flush'ına bindirmek.
  3. Kayıt: chunk ve büyük paketleri içerik adresli saklamak ya da sıkıştırmak.
  4. Varlık replikasında giden paketlerin işlenmesi (`ClientEntities.onOutbound`, `onAcknowledged`) ve paketten gelen
     bir bölümün ilk blok değişikliği.
