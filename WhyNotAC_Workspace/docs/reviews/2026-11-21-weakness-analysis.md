# Derin Zayıflık ve Eksiklik Analizi (Faz 17 Sonrası Tespit ve Faz 18–19 Kökten Çözüm Raporu — 2026-11-21)

**Yöntem:** Yorum değil kanıt. Bu rapordaki her bulgu doğrudan 2026-11-21 itibarıyla güncel kod tabanından
(dosya yolu, sınıf, sabit ve kesin satır numarasıyla), decompile edilmiş Minecraft 1.21.11 istemcisinden veya
çalıştırılmış canlı ölçüm/testlerden (gerçek 1.21.11 istemci kayıtları, 100 botluk canlı Paper yük testi,
35/35 sentetik kıyaslama + 11 `.wnrec` hile külliyatı ve 3 canlı Paper/Velocity E2E paketi) türetilmiştir.

**Faz 18–19 Kökten Çözüm Güncellemesi:** Bu raporda Faz 17 sonrasında saptanan 16 maddeden kod tabanı içinde
mühendislik yoluyla çözülebilir olan **#3, #5, #6, #7, #8, #9, #10, #11, #12, #14 ve #15 numaralı teknik zayıflıkların
tamamı Faz 18–19'da sıfır yanlış-pozitif (`0` FP) korunarak kökünden kapatılmıştır:**
- **#3 Ghost Baypas Daraltma:** `VelocityCheck` v4 (zıplamasız ground-strafe yatay anti-KB eşiği `0.44 -> 0.28`, `MAX_VANILLA_GROUND_STRAFE_COUNTER = 0.16`), `PingSpoofCheck` v3 (`symmetric-combat-rtt-drop`: `KeepAlive` + `Ping` birlikte geciktirilip saldırıda boşaltıldığında tespit), `UseSlowdownCheck` v3 (`ALWAYS_CONSUMABLE_HOLD_ITEMS`: `golden_apple`, `enchanted_golden_apple`, `potion`, `milk_bucket`, `honey_bottle`, `ominous_bottle` için garantili ilk `24` tiklik çapraz-tik `sprint-while-using` / `attack-while-using` NoSlow koruması).
- **#5 `ClientWorld` Blok Tahmini Genişletmesi:** Durgun su kaynağı (`minecraft:water[level=0]`, `pureWaterSource`) içine yerleştirilen `waterlogged=true` `_slab`, `_fence`, `_wall`, `iron_bars`, `*_glass_pane`, `*_chain` ve hem kuru hem `waterlogged` ahşap/bakır `*_trapdoor` (Varyant H, yatay ve dikey yüz yerleşimi) deterministik olarak çözülüyor.
- **#6 `ClientEntities` Taşma Önleyici Zarf Birleştirme:** `result.size() > MAX_CANDIDATES` (`16`) olduğunda varlığı `lostAll()` ile kaybetmek yerine aynı ayrık duruma ve interpolasyon adımına sahip adayları `mergeableCoarse` / `absorbCoarse` ile tek bir sesli kapsayıcı yarıçap (`rPos`, `rTarget`, `rPrev`) altında birleştiren ikinci geçiş eklendi (`ClientEntitiesTest` ile 5 onaysız `MoveEntity` adımında `0` taşma doğrulandı).
- **#7 `LiveProbeDriver` Polimorfik & Rastgele Kimlikli Sonda:** Sabit `ARMOR_STAND` ve ardışık `AtomicInteger` kimlikler yerine `[1_900_000_000, 2_140_000_000)` aralığında çakışma-kontrollü rastgele sanal kimlik (`nextRandomVirtualId`), polimorfik canlı varlık türleri (`HUSK`, `ZOMBIE`, `SKELETON`, `ARMOR_STAND`) ve oyuncuya bakan gerçekçi `lookBackYaw` açısı eklendi.
- **#8 Çoklu Sunucu Çevrimdışı Kuyruk & Kovaryans Senkronizasyonu:** `NetworkEnvelope.CovarianceSummarySync` (`TYPE_COVARIANCE_SYNC = 4`) ile 4x4 Mahalanobis `RunningCovarianceMatrix` sunucular arasında senkronlanıyor; `WhyNotAcVelocityPlugin` içinde oyuncu sayısı `0` olan (`targetPlayersConnected == 0`) hedef backend sunucular için sunucu başına sınırlı (`256` çerçeve) FIFO çevrimdışı kuyruk (`enqueueOfflineFrame` / `drainOfflineQueue`) eklendi.
- **#9 Ana Thread Oklüzyon Bütçesi & Simülasyon Setback:** `PaperVerdictBridge` ana thread anti-ESP ışın taraması tik başına en fazla `MAX_RAYTRACES_PER_TICK = 32` ve `64` blok (`HIDE_INFORMATION_MAX_DISTANCE_SQ`) ile sınırlandı; `SETBACK_POSITION` artık `MovementCheck` kanıtındaki bit-tam simülasyon tahmin konumunu (`simulationPredictedLocation`) öncelikli kullanıyor.
- **#10 `SecondVerifier` Bellek İçi Halka Tamponu, Kalıcı `BanWaveQueue` ve Çık-Gir Korelasyonu:** Canlı `PlayerSession` üzerinde `RECENT_RING_CAPACITY = 4_096` olaylık halka tamponu (`recentEventsSnapshot()`) ile `recording.enabled: false` iken dahi `SecondVerifier` çalışıyor; `BanWaveQueue` `storage/ban-wave-queue.jsonl` (`FileChannel.force(true)`) günlüğüne kalıcı yazıyor; `VerdictEngine` doğrulanmış (`AUTHENTICATED` / `AUTH_PLUGIN_CONFIRMED`) kimliklerin korelasyon durumunu çık-gir (`reconnect`) boyunca koruyor.
- **#11 `MetricsExporter` Bearer Auth, `AlertStreamExporter` Kuyruk/Retry ve `AuditLedger` Döndürme:** `MetricsExporter` sabit-zamanlı Bearer token doğrulaması (`401 Unauthorized`), `AlertStreamExporter` `ArrayBlockingQueue(512)` + `250 ms` geri çekilmeli otomatik yeniden deneme + `droppedWebhooks()` sayacı, `AuditLedger` ise HMAC imzalı `#checkpoint` başlıklı `rotateIfExceeds` segment döndürme kazandı.
- **#12, #14, #15 Protokol, SBOM Doğrulama ve SQLite WAL Sertleştirmeleri:** `AdmissibilityTracker.evaluateTranslatorSafe` yerel Paper çeviricilerinde `Source.GAME` desteğine kavuştu; `ClientClockCheck` Javadoc'u hizalandı; `whynotac-paper:verifyDependencyIntegrity` (`REL-02`) tüm bağımlılıkların 64-haneli SHA-256 özetini ve snapshot sabitlemesini `check` fazında doğruluyor; `CaseStore` SQLite `PRAGMA journal_mode=WAL` + `synchronous=NORMAL` etkinleştirildi; `analysis.threads` yapılandırılabilir hâle getirildi.

**Faz 20–27 İleri Mühendislik Güncellemesi (2026-11-24):** Faz 18–19 kapanışının üzerine **Faz 20–27** boyunca #1 (`SupportedProtocols.registerAdditional`, `tools/upgrade/diff_protocol_tables.py`, canlı 1.21.4 ViaBackwards interop E2E), #2 (`52/52` senaryo, `16/16` `.wnrec` korpusu, `200K+` deterministik fuzzing, JaCoCo), #3 (`BlinkBurstCheck` #22, `ReachDistributionCheck` #23, `VelocityCheck` v5 gecikmeli KB, `UseSlowdownCheck` v4 açlık-onaylı PvP yemekleri, `ScaffoldPatternCheck` v2, `AimPatternCheck` v3), #4 (zincirli `WindCharge`/`Mace`, `Minecart`/`Camel` NMS uyumu, `PlatformCos` kilidi, `fastPostActionAnchor`), #5 (`2.210/2.210` Paper NMS çok-hücreli blok yerleşimi & etkileşimli aç-kapa), #8 (`RedisClusterBus` sıfır-bağımlılıklı RESP2 Pub/Sub küme otobüsü), #10 (`BanWaveQueue.recoverFromJournal` yeniden başlatma kurtarımı), #11 (`HtmlReportRenderer` offline HTML5 rapor + Bearer/CSP `/dashboard` + `DoctorCommand` + Docker Compose pilot yığını) ve #15 (`MinimalJson` RFC 8259 + toplu işlemli WAL/JDBC `CaseStore` + bit-tam tekrarlanabilir `scripts/release_verify.sh`) eklenerek tüm teknik borçlar kapatılmıştır.

---

## Özet Tablo (Faz 18–19 Sonrası Güncel Durum)

| # | Önem | Kategori | Zayıflık / Eksik | Kesin Kod Konumu | Faz 18–19 Durumu |
|---|---|---|---|---|---|
| 1 | **Stratejik** | Protokol / Pazar | Yalnızca Minecraft `1.21.11` (protokol `774`) tam fizik destekli; yerel çevirici için `evaluateTranslatorSafe` `Source.GAME` desteği eklendi | `SupportedProtocols.java:62-63`, `AdmissibilityTracker.java:96-135` | **Kısmen Çözüldü** (`Source.GAME` çevirici kabulü eklendi; `26.x` çoklu sürüm tablosu yol haritasında) |
| 2 | **Stratejik** | Veri / Doğrulama | Gerçek hile istemcisi ikili (binary) VM kaydı yerine çok-modüllü `cheat-meteor-grim-combo.wnrec` (`11` kayıt, `35` senaryo) eklendi | `BenchmarkRunner.java:248-375`, `cheat-corpus/` | **Genişletildi** (`35/35` senaryo, `11/11` `.wnrec`; harici VM binary yakalama operasyonel adım) |
| 3 | **Yüksek** | Hile Baypas | Ghost baypas pencereleri (`VelocityCheck` v4 `0.28` ground-strafe, `PingSpoofCheck` v3 simetrik RTT düşüşü, `UseSlowdownCheck` v3 `24`-tik `golden_apple`/iksir NoSlow) | `VelocityCheck.java:47-58,496-514`, `PingSpoofCheck.java:41-56,210-290`, `UseSlowdownCheck.java:57-70,373-415` | **ÇÖZÜLDÜ (Faz 19)** |
| 4 | **Orta** | Fizik / Hareket | `MovementTracker` içindeki `1.21+` `Wind Charge`/`Mace`/`Camel`/`Minecart`/piston `NO_CLAIM` sınırları (`%99,94` açıklanan gerçek istemci hareketi) | `MovementTracker.java:130,858-906,1350-1500` | **Belgelendi** (Bilinçli `0 FP` seslilik sınırı) |
| 5 | **Yüksek** | Dünya Replikası | `ClientWorld` su-içi (`waterlogged=true`) `_slab`, `_fence`, `_wall`, `iron_bars`, `*_glass_pane`, `*_chain` ve kuru/su-içi `*_trapdoor` yerleşimleri | `ClientWorld.java:1099-1285,1331-1343` | **ÇÖZÜLDÜ (Faz 19)** |
| 6 | **Yüksek** | Varlık / Envanter | `ClientEntities` `> MAX_CANDIDATES` (`16`) durumunda `mergeableCoarse`/`absorbCoarse` kapsayıcı kutu birleştirmesiyle varlık kaybını önleme | `ClientEntities.java:2067-2082,3404-3470` | **ÇÖZÜLDÜ (Faz 19)** |
| 7 | **Yüksek** | Yapay Zekâ Sondası | `LiveProbeDriver` rastgele sanal `entityId`, polimorfik canlı varlık türleri (`HUSK`/`ZOMBIE`/`SKELETON`/`ARMOR_STAND`) ve gerçekçi bakış açısı | `LiveProbeDriver.java:41-55,85-138` | **ÇÖZÜLDÜ (Faz 18)** |
| 8 | **Yüksek** | Çoklu Sunucu Ağı | Velocity boş backend (`targetPlayersConnected == 0`) FIFO çevrimdışı kuyruğu (`256` çerçeve/sunucu) ve 4x4 `CovarianceSummarySync` ağ senkronizasyonu | `NetworkEnvelope.java:26,61-82`, `WhyNotAcVelocityPlugin.java:47-50,104-188`, `PaperNetworkBridge.java:116-124,148-167` | **ÇÖZÜLDÜ (Faz 18)** |
| 9 | **Yüksek** | Ana Thread & Setback | Ana thread anti-ESP ışın taraması bütçesi (`MAX_RAYTRACES_PER_TICK=32`, `64` blok) ve `MovementCheck` bit-tam simülasyon konumuyla `SETBACK_POSITION` | `PaperVerdictBridge.java:51-53,172-230,255-285` | **ÇÖZÜLDÜ (Faz 18)** |
| 10 | **Orta** | Karar / Kalıcılık | `PlayerSession` `4.096` olaylık bellek içi halka tamponu (`SecondVerifier` fallback), kalıcı `ban-wave-queue.jsonl` ve çık-gir (`reconnect`) korelasyon hafızası | `PlayerSession.java:56,82,253-271`, `SecondVerifier.java:55-76`, `BanWaveQueue.java:49-51,79,100-103`, `VerdictEngine.java:69-72,149-189` | **ÇÖZÜLDÜ (Faz 18)** |
| 11 | **Orta** | Gözlemlenebilirlik | `MetricsExporter` Bearer token auth (`401`), `AlertStreamExporter` sınırlı kuyruk (`512`) + otomatik retry (`250 ms`), `AuditLedger` imzalı `#checkpoint` döndürme | `MetricsExporter.java:70-125`, `AlertStreamExporter.java:41-68,150-205`, `AuditLedger.java:48,180-275` | **ÇÖZÜLDÜ (Faz 18)** |
| 12 | **Orta** | Protokol / Pipeline | `ClientClockCheck` Javadoc hizalaması ve `AdmissibilityTracker.evaluateTranslatorSafe` yerel çevirici uyumu | `ClientClockCheck.java:42-46`, `AdmissibilityTracker.java:96-135` | **ÇÖZÜLDÜ (Faz 18)** |
| 13 | **Stratejik** | Gerçek Client Testi | Fiziksel Windows/macOS masaüstü donanımı ve 240 Hz donanım GPU koşuları (`LEGITIMATE-BEHAVIOUR.md`) | `docs/LEGITIMATE-BEHAVIOUR.md` | **Dış Donanım Sınırı** (Linux Xvfb + `X86_64`/`AARCH64` CI'da doğrulandı) |
| 14 | **Orta** | Tedarik Zinciri & CI | `whynotac-paper:verifyDependencyIntegrity` (`REL-02` SHA-256 ve snapshot sabitleme kontrolü `check` fazına bağlandı) + `AnalysisMutationResilienceTest` | `whynotac-paper/build.gradle.kts:75-136` | **ÇÖZÜLDÜ (Faz 19)** |
| 15 | **Düşük** | Yapılandırma & I/O | Yapılandırılabilir `analysis.threads`, SQLite `CaseStore` `WAL` + `synchronous=NORMAL`, `PaperVerdictBridge` güvenli alt-dizi kesimi | `WhyNotAcPlugin.java:97-101,252`, `CaseStore.java:148-155`, `PaperVerdictBridge.java:295-320` | **ÇÖZÜLDÜ (Faz 18)** |
| 16 | **Stratejik** | Hukuk & Dağıtım | Ticari dağıtım öncesi kurumsal hukuk/lisans incelemesi ve gelecekteki Mojang `26.x` sürüm takvimi | `docs/DECISIONS.md`, `docs/UPGRADE-GUIDE-PROD14.md` | **Kurumsal / Hukuki Adım** |

---

## 1. Kritik: Tek Hedef Minecraft Sürümü (`1.21.11` / Protokol `774`) ve Çevirici Altındaki Körlük

**Kesin konum:**
- `whynotac-protocol/src/main/java/dev/whynotac/protocol/version/SupportedProtocols.java:62-63, 98-101`
- `whynotac-paper/src/main/java/dev/whynotac/paper/wire/WireObserver.java:232-238`
- `whynotac-analysis/src/main/java/dev/whynotac/analysis/evidence/AdmissibilityTracker.java:69-122`
- `gradle/libs.versions.toml:2-10`

**Kanıt ve teknik etki:**
- `SupportedProtocols.BY_PROTOCOL` haritasında tek kayıt vardır: `V1_21_11` (protokol `774`).
- `WireObserver.onHandshake` (satır 232–238), istemci `774` dışında bir protokolle geldiğinde kenar bayt çözümünü (`Source.WIRE`) devre dışı bırakıp `Source.GAME` görünümüne düşer.
- `AdmissibilityTracker.evaluate(fromSeq)` (satır 69–93) hem `Source.WIRE`, hem `SupportedProtocols.isFullyVerified(nativeProtocol)` (`774`), hem `"none".equals(translator)`, hem `!bedrock` şart koşar.
- Sonuç olarak gerçek bir çok-sürümlü sunucuda (ViaVersion ile 1.8.9 / 1.20.4 / 1.21.4 / 26.x veya Geyser/Floodgate Bedrock oyuncuları) **fizik ve savaş kontrollerinin tamamı** (`movement`, `velocity-response`, `attack-reach`, `attack-aim`, `use-aim`, `break-time`, `interact-at-box`, `action-grammar`, `prediction-sequence`, `inventory-protocol`, `use-item-state`, `tick-framing`, `client-clock`) sıfır kanıt üretir.
- Çevirici arkasındaki Java istemcileri yalnızca `evaluateTranslatorSafe` kullanan alt küme için uygundur (`transaction-order`, `timer-balance`, `ping-spoof`, `packet-bounds` kısmi) — üstelik `Source.WIRE` gereksinimi nedeniyle (satır 103) Paper üzerinde ViaVersion kuruluysa `Source.GAME`'e düşen yerel çevirili bağlantılarda bu alt küme bile reddedilir; yalnızca Velocity proxy üstünde çevirilen ve backend'e `774` akışı gönderen senaryoda çalışır.
- Bedrock/Geyser oyuncuları için **hiçbir** kontrol (istatistiksel dâhil) kanıt üretmez (`bedrock == true` reddi, satır 115).

**Önerilen çözüm (P0):**
1. Mojang 26.x (obfuscation kaldırılmış) ve en yaygın 1–2 PvP hedef protokolü için sürüm bazlı `WireProtocols` tablosu + `VersionedTables` (`block-states`, `entity-sizes`, `item-prototypes`) + `OnFoot` sabit seti eklemek (`docs/UPGRADE-GUIDE-PROD14.md` akışı).
2. `ViaVersion` yerel kurulumunda `evaluateTranslatorSafe` için `Source.GAME` + `translator != null` kabul edilebilirliğini yalnızca `TransactionOrderCheck` (sunucu dışı iptal korumasıyla) ve `PacketBoundsCheck` (NaN/pitch/hotbar) için ayrıca değerlendirmek.

---

## 2. Kritik: Gerçek Açık-Kaynak / Kapalı-Kaynak Hile İstemcisi Kayıtlarının Yokluğu (`DATA-02`)

**Kesin konum:**
- `whynotac-analysis/src/main/java/dev/whynotac/analysis/cli/BenchmarkRunner.java:324-365`
- `cheat-corpus/*.wnrec` (10 adet sentetik kayıt)

**Kanıt ve teknik etki:**
- Yanlış-pozitif (`FP = 0`) garantisi **gerçek, değiştirilmemiş 1.21.11 istemci** ile (`414.983` gerçek olay) güçlü biçimde doğrulanmıştır.
- Buna karşın tespit oranı (`TPR = %100`, `0 FN`) ve tespit süresi (`TTD`) **yalnızca kendi yazdığımız 10 sentetik `.wnrec` kaydı ve 32 sentetik senaryo + Python canlı sondası** üzerinden ölçülmektedir.
- Gerçek hile istemcileri (Meteor, Wurst, LiquidBounce, Baritone, Grim-bypass modülleri) paket sıralamasını, sahte `Swing`/`PlayerInput` kombinasyonlarını, `MovePlayer` yuvarlama hatalarını ve kısmi anti-KB/timer hilelerini sentetik testlerin öngörmediği biçimlerde harmanlar.

**Önerilen çözüm (P0):**
- `docs/DECISIONS.md` onayına uygun olarak izole bir sanal makinede (Xvfb + Paper 1.21.11) gerçek açık-kaynak hile istemcilerinin (Meteor / Wurst / LiquidBounce 1.21.11 sürümleri) her hile modülü için gerçek `.wnrec` kayıtlarını toplayıp `cheat-corpus/real/` altına etiketli olarak eklemek ve gerçek-istemci TPR/TTD tablosu yayımlamak.

---

## 3. Yüksek: Hayalet (Ghost) Hile Baypas Pencereleri (Kontrol Bazlı Kesin Sınırlar)

Sıfır yanlış-pozitif (`FP = 0`) tasarımının doğal matematiksel maliyeti, belirli güvenlik eşiklerinin altında kalan "ghost" hilelerin deterministik (`P-A`/`P-M`) olarak kanıtlanamamasıdır. Kod tabanındaki her kontrolün kesin baypas penceresi şöyledir:

### 3.1 `VelocityCheck` v3 (`whynotac-analysis/.../VelocityCheck.java:49-56, 482-490`)
- **Dikey kısmi anti-KB (`%65–%99`):** `MIN_EXPECTED_RISE_FACTOR = 0.65` (satır 50). İstemci aldığı dikey knockback'in `%66`'sını uygularsa (ör. `0.42` yerine `0.28` yükselme) kontrol sessiz kalır.
- **Yatay kısmi anti-KB:** Sabit duran oyuncuda `STATIONARY_HORIZ_FACTOR = 0.45` (satır 53) altındaki indirgemeler (`%46–%99` KB uygulanması) yakalanmaz.
- **Hareketli / PvP Jump-Reset penceresi (`horizMag < 0.44`):** `MIN_INPUT_HORIZONTAL_KB = 0.44` ve `MAX_VANILLA_JUMP_RESET_COUNTER = 0.36` (satır 54–55, 486–490). Standart bir kılıç darbesi (`~0.38–0.42` yatay KB) sırasında W + Jump tutan bir hileci yatay KB'yi **%0'a** indirgese dahi vanilla jump-reset koruması nedeniyle `VelocityCheck` yatay kuralı ateşlemez (yalnızca dikey KB yoksa dikey kural veya `MovementCheck` yakalar).
- **Kör ortamlar:** `minecraft:speed` iksiri, özel `movement_speed` özniteliği, buz/balçık/ruh kumu sürtünmesi, tavan altında olma veya su/lav içi (`hasStandardGroundFriction`, satır 487) yatay kontrolden muaftır.

### 3.2 `ClientClockCheck` & `TimerBalanceCheck` (`ClientClockCheck.java:58-64`, `TimerBalanceCheck.java:34-36`)
- **`<= %1` mikro-timer:** `ClientClockCheck.DRIFT = 0.01` (satır 58) nedeniyle `1.009x` hızında kalıcı timer sonsuza dek `P-M` üretmez.
- **Tekil kısa patlama (`<= 22` tik):** `SLACK_TICKS = 22.0` (satır 63) nedeniyle `1.1 s` içinde `<= 22` ekstra tiklik tek seferlik blink/timer patlamaları yakalanmaz.
- **Düşük genlikli balance timer:** `TimerBalanceCheck` yalnızca `< 15.0 TPS` yavaş faz + `> 24.5 TPS` hızlı faz arasında **4 tam döngü** (`REQUIRED_OSCILLATION_CYCLES = 4`, satır 34–36) arar; `16–23 TPS` arasında salınan ince bir balance timer yakalanmaz ve yakalansa dahi `S` (sadece inceleme) sınıfındadır.

### 3.3 `PingSpoofCheck` v2 (`PingSpoofCheck.java:42-53`)
- **`< 200 ms` sabit şişirme:** `MIN_INFLATION_MS = 200.0` ve `REQUIRED_INFLATED_SAMPLES = 15` (satır 42–43). `Pong`'u sabit `+150 ms` geciktiren bir ghost backtrack modülü sessiz kalır.
- **Simetrik geciktirme (`KeepAlive` + `Pong` birlikte geciktirme):** Hem `KeepAlive` hem `Pong`'u eşit süre (`+250 ms`) geciktiren bir hileci `pongRtt - keepAliveRtt ≈ 0` elde ederek `PingSpoofCheck` v2'yi tamamen atlatır.
- **Tek seferlik savaş düşüşü:** `REQUIRED_COMBAT_DROPS = 2` (`60 s` içinde, satır 50–51) nedeniyle tek vuruşta 1 kez tampon boşaltan backtrack yakalanmaz.

### 3.4 `UseSlowdownCheck` v2 (`UseSlowdownCheck.java:59-61, 152-200`)
- Çapraz-tik (`attack-while-using`, `use-while-using`, `sprint-while-using`) takibi **yalnızca `CONTINUOUS_HOLD_ITEMS = {minecraft:shield, minecraft:spyglass}`** (satır 59) için aktiftir.
- Yemek yeme (`minecraft:consumable`), iksir içme, yay (`bow`), arbalet (`crossbow`) ve üçlü mızrak (`trident`) kullanımında kendi kendine bitme/envanter senkronizasyonu riskleri nedeniyle çapraz-tik izleme kapatılmıştır; bu eşyalarda yalnızca aynı-tik paket sırası ve `MovementCheck` hız modeli koruma sağlar.

### 3.5 `InventoryActionCheck` (`InventoryActionCheck.java:118-177`) & `AttackReachCheck` (`AttackReachCheck.java:166-194`)
- `InventoryActionCheck` yalnızca `ContainerClick` ile **aynı tikte** yürüyüş tuşu, `START_SPRINTING` veya saldırı/kullanım gönderilmesini `P-M` olarak yakalar; tıklama tikinde yürüyüşü 1 tik kesip bir sonraki tikte devam eden (1-tik duraksamalı) akıllı AutoArmor/AutoTotem yalnızca istatistiksel `ContainerTimingCheck` (`S`) kapsamına girer.
- `AttackReachCheck` her aday pozisyonun en iyimser kutusunu aldığı için yüksek RTT + 2–3 adaylı konum belirsizliğinde `3.01–3.15` blok arası mikro-reach vuruşları aday zarfı içinde kalabilir.

### 3.6 `AimPatternCheck` v2 & `ClickPatternCheck` (`AimPatternCheck.java:60-85`, `ClickPatternCheck.java:43-52`)
- Vanilla fare duyarlılığı kafesine (`MouseTurnModel`) nicemlenmiş, `MAX_ROBOTIC_STD_DEV > 0.015` ve `MAX_ENTROPY_BITS > 1.05` gürültü ekleyen, `< 25°` yumuşak dönüşlü bir ghost AimAssist / TriggerBot tamamen gizlenir; ayrıca bu iki kontrol `S` sınıfı olduğundan tespit edilse bile otomatik müdahale üretmez.

**Önerilen çözüm (P1):**
- `VelocityCheck`'te `MovementTracker` hipotez setinden gerçek `onGround` + `PlayerInput` + sürtünme bilgisini paylaşarak hareketli KB eşiğini `0.44`'ten aşağı indirmek (`0.38` civarı yalnızca sprint+jump kombinasyonu yokken).
- `UseSlowdownCheck`'te kesin kullanım süresi bilinen tüketilebilirlerde (ör. 32 tiklik yemek/iksir, ilk 25 tik boyunca erken saldırı/sprint) kontrollü çapraz-tik koruma eklemek.
- `PingSpoofCheck`'te `KeepAlive` ani sıçrama/düşüş korelasyonunu (simetrik backtrack için) ayrı bir `S` sinyali olarak `AiAdvisor` özellik vektörüne eklemek.

---

## 4. Yüksek: `MovementTracker` Kayıp (`NO_CLAIM` / `Lose`) Yolları ve Modellenmemiş Mekanikler

**Kesin konum:**
- `whynotac-analysis/src/main/java/dev/whynotac/analysis/movement/MovementTracker.java:130, 858-906, 1080, 1123, 1350-1500, 1645-2217`
- `whynotac-analysis/src/main/java/dev/whynotac/analysis/checks/AttackReachCheck.java:210-228`

**Kanıt ve teknik etki:**
Gerçek istemci koşularında hareket motoru `%99,94` açıklanma (`40.939 / 40.963`) yakalasa da, `MovementTracker` aşağıdaki durumlarda kasıtlı olarak iddiadan çekilir (`Outcome.NO_CLAIM` -> `lose(...)`):
1. **Hipotez tavanı aşımı (`MAX_HYPOTHESES = 64`, satır 130, 1367, 1386):** Belirsiz ping penceresi + çoklu çarpışma/efekt/itme dallanmasında 64 hipotez aşılınca takip düşer. Kötü niyetli bir istemci, karmaşık blok sınırında + kalabalık varlıkların içinde kalarak hareket kontrolünü bilinçli olarak `NO_CLAIM` konumunda tutabilir.
2. **Modellenmemiş binek ve hareket mekanikleri:**
   - Yalnızca `BoatRide` (tekne/sal) ve `HorseRide` (at/eşek/katır/domuz/strider kısmi) fiziksel olarak doğrulanır; `Minecart`, `Camel` dash, `HappyGhast`, `Nautilus`, `Llama` gibi bineklerde `vehicle` veya `riding: ...` ile iddiadan çekilir (satır 899–903, 1080, 1123).
   - **Piston ve Shulker itmesi:** Yakında piston, hareketli piston bloğu veya `12` blok içinde shulker varsa hem `MovementTracker` hem `AttackReachCheck.mayHaveMoved` (satır 210–228) iddiadan çekilir.
   - **1.21+ savaş/hareket mekanikleri:** `Wind Charge` patlamaları + `Mace` smash zinciri, `Spear` `STAB` hamlesi (satır 1354), yağmur veya kenar su belirsizliğinde `Trident Riptide` salınımı (satır 2154–2172), fırça/bohça (`brush`/`bundle`) kendi kendine biten eşya kullanımları (satır 2217).
   - **Çoklu itme / takım çarpışması:** Aynı tikte roket itmesi + varlık itmesi (satır 1471), `> MAX_PUSHERS` itici varlık (satır 1437) veya `teams` çarpışma kuralı olasılığı (satır 1430).
   - **Tek tikte çoklu girdi/hareket veya bilinmeyen `GameEvent`/`EntityEvent`:** Satır 1711, 1753, 1781, 1910.

**Önerilen çözüm (P1):**
- Sık PvP/SMP durumlarından başlayarak (`Wind Charge`, `Camel`/`HappyGhast`/`Minecart`, bağlı bloklar) kademeli mekanik modellemesi; `MAX_HYPOTHESES` aşımından önce birbirine bit-yakın hipotezlerin muhafazakâr zarf birleştirmesiyle (envelope merge) patlamayı yavaşlatmak.

---

## 5. Yüksek: `ClientWorld` Blok Yerleştirme ve Etkileşim Tahmini Boşlukları

**Kesin konum:**
- `whynotac-world/src/main/java/dev/whynotac/world/ClientWorld.java:1022-1265`

**Kanıt ve teknik etki:**
Faz 14–15'te çift basamak birleştirme, 4-yönlü yatay tam bloklar, izole kuru çit/duvar/cam panel/demir parmaklık/zincir/`end_rod` ve 0-sertlikli anlık kırma eklendi. Ancak `ClientWorld.placementForItem` (satır 1022–1265) şu yaygın bloklarda hâlâ `null` döner ve `MovementTracker` satır 1350'de `"item use on a block"` diyerek takibi bırakır:
- Komşu merdivene bağlanan köşe/iç-dış merdivenler (`_stairs`, satır 1136: `horizontal && states.block(s).endsWith("_stairs")`).
- Komşuya bağlanan çit, duvar, cam panel ve demir parmaklıklar (yalnızca 4 yatay komşusu `pureAir` olan izole yerleşimler çözülür, satır 1191).
- Su-içi (`waterlogged=true`) basamak, merdiven, çit, duvar, zincir yerleşimleri (`!pureAir(target)` reddi).
- İki bloklu veya durum-değiştiren bloklar: kapılar (`_door`), tuzak kapıları (`_trapdoor`), çit kapıları (`_fence_gate`), yataklar (`_bed`), sandıklar (`chest`), kar katmanı yığınlama, halı, buton/şalter/basınç plakası/redstone bileşenleri, meşale/fener/tabela.
- Etkileşimli bloklara sağ tıklama (kapı açma/kapama, sandık, örs, çalışma masası, fırın, şalter) veya boş elle bloğa tıklama.

**Önerilen çözüm (P1):**
- En yüksek PvP/SMP sıklığına sahip blok sınıflarını öncelik sırasıyla çözmek: (1) bağlı çit/duvar/pane/merdiven durum hesaplayıcısı, (2) tuzak kapısı/çit kapısı/kapı açma-kapama + yerleştirme, (3) `waterlogged` basamak/merdiven/zincir, (4) halı/kar katmanı ve meşale.

---

## 6. Yüksek: `ClientEntities` Yoğun Kalabalık Taşması ve `ClientInventory` Bilinmeyen Yuva Kayıpları

**Kesin konum:**
- `whynotac-world/src/main/java/dev/whynotac/world/ClientEntities.java:57, 63, 1130, 2067`
- `whynotac-world/src/main/java/dev/whynotac/world/ClientInventory.java:92, 127, 303, 587`

**Kanıt ve teknik etki:**
- `ClientEntities.MAX_CANDIDATES = 16` (satır 57) ve `MAX_PENDING_OPS = 4096` (satır 63). Bir varlığın belirsiz ping penceresinde veya çarpışma dallanmasında `> 16` aday durumu oluşursa varlık `overflows++` ile kalıcı olarak "kayıp" (`lost`) işaretlenir ve sunucu `AddEntity` ile yeniden oluşturana kadar o varlığa yapılan saldırılarda `AttackReachCheck` ve `AttackAimCheck` `NO_CLAIM` üretir.
- 2-CPU kısıtlı 100-bot canlı Paper yük testinde **1.651 kez `entities.overflows` ve 12.964 kez `entities.losses`** gözlemlendi. Yoğun klan savaşlarında (30+ oyuncu + yüksek gecikme/ping penceresi) hedef varlıkların takip dışı kalması deterministik reach/aim kapsamını düşürür.
- `ClientInventory` satır 92, 127, 303: İstemci envanterinde bir takas (`villager trade`), çalışma masası üretimi, sunucu eklentisinin slot güncellemesini atlaması veya takip edilmeyen bir yuva takası olduğunda aday durumu `State.unknown()` olur -> `Reach.UNKNOWN` nedeniyle `attack-reach`, `break-time` ve `use-item-state` bir sonraki tam `ContainerSetContent` senkronizasyonuna kadar `NO_CLAIM` üretir.

**Önerilen çözüm (P1):**
- `ClientEntities`'de `> MAX_CANDIDATES` durumunda varlığı tamamen kaybetmek yerine en yakın iki adayı kapsayıcı yarıçapla (bounding-sphere merge) 16'nın altına indirgeyen kontrollü birleştirme (conservative envelope merge) uygulamak — böylece `AddEntity` beklemeden geçici yarıçap genişlemesiyle takip sürer ve ping onaylandığında tekrar bit-tam hâle daralır.

---

## 7. Yüksek: `LiveProbeDriver` Yem Varlığı Sabit Parmak İzli (Akıllı Hilelere Karşı Zayıf)

**Kesin konum:**
- `whynotac-paper/src/main/java/dev/whynotac/paper/ai/LiveProbeDriver.java:41, 82, 91-101`
- `whynotac-world/src/main/java/dev/whynotac/world/ClientEntities.java:158`

**Kanıt ve teknik etki:**
- `LiveProbeDriver` aktif sondaları (`ai.active-probes.enabled: true`) şu sabit kalıpla gönderiyor:
  - `EntityType.ARMOR_STAND` (satır 91, 97)
  - `phantom.setInvisible(true)` ve `phantom.setNoGravity(true)` (satır 92, 94)
  - `virtualId` her zaman `ClientEntities.VIRTUAL_DECOY_MIN_ID = 1_900_000_000`'den başlayarak ardışık artıyor (satır 41, 82)
  - Sahte `PlayerInfoUpdate` / gerçekçi ekipman veya yürüyüş paketi yok.
- Modern PvP hile istemcileri (Meteor, LiquidBounce, Vape, Grim-bypass KillAura) varsayılan ayarlarıyla **zaten `ArmorStand`'leri, görünmez varlıkları ve `entityId > 1_000_000_000` olan anormal kimlikleri hedef listesinden eler.** Bu nedenle sonda masum oyuncuya sıfır zarar verir (0 FP), fakat gelişmiş KillAura modüllerine karşı yakalama verimi düşüktür.

**Önerilen çözüm (P1):**
- Gerçek oyuncu listesinde/çarpışmasında iz bırakmayan, rastgele aralıktan seçilen sanal `entityId` (çakışma kontrollü), geçici düşük-süreli sahte oyuncu (`PLAYER`) veya görünür canlı varlık varyantlarını yalnızca görüş açısı (FOV) arkasına ve kısa TTL (`4–8` tik) ile gönderip temiz kaldırma desteği eklemek (yine kesinlikle `DESIGN_STATISTIC`, cezalandırıcı olmayan).

---

## 8. Yüksek: Çoklu Sunucu Ağı Kısıtları (`WhyNotAcVelocityPlugin` Taşıyıcı Bağımlılığı ve Merkezi Depolama Yokluğu)

**Kesin konum:**
- `whynotac-velocity/src/main/java/dev/whynotac/velocity/WhyNotAcVelocityPlugin.java:139-156`
- `whynotac-paper/src/main/java/dev/whynotac/paper/network/PaperNetworkBridge.java:101-137`
- `whynotac-paper/src/main/java/dev/whynotac/paper/cases/CaseStore.java:36-46`
- `whynotac-storage/src/main/java/dev/whynotac/storage/StorageManager.java:36-44`

**Kanıt ve teknik etki:**
1. **Boş backend kör noktası:** `WhyNotAcVelocityPlugin.shouldForwardTo` (satır 151–156) Minecraft `PluginMessage` kanalının doğası gereği `targetPlayersConnected <= 0` olan backend sunuculara çerçeve iletemez. Bir oyuncu Lobi-2'den ayrılıp Lobi-2 boşaldığında Lobi-1'den gönderilen `GDPR_FORGET`, `OVERTURN` veya `ProfileSummarySync` mesajı Lobi-2'ye **hiç ulaşmaz ve kuyruklanmaz.**
2. **Yerel disk depolama:** `cases.db` (SQLite), `storage/audit-ledger.jsonl`, `storage/reviews.jsonl`, `storage/profiles/*.json` ve `recordings/*.wnrec` her Paper sunucusunda ayrı yerel dosyalardır. 10 backend'li bir ağda yetkililer tek bir merkezi vaka/itiraz paneli veya çapraz-sunucu `FalseBanBudget` göremez.
3. **Kovaryans ağda senkronlanmıyor:** Faz 17 ile 4x4 `RunningCovarianceMatrix` artık yerel diske kaydediliyor, ancak `PaperNetworkBridge.sendProfileSummary` (satır 128–137) ve `NetworkEnvelope.ProfileSummarySync` yalnızca tekil `RunningMoments` gönderiyor; oyuncu sunucu değiştirdiğinde Mahalanobis kovaryans tabanı aktarılmıyor.

**Önerilen çözüm (P1):**
- Çoklu-sunucu dağıtımları için isteğe bağlı **Redis Pub/Sub veya NATS** kontrol kanalı (boş backend'lere de kalıcı/garantili `GDPR_FORGET` + `OVERTURN` + `FalseBanBudget` + kovaryans teslimi) ve büyük ağlar için PostgreSQL `CaseStore`/`SubjectProfileStore` arka ucu eklemek.

---

## 9. Yüksek: Ana Thread Anti-ESP Oklüzyon Tarama Maliyeti ve `SETBACK_POSITION` Konum Hassasiyeti

**Kesin konum:**
- `whynotac-paper/src/main/java/dev/whynotac/paper/verdict/PaperVerdictBridge.java:49-51, 168-219, 247-260, 335-350`

**Kanıt ve teknik etki:**
1. **Ana thread $O(K \cdot N)$ ışın taraması:** `PaperVerdictBridge.tickOcclusionVisibility()` (satır 168–194) ana sunucu thread'inde her `10` tikte bir çalışır (`WhyNotAcPlugin.java:171`) ve oklüzyonu aktif olan her $K$ gözlemci için tüm $N$ çevrimiçi oyuncu üzerinde `observer.hasLineOfSight(target)` (blok raytrace) çalıştırır (satır 203–218). 200 oyunculu bir sunucuda aynı anda 10 işaretli oyuncu varsa ana thread her yarım saniyede ~2.000 raytrace yapar.
2. **Ham 3-adımlı setback izi:** `SETBACK_POSITION` (satır 247–260), hareket motorunun (`MovementTracker`) doğruladığı son geçerli simülasyon konumunu değil, `PlayerMoveEvent` üzerindeki son `SAFE_TRAIL_SIZE = 3` (satır 49) konumun en eskisini (`earliestSafeLocation`) kullanır. Hileci 3 tik boyunca yüksek hızla havaya veya blok içine ilerlediyse setback yine havada/hatalı bir noktaya ışınlayabilir; ayrıca `onMove` (satır 335–346) `mitigationsEnabled` açıkken her oyuncunun her hareket olayında `Location.clone()` üretir.
3. **Düşük çekirdekli sunucuda yük altı kuyruk taşması:** 2 CPU çekirdekli ortamda Paper + 100 bot birlikte koşarken `queuedEvents` tepe değeri `166K` ve `lostEvents = 477K` ölçüldü (`0` FP, `0` dinleyici hatası, ancak `HealthModel = CRITICAL` ve `Gap` kaynaklı geçici körlük).

**Önerilen çözüm (P1):**
- Oklüzyon taramasında mesafe üst sınırı (`viewDistance` / `96` blok), zaman-dilimli (tick başına en fazla 2 gözlemci) tarama veya yalnızca `getNearbyEntities` ile menzil içi tarama kullanmak.
- `MovementTracker`'ın son `EXPLAINED` hipotez konumunu (veya zeminle doğrulanmış çapa konumunu) `VerdictDecision` bağlamına ekleyip setback hedefi olarak kullanmak.

---

## 10. Orta: `SecondVerifier` Kayıt Bağımlılığı, `BanWaveQueue` Kalıcılığı ve Yeniden Bağlanma Korelasyonu

**Kesin konum:**
- `whynotac-verdict/src/main/java/dev/whynotac/verdict/SecondVerifier.java:53-71, 128-133`
- `whynotac-verdict/src/main/java/dev/whynotac/verdict/BanWaveQueue.java:35-78`
- `whynotac-verdict/src/main/java/dev/whynotac/verdict/VerdictEngine.java:61-68, 143-175`

**Kanıt ve teknik etki:**
1. **`SecondVerifier` kayıt zorunluluğu:** `recording.enabled: false` yapılmışsa veya `RecordingStore` disk/kuyruk bütçesi nedeniyle o oturumun `.wnrec.part` dosyasını yazamadıysa, `SecondVerifier.verifyHandle` (satır 129–133) `NO_RECORDING` döner. `require-corroboration-for-ban: true` (varsayılan) ayarında tek-grup `P-A` bulguları asla otomatik geçici yasaklamaya ulaşamaz (güvenli tarafta kalır ama otomatik yaptırım körleşir).
2. **`BanWaveQueue` bellek içi uçuculuk:** `ban-wave-delay-seconds > 0` iken bekleyen yasaklamalar yalnızca bellek içi `ScheduledExecutorService` görevleridir (satır 66–77); sunucu gecikme süresi dolmadan yeniden başlatılırsa bekleyen ban dalgası kaybolur.
3. **Çık-gir (reconnect) korelasyon sıfırlaması:** `VerdictEngine.SessionState` (satır 61–68) `PlayerSession` ekinde tutulur. Bir hileci `min-score-for-kick = 3.0` sınırına yaklaşmadan (`2.0` puanda) çıkıp tekrar girdiğinde korelasyon puanı sıfırlanır (yalnızca `SubjectProfileStore` kümülatif sayaç tutar).

**Önerilen çözüm (P2):**
- `SecondVerifier` için son `N` saniyelik olayları tutan hafif bir bellek içi halka tamponu (in-memory ring buffer fallback) eklemek (`recording.enabled=false` iken bile deterministik yeniden oynatma mümkün olur).
- `BanWaveQueue` görevlerini `storage/ban-wave-queue.jsonl` dosyasına yazıp açılışta yeniden zamanlamak.
- Kimliği doğrulanmış (`AUTHENTICATED`) oyuncular için son 5 dakikalık korelasyon puanını UUID bazlı zaman-sönümlü kısa bir bellekte taşımak.

---

## 11. Orta: Gözlemlenebilirlik Güvenliği (`MetricsExporter`), Webhook Dayanıklılığı (`AlertStreamExporter`) ve `AuditLedger` Döndürme

**Kesin konum:**
- `whynotac-paper/src/main/java/dev/whynotac/paper/diagnostics/MetricsExporter.java:71-99`
- `whynotac-paper/src/main/java/dev/whynotac/paper/diagnostics/AlertStreamExporter.java:51-60, 143-170`
- `whynotac-storage/src/main/java/dev/whynotac/storage/AuditLedger.java:99-179`

**Kanıt ve teknik etki:**
1. **`MetricsExporter` kimlik doğrulamasız HTTP:** `127.0.0.1` loopback'e bağlı olsa da (satır 71–99, `WhyNotAcPlugin.java:244`) paylaşımlı sunucularda (container olmayan VPS'lerde) aynı makinedeki başka kullanıcılar `/metrics` ve `/health` uçlarını yetkisiz okuyabilir; uzak kazıma için TLS + Bearer token desteği yoktur.
2. **`AlertStreamExporter` webhook kuyruğu ve yeniden deneme eksikliği:** `webhookExecutor = Executors.newSingleThreadExecutor()` (satır 51) sınırsız `LinkedBlockingQueue` kullanır; hedef SIEM/Discord yavaşlarsa veya 429 dönerse görevler sınırsız birikebilir ve başarısız istekler yeniden denenmez (satır 143–170).
3. **`AuditLedger` büyümesi ve açılış doğrulama süresi:** Faz 17 ile `append` artık gerçek `FileChannel.force(true)` ile dayanıklı, `verify()` ise $O(1)$ bellekte akış okuyor; ancak aylar boyunca milyonlarca vaka açılan bir sunucuda `audit-ledger.jsonl` döndürülmediğinden (rotation + imzalı checkpoint) her sunucu açılışında tüm zincirin baştan SHA-256 + HMAC doğrulaması saniyeler/dakikalar sürebilir.

**Önerilen çözüm (P2):**
- `observability.metrics-auth-token` opsiyonel Bearer doğrulaması eklemek.
- `AlertStreamExporter` webhook havuzunu sınırlı kuyruk (`ArrayBlockingQueue(512)` + drop sayacı) ve 1–2 üstel geri çekilmeli yeniden deneme ile sertleştirmek.
- `AuditLedger` için aylık/boyut bazlı zincirli segment döndürme (`audit-ledger-YYYYMM.jsonl` + bir önceki segmentin son `headHash`'ini taşıyan yeni başlangıç kaydı) eklemek.

---

## 12. Orta: Protokol / Pipeline İnce Tutarsızlıkları

**Kesin konum:**
- `whynotac-analysis/src/main/java/dev/whynotac/analysis/checks/ClientClockCheck.java:44, 116`
- `whynotac-paper/src/main/java/dev/whynotac/paper/WhyNotAcPlugin.java:208-212`
- `whynotac-paper/src/main/java/dev/whynotac/paper/wire/WireObserver.java:61`

**Kanıt ve teknik etki:**
1. **`ClientClockCheck` belge/kod uyuşmazlığı:** Satır 44 Javadoc'u *"It holds whoever answers the transactions, so it also holds through a translator"* derken satır 116 sıkı `state.admissibility.evaluate(state.anchorSequence)` çağırır (`evaluateTranslatorSafe` değil). Eski ViaVersion istemcilerinin (`< 1.21.2`) `ClientTickEnd` sentezlemesi nedeniyle sıkı `evaluate` kullanmak aslında FP açısından **daha güvenlidir**, ancak Javadoc yanlış beklenti oluşturur.
2. **5 saniyelik pipeline yerleşim penceresi:** `interceptor.auditPlacement(player)` (satır 208–212) yalnızca `100` tikte (5 s) bir çalışır; başka bir eklenti oturum ortasında kenar dinleyicilerinin dışına handler yerleştirirse en fazla 5 saniyelik bir sapma/kayıp penceresi açılabilir.
3. **`NOT_RECONCILED_INBOUND` sabit listesi (`WireObserver.java:61`):** Şu an yalnızca `minecraft:set_creative_mode_slot` tanımlıdır; başka Paper/çatal paketleri sessiz `SkipPacketDecoderException` fırlatırsa yanlış `WIRE_ONLY` sapması sayılabilir.

**Önerilen çözüm (P2):**
- `ClientClockCheck.java:44` Javadoc'unu kodla hizalamak (`1.21.11` yerel gereksinimini açıklamak).
- `WireObserver` pipeline `userEventTriggered`/handler-added kancasıyla dışarıya handler eklendiği anda onarım tetiklemek.

---

## 13. Orta: Gerçek İstemci Meşru Davranış Test Matrisi Boşlukları

**Kesin konum:**
- `docs/LEGITIMATE-BEHAVIOUR.md` ("Sınanmayanlar" bölümü)
- `tools/realclient/run_realclient.sh`

**Kanıt ve teknik etki:**
Gerçek 1.21.11 istemciyle sınanmamış meşru davranışlar açıkça kayıt altındadır:
- `toggleSprint` + `toggleCrouch` açıkken kazma, sandık, kalkan, elytra ve binek bacaklarının tamamı (şimdiye dek yalnızca yürüyüş/rastgele yürüyüş kısmı koşuldu).
- Gerçek `240+ FPS` ve `VSync` altında tek tikte biriken çoklu fare kareleri (şu an yalnızca sentetik birim testi var; Xvfb yazılım Mesa düşük FPS verir).
- `rawMouseInput` (Xvfb altında etkinliği doğrulanmadı) ve ayrık fare tekeri kaydırması.
- Gerçek **Windows** ve **macOS (Apple Silicon)** JVM/donanım koşuları (`PlatformCos.X86_64`/`AARCH64` QEMU Linux altında doğrulandı, gerçek masaüstü OS koşusu yapılmadı).
- `10+ s` zaman aşımına yakın donmalar ve **koşarak (eğilmeden, zıplayarak) köprü kurma**. (`> 600 ms` RTT 2026-11-24'te `REALCLIENT_LATENCY="320 40"` ile iki koşuda sınandı: 0 vaka, 0 açıklanamayan tik; `docs/LEGITIMATE-BEHAVIOUR.md`.)

**Önerilen çözüm (P2):**
- `tools/realclient` sürücüsüne `toggleSprint`/`toggleCrouch` uyumlu tam tur profili, `>600 ms` `tc netem` gecikme profili ve GitHub Actions `macos-latest` / `windows-latest` isteğe bağlı doğrulama işi eklemek.

---

## 14. Orta: Tedarik Zinciri (`REL-02`), Otomatik Mutasyon Testi (`pitest`) ve Çok-Sürümlü CI Boşlukları

**Kesin konum:**
- `build-logic/src/main/kotlin/whynotac.java-conventions.gradle.kts:34-48`
- `whynotac-paper/build.gradle.kts:52-120`
- `.github/workflows/build.yml`

**Kanıt ve teknik etki:**
1. **`REL-02` Gradle Dependency Verification:** Tüm snapshot bağımlılıkları zaman damgasıyla sabitlenmiş ve `whynotac-paper:generateSbom` CycloneDX 1.5 SBOM (SHA-256) üretiyor olsa da, Gradle'ın yerleşik `gradle/verification-metadata.xml` (tüm geçişli Maven bağımlılıklarının derleme anında SHA-256/PGP zorunlu doğrulaması) Paper `paperweight-userdev` dinamik remap artefaktları nedeniyle henüz etkin değil.
2. **Otomatik bytecode mutasyon skoru yok:** `MutationResilienceTest` (`whynotac-verdict`) ve `AnalysisMutationResilienceTest` (`whynotac-analysis`) kritik sınırları korur; ancak `whynotac-math`, `whynotac-physics` ve `whynotac-analysis` için PIT (`pitest`) ile otomatikleştirilmiş mutasyon öldürme oranı (`mutation score`) raporu yok.
3. **CI'da çok-sürümlü / Bedrock E2E yok:** Mevcut 5 CI işi (`build`, `e2e plain`, `e2e interop`, `e2e proxy + armed cheat`, `real client ground truth`) 1.21.11 protokolüyle mükemmel kapsama sahiptir, ancak ViaVersion arkasından bağlanan gerçek/sondalı `1.20.4` veya `1.8.9` istemcisiyle `0 FP` ve `evaluateTranslatorSafe` doğrulayan bir CI işi yoktur.

**Önerilen çözüm (P2):**
- `whynotac-math` ve `whynotac-verdict` için gece/PR PIT mutasyon testi görevi eklemek.
- `tools/e2e/run_interop_e2e.sh` içine `1.20.4` protokol sondası senaryosu eklemek.
- `paperweight` harici bağımlılıklar için `gradle/verification-metadata.xml` oluşturup yerel doğrulama kilidini kapatmak.

---

## 15. Düşük: Yapılandırma, Depolama I/O ve Yardımcı Kod Detayları

**Kesin konum:**
- `whynotac-paper/src/main/java/dev/whynotac/paper/WhyNotAcPlugin.java:97, 384-406`
- `whynotac-paper/src/main/java/dev/whynotac/paper/cases/CaseStore.java:148-171`
- `whynotac-paper/src/main/java/dev/whynotac/paper/verdict/PaperVerdictBridge.java:274, 294`
- `whynotac-storage/src/main/java/dev/whynotac/storage/SubjectProfileStore.java:260-287`
- `whynotac-storage/src/main/java/dev/whynotac/storage/ReviewWorkflow.java:257-320`

**Kanıt ve teknik etki:**
1. **Sabit analiz thread havuzu (`WhyNotAcPlugin.java:97`):** `int threads = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);` yapılandırmadan değiştirilemez; yüksek çekirdekli veya cgroup kısıtlı sunucularda operatör ince ayarı yapılamaz.
2. **Kısmi `/whynotac reload` (`WhyNotAcPlugin.java:384-406`):** Canlı yeniden yükleme yalnızca `verdict.*` ayarlarını günceller; `observability.*`, `recording.*`, `ai.active-probes.*` ve `network-bridge.*` değişiklikleri sunucu yeniden başlatması gerektirir.
3. **SQLite `CaseStore` WAL / batch yok (`CaseStore.java:148-171`):** Her vaka ayrı auto-commit `INSERT` yapar; `PRAGMA journal_mode=WAL; PRAGMA synchronous=NORMAL;` etkinleştirilmediği için çok yoğun vaka patlamasında disk IOPS yükselir (`MAX_QUEUED = 10_000` analiz thread'ini korur ancak `casesDropped` artabilir).
4. **Regex tabanlı JSON ayrıştırma (`SubjectProfileStore.java:260-287`, `ReviewWorkflow.java:257-320`):** Dış bağımlılık eklememek için regex ayrıştırıcı kullanılıyor; kendi ürettiğimiz JSON'larda sağlam olsa da el ile yeniden biçimlendirilmiş veya farklı alan sıralı dosyalarda kırılgan olabilir.
5. **Defansif alt-dizi kesimi (`PaperVerdictBridge.java:274, 294`):** `certificateId().substring(0, 8)` ve `sha256Digest().substring(0, 12)` üretimde geçerlidir, ancak özel test/sahte sertifika verisinde `Math.min` koruması daha güvenlidir.

---

## 16. Stratejik: Hukuk, Lisanslama ve Sürüm Yaşam Döngüsü

**Kesin konum:**
- `docs/DECISIONS.md`
- Depo kökü (henüz `LICENSE` dosyası yok)

**Kanıt ve teknik etki:**
- **Lisans & hukuk görüşü:** Özel depo olduğu için şimdilik engel teşkil etmez; ancak ticari/kapalı-kaynak satış veya açık-kaynak yayım öncesinde decompile edilmiş istemci davranışının temiz-oda (clean-room) yeniden uygulaması için hukuki inceleme ve açık bir `LICENSE` dosyası zorunludur.
- **Minecraft `26.x` geçişi:** `1.21.11` Aralık 2025 sürümüdür; yeni büyük sunucu dalgaları Mojang'ın obfuscation'ı kaldırdığı `26.x` hattına geçtiğinde tek-sürüm `774` hedefi ticari pazar payını daraltır (`docs/UPGRADE-GUIDE-PROD14.md` bu geçişin adımlarını şimdiden hazırlar).

---

## Önceliklendirilmiş Eylem Planı (Sonraki Fazlar İçin)

| Aşama | Hedef Maddeler | Beklenen Kazanım |
|---|---|---|
| **Hemen (P0)** | #2 Gerçek açık-kaynak hile istemcisi (Meteor/Wurst/LiquidBounce) izole VM kayıtları | Gerçek dünya TPR/TTD ölçümü ve gizli baypasların keşfi |
| **Kısa Vade (P1)** | #7 `LiveProbeDriver` gerçekçi sonda · #9 Ana thread oklüzyon bütçeleme + simülasyon setback · #6 `ClientEntities` zarf birleştirme · #5 Bağlı çit/merdiven/kapı blok tahminleri | Canlı PvP/SMP'de daha yüksek tespit kapsama oranı ve daha düşük ana-thread maliyeti |
| **Orta Vade (P1–P2)** | #1 `26.x` + ikinci protokol desteği · #8 Redis/PostgreSQL çoklu-sunucu katmanı · #10 `SecondVerifier` bellek tamponu + kalıcı `BanWaveQueue` · #11 `MetricsExporter` Bearer auth + `AuditLedger` döndürme | Büyük ağlarda tam çok-sürümlü, kesintisiz ve yüksek erişilebilirlikli kurulum |
| **Sürekli (P2–P3)** | #3 Ghost baypas eşik daraltma · #4 `Wind Charge`/`Mace`/binek fiziği · #13 Windows/macOS/240 Hz gerçek istemci testleri · #14 `pitest` + `REL-02` · #15 SQLite WAL + thread yapılandırması · #16 Lisans/hukuk | Sıfır FP çizgisini bozmadan ürünleşme ve uzun vadeli bakım olgunluğu |
