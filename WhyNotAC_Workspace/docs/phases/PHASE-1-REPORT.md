# Faz 1 Raporu — Paket Yakalayıcı ve Yüksek Hassasiyetli Matematik Çekirdeği

**Durum:** tamamlandı, onay bekliyor · **Hedef:** Paper 1.21.11 (protokol 774) · **Java 21**

## 1. Teslim edilenler

| Modül | İçerik | Test |
|---|---|---|
| `whynotac-math` | Vanilla bit-tam `Mth`/`Vec3`/LpVec3/fare modeli/gönderim kuralları, ULP ve tolerans, hatasız dönüşümler (TwoSum, FMA-TwoProduct), Neumaier toplamı, kaydırmalı Welford momentleri | 39 birim testi |
| `whynotac-protocol` | 1.21.11 paket modeli (sealed record'lar), `PlayerSession` zaman çizelgesi, sınırlı aktör posta kutusu, `TransactionTracker`, RTT/jitter tahmincisi, sandviç politikası | 18 birim testi |
| `whynotac-paper` | Netty/NMS yakalayıcı, NMS→model çevirici, bundle açma, transaction sandviçi, tick başına transaction, `/whynotac inspect` | 10 Netty testi + canlı E2E |
| `whynotac-conformance` | Gerçek 1.21.11 sınıflarına karşı ≈74 milyon bit-tam karşılaştırma | 12 diferansiyel test |
| `tools/e2e` | Gerçek Paper sunucusu + protokol-774 sonda client + otomatik doğrulayıcı | 9 canlı kontrol |

Toplam: **79 otomatik test + 9 canlı kontrol, hepsi geçiyor.** Derleme `-Xlint:all -Werror`
ile uyarısızdır.

## 2. Paket yakalayıcının çalışma mantığı

1. **Enjeksiyon:** Paper'ın `ChannelInitializeListenerHolder`'ı, sunucunun kendi
   `ChannelInitializer`'ının **sonunda** çağrılır. O anda `packet_handler` hazırdır ve auto-read
   henüz kapalıdır. Yani ilk bayttan önce hattayız. Handler `packet_handler`'ın hemen önüne
   eklenir. Burada gelen mesajlar şifre çözülmüş, açılmış, çerçevesi kaldırılmış
   **decode edilmiş `Packet` nesneleridir**. Giden her paket de `unbundler`/`encoder`'dan önce
   buradan geçer. Eklenti geç yüklenirse mevcut bağlantılar kendi event-loop'larında enjekte
   edilir.
2. **Zaman damgası:** `System.nanoTime()` Netty thread'inde, paket oyun thread'ine ulaşmadan
   alınır. Bu, sunucu tarafındaki en erken gözlem noktasıdır.
3. **Çeviri:** NMS nesnesi değişmez bir kayda kopyalanır. Değerler dokunulmadan taşınır
   (double double, float float kalır). Gönderilmeyen hareket bileşenleri `0.0` değil `NaN`'dır.
   Modellenmeyen gelen paketler de `Unmodeled(tip)` olarak zaman çizelgesine girer, çünkü
   zamanlamaları da kanıttır.
4. **Sıralama:** Bir bağlantının iki yönlü trafiği tek bir event-loop thread'indedir. Her olay,
   oyuncu başına **toplam sıra** veren bir `sequence` ve client tick numarası alır
   (`ClientTickEnd` sayımı).
5. **Analize aktarım:** `SerialMailbox` (çok üretici, tek tüketici aktör). Ağ thread'i analiz
   beklemez. Dinleyiciler oturum başına seri ve sıralı çağrılır, kilit gerekmez. Kuyruk dolarsa
   olaylar düşer ama düşen olaylar sıra numarası tüketir ve açık bir `Gap` olayı üretilir. Gap'i
   aşan kanıt geçersizdir (sıfır yanlış-ban ilkesi).
6. **Transaction sandviçi:** Knockback, patlama itmesi, teleport, öznitelik, efekt, yetenek,
   oyun modu ve respawn paketleri `Ping(a) · paket · Ping(b)` arasına alınır. Client ping'leri
   oyun thread'inde, aynı sırayla yanıtlar (decompile ile doğrulandı). Böylece değişikliğin hangi
   client tick penceresinde uygulandığı **kanıtlanır**, tahmin edilmez. Ayrıca her sunucu
   tick'inde bağımsız bir transaction gönderilir (sürekli RTT izi).
7. **Fail-open:** Çeviri hatası, dinleyici hatası, enjeksiyon hatası: hepsi yakalanır,
   sınırlı sayıda loglanır, paket **her zaman** değişmeden iletilir. Faz 1 tamamen pasif bir
   gözlemcidir ve hiçbir oyuncuya hiçbir işlem yapmaz.

## 3. Matematik: "0.000001 hassasiyet" ne demek ve ne demek değil

**Dürüst sınırlar (verinin kendisinden gelir, bizden değil):**

| Kanal | Taşıyıcı | Çözünürlük | Sonuç |
|---|---|---|---|
| Konum | double | dünya sınırında bile 3.7e-9 | 1e-6 garantisi **gerçek** |
| Yaw/pitch | float32 | \|yaw\| ≥ 16° iken ≥ 1.9e-6, 10 000°'de 9.8e-4 | 1e-6, client'ın kendi değerinde **yok** |
| Mikro hareket | client gönderim kuralı | 2e-4 altı hiç gönderilmez | 2e-4'lük protokol belirsizlik küresi |
| Knockback | LpVec3 | eksen başına ≈ 3.05e-5 | client'ın gördüğü değer birebir modellenir |

**Bizim taahhüdümüz:** ölçüme **sıfır ek hata** eklemek ve her ölçümü bilgi sınırıyla birlikte
taşımak.

- **Kesin açı farkı:** İki float'ın farkı double'da hesaplanırsa (üs farkı ≤ 29 iken) tam
  temsil edilir: 24 + 29 ≤ 53 bit. `double % 360` (IEEE fmod) kesindir. Tek ±360 düzeltmesi de
  Sterbenz önsavı gereği kesindir. Yani dönüş hızı **sıfır yuvarlama hatasıyla** ölçülür.
  `BigDecimal` ile 200 000 rastgele çift üzerinde kanıtlandı.
- **Hatasız dönüşümler:** `TwoSum` ve `fma` tabanlı `TwoProduct`, sonucu ve yuvarlama hatasını
  `hi + lo = tam değer` olacak şekilde verir (`BigDecimal` ile 100 000 örnekte kanıtlandı).
- **Neumaier toplamı:** `1e16 + 10 000×1 − 1e16` naif toplamda `0`, burada tam `10 000`. 300 000
  karışık ölçekli terimde hata ≤ 1 ulp.
- **Kaydırmalı Welford:** İlk test, 1e7 civarında 1e-6 yayılımlı veride düz Welford'un 1.3e-5
  göreli hata verdiğini **yakaladı** (ortalama, ulp(1e7) = 1.9e-9 çözünürlükte tutuluyordu). İlk
  örnek referans alınarak `x − K` Sterbenz gereği kesin hale getirildi. Hata ≤ 1e-9 (varyans) ve
  ≤ 1e-15 (ortalama). Aynı veride ders kitabı formülü `E[x²] − E[x]²` %100'den fazla sapıyor.
- **Ters problem (fare):** `c ↦ turnYaw(yaw, c·m)` monoton bir yuvarlamalar zinciridir. Bu
  yüzden gözlenen bir yaw'ı üreten tam sayı imleç sayımları **bitişik bir aralıktır** ve iki ikili
  aramayla bulunur. Aralık boşsa hiçbir tam sayı girdi bu dönüşü açıklayamaz. Birden genişse
  oyunun kendisi bile girdileri ayırt edemez, dolayısıyla biz de kanıt saymayız. Büyük yaw'da bu
  belirsizliğin oluştuğu test ile gösterildi.
- **Sinüs tablosu kanıtı:** Client tabloyu `Math.sin` ile doldurur. Bu, CPU/JVM'e göre son
  bitte değişebilir. Ölçüm: 65 536 girdinin 2 197'sinde double farkı var, float'ta 0 fark var.
  Her değer float yuvarlama sınırına en az 2 502 ulp uzakta. Şartname 1 ulp'e izin verdiğinden
  **her oyuncunun tablosu bizimkiyle bit-bit aynıdır.**

## 4. Test süreci ve sonuçlar

**a) Birim ve Netty testleri (67 + 12 uygunluk = 79):** Her hassasiyet iddiası başka bir double ile değil, `BigDecimal` tam
aritmetiğiyle doğrulandı. Eşzamanlılık testi: 4 üretici thread × 50 000 olay, 8 analiz
thread'i. Sıra sapması 0, örtüşen dinleyici çağrısı 0.

**b) Diferansiyel uygunluk (≈74 milyon karşılaştırma):** Gerçek `Mth` (≈56 M), `Vec3` (≈11.5 M),
`LpVec3` (3 M, bayt bayt), `Entity.calculateViewVector` / `getInputVector` / `turn` (3.3 M; 2 000
oturum × 500 kare, ±2·10⁶ derecelik yaw dahil). Girdiler NaN, ±∞, ±0, alt-normaller, rastgele
bit desenleri, dünya sınırı ve yarım-sayı kenar durumlarını içerir. **Fark: 0.**

**c) Mutasyon testi (testlerin testi):** İki ince hata kasıtlı eklendi:
`(float)(x * 0.15F)` ↔ `(float)x * 0.15F` ve view vector'de double çarpım. Paket ikisini de
yakaladı (29. karede 1-ulp pitch sapması, 4e-12 vektör sapması). Kod geri alındı.

**d) Netty testleri (10):** Gerçek NMS paketleri, bazıları oyunun kendi codec'iyle tel
baytlarından decode edildi (saldırı paketi). Bu testler gerçek bir **sıralama hatasını**
yakaladı: tele `ping, knockback, ping` gidiyordu ama zaman çizelgesi
`knockback, ping, ping` kaydediyordu. Düzeltildi. Artık zaman çizelgesi her zaman tel
sırasındadır.

**e) Canlı uçtan uca test** (`tools/e2e/run_e2e.sh`): Gerçek Paper 1.21.11 build 132 sunucusu ve
standart kütüphaneyle yazılmış protokol-774 client. Client tam login → configuration → play
akışını yapar. Fareyi `MouseHandler` + `Entity.turn`'ün float32 aritmetiğiyle taklit eder, tuş
paketleri ve `ClientTickEnd` gönderir. Kasıtlı iki ihlal yapar.

```
PASS inspect output present
PASS withheld transaction detected (skipped=1)
PASS forged transaction detected (unknown=1)
PASS transaction accounting closes (214 = 212 + 1 + 1)
PASS no timeline events dropped
PASS probe was never disconnected (interceptor is passive)
PASS anomalies reported
PASS rotation observed
PASS server yaw -2.9802322387695312e-08 with exact delta 0.4500000476837158 matches a step the probe sent, bit for bit
RESULT: OK
```

Ölçülen gecikme (localhost): RTT 1.8 ms, SRTT 2.2 ms, jitter 0.5 ms. Dikkat çekici bir gözlem:
sonda client'ın imleç sayımları her 7 tick'lik döngüde toplamda 0'dır, ama yaw `0`'a değil
`-2.98e-8`'e döndü. Bu, float32 birikim kaymasının canlı kanıtıdır. Naif "GCD" aim kontrolleri
tam olarak bunu hile sanır.

## 5. Açık sınırlar ve riskler

- Yalnızca Paper (NMS). Spigot/Folia test edilmedi. `ChannelInitializeListenerHolder` resmî
  olarak desteklenmeyen bir iç API'dir.
- ViaVersion üzerinden bağlanan farklı sürüm client'lar: paketler doğru görünür ama fizikleri
  farklıdır. Faz 2'de ele alınmalı.
- Performans henüz JMH ile ölçülmedi. Tasarım paket başına bir çeviri kaydı ve senkron bir
  kuyruğa eklemeden ibarettir, ama ölçülmemiş performans iddiası yapmıyorum.
- "%0 yanlış-ban" matematiksel olarak garanti edilemez. Taahhüt edilen şey: ölçülmüş ve keyfî
  küçük bir olasılık, bilgi sınırının altında asla kanıt yok, belirsizlikte kanıt sıfırlama,
  varsayılan olarak cezasız izleme (bkz. `ARCHITECTURE.md`).

## 6. Önerilen Faz 2: hipotez-kümeli fizik motoru

1. Paket yakalayıcı için JMH ölçümü (oyuncu başına ns/paket, GC baskısı).
2. **Client'ın gördüğü dünya:** chunk/blok güncelleme paketlerinden, transaction onaylı bir
   dünya replikası (ana thread'den dünya okumaya kıyasla lag'de doğru, bellek maliyetli).
3. `LivingEntity.travel/aiStep` bit-tam replikası: zemin/hava/su/lav/elytra, sürtünmeler, adım,
   VoxelShape çarpışmaları, öznitelikler. Her biri gerçek koda karşı diferansiyel test edilecek.
4. Hipotez kümesi: `PlayerInput` (gerçek tuşlar) × sprint/eğilme/zıplama × knockback penceresi ×
   2e-4 belirsizliği. Çıktı: tutarlı küme ve gözleme uzaklık (bilgi sınırıyla birlikte).
5. 1.21.11 mekanikleri: mace smash/Wind Burst, rüzgâr topu, riptide, mızrak (`LUNGE`,
   `KineticWeapon`, `STAB`).
6. Temiz oyun kayıtlarıyla replay altyapısı (yanlış-pozitif oranını ölçmek için).
