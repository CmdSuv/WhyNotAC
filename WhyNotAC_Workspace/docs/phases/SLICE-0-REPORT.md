# Slice 0: ilk kontrol, uçtan uca

**Amaç:** Tek, ucuz ve kanıtlanabilir bir kontrolü bütün zincirden geçirmek: kanıt, kabul
edilebilirlik, politika, karar (SHADOW), kalıcı kayıt, uyarı, inceleme komutu, kanıt dosyası
(dossier) ve sunucudan bağımsız yeniden doğrulama. Böylece fizik motoru gibi büyük katmanlar
yazılmadan önce bütün arayüzler gerçek veriyle ve gerçek zamanlamayla denenmiş olur. Bu, eksik
analizinin ilk önerisidir (STATE-1).

## Kontrol: tick framing (P-M)

Vanilla 1.21.11 client'ı bir client tick'inde en fazla bir hareket paketi gönderir. Decompile edilmiş
client'taki bütün `ServerboundMovePlayerPacket` gönderen yerler:

| Yer | Ne zaman |
|---|---|
| `LocalPlayer.sendPosition()` (binerken `LocalPlayer.tick()`) | tick başına bir kez, ardından `ClientTickEnd` |
| `ClientPacketListener.handleMovePlayer` | sunucu teleport'una yanıt: AcceptTeleportation + PosRot |
| `ClientPacketListener.handleRotatePlayer` | sunucunun bakış yönü paketine yanıt: Rot |
| `ClientPacketListener.handleTeleportEntity` | oyuncunun kaldırılmış aracının teleport'una yanıt: PosRot |

Kural: iki `ClientTickEnd` arasında en fazla bir hareket paketi, artı bu aralıkta client'ın işlemiş
olabileceği her tetikleyici sunucu paketi için bir yanıt.

- **Yanıt hakkının başlangıcı:** tetikleyici paket gönderildiği an.
- **Yanıt hakkının sonu:** client'ın, tetikleyiciden sonra gönderilmiş bir transaction'ı onayladığı
  aralığın sonu. Yanıt, aynı karede o pong'dan önce gönderilir.
- **Harcama:** haklar yalnızca gereken aralıkta harcanır. Bir tick'in kendi paketi, bir yanıtın
  hakkını tüketemez.

Bu kural sağlamdır: vanilla client onu hiçbir koşulda ihlal edemez. İhlal eden client değiştirilmiştir.
Bu hile kanıtı olmak zorunda değildir: protokolü bozan meşru bir mod da üretebilir. Bu yüzden sınıfı
P-M'dir (POLICY.md). Bir tick içinde birden çok konum göndererek düşme yüksekliği uyduran mace hileleri
ve tıklayarak teleport eden hileler tam olarak bunu yapar.

Kabul edilebilirlik (POLICY.md): kenarda gözlem, native 774, çevirici yok, transaction'ları client
yanıtlıyor, Bedrock değil, pencerede kayıp ya da çevrilemeyen olay yok. Doğrulanmamış bilgi temiz
sayılmaz.

## Zincir

```
TickFramingCheck ──Evidence──► ShadowVerdict ──Case──► CaseStore (SQLite + dossier JSON)
  (analiz thread'i)            ActionPolicy             Alerts (whynotac.alerts.shadow)
                               SHADOW: yalnız           /whynotac cases · /whynotac case <id>
                               uyarı + inceleme         java -jar WhyNotAC.jar verify <dossier>
```

- **`ActionPolicy`**, POLICY.md'deki matrisin veri halidir. Bir test belgedeki tabloyu ayrıştırır ve
  koddaki tabloyla hücre hücre karşılaştırır, yani ikisi birbirinden kopamaz. Operatör yasak bir
  hücreyi açamaz.
- **Dossier**, kanıtı, kabul edilebilirliği, izin verilen eylemleri, oturum kaydının yolunu
  (dossier'ye göreli, mutlak yedekli) ve bir parmak izini içerir.
  `java -jar WhyNotAC.jar verify <dossier>` kontrolü kayıt üzerinde, sunucudan bağımsız olarak
  yeniden çalıştırır ve aynı parmak izini ister.
- **Kalıcılık:** tek bir yazıcı thread'i, sınırlı kuyruk. Kuyruk doluysa vaka düşürülür ve sayılır,
  analiz thread'i asla beklemez. `cases.retention-days` (varsayılan 90) eski vakaları ve
  dossier'leri siler: vakalar oyuncuyu adlandırır, yani kişisel veridir.

## Doğrulama

| Test | Sonuç |
|---|---|
| Birim | 20 yeni test. Politika (her hücre, belgeyle eşlik, istatistik asla ceza vermez, doğrulanmamış kimlik asla otomatik banlanmaz) ve kontrolün sağlamlık durumları (aynı aralıkta yanıt ve tick paketi, farklı karelerde yanıt ve pong, hakkın süresinin dolması, doygunluk, beyan edilmiş protokolün sayılmaması, Bedrock, kayıp olay, kenar kaybı, parmak izi kararlılığı) |
| E2E (gerçek Paper) | Sonda tick 200'de iki hareket paketi gönderir. Sonuç: tam olarak 1 vaka (P-M, 2 paket, yanıt hakkı 0), SHADOW, izinler yalnızca ALERT + REVIEW, konsolda shadow uyarısı, `/whynotac cases` listeliyor. `verify` kaydı sunucudan bağımsız tekrar oynatıp aynı kanıtı buldu. Offline mode kimlik `UNAUTHENTICATED` kaldı |
| Dürüst trafik | Gerçek client, ViaVersion + PacketEvents ile interop E2E ve 100 botluk yük testi: 0 vaka (CI'da da kontrol edilir) |

## Sürüm 2 ve ikinci kontrol: client saati (2026-10-08)

### Tick framing v2
Kırmızı takım incelemesi v1'de üç açık buldu, üçü de kapatıldı:

- **Teleport yanıtı kimlikle eşleşir.** v1'de her sunucu teleport'u herhangi bir hareket paketine
  harcanabilecek bir yanıt hakkı veriyordu: client kendi tetiklediği setback'lerle ek paket hakkı
  kazanabilirdi. v2'de sunucunun gönderdiği ve henüz kabul edilmemiş bir teleport kimliğinin
  `AcceptTeleportation`'ından sonraki ilk hareket paketi o teleport'un yanıtıdır, başka hiçbir paket
  değil. Hiç gönderilmemiş bir kimliği kabul etmek (tahmin edilmiş kimlikle packet fly) hak kazandırmaz.
- **Biçim eşleşir.** Bakış yönü paketinin hakkı yalnız bakış paketine (`Rot`), varlık teleport'unun
  hakkı yalnız konum ve bakış paketine (`PosRot`) uyar.
- **Anında yargı.** v1 fazlalığı `ClientTickEnd` gelince sayıyordu: `ClientTickEnd` göndermeyen bir
  client kaçabiliyordu. v2 her hareket paketinde yargılar, aralık başına bir kez.

Haklar harcanmaz, yalnızca süreleri dolar. Hangi paketin tick'in kendi paketi olduğu bilinemez; hakkı
tutmak yalnızca client lehine hata yapar. Yanıtın bayrakları (`onGround`, `horizontalCollision`) şart
değildir: vanilla false/false gönderir, ama bunu istemek sağlamlığa bir şey katmaz.

Dürüst trafikte ve kaynakta bulunan iki hata, ikisi de commit'ten önce düzeltildi:
- 100 botluk yük testinin ilk v2 koşusu **351 vaka** açtı. Botların teleport yanıtı `onGround=true`
  gönderiyordu, v2'nin ilk hâli ise bayrakları da istiyordu. Bayrak şartı kaldırıldı, sonda ve botlar
  vanilla'yla birebir yapıldı, regresyon testi eklendi.
- Client'ın ağ thread'i keep-alive ve chunk partisi cevaplarını kendisi yazar (`handleKeepAlive`,
  `handleChunkBatchFinished`). Ana thread'in art arda kuyruğa koyduğu accept ve yanıtın arasına
  böyle bir paket düşebilir. İlk hâl bu durumda yanıtı sıradan hareket sayardı. Artık accept'ten
  sonraki ilk hareket paketi yanıttır, aradaki paketler önemsizdir. Bir accept yine en fazla bir yanıt
  kapsar.

### Client saati (timer, P-M)
`ClientTickEnd` göndermeyen client'ı v2 yakalar. Fazladan `ClientTickEnd` gönderen client'ı (fazla
hareket paketini gizlemek için ya da timer hilesi) yeni kontrol yakalar: `ClientClockCheck`.

Kanıt (decompile edilmiş 1.21.11 client'ı, `Minecraft.runTick`):
1. `DeltaTracker.advanceTime`: geçen süre bölü `max(50 ms, sunucunun tick süresi)` kesirli bir kalana
   eklenir, tam kısmı sayılır. Sunucu client'ı yavaşlatabilir, hızlandıramaz.
2. `PacketProcessor.processQueuedPackets`: kuyruk boşalana kadar paketler işlenir, bu sırada gelenler
   de. Pong'lar yalnız burada gönderilir.
3. En fazla 10 tick çalışır, gerisi atılır. Her tick tek bir `ClientTickEnd` ile biter (başka yerde
   gönderilmez).

Kuyruğun boşaltılması uzun sürebilir (yavaş makinede chunk yağmuru) ve ondan sonraki tick'ler
boşaltmadan önce sayılmıştır. Buradan, pong i ile pong j arasındaki `ClientTickEnd` sayısı
21 + (c_j − c_i) / 50 ms'den azdır: yanıtlayan karenin 10 tick'i, boşaltmanın pong'dan önceki süresini
kapsayan sonraki karenin 10 tick'i ve bir kesirli tick. Client'ın süresi nedensellikle sunucunun
saatine bağlanır: pong i, ping i gönderildikten (s_i) sonra, pong j sunucuya ulaşmadan (r_j) önce
gönderilmiştir. Sınır: **(r_j − s_i) · (1 + %1) / 50 ms + 22**. %1 saat payı normal kuvars hatasının
yüz katıdır; 22'nin son 1'i milisaniye zaman damgaları ve float yuvarlaması içindir. En iyi çift her
cevapta tek bir minimumla bulunur (O(1)). Sınır, transaction'ları kim cevaplarsa cevaplasın geçerlidir.

**Commit'ten önce bulunan hata:** ilk tasarımda pay 12 tick'ti ("cevaptan sonra yalnız o karenin 10
tick'i gelir"). Kaynak kod, kuyruğun boşaltılırken gelen paketleri de işlediğini gösterdi: uzun bir
boşaltma sırasında cevaplanan bir transaction'dan sonra iki karenin tick'leri gelebilir. Gerçek kare
yapısını taklit eden simülatör eski payla vanilla client'ta yanlış kanıt üretti, yeni payla üretmiyor.
Simülasyonda vanilla sınıra en fazla 19,8 tick yaklaştı (kanıtlanmış üst sınır 21).

Tespit: iki kat hızlı tick yaklaşık 1,1 saniye artı gidiş-dönüş sürede, %5 hızlı timer yaklaşık yarım
dakikada. %1'in altındaki bir timer iddia edilmez.

Varsayım, her kanıtta yazılı: client'ın monoton saati gerçek zamandan en fazla %1 sapar. İşletim
sistemi düzeyindeki bir hız hilesi (speedhack) bunu bilerek bozar.

### Doğrulama
| Test | Sonuç |
|---|---|
| Birim | Tick framing 22 test (eşleşme, biçim, bayraklar, gönderilmemiş kimlik, ağ thread'i paketi, `ClientTickEnd` saklama). Client saati 7 test: gerçek kare yapısıyla vanilla simülasyonu (20 seed, 5-290 ms RTT, donmalar, 1,25 saniyeye kadar boşaltmalar), uzun boşaltma, en kötü patlama, yavaşlatılmış client, iki kat ve %5 timer, %0,5 timer iddia edilmiyor. Mutasyon: pay 12'ye indirilince iki vanilla testi düşüyor |
| E2E (gerçek Paper, yalın ve ViaVersion + PacketEvents ile) | Sonda tick 200'de iki hareket paketi, tick 240'ta 40 fazladan `ClientTickEnd` gönderir. Sonuç: tam olarak iki vaka. Tick framing: 2 paket, yanıt hakkı 0. Client saati: "52 ms içinde 41 client tick'i (en fazla 23 mümkün)". İkisi de SHADOW, yalnızca ALERT + REVIEW. `verify` ikisini de kayıttan bağımsız buldu (yalın 71, interop 73 kontrol) |
| Gerçek client | Resmi, değiştirilmemiş client gerçek girdiyle: 0 vaka |
| Yük, 100 bot | Düz dünyada 735.500, gerçek arazide 669.587 client tick'i: 0 vaka, 0 reddedilen aday ([sonuçlar](../benchmarks/2026-10-08-world/README.md)) |

## Sınırlar ve sonraki adımlar

- Kontrol SHADOW'da. ARMED'a geçiş için POLICY.md'deki ölçütler gerekiyor: temiz kayıtlarda 0, hile
  kayıtlarında ölçülmüş tespit oranı, yazılı `soundness` notu.
- `TeleportEntity` yalnızca varlık kimliğiyle modellendi ve her varlık için temkinli olarak yanıt hakkı
  verir. Aracı izleyen bir model bu hakkı yalnızca oyuncunun kaldırılmış aracına daraltacak.
- Etkisizleştirme (setback) henüz yok: kontrol yalnızca kanıt üretir.
- Yeni kontroller aynı arayüzleri kullanır: `TimelineListener` + `AdmissibilityTracker` +
  `EvidenceSink`, kayıt `Checks`'te (eklenti ve `verify` aynı listeyi kullanır). Client saati bunu
  doğruladı: ikinci kontrol hiçbir arayüzü değiştirmeden eklendi.
