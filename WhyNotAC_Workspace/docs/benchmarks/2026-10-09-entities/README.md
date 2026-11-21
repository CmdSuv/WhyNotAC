# Yük testi: client'ın gördüğü varlıklar, 2026-10-09

`LOAD_WARMUP=120 LOAD_LEVEL_TYPE=normal tools/load/run_load.sh <dir> <variant> <bot> 60`. Kurulum:
- gerçek arazi, sabit seed, cluster yerleşimi, görüş 4;
- Paper 1.21.11, 4 çekirdekli bulut makinesi;
- sunucu CPU 0-1'de, botlar CPU 2-3'te.

Her bot dürüst bir vanilla client gibi hareket eder ve ping'leri cevaplar. Cluster'da her oyuncu bütün
diğerlerini görür. Oyuncu başına gelen varlık trafiği bu yüzden bot sayısıyla birlikte büyür.

Ham sonuçlar bu klasördeki JSON dosyalarındadır:
- `100-*-before.json`: `5a1c8fe`;
- `40-*.json`: `3538f3c`;
- `100-whynotac-after.json`: `db0c03e`.

Thread CPU değerleri aşağıdaki tablolarda düzeltilmiş hâliyledir. JSON'lardaki `threadCpuCores` ise
2 kat yüksektir (ölçüm aracındaki hata, aşağıda).

## Doğruluk (asıl sonuç)

Bütün WhyNotAC koşularında:
- 0 kayıp olay, 0 sapma, 0 anomali, 0 dinleyici hatası, 0 vaka;
- her ping cevaplandı;
- flush çevrelemesi açıkken varlık replikasında 0 taşma ve 0 kayıp.

| Koşu | Varlık tick'i | Dallanma | Taşma |
|---|---|---|---|
| 40 bot, flush çevrili | 134.897 | 1.029 | 0 |
| 40 bot, çevrelemesiz | 128.080 | 798.816 | 237 |
| 100 bot, flush çevrili (`db0c03e`) | 651.933 | 69.376 | 0 |

Çevreleme olmadan replika her tick'te, client'ın flush'ın ne kadarını okuduğunu bilmediği için dallanır.
16 adayı aşan varlıklar ("taşma") yeniden eklenene kadar bilinmez kalır. Çevrelemeyle bu neredeyse hiç
olmaz.

Botların bir kısmı vanilla'nın "flying is not enabled" kontrolüyle atıldı: eklentili ve eklentisiz
koşularda aynı sayıda (40 botta 7-8, 100 botta 10). Bu WhyNotAC'tan değil, bot modelinden kaynaklanıyor.

## Maliyet

### 100 bot: önce ve sonra

Sunucunun iki çekirdeği eklentisiz bile dolu. Isınma 300 saniyede MSPT 25'in altına inmedi.

| Koşu | MSPT (1 dk) | En uzun tick (ms) | TPS | Netty | Ana thread | Analiz | Kayıt |
|---|---|---|---|---|---|---|---|
| baseline (eklenti yok) | 82,5 | 4.703 | 11,9 | 0,440 | 0,591 | - | - |
| whynotac `5a1c8fe` | 504,6 | 11.700 | 2,0 | 0,845 | 0,306 | 0,238 | 0,051 |
| whynotac-noflush `5a1c8fe` | 333,1 | 13.431 | 3,0 | 0,725 | 0,330 | 0,250 | 0,040 |
| whynotac `db0c03e` | 105,5 | 4.213 | 9,4 | 0,603 | 0,396 | 0,210 | 0,046 |

**`5a1c8fe` ile sunucu çöktü.**
- Netty thread'leri sunucunun iki çekirdeğinden 0,4 çekirdek fazlasını aldı. Ana thread aç kaldı.
- Ölçümün ortasında bir tick 11,7 saniye sürdü. Watchdog dökümüne göre ana thread o sırada tick başında
  birikmiş paketleri işliyordu (`runAllTasksAtTickStart`, `handleAnimate`).
- Yavaşlayan tick'ler arasında botların paketleri birikiyor, bir sonraki tick daha da uzuyordu.

**JFR'de bulunan ve düzeltilen sıcak noktalar:**
- **Chunk tick'leme (varlık replikası):**
  - her varlık her tick'te bir `HashSet<Long>`/`HashMap<Long, …>` aramasıyla boxing yapıyordu;
  - yanıtlanan her ping'de bütün chunk'ların geçmişi geziliyordu.
  - Şimdi ilkel bir `LongMap` var ve yalnız değişen chunk'lar budanıyor. Analiz thread'lerinde bu iş
    örneklerin %28'inden %3-5'ine indi.
- **`PacketTranslator.outbound`:** JDK 21'in tür `switch`'i durumları sırayla dener. Trafiğin çoğu olan
  varlık paketleri 25. sıradaydı. En sık gelenler artık başta.
- **`MovePlayer.equals/hashCode`:** record'un üretilmiş metotları method handle üzerinden gidiyordu ve
  her hareket paketi değerle eşleştiriliyor. Metotlar elle yazıldı.
- **`Section.of`:** her blok güncellemesi bölümü 4096 kutulanmış `HashMap` işlemiyle yeniden kuruyordu.
  Yerine kutulamasız bir indeks geldi.

Bu düzeltmelerle TPS 2,0'dan 9,4'e çıktı (eklentisiz 11,9). En uzun tick eklentisiz koşununkinin
altında kaldı.

### 40 bot: sunucu doymadan

Burada ısınma 130 saniyede oturdu, bileşen maliyeti bu koşulda okunur.

| Koşu | MSPT (1 dk) | TPS | Süreç CPU | Netty | Ana thread | Analiz | Kayıt | Ping |
|---|---|---|---|---|---|---|---|---|
| baseline | 30,3 | 19,0 | 1,18 | 0,151 | 0,382 | - | - | 0 |
| whynotac | 42,3 | 16,9 | 1,55 | 0,278 | 0,369 | 0,126 | 0,016 | 361.581 |
| whynotac-noflush | 43,2 | 16,5 | 1,54 | 0,254 | 0,376 | 0,115 | 0,020 | 220.299 |

- **WhyNotAC'ın maliyeti:** yaklaşık 0,27 çekirdek (Netty +0,13, analiz 0,13, kayıt 0,02). Bu, ortalama
  32 oyuncu için oyuncu başına saniyede yaklaşık 8 ms. Her oyuncu her tick'te 31 hareket eden oyuncuyu
  ve çevredeki mob'ları alıyor.
  - Henüz `Section.of` düzeltmesi yoktu. 100 botluk "sonra" koşusunda analiz thread'lerindeki en büyük
    tek kalem buydu.
- **Flush çevrelemesinin maliyeti:**
  - Netty'de +0,024 çekirdek ve %64 daha fazla ping.
  - Karşılığında replika kesin kaldı: 1.029'a karşı 798.816 dallanma, 0'a karşı 237 taşma.
  - Flush başına iki ping zorunlu. Açılıştaki ping cevaplanmamışsa client flush'tan hiçbir şey
    işlememiştir. Kapanıştaki ping cevaplanmışsa hepsini işlemiştir.
  - Biri olmazsa client'ın flush'ı TCP'de bölünmüş okuması dışlanamaz. Simülasyonda yalnız flush sonunda
    ping olunca kesin kontrollerin oranı %61'den %34'e düşüyor.

### Ölçüm aracındaki hata

JFR'nin `jdk.ThreadCPULoad` değeri, JVM'in çalışabildiği CPU'ların payıdır (`os::active_processor_count`
taskset'i hesaba katar). `JfrAttribution` ise bunu makinenin 4 çekirdeğiyle çarpıyordu. Sunucu 2 çekirdeğe
bağlı olduğundan bütün thread değerleri 2 kat yüksek çıktı. Thread toplamları her koşuda süreç CPU'sunun
2 katıydı.

Araç artık sunucunun CPU sayısını kullanıyor. 2026-10-08 belgeleri düzeltildi:
- düz dünya: oyuncu başına saniyede 2,9 ms değil 1,5 ms;
- gerçek arazi: 7,4 ms değil 3,7 ms.

## Kalan sıcak noktalar ve sıradaki işler

100 botluk "sonra" koşusunun JFR profili:
- **Analiz:**
  - üçte biri varlık replikasının tick başına işi: `onTick`, bekleyen işlemleri doğrusal tarayan
    `applicable` ve ping cevabında `onAcknowledged`;
  - geri kalanı oturum kuyruğu, kayıt kodlaması ve kontroller.
- **Netty:**
  - kenarda her paketin istemci codec'iyle çözülmesi (`WireProtocols.decode`);
  - ping yazma ve `TransactionTracker` (boxing'li haritalar, paylaşılan `SecureRandom`);
  - oturum kuyruğu (`SerialMailbox`: paket başına iki paylaşılan atomik sayaç ve thread uyandırma).
- **Ölçüm yan etkisi:** vanilla'nın JFR profiler'ı, JFR açıkken her paket için uzak adresi metne
  çeviriyor (`JfrProfiler.networkStatFor`). Bu bütün varyantlara eşit biniyor.

Hedef aynı: aynı düzenekte oyuncu başına saniyede 1 ms'nin altı. Sıradaki adımlar:
- değişmeyen varlıkları tick'lememek;
- bekleyen işlemleri tick'ler arasında indekslemek;
- kuyruğa toplu ekleme;
- kenarda yalnız gereken paketleri çözmek;
- yalnız replikaların okuduğu paketleri taşıyan flush'ları çevrelemek.
