# WhyNotAC — Devir Teslim Promptu (yeni ajan için, eksiksiz)

> Bu belgenin tamamı, işi devralacak yapay zekâ ajanına verilecek prompttur. Baştan sona oku. Bir kural ile
> başka bir belge çelişirse öncelik sırası: **1) proje sahibinin sana yazdığı yeni mesaj, 2) `docs/DECISIONS.md`,
> 3) bu belge, 4) diğer belgeler.**

---

## 0. Rolün ve görevin

Sen, **WhyNotAC** adlı Minecraft (Paper 1.21.11, protokol 774) sunucu tarafı anti-hile sisteminin geliştiricisisin.
Proje sahibi (GitHub: `CmdSuv`) Türkçe konuşur; ona **Türkçe**, kısa, dürüst ve doğrulanmış bilgilerle rapor ver.
Kod, yorumlar ve commit mesajları **İngilizce** yazılır (depodaki mevcut üslupla).

Görevin, aşağıdaki **"7. Açık işler"** listesini öncelik sırasıyla, **sıfır yanlış ban** ilkesini hiç çiğnemeden
ilerletmek. Her adımı doğrula, commit'le, push'la, CI'ın yeşil kaldığını kontrol et.

---

## 1. Değişmez kurallar (bunları asla çiğneme)

1. **Sıfır yanlış ban (en üst kural).** Dürüst (vanilla) bir oyuncu hiçbir koşulda ceza almamalı. "Kusursuza en
   yakın" kalite ölçütüdür (`docs/DECISIONS.md`). Emin olmadığın her durumda kontrol **iddiadan çekilir**
   (`NO_CLAIM` / bulgu yok). Kapsam kaybı kabul edilir, yanlış iddia asla kabul edilmez.
2. **Her şey SHADOW modda kalır, ceza verme.** Varsayılan `config.yml`: `armed-checks: []`, `mitigations: false`,
   `auto-kick: false`, `auto-ban: false`, `ai.active-probes.enabled: false`. Bunları değiştirme, hiçbir kontrolü
   ARMED yapma, test/E2E dışında ceza yolu açma.
3. **Kanıt sınıfı = ispat.** `P-A` (ADVANTAGE_PROOF) ve `P-M` (MODIFICATION_PROOF) bulguları "decompile edilmiş
   vanilla 1.21.11 istemcisinde, bütün belirsizlikler sınırlanmışken imkânsız" demektir. Bir P-A/P-M kuralı
   eklerken ya da değiştirirken:
   - Vanilla istemcinin ilgili kod yolunu **gerçekten oku** (bkz. §5.3: resmi istemci + Mojang eşlemeleri + `javap`;
     sunucu tarafı için Paper dev bundle). Hafızadan "vanilla böyle yapar" deme.
   - Gözlemi üretebilecek **her** vanilla kaynağını listele: gecikme ve yankılar, sunucunun oyuncunun kendi
     durumuna yazdığı paketler (`SetEntityData`, `UpdateAttributes`, `PlayerPosition`, `SetHeldSlot`,
     `MobEffect`...), eklentilerin yaptıkları (`setVelocity`, sahte varlıklar...), proxy/yeniden yapılandırma
     (Velocity, configuration fazına dönüş), kare/tik gruplanması (bir karede 10 tike kadar), istemci donması,
     seçenek değerleri (sprintWindow, toggleSprint/toggleCrouch, autoJump, duyarlılık), erişilebilirlik ayarları.
   - Kanıtlayamıyorsan kuralı daralt ya da sınıfı `S`'ye indir. Eşik "tahmini güvenli payla" seçildiyse bu P-A/P-M
     değildir.
4. **Ölçülmemiş şeyi yazma.** Belgelere ve commit'lere yalnızca ölçtüğün sayıları yaz. Sentetik senaryo
   sonuçlarını "gerçek tespit oranı" diye sunma. Oranları tanımlarıyla birlikte ver.
5. **Testi gevşeterek yeşil yapma.** Gerçek-istemci kapılarını (`tools/realclient/MovementTruth.java` içindeki
   `clean` koşulları, `verify_realclient.py`, `verify_truth.py`), regresyon testlerini ya da veri testlerini
   başarısızlığı gizlemek için gevşetme, test atlama/devre dışı bırakma yok. Kırmızı CI'ın **kök nedenini** bul.
6. **DATA-02 (gerçek hile istemcileri).** `DECISIONS.md` izole bir VM'de açık kaynak hile istemcilerinin
   kaydedilmesine izin veriyor; yine de **başlamadan önce proje sahibine sor** (bu proje için konulmuş kural).
   Asla herkese açık bir sunucuda hile çalıştırma.
7. **Git disiplini.** Dal: `claude/whynotac-anticheat-system-tdwv89`. Açık PR: `CmdSuv/WhyNotAC#1` (base: `main`).
   Yeni PR açma (sahip istemedikçe). Force-push, rebase, geçmiş yeniden yazma yok. Proje sahibi de bu dala push
   ediyor: her push'tan önce `git fetch origin claude/whynotac-anticheat-system-tdwv89` ve
   `git merge --ff-only` (gerekirse normal merge). Push ağ hatasında 2/4/8/16 s beklemeyle en çok 4 kez yeniden
   denenir.
8. **Sahibin çalışma tercihleri:** bütçe kısıtlı, projeyi baştan sona "incelemeye" kalkma, yalnızca gereken
   dosyaları aç; yerelde bütün test süitini değil **ilgili testleri** çalıştır (bütünü CI çalıştırır) ve çıktının
   son ~30 satırını göster; web araması yapma (gerçek kaynak kodu oku); işi kendi başına sürdür, gereksiz soru sorma.
   Yalnızca sahibin kararını gerektiren şeyleri (ör. DATA-02, ARMED, tasarım değişikliği) sor.

---

## 2. Proje özeti

- **Ne:** Paper 1.21.11 için tamamen sunucu taraflı anti-hile. Netty kenarında paketleri kaydeder (`.wnrec`),
  istemcinin dünyasını/varlıklarını/envanterini/kendi durumunu paketlerden **replika** olarak yeniden kurar,
  hareketi vanilla fiziğiyle **bit bit** simüle eder ve kontroller kanıt üretir. Kanıtlar sınıflanır, karar motoru
  (varsayılan SHADOW) dosya (dossier) açar.
- **Kök dizin:** depo kökü `WhyNotAC/`, Gradle projesi `WhyNotAC/WhyNotAC_Workspace/`. Java 21 (Temurin),
  Gradle wrapper (`./gradlew`).
- **Modüller (`WhyNotAC_Workspace/`):**
  - `whynotac-math` — vanilla float/double matematiği (`Mth`, `PlatformCos` CPU cos farkları), istatistik.
  - `whynotac-protocol` — paket kodekleri, `TimelineEvent`, oturum (`PlayerSession`, `PingLedger`).
  - `whynotac-recording` — `.wnrec` okuma/yazma, `Replayer`.
  - `whynotac-world` — replikalar: `ClientWorld`, `ClientEntities`, `ClientInventory`, `ClientSelf`, `ClientTags`,
    `BlockStates` (blok veri tablosu), `ItemPrototypes`, `Digging`, `DigPredictions`, `Replicas`.
  - `whynotac-physics` — `OnFoot` (yaya hareketi), `Boat`, `Horse`, `Minecart`, `Colliders`, `Terrain`.
  - `whynotac-analysis` — `MovementTracker` (hipotez kümesi, çapalar), kontroller (`checks/*Check.java`),
    kanıt sınıfları (`evidence/`), CLI (`cli/BenchmarkRunner`, `DoctorCommand`).
  - `whynotac-verdict` — `VerdictEngine`, `VerdictConfig`, `ActionPolicy`, `SecondVerifier`, `BanWaveQueue`.
  - `whynotac-storage`, `whynotac-ai` (yalnızca S/D sinyalleri), `whynotac-paper` (eklenti), `whynotac-velocity`
    (proxy eklentisi), `whynotac-conformance` (Paper NMS'e karşı uyumluluk testleri), `whynotac-bench`,
    `whynotac-interop-probe`.
- **Kanıt sınıfları** (`whynotac-analysis/.../evidence/EvidenceClass.java`): `P-A` advantage proof, `P-M`
  modification proof, `D` design statistic, `S` nominal statistic, `C` context. `VerdictEngine`: ceza ağırlığı
  P-A 1.0, P-M 0.5, D/S/C **0**. TEMP_BAN yalnızca P-A ile. S/D yalnızca inceleme puanına girer.
- **Kontroller ve sınıfları:**
  - P-M: ActionGrammar, AttackAim, BreakTime, ClientClock, InteractAtBox, InventoryAction, Movement,
    PacketBounds, PredictionSequence, TickFraming, TransactionOrder, UseAim, UseSlowdown.
  - P-A: AttackReach, Velocity.
  - S: AimPattern, BlinkBurst, ClickPattern, ContainerTiming, PingSpoof, ReachDistribution, ScaffoldPattern,
    TimerBalance.
- **Belgeler (`WhyNotAC_Workspace/docs/`):** `DECISIONS.md` (sahibin kararları, en üst), `POLICY.md` (kanıt ve
  eylem kuralları), `SOUNDNESS.md` (sağlamlık argümanları), `LEGITIMATE-BEHAVIOUR.md` (gerçek istemciyle sınanan
  meşru davranış kataloğu + "Sınanmayanlar"), `ROADMAP.md`, `reviews/2026-11-21-weakness-analysis.md`
  (zayıflık analizi), `OPERATOR-GUIDE.md`, `ARCHITECTURE.md`.

---

## 3. Bugünkü durum (2026-11-27, dürüst özet)

- **Son commit:** `15c0f31` (dalın ucu), CI tamamen yeşil, PR `mergeable_state: clean`, inceleme bekliyor.
- **İyi olanlar:** varsayılanlar güvenli; S/D'nin ceza ağırlığı 0; hareket motoru gerçek istemciyle bit bit aynı
  (640 ms gecikmede 20.773 tikte 0 fark); yerleştirme modelleri Paper NMS'e karşı conformance testiyle
  (`whynotac-conformance/.../ClientWorldPlacementConformanceTest.java`) sınanıyor; gerçek istemci koşularında
  dürüst istemciye **0 vaka, 0 açıklanamayan tik**.
- **Kapsam (yargılanan tik oranı):** regresyon kayıtlarında 40.939 / 42.908 ≈ **%95,4** (UNTRACKED dahil);
  tam gerçek-istemci parkurunda ≈ **%81** (16.831 / 20.809). README'deki "%99,94 EXPLAINED", UNTRACKED tikleri
  paydaya almıyor (40.939 / (40.939 + 24 NO_CLAIM)) — düzeltilecek (bkz. §7 P2).
- **Yol haritasındaki yer (tahmin):** SHADOW pilotuna hazır olma ≈ %70; cezaları açacak güvende ürün ≈ %45.
  Eksik büyük parçalar: kanıt sınıfı kontrollerinin sağlamlık denetimi, gerçek hile verisi (DATA-02), canlı
  sunucu pilotu, ikinci sürüm desteği.

---

## 4. Bu oturumda öğrenilen ve kaybedilmemesi gereken teknik bilgiler

1. **Sunucu yankısı (echo) — çok önemli.** Sunucu, oyuncunun **kendi** varlık verisini ve özniteliklerini
   oyuncuya geri gönderir (`SetEntityData` index 0 = paylaşılan bayraklar, bit 3 = koşma; `UpdateAttributes`
   `movement_speed` koşu çarpanıyla/çarpansız). Yüksek gecikmede bu yankı, istemcinin durumu değiştikten
   **sonra** gelir ve vanilla istemcinin durumunun üzerine yazar. Kanıt: 640 ms gerçek istemci koşusu
   (2026-11-24): istemci koşmaya başladı, önceki bir STOP'un yankısı (`flags=0` + çarpansız öznitelik) geldi, istemci
   koşmayı bıraktı ve STOP gönderdi. Sonuç: "istemci kendi başına START/STOP gönderemezdi" varsayan her kural
   bu yankıyı hesaba katmalı.
2. **Doğruluk ajanı** (`tools/realclient/agent/`): `LivingEntity.speed` alanı özniteliği bir tik gecikmeli taşır;
   f0aa820'den beri ajan `movementSpeed` özniteliğini de kaydeder ve `MovementTruth` tik başını ondan okur.
3. **Hareket takipçisi (`MovementTracker`)**: her tik için hipotez kümesi; `EXPLAINED` (istemci hipotezlerden
   biriyle bit bit aynı), `NO_CLAIM` (iddiadan çekildi, kayıp), `UNTRACKED` (kayıptan sonra yeniden çapalanana
   kadar). Çapalar: giriş/respawn; `settleAnchor` (12 tuşsuz tik + aynı konum); `runAnchor` (#102/#103: düz
   koşu/sprint-zıplama, monoton hız aralığı daraltma, en az 3 konumun bit bit tutması); `fastPostActionAnchor`
   (Phase 21 — §7 P0-1'deki açık burada).
4. **Dünya replikası tahmin modeli (`ClientWorld`)**: istemcinin kendi blok tahminleri blok-eylem sırasına
   (sequence) göre tutulur. `resolvedUses`: KEPT (hiçbir şey koymadı), SAME_BLOCK (aynı bloğun herhangi bir
   durumu), yerleştirilen durum. Nedensellik: bir kullanımın "öncesi" anlık görüntüsü kullanım geldiği anda alınır
   (`useBefore`). Kazı (#104/#105): `START/STOP_DESTROY_BLOCK` hedefi + 6 komşuyu işaretler; komşular tepki
   vermiyorsa (`NEIGHBOUR_REACTIVE` && !`UPDATE_KEEPS_BLOCK`) KEPT, bloğunu koruyorsa SAME_BLOCK çözülür; kendi
   kancası olan bloklarda (`BlockStates.DIG_HOOKED`: attack/playerWillDestroy/destroy) çözülmez. Anlık olmadığı
   kanıtlanan START = hiçbir şey koymadı; hayatta kalma STOP'u = bloğun sıvısı (hava / su kaynağı). Çözümleyici:
   `DigPredictions` (`Replicas.listeners()` içinde, en sonda). Kırma aritmetiği: `Digging`.
5. **Blok veri tablosu**: `whynotac-world/src/main/resources/dev/whynotac/world/block-states.txt.gz`, biçim 6.
   Bayraklar resmi istemciden `tools/realclient/agent/.../BlockDataDump.java` ile üretilir, Paper'a karşı
   `whynotac-paper/src/test/.../BlockStatesDataTest.java` ile sınanır; CI'daki gerçek-istemci işi dökümün gönderilen
   tabloyla birebir aynı olmasını şart koşar. Bayrak eklersen üçünü birden güncelle.
6. **`OnFoot.sameForMovement`** hareketi etkilemeyen bayrakları (`BlockStates.PLACEMENT_FLAGS`) maskeler.
7. **Bilinen sürücü varsayımları** (gerçek-istemci FAIL'leri ama kontrol hatası değil): yüksek gecikmede bir
   paket yığınında iki fare hareketinin aynı tike düşmesi (yaw adımı), ışınlanmadan önce basılan zıplama yüzünden
   bir süzülmenin başlamaması, aşırı gecikmede rastgele yürüyüşün henüz yüklenmemiş bir chunk'a ışınlanıp
   `walkTruthFound=0` vermesi.
8. **Altyapı tuzakları:** Maven Central bazen 429 verir (paper modülü test bağımlılıkları) → 30–120 s bekleyip
   yeniden dene; `--offline` paper modülünde depo-başı önbellek yüzünden işe yaramayabilir. Konteyner yeniden
   başlarsa arka plandaki uzun koşular ölür; günlüğü kontrol et. CI'daki Gradle indirme adımı zaten yeniden deniyor.

---

## 5. Ortam ve komutlar

### 5.1 Derleme ve test
```bash
cd WhyNotAC_Workspace
./gradlew build                                   # CI'nın yaptığı: her şey (yerelde nadiren)
./gradlew :whynotac-world:test --tests '*ClientWorldTest'
./gradlew :whynotac-analysis:test --tests '*MovementCheckTest' --tests '*RegressionReplayTest'
./gradlew :whynotac-paper:test --tests '*BlockStatesDataTest' --tests '*ItemPrototypesDataTest'
./gradlew :whynotac-conformance:test --tests '*ClientWorldPlacementConformanceTest'
./gradlew :whynotac-paper:jar                     # eklenti jar'ı (replay araçları için de classpath)
```
Test sonuçları: `*/build/test-results/test/*.xml`.

### 5.2 Gerçek istemci düzeneği (doğrulamanın altın standardı)
```bash
tools/realclient/run_realclient.sh <work-dir>     # 25–40 dk; resmi 1.21.11 istemcisi + Paper, Xvfb + Mesa, XTest girdisi
```
- Gerekenler: Xvfb, yazılım OpenGL (Mesa), python3, Java 21; ilk koşu resmi istemciyi
  (`tools/realclient/fetch_client.py`, SHA-1 doğrulamalı) ve Paper jar'ını indirir.
- Ortam değişkenleri: `REALCLIENT_LATENCY="<ms> <jitter>"` (iki yönlü TCP gecikme aracısı),
  `REALCLIENT_FREEZE="<period_s> <freeze_s>"` (istemciyi dondurur), `REALCLIENT_MAX_FPS`, `REALCLIENT_OPTIONS`
  (ek `options.txt` satırları), `REALCLIENT_SENSITIVITY`, `REALCLIENT_INVERT_Y`, `REALCLIENT_VIEW_DISTANCE`,
  `REALCLIENT_DISPLAY`.
- Çıktı: `PASS/FAIL` satırları; `<work-dir>/movement.json` (`MovementTruth`: `mismatched` 0 olmalı;
  `tracker.unexplained` 0, `tracker.truthMissing` 0 olmalı); kayıt `<work-dir>/plugins/WhyNotAC/recordings/`.
- Hareket/dünya/varlık/envanter kodunu değiştirdiysen bu koşuyu yap; CI'da da her push'ta çalışır.

### 5.3 Vanilla kodunu okumak (hafızadan değil)
- İstemci jar'ı ve Mojang eşlemeleri: `<work-dir>/client-cache/client/1.21.11.jar` ve `1.21.11-client.txt`.
  Eşlemeden sınıfın obfuscated adını bul, `javap -c -p -classpath <jar> <obf>` ile bayt kodunu oku.
- Sunucu tarafı: Paper dev bundle (`whynotac-conformance` ve `whynotac-paper` testleri NMS'e erişir); örnek:
  gradle önbelleğindeki `paperweight-userdev/.../output.jar` + `javap`.

### 5.4 Kayıtlar ve regresyon
- `regression/*.wnrec` + `RegressionReplayTest` (kanıt yok, marjlar ≥ 0). `cheat-corpus/*.wnrec`
  `BenchmarkRunner` ile üretilen **sentetik** kayıtlardır (gerçek hile istemcisi değil).
- Tek bir kaydı denetlemek için küçük Java betikleri yaz (`Replayer.replay(RecordingReader.open(path), chain)` +
  `Replicas.vanilla().listeners()` + `MovementTracker`), jar'ı classpath'e ver.

---

## 6. Her değişiklik için "bitti" tanımı

1. Kök neden ve vanilla gerekçesi yazılı (kod yorumu ve commit gövdesinde, ilgili vanilla metot adlarıyla).
2. Değişikliği yakalayan **test**: kural önce yanlış davranırken test başarısız olmalı (mutasyonla doğrula:
   düzeltmeyi geri al → test kırmızı).
3. İlgili testler yeşil (dokunduğun modül + `RegressionReplayTest`; hareket ise `MovementCheckTest`; veri/flag ise
   Paper veri testleri; yerleştirme ise conformance testi).
4. Hareket/dünya değiştiyse gerçek istemci koşusu: 0 vaka, 0 açıklanamayan tik, `mismatched` 0.
5. Belgeler güncel: `SOUNDNESS.md` (kuralın kanıtı), `LEGITIMATE-BEHAVIOUR.md` (yeni sınanan davranış),
   gerekirse `ROADMAP.md` / zayıflık analizi. Yalnızca ölçülen sayılar.
6. Commit (İngilizce, açıklayıcı başlık + gövdede neden/ölçüm), fetch + ff-merge, push, CI'ın bütün işleri yeşil:
   `build`, `live E2E (vanilla)`, `live E2E (interop)`, `live E2E (velocity proxy)`, `real client ground truth`.
7. Sahibe Türkçe, kısa rapor: ne yapıldı, nasıl doğrulandı, ne doğrulanmadı.

---

## 7. Açık işler (öncelik sırasıyla)

### P0 — İncelemede bulunan 5 sağlamlık açığını kapat (her biri ayrı commit + test)

**P0-1. `MovementTracker.fastPostActionAnchor` — çift dokunma koşu tetiği (P-M MovementCheck, olası yanlış bulgu)**
- Yer: `whynotac-analysis/src/main/java/dev/whynotac/analysis/movement/MovementTracker.java`,
  `fastPostActionAnchor(...)` (~satır 3181) ve çağrıldığı yer (~satır 2792).
- Sorun: eski `settleAnchor` 12 tuşsuz tik (`SETTLE_KEYLESS_TICKS`) bekler; bu çapa `keylessTicks >= 1` ile kurulup
  tahmini durumda `sprintTriggerTime = 0` varsayıyor. Vanilla'da (`LocalPlayer.aiStep`) taze bir ileri basışı
  koşmuyorken `sprintTriggerTime = sprintWindow` (varsayılan 7) yapar, her tik bir azalır; tetik > 0 iken yeni bir
  ileri basışı koşmayı başlatır. Takipçi bu değeri durumun parçası olarak taşır, bilinmeyen değer için dallanmaz.
- Senaryo: oyuncu duvara bakarken (kısa bir eylem kaybından sonra) W'ya dokunur (duvar yüzünden konum değişmez,
  tetik 7 olur), bırakır; bir sonraki tik konum aynı, çarpışma yok → çapa tetik 0 ile kurulur; oyuncu birkaç tik
  içinde W-W yapar → istemci bir tik koşar ve `START_SPRINTING` gönderir, motor öngörmez → açıklanamayan tik.
- Düzeltme seçenekleri: (a) çapa için en az `max(sprintWindow)` tuşsuz tik iste (seçeneğin olası en büyük değeri;
  `options.sprintWindow` aralığını istemci kodundan doğrula) **veya** (b) tetik değerlerinin her biri için
  hipotez ekle (0..pencere-1). (b) kapsamı korur, (a) daha basit.
- Test: `MovementCheckTest` içinde yukarıdaki senaryoyu sentetik istemciyle kur; düzeltmeden önce açıklanamayan
  tik/kanıt çıkmalı, sonra çıkmamalı.
- Not: bu bulgu kod okumasıyla çıkarıldı, testle henüz doğrulanmadı. Önce testle doğrula.

**P0-2. `UseSlowdownCheck` "sprint-while-using" — sunucu yankısı (P-M, yanlış bulgu)**
- Yer: `whynotac-analysis/src/main/java/dev/whynotac/analysis/checks/UseSlowdownCheck.java` (öz `SetEntityData`
  işleme ~satır 239; START kuralı ~satır 381).
- Sorun: kontrol öz varlık verisinde yalnızca index 8 (eşya kullanma) ve 6'ya (poz) bakıyor, index 0'ı (koşma biti)
  hiç ele almıyor. Bayat bir START yankısı (§4.1) kalkan kullanılırken gelirse vanilla `sendIsSprintingIfNeeded`
  `START_SPRINTING` gönderir (kalkan ileri tuşunu kesmez; `shouldStopRunSprinting` kullanımla durmaz — `OnFoot`
  modeli gerçek istemciyle doğrulanmış).
- Düzeltme: oyuncunun kendi `SetEntityData` index 0'ında koşma biti 1 olan bir paket gönderildiğinde, istemci onu
  işleyene (ardından gelen ping'in cevabı) ve bir tik daha geçene kadar bu kuralı uygulama (`clearHold`).
- Test: kalkan kullanımı sırasında koşma biti 1 olan öz `SetEntityData` + sonraki START → bulgu olmamalı;
  yankı yokken START → bulgu olmalı.

**P0-3. `PacketBoundsCheck` invalid-chunk-batch-rate `> 64` (P-M, yanlış bulgu)**
- Yer: `whynotac-analysis/src/main/java/dev/whynotac/analysis/checks/PacketBoundsCheck.java` (~satır 242).
- Kanıt (resmi istemci bayt kodu, `ChunkBatchSizeCalculator`, 1.21.11'de obf `hhx`):
  `getDesiredChunksPerTick() = (float)(7000000.0 / aggregatedNanosPerChunk)`;
  `onBatchFinished`: `aggregated = (aggregated*w + clamp(nanosPerChunk, aggregated/3, aggregated*3)) / (w+1)`,
  `w = min(49, w+1)`. Mutlak alt sınır yok, yani üst sınır da yok; sunucu (`PlayerChunkSender`) [0.01, 64]'e kırpar.
  Hızlı makinede/düz dünyada yeterince batch sonra vanilla 64'ü aşabilir.
- Düzeltme: `> 64.0F` koşulunu kaldır; sonlu olmama ve `<= 0` kalsın (vanilla'da imkânsız).
- Test: rate 100 → bulgu yok; NaN/0/negatif → bulgu.

**P0-4. `VelocityCheck` durağan yatay itme (P-A, yanlış bulgu)**
- Yer: `whynotac-analysis/src/main/java/dev/whynotac/analysis/checks/VelocityCheck.java` (`armKnockback`,
  `expectedHorizontalMinimum`).
- Sorun: yatay aday için `effVy >= 0.0` yeterli; durağan (tuşsuz) oyuncu için beklenen en az yatay yer değiştirme
  `max(0.08, horizMag*0.45)` ve bu dalda zemin/eğilme kontrolü yok. Vanilla `Player.maybeBackOffFromEdge`,
  `movement.y <= 0` iken eğilen (`isStayingOnGroundSurface`) oyuncunun kenardan çıkmasını engeller; bir eklentinin
  `setVelocity(x, 0, z)` ile ittiği, kenarda eğilen dürüst oyuncu yatayda hiç hareket etmeyebilir. Shift tuşu
  "girdi" sayılmıyor.
- Düzeltme: yatay aday için `effVy > 0` (kesin) iste **ve/veya** shift basılıyken (PlayerInput.shift) ya da ayak izi
  tamamen desteklenmiyorken yatay iddiayı kapat. Ayrıca kontrolün P-A iddiasını, sabit eşiklerin her vanilla
  etkiyi kapsadığını gösterecek şekilde `SOUNDNESS.md`'de belgele (bkz. P1).
- Test: kenarda eğilen, `vy=0` yatay itme → bulgu yok.

**P0-5. `ClientEntities` yüksek kimlik filtresi (replika eksikliği → hareket/itme iddiaları)**
- Yer: `whynotac-world/src/main/java/dev/whynotac/world/ClientEntities.java` (`VIRTUAL_DECOY_MIN_ID =
  1_900_000_000`, ~satır 549: bu kimlik ve üstündeki her varlık atılıyor).
- Sorun: (a) sondalar açıkken görünmez yem husk/zombie istemci için gerçek, itilebilir varlıklar; replika
  bilmediği için itilen oyuncunun hareketi açıklanamaz → yanlış P-M. (b) sondalar kapalıyken bile başka
  eklentilerin yüksek kimlikle gönderdiği istemci-tarafı varlıklar (paket NPC'leri, sahte bariyer shulker'ı/teknesi)
  gizlenir.
- Düzeltme: yalnızca `LiveProbeDriver`'ın gerçekten verdiği kimlikleri (paylaşılan bir kayıt defteriyle) hariç tut;
  yemleri replikada itici/katı varlık olarak say ya da yemlerin oyuncu kutusuyla hiç örtüşmeyeceğini garanti et.
- Test: başka kaynaktan gelen kimliği ≥ 1.9e9 varlık replikada görünmeli; yem yakınında `mayHavePusherNear` true.

### P1 — Kanıt sınıfı (P-A/P-M) kontrollerinin tam sağlamlık denetimi
Her kontrolün her kuralı için §1.3'teki yöntemi uygula ve sonucu `docs/SOUNDNESS.md`'ye kural kural yaz (vanilla kod
yolu, olası vanilla kaynakları listesi, sonuç: kanıtlandı / daraltıldı / S'ye indirildi). Sıra önerisi (yeni ve
eşik tabanlı olanlar önce): VelocityCheck, UseSlowdownCheck, PacketBoundsCheck, TransactionOrderCheck,
InventoryActionCheck, AttackReachCheck, ActionGrammarCheck, TickFramingCheck, ClientClockCheck, BreakTimeCheck,
PredictionSequenceCheck, InteractAtBoxCheck, AttackAimCheck, UseAimCheck, MovementCheck (çapalar dahil).
Özellikle bak: §4.1 yankısı; `SetHeldSlot` sonrası `carriedIndex`; KeepAlive'ın `sendWhen` ile ertelenebilmesi;
configuration fazına dönüş (Velocity); kare başına 10 tik; donmalar; erişilebilirlik ve seçenekler.

### P2 — Belgeleri dürüstleştir
- `README.md` satır 9 ve `docs/ROADMAP.md` (~satır 209): "%99,94 EXPLAINED"i tanımıyla yaz veya iki oranı birden
  ver: EXPLAINED/(EXPLAINED+NO_CLAIM) ve EXPLAINED/tüm tikler (regresyonda ≈%95,4; tam parkurda ≈%81).
- "52/52", "16/16", "0 FN" iddialarını "kendi yazdığımız sentetik senaryolarda" diye niteleyerek yaz;
  `cheat-corpus` adlarının gerçek hile istemcisi kaydı olmadığını açıkça belirt (`cheat-corpus/README.md`).
- "100 botluk yük testinde 0 FP": botların vanilla istemci olmadığını belirt.

### P3 — Meşru davranış matrisinin kalanları (`docs/LEGITIMATE-BEHAVIOUR.md` → "Sınanmayanlar")
- Bağlantı zaman aşımına yaklaşan donmalar (10 s ve üstü): `REALCLIENT_FREEZE` ile (sunucu keepalive zaman
  aşımının altında kal).
- Geçişli koşma/çömelme (toggleSprint/toggleCrouch) ile tam tur: sürücünün (`tools/realclient/course.py`,
  `run_realclient.sh`) tuşları bu ayara göre basması gerekir.
- Koşarak köprü (eğilmeden, zıplayarak), boş elle ve etkileşimli bloklara kullanımlar.
- Ham fare girdisi, ayrık kaydırma; çok yüksek FPS (GPU gerektirir); Windows/macOS (isteğe bağlı CI matrisi).
- Her koşudan sonra kataloğa ölçülen sayılarla satır ekle; sürücü varsayımı kaynaklı FAIL'leri ayrıca not et.

### P4 — Kapsamı (UNTRACKED) doğruluğu bozmadan artır
- Kayıp nedenlerini ölç (kayıttan kayıp nedeni ve takipsiz tik sayısı), en büyük kalemlerden başla
  (ör. henüz yüklenmemiş chunk sonrası yeniden çapalama, rastgele yürüyüşte çapa). Her yeni çapa,
  durumun **bütün** gizli alanlarını (sprintTriggerTime, noJumpDelay, autoJumpTime, crouching, pose, fallDistance,
  sprint çarpanı...) ispatlamalı; ispatlayamıyorsa dallanmalı.

### P5 — Canlı SHADOW pilotu (sahibin onayıyla)
- Küçük gerçek bir sunucuda birkaç hafta yalnızca SHADOW: dossier'ları topla, her bulguyu elle incele, yanlış
  bulgu çıkarsa kök nedenini düzelt. Ceza yok.

### P6 — DATA-02: gerçek hile istemcileriyle ölçüm (başlamadan önce sahibine sor)
- İzole VM'de Xvfb + Paper 1.21.11, açık kaynak istemciler (Meteor, Wurst, LiquidBounce 1.21.11 sürümleri), her modül
  için etiketli `.wnrec` kayıtları (`cheat-corpus/real/`), gerçek TPR/TTD tablosu. Herkese açık sunucuda asla.

### P7 — İkinci sürüm/protokol desteği (uzun vade; mimari buna izinli, `DECISIONS.md`: şimdilik 1.21.11).

---

## 8. Yapma listesi

- Varsayılanları ARMED yapma, ceza/kick/ban açma, sondaları varsayılan açma.
- P-A/P-M'yi eşik "yeterince güvenli" diye verme; kanıt yoksa S'ye indir ya da iddiadan çekil.
- Gerçek-istemci kapılarını, regresyon testlerini, veri testlerini başarısızlığı gizlemek için değiştirme.
- Ölçmediğin sayıyı yazma; sentetik sonucu gerçek tespit oranı diye sunma.
- Force-push, rebase, başkasının commit'ini yeniden yazma; yeni PR açma.
- `DECISIONS.md`'yi sahibin onayı olmadan değiştirme.
- Gizli bilgi (token, şifre) commit'leme.

---

## 9. İlk adımın

1. `git fetch` + `git merge --ff-only` ile dalı güncelle; `git log --oneline -5` ile durumu gör.
2. Bu belgeyi ve `docs/DECISIONS.md`, `docs/POLICY.md`, `docs/SOUNDNESS.md`'yi oku (yalnızca bunları).
3. **P0-3** ile başla (en küçük ve kesin): kuralı düzelt, test ekle, ilgili testi çalıştır, commit + push, CI'ı izle.
4. Sonra P0-2, P0-4, P0-5, P0-1 (P0-1 için önce testle doğrula). Her birinden sonra sahibe kısa Türkçe rapor.
5. Ardından P1 denetimine geç ve sonuçları `SOUNDNESS.md`'ye işle.
