# WhyNotAC — Yol haritası

**Tarih:** 2026-10-15. **Kaynaklar:** iki denetim. Birincisi denetim ve araştırma çalışmasıdır
(2026-10-07, 231 madde). İkincisi doğrulama ve ürün boşluk analizidir (2026-10-09, 60 madde). Madde
kimlikleri (`PHY-M3`, `VAL-10` gibi) bu çalışmalardan gelir. Bu dosya sırayı ve kapıları belirler.
Normatif kurallar [POLICY.md](POLICY.md)'dedir; ikisi çelişirse POLICY geçerlidir.

## Bugünkü durum

| Katman | Durum | Ayrıntı |
|---|---|---|
| Ağ kenarı: bayt düzeyinde dinleme, uzlaştırma, transaction sandviçi | Tamam | [Faz 2b-1](phases/PHASE-2B-1-REPORT.md) |
| Client kimliği, topoloji, beyanlar (güven düzeyleriyle) | Kısmen | Çevrilmiş client'lar için ikinci kenar katmanı yok (`NET-01`) |
| Client'ın dünyası, varlıkları, envanteri, kendi oyuncusu (client sırasıyla) | Tamam | [2b-3](phases/PHASE-2B-3-REPORT.md), [2b-5](phases/PHASE-2B-5-REPORT.md), [2b-6](phases/PHASE-2B-6-REPORT.md), [2b-7](phases/PHASE-2B-7-REPORT.md), [2b-8](phases/PHASE-2B-8-REPORT.md) |
| Kanıt, politika, vaka, dosya, bağımsız `verify` | Tamam | [Slice 0](phases/SLICE-0-REPORT.md) |
| Kontroller: `tick-framing` (P-M), `client-clock` (P-M), `attack-reach` (P-A), `attack-aim` (P-M), `use-aim` (P-M), `break-time` (P-M), `prediction-sequence` (P-M), `action-grammar` (P-M), `movement` (P-M) | SHADOW | Dürüst trafikte 0 vaka: E2E, gerçek client, yük (yük botları vanilla fiziğiyle yürümediği için `movement`, gerçek arazide seçim yapmadan vurdukları için `attack-aim`'in blok bulguları orada ayrı sayılır) |
| Kayıt, takma ad, veri sahibi talepleri | Tamam | [docs/privacy](privacy/README.md) |
| Gerçek client, E2E, interop, yük testi | CI'da / araçta | `tools/realclient`, `tools/e2e`, `tools/load` |
| Hareket fiziği (`whynotac-physics`) | Yaya, süzülme ve binekler tamam | Çarpışma çekirdeği resmi client'ın koduyla; yaya hareket adımı (otomatik zıplama, hız değiştiricileri, efektler, eşya kullanımı, tırmanma, slime/yatak sekmesi, çit/duvar üstü, su (akıntı, yüzme, göz), sürünme, içinden etki eden bloklar (ağ, çalı, bal, kabarcık sütunu, toz kar), lav (Nether'in hızlı lavı dahil), iskele, dünya sınırı, suda riptide ve dönme saldırısı), elytra ile süzülme ve roket, tekne, at, domuz, strider, happy ghast, nautilus ve deve gerçek client'ın parkuruyla bit bit aynı; sunucu tarafı `movement` kontrolü (sürülen binekler dahil) SHADOW'da, canlıların itmesini takımlarıyla (görev #33), client'ın henüz işlemediği ama adım için eşdeğer blok değişikliklerini (görev #50), sunucunun hızını (savrulma, patlama, mızrak hamlesi) ve koyduğu dönüşü (yanıtıyla, görev #93) izliyor; gerçek client oturumunda 0 açıklanamayan tick |
| Kalıcı depolama, karar, yapay zekâ modülleri | Planlı | `whynotac-storage`, `whynotac-verdict`, `whynotac-ai` |

## Kapı kuralı

Her adım bir kapıyla biter. Kapı, aşağıdakilerin hepsinin CI'da yeşil olmasıdır:
- birim ve uyum testleri;
- E2E (yalın ve interop);
- gerçek client;
- dürüst trafikte 0 vaka (E2E, gerçek client, yük testi).

Yeni bir kontrol SHADOW başlar. ARMED'a yalnızca POLICY'deki koşullarla geçer.

## Faz 3 — Hareket fiziği (çekirdek)

Hareketle ilgili her P-A kanıtı bu motora dayanır. Motor, client'ın tick'ini bit bit yeniden üretir.
Bilinmeyen her şeyi sınırlı bir küme olarak taşır; tek bir toplu tolerans kullanmaz.

1. **Tick'in yeniden kurulması** (`PHY-A1`, `PHY-M17`, `PHY-M18`): paketlerin hangi client
   tick'inde uygulandığı, gönderim kuralları (2·10⁻⁴ eşiği, 20 tikte bir hatırlatma), yüklenme ve
   tick hızı. Var olan tick-framing ve öz-oyuncu replikasının üzerine kurulur.
2. **Çarpışma çekirdeği** (`PHY-A4`, `F8`): client'ın kendi çarpışma kodu bit bit uygulanır
   (Paper'ın Moonrise çarpışması değil). Şekiller oyunun kendisinden alınır. Basamak çıkma ve
   destek bloğu dahildir. Uyum testleri `whynotac-conformance`'tadır.
3. **Girdi ve yürüme** (`PHY-M1`–`PHY-M4`, `PHY-A8`, `PHY-M15`): tuşlar ve hareket vektörü,
   koşma durum makinesi, sürtünme, yerçekimi özniteliği, etkiler, öznitelik değiştirici sırası.
4. **Küme motoru** (`PHY-A5`, `PHY-A7`): kesin hızlı yol, aralık aritmetiğiyle kapsama, sınırda
   dallanma. Platforma bağlı kayan nokta noktaları kayıt altındadır.
5. **Ortam** (`PHY-M5`–`PHY-M9`, `PHY-M19`, `PHY-M20`, görev #33): duruş ve boyut, tırmanma,
   blok içi etkiler, sekme, akışkanlar, iten bloklar ve varlıklar, dünya sınırı.
6. **İtmeler** (`PHY-M10`–`PHY-M14`, `PHY-M21`, `PHY-M22`): sunucu hızı ve patlamalar, elytra ve
   roket, riptide, mızrak, binekler, sunucunun client'ı ezen durumları.
7. **Bu motorun üzerindeki kontroller:** hareket kümesi dışı konum (P-A), savrulmanın uygulanması
   (`C6`), zemin bayrağı, birikimli tick sayısı (`M5`, `VER-19`).

**Faz 3 kapısı:** gerçek client'ın tick başına doğruluk kaydı (`PHY-A9`) var. Bir senaryo
matrisinde, client'ın her tikteki durumu motorun kümesinin içinde olmalıdır. Sunucu içi oyuncu
fiziğiyle diferansiyel fuzz testi de yapılmalıdır (`PHY-A10`). Yapıldı, iki katmanda; ikisi de resmi oyunun kendi koduna
karşı ve her koşuda yeni tohumla. Çarpışma çekirdeği client'ın `VoxelShape`/`Shapes`/`Entity` koduyla rastgele 50.000
durumda bit bit karşılaştırılır. Tüm hareket adımı için kahin gerçek client'ın kendi fiziğidir: parkur üzerinde
üç rastgele yürüyüş (`fuzz.py`: başlangıç, bakış yönü, hareket, zıplama, eğilme ve koşma tuşları tohumdan) yapılır, her
tiki motorla ve takipçiyle karşılaştırılır. İlk koşuda yürüyüşlerin 632 tikinin hepsi iddia edildi ve açıklandı; hareket
paketi gönderen 491 tikin her birinde client'ın kendi durumu hipotezlerdendi (`walkTruthFound` kapısı). Sunucunun kendi
oyuncu kodu client'ın hareket adımını koşmaz (yalnızca `move` ile çarpışma); bu yüzden kahin client'tır.

## Faz 3b — Savaş ve dünya etkileşimi (fizikle paralel)

- Varlık kutuları (görev #37): kutup ayısının client'ta oynayan ayağa kalkma animasyonu izlenir. Bir tik içinde
  birkaç kez değişen ölçekte kutu en büyüğüyle sınırlanır. Gerekçesiyle iddia edilmeyenler: shulker ve wind charge
  (client kendisi taşır), asılı varlıklar, ender ejderhasının parçaları (client yerleştirir). Mermilerin
  seçilebilirliği bilinmez sayılır, iddiayı engellemez.
- Tick dilbilgisi (`G1`, `G2`): saldırının ardından el sallama ve client'ın eylemlerinin (saldırı, eşya kullanma,
  blok kırma, hotbar) tikin hareketinden önce gelmesi `action-grammar` ile yapıldı (görev #56). Eylem bağlamından
  (`G3`) sunucunun açtığı menü ekrandayken eylem ya da yürüme tuşu (`action-grammar`) ve eşya kullanırken saldırı,
  etkileşim ya da yeni kullanım (`movement`) yapıldı (görev #57).
- Bakış ve engel (`C2`): saldırılan varlığın tikin bakışıyla kesişmesi (görev #58) ve bakışın ona, kesin durduran
  bir bloktan önce değmesi (duvar arkasından vuruş, görev #59) `attack-aim` ile yapıldı; blokların seçim (outline)
  şekilleri resmi client'tan.
- Blok erişimi ve yüz doğrulama (`W2`): eşya kullanımının dönüşü tikin bakışıyla bit bit aynı ve bloğa tıklamanın
  imleci bakışın o bloğun outline'ına girdiği yerde, girebileceği yüzde, menzilde ve araya blok girmeden (`use-aim`,
  görev #60) yapıldı; kırma paketlerinin bloğu ve yüzü de aynı seçimden. Kırma ilerlemesi (`W1`, `break-time`, görev #61):
  bitiş, client'ın her çarpanın en büyük değeriyle float toplamı 1'e ulaşmadan gelemez; alet kurallarının etiketleri
  client'ın sunucudan aldıklarıyla (`update_tags`, `ClientTags`, görev #62; hareket kontrolü de etiketler motorunkilerden
  başka olabildikçe iddia etmez); yerde olmayış (÷5) client'ın kendi `onGround` bayrağıyla, sahte cevaplar ve araya
  girebilen paketler dışında sayılır (görev #63). Yapıldı. Yerleştirme (`W3`): blok tahminlerinin numaraları client'ın
  kendi sayımıyla (`prediction-sequence`, görev #64; yeni dünyada 1'den) yapıldı. Yerleştirilen bloğun durumunu sunucu
  kendisi hesaplar (`BlockPlaceContext`: tıklama ve oyuncunun sunucudaki dönüşü); ikisi de `use-aim` ile tikin seçimine
  ve bakışına bağlı, bu yüzden ayrı bir kontrol gerekmez.
- Fiziğe dayananlar: kritik vuruş (`C7`), topuz (`C8`). İkisini de sunucu kendi oyuncu durumundan hesaplar
  (`Player.canCriticalAttack`: `fallDistance > 0`, yerde değil, koşmuyor...; topuzun hasarı `fallDistance`'tan); o durum
  client'ın hareket paketlerinden gelir. Sahte kritik ya da sahte düşüş, ya aynı tikte fazladan hareket paketi
  (`tick-framing`) ya da hiçbir vanilla client'ın gönderemeyeceği konum veya `onGround` bayrağıdır (`movement`, yaya
  motorunun kapsamında bit bit). Elytra ile süzülme (bit bit, client'ın CPU'sunun `Math.cos`'u dahil) ve havai fişek
  roketinin itmesi motorda, dünya sınırı da (sabit ve lerp'li, client'ın tikleriyle), toz kar da (düşüşe, ayağın
  yüksekliğine, shift'e ve client'ın envanterinden izlenen deri botlara göre; botla tırmanma, donma hızın
  özniteliğiyle). Tekne (`AbstractBoat.tick`, oyuncunun sürdüğü, binişteki interpolasyonu dahil) motorda, gerçek client'ın 834 tikiyle bit bit; `movement` kontrolü sürülen tekneyi binişten itibaren izler (`BoatRide`: gerçek client oturumunda 834 tikin hepsi iddia edildi ve açıklandı). At (eşek, katır, iskelet ve zombi at; `AbstractHorse.tick`: şaha kalkma sayacı, su, ruh kumu, zıplama, basamak ve duvar çarpışması) motorda, gerçek client'ın 725 tikiyle bit bit; `movement` kontrolü sürülen atı binişten itibaren izler (`HorseRide`: gerçek client oturumunda 375 tik iddia edildi ve açıklandı, 0 açıklanamayan; binek zıplamasının gücü tuştan doğrulanır; binişte süren interpolasyon da: yarım blok ışınlanan atla gerçek client'ta bit bit). Domuz (havuçlu olta; hızlanmayı client kendisi sayar: `ItemSteering`) ve strider (çarpık mantarlı olta; lavın sabit şekli üstünde durur, lavda yüzer, karada boğulup yavaşlar: client her tik kendisi belirler) da `HorseRide` ile izlenir. Kontrol edilemeyen bir araçta (vagon, başka oyuncu, binilmeyen mob) gönderilen `MoveVehicle`, `PaddleBoat` ya da binek zıplaması kanıttır. Riptide'sız trident bırakmak iddiayı bitirmez (gücü sunucu tarafında büyülerden hesaplanır); riptide'lı trident suda ve yağmurda değilken kullanılamaz, oradan riptide gibi fırlamak bulgudur. Happy ghast (koşumlu; bakış boyunca uçar, yer çekimi yok) da `HorseRide` ile izlenir; eyersiz (koşumsuz) bineğe binilmişken sürücü paketi de kanıttır. Nautilus (eyerli ve evcil; bakış boyunca yüzer, suda yer çekimsiz, sıvı itmez; binek zıplaması bakış boyunca atılma, 40 tik bekleme: beklemede atılma kanıttır) da `HorseRide` ile izlenir. `can_float_while_ridden` varlık etiketi de vanilla'ya bağlandı. Deve (eyerli; at gibi sürülür, binici koşarken 0.1 hızlı, binek zıplaması düzlemde bakış boyunca ve yukarı atılma, 55 tik bekleme; ayakta ve duruş geçişi bitmişken: oyun saati için `SetTime` artık protokolde ve kayıtta) da `HorseRide` ile izlenir. Suda riptide (en az 10 tik kullanım, güç ve su: bakış boyunca itme, yerde 1.2 kaldırma, 20 tik dönme saldırısı; poz yalnızca sunucunun bayrağıyla; dönerken süpürülen kutuda hiçbir varlık yok sayılmaz) motorda ve takipçide: gerçek client'ta iki bırakış bir önceki tikten bit bit, 40 dönme tiki eşit, takipçi bırakışları ve 36 dönme tikini açıkladı. Yağmurda (gök ve biyom bilinmez), süresi bilinmeyen kullanımda ya da yakında varlık varken iddia biter. Mızrağın hamlesi yalnızca sunucuda koşar ve client'a `SetEntityMotion` olarak gelir (izleniyor; `MovementCheckTest`): gerçek client'ta iki dürtmenin iki hamlesinden sonra takipçi client'ın tikini hamlenin hızıyla açıkladı, 0 açıklanamayan. Sunucunun koyduğu dönüş (`PlayerRotation`) artık iddiayı bitirmez: client'ın tikin dışında hemen gönderdiği yanıt akıştaki yerinden bulunup tikin dışında tutulur (`RotationReplies`); gerçek client'ta iki dönüşün yanıtı vanilla hesabıyla bit bit tuttu, takipçi ikisinden sonra da açıkladı.

## Faz 4 — Doğrulama ve veri

- **Regresyon kaydı ve replay kapısı** (`VAL-10`, kritik): CI kayıtları saklanır. Her kontrol
  sürümü bunların hepsinde yeniden oynatılır. Canlı ve replay eşitliği kapıdır. Yapıldı: [regression/](../regression/README.md)
  kümesi ve `RegressionReplayTest` (her derlemede bütün kontroller, 0 kanıt ve 0 reddedilen aday; ortasına yarım blok
  adım konan oturumu hareket kontrolü bulur). Tekne interpolasyonu hatasının oturumu kümede: düzeltmesiz kapı kırılır.
- **Meşru davranış kataloğu** (`E1`, `DATA-01`, `VAL-13`): vanilla seçenekleri, erişilebilirlik
  yolları, izinli makrolar. Gerçek client senaryo matrisi.
- **Paylar** (`VAL-4`): her kanıt, dürüst trafiğin sınıra ne kadar yaklaştığını raporlar. Sınırı olan üç kontrol için
  yapıldı (`attack-reach`, `break-time`, `client-clock`). Paylar `/whynotac stats`'ta ve `ReplayCost`'ta raporlanır,
  tekrar oynatma kapısı negatif payı reddeder. Değerler [analiz README'sinde](../whynotac-analysis/README.md).
- **Saat varsayımı** (`VAL-3`): sunucu saatinin kayması izlenir.
- **Test teknikleri:** fuzz (`TEST-01`), özellik tabanlı testler (`TEST-02`), mutasyon (`TEST-03`),
  deterministik simülasyon (`TEST-04`).
- **Hile verisi** (`DATA-02`): yalnızca sahibin onayıyla, izole bir sanal makinede, açık kaynak
  client'larla kaydedilir.

## Faz 5 — Karar ve uygulama

- **Bağımsız yeniden doğrulama** (`VER-05`, `VER-06`): ikinci bir motor ve vanilla kodunda yeniden
  çalıştırma. Bir P-A kanıtı, eylem tetiklemeden önce bunlardan geçer.
- **Kanıt sertifikaları** (`VER-07`) ve **yanlış ban bütçesi defteri** (`VER-08`).
- **Uygulama hattı** (`VER-12`, `CFG-01`): eylemler gecikmeli ve dağıtılmış gelir. Yürütme anında
  yeniden doğrulanır. Devre kesici, genel kapatma anahtarı ve tipli, doğrulanmış yapılandırma vardır.
- **Depolama** (`STO-01`–`STO-03`): sabit vaka deposu, değiştirilemez dosyalar (hash zinciri ve
  imza). Dosyalar derlemeye bağlanır (`PROD-13`).
- **İnceleme ve itiraz** (`ADM-02`, `VER-15`): dört göz, itiraz akışı, sade dilde açıklama.
- **Yapay zekâ** (`AI-00`, `AI-05`): yalnızca vaka seçer ve sonda planlar. Hiçbir zaman eylem
  tetiklemez (POLICY).

## Faz 6 — İşletme ve dağıtım

- **Tedarik zinciri:**
  - ~~CI action'ları SHA'ya sabitlenir, en az yetkili token kullanılır (`REL-01`).~~ Yapıldı.
  - Gradle bağımlılıkları doğrulanır (`REL-02`). Ertelendi: çevrimdışı doğrulama meta verisi Paper'ın dev bundle'ını
    kapsamıyordu (CI bağımlılıkları indirirken doğrulama başarısız olur); bundle'ın imzalı sağlama toplamları
    eklenebildiğinde açılacak.
  - Sürümler imzalı ve tekrarlanabilir olur, SBOM eklenir (`REL-03`).
- **Gözlemlenebilirlik:** metrikler ve sağlık modeli (`OBS-01`). Performans hedefi gerçek arazide
  oyuncu başına < 1 ms/s (görev #27). Bugün 40 botla oyuncu başına 3,2 ms/s (analiz 1,9, ağ kenarı 1,0, kayıt 0,3).
  Kalanın yapısal nedenleri [ölçümde](benchmarks/2026-10-30-world/README.md) yazılı.
- **Ağ:** çevrilmiş client'lar için ikinci kenar katmanı (`NET-01`), Velocity eşlik eklentisi
  (`NET-22`), ağ çapında profiller yalnızca doğrulanmış kimliklerle.

## Sahibin kararını bekleyenler

| Konu | Neden önemli | Kaynak |
|---|---|---|
| Sürüm stratejisi: yalnız 1.21.11 mi, sonraki sürümler de mi? | Hedef sürüm yaşlanıyor; her sürüm fiziğin yeniden doğrulanması demek | Zayıflık #14, `PROD-14` |
| Satış ve lisans | Minecraft EULA'sı ve dağıtım kanalları satışı ve gizlemeyi sınırlar; hukuk görüşü gerekir | Zayıflık #15, `PROD-9`, `PROD-11` |
| Davranışsal profil (hesap devri, alt hesap bağlama) | Büyük olasılıkla özel nitelikli (biyometrik) veri; öneri: yapılmasın | `PROD-4` |
| Otomatik ceza | Anlamlı insan incelemesi, itiraz edilebilir açıklama gerekir | `PROD-5`, `PROD-19` |

## Denetimden kapatılanlar

| Madde | Çözüm |
|---|---|
| Zayıflık #1, #4, #6–#10, #12, #13 | [Zayıflık analizi, durum bölümü](reviews/2026-10-06-weakness-analysis.md) |
| `ROB-01` boş çerçeve | Kenar, boş çerçeveyi oyunun decoder'ı gibi yok sayar (`WireObserver`) |
| `F1`, `F3`, `F4` replikalar | Varlık, dünya, envanter ve öz-oyuncu replikaları (Faz 2b-3, 2b-5–2b-8) |
| `PHY-A9` gerçek client doğruluk kaydı | `tools/realclient` javaagent kaydedicisi |
| `C1` erişim (ilk P-A) | `attack-reach` (Faz 2b-8), kutular görev #37 |
| `REL-01` CI sabitleme | Her action bir commit'e sabit (yorumda o anki etiketi var); token yalnızca okuyabilir |
| `PROD-1`, `PROD-2`, `PROD-3` vakalar ve günlükler | `/whynotac privacy` kayıtları ve vakaları birlikte kapsar. Vaka saklama süresi saatte bir uygulanır, sahipsiz dosya kalmaz. Günlüklerde IP adresi yok |
