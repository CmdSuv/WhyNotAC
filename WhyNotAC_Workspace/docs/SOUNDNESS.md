# Kontrollerin Soundness Notları (`POLICY.md` § Yaşam Döngüsü)

Bu belge, `whynotac-analysis` (ve `whynotac-ai` tasarım deneyleri) içindeki her kontrolün **`docs/POLICY.md`** kapsamındaki normatif **soundness** sözleşmesini belgeler:

- **Matematiksel / Protokol Dayanağı:** Decompile edilmiş Minecraft **1.21.11** (protokol 774) istemci kodu üzerinden sabitlenen kural.
- **Zorunlu Varsayımlar:** Kanıt geçerliği için sağlanması gereken ortam/gözlem koşulları.
- **Bilgi Sınırı ve Dışlanan Durumlar (`NO_CLAIM`):** Replikaların sınırlayamadığı hâllerde yanlış-pozitif üretmemek için iddianın çekildiği sınırlar.
- **Atlatma / Geri Çekilme Sınırı:** Bilinen kaçınma pencereleri ve bunlara karşı etkisizleştirme / ikincil kontrol kapsamı.

> **Kabul edilebilirlik önkoşulu (`AdmissibilityTracker`):** `attack-reach`, `attack-aim`, `use-aim`, `break-time`, `prediction-sequence`, `action-grammar`, `movement`, `tick-framing`, `transaction-order`, `click-pattern`, `aim-pattern` ve `design-probe` yalnızca kenarda (`WIRE`/`WIRE`), yerel 1.21.11 (`NATIVE_PROTOCOL=774`), çeviricisiz (`TRANSLATOR=none`), `TRANSACTION_ORIGIN=CLIENT`, Bedrock dışı (`!GEYSER_LOCAL`) ve pencere içinde `Gap` / `Untranslatable` olmayan aralıklarda kanıt üretir.
> (`client-clock` ek olarak `ServerClock` ile sunucu monoton saatinin duvar saatinden sapmasının `<= %0,5` olmasını şart koşar ve `Ping`/`Pong` nedenselliği sayesinde çeviri altında da çalışır.)

---

## 1. Hızlı Özet Matrisi

| Kontrol ID | Sınıf | Bağımlılık Grubu | Bilgi Sınırı / Pay | Etkisizleştirme (`NEUTRALIZE`) | Derin Teknik Ayrıntı |
|---|---|---|---|---|---|
| `attack-reach` | `P-A` | `ATTACK_REACH` | `EPSILON = 1e-4` blok, tüm göz & hedef kutu kümesi | `CANCEL_IMPOSSIBLE_HIT` | [Aşağıda §2.1](#21-attack-reach-p-a) |
| `attack-aim` | `P-M` | `RAYCAST_AIM` | Tikin tüm gözlerinden tüm olası kutulara ışın & kesin blok | `CANCEL_IMPOSSIBLE_HIT` | [Aşağıda §2.2](#22-attack-aim-p-m) |
| `use-aim` | `P-M` | `RAYCAST_AIM` | Tikin bakışı (`UseItem` bit-tam), `BlockClick` imleç/yüz/engel | `CANCEL_IMPOSSIBLE_BLOCK_ACTION` | [Aşağıda §2.3](#23-use-aim-p-m) |
| `break-time` | `P-M` | `BLOCK_TIMING` | Float toplam `1.0F` sınırı (`0` tolerans; en büyük çarpanlarla) | `CANCEL_IMPOSSIBLE_BLOCK_ACTION` | [Aşağıda §2.4](#24-break-time-p-m) |
| `tick-framing` | `P-M` | `CLOCK_AND_FRAMING` | Tik başına 1 hareket + eşlenmiş teleport/dönüş/araç yanıt token'ı | `NONE` (otomatik değil; opt-in `KICK`) | [Aşağıda §2.5](#25-tick-framing-p-m) |
| `client-clock` | `P-M` | `CLOCK_AND_FRAMING` | `SLACK_TICKS = 22.0`, `DRIFT = %1` (`ServerClock <= %0,5`) | `NONE` (opt-in `KICK`) | [Aşağıda §2.6](#26-client-clock-p-m) |
| `transaction-order` | `P-M` | `CLOCK_AND_FRAMING` | `MAX_TICKS_AFTER_KEEPALIVE = 12`; tek TCP akışı + `PacketProcessor` sırası | `NONE` (opt-in `KICK`) | [Aşağıda §2.7](#27-transaction-order-p-m) |
| `action-grammar` | `P-M` | `PACKET_GRAMMAR` | `handleKeybinds` -> `tickEntities`, saldırı/STAB sonrası `Swing`, açık menü | `NONE` (opt-in `KICK`) | [Aşağıda §2.8](#28-action-grammar-p-m) |
| `prediction-sequence` | `P-M` | `PACKET_GRAMMAR` | `BlockStatePredictionHandler` seviye aralığı `[levelsLow, levelsHigh]` | `NONE` (opt-in `KICK`) | [Aşağıda §2.9](#29-prediction-sequence-p-m) |
| `packet-bounds` | `P-M` | `PACKET_GRAMMAR` | Sonlu `MovePlayer`/`MoveVehicle`, hotbar `0..8`, `entityId != selfId`, blok imleci `[-1, 2]`, `ContainerClick` yuva/SWAP | `NONE` (opt-in `KICK`) | [Aşağıda §2.9b](#29b-packet-bounds-p-m) |
| `movement` | `P-M` | `MOVEMENT_PHYSICS` | Bit-tam 1.21.11 çarpışma + hipotez kümesi (`≤ 64`), x86/ARM `Math.cos` | `SETBACK_POSITION` | [Aşağıda §2.10](#210-movement-p-m) |
| `click-pattern` | `S` | `STATISTICAL_SIGNALS` | 40 tik CPS eşiği (`50`/`80`), 60 aralıklı `stdDev ≤ 0,14` / periyot-2,3 | Yasak (`NONE`) | [Aşağıda §2.11](#211-click-pattern-s) |
| `aim-pattern` | `S` | `STATISTICAL_SIGNALS` | `25°` sıçrama / `0,015°` `stdDev` / düşük entropi (`≤ 1,05 bit`) / kafes bozulması | Yasak (`NONE`) | [Aşağıda §2.12](#212-aim-pattern-s) |
| `container-timing` | `S` | `STATISTICAL_SIGNALS` | `10` tikte ≥ `10` ayrı sandık yuvası / `14` tıklamada `stdDev ≤ 0,10` | Yasak (`NONE`) | [Aşağıda §2.13](#213-container-timing-s) |
| `scaffold-pattern` | `S` | `STATISTICAL_SIGNALS` | `22°` yerleştir-geri-dön (`place-and-snapback`) / `12` blokta `stdDev ≤ 0,10` köprü ritmi | Yasak (`NONE`) | [Aşağıda §2.13b](#213b-scaffold-pattern-s--asla-cezalandırmaz) |
| `design-probe` | `D` | `STATISTICAL_SIGNALS` | Ville eşitsizliği (`e-process ≥ 1 / alpha`) rastgele yem sonda deneyi | Yasak (`NONE`; `HIDE_INFORMATION` izinli) | [Aşağıda §2.14](#214-design-probe-d) |

---

## 2. Kontrol Bazlı Soundness Sözleşmeleri

### 2.1 `attack-reach` (`P-A`)
- **Dayanak:** Decompile 1.21.11 `GameRenderer.pick` + `Minecraft.startAttack` (`AttackReach.java`, `AttackReachCheck.java`). Vuruş seçimi (tikin başındaki el eşyası) ve vuruş kontrolü (tikin hotbar tuşlarından sonraki eli) ayrı ayrı değerlendirilir; tüm `(pick, check)` çiftlerinin izin verdiği maksimum erişim (`entity_interaction_range` veya `attack_range` bileşeni) ile göz zarfı-hedef kutu mesafesi kıyaslanır.
- **Bilgi Sınırı / Pay:** Karşılaştırma payı `EPSILON = 1.0E-4` blok (`double` yuvarlama farkı). Pay (`VAL-4`): `allowed - distance`.
- **Varsayımlar:** İstemci `1.21.11` saldırı seçim/doğrulama kurallarını uygular; oyuncu ve hedef son bilinen veya sınırlanabilen pozisyon/boyut penceresindedir.
- **Dışlanan Durumlar (`NO_CLAIM`):** Kendine vuruş; creative veya spectator olasılığı (`mayBeCreativeOrSpectator`); araç/başka kamera/bilinmeyen göz (`eye == null`); elde bilinmeyen menzil/eşya (`Reach.UNKNOWN`); hedef yok/bilinmiyor/seçilebilir değil (`pickable == 0`); son konumdan sonra piston/shulker kutusu itmesi (`MOVER_BLOCKS_MARGIN = 2`) veya yakında shulker varlığı (`SHULKER_RADIUS = 12.0`).
- **Atlatma / Sınır:** Kısa süreli hız/blink saklamasında bile tüm olası hedef ve göz penceresinin dışına çıkan ilk vuruşta yakalar (`SecondVerifier` ile tekrar doğrulanır ve `ARMED` modda `CANCEL_IMPOSSIBLE_HIT` uygular).

### 2.2 `attack-aim` (`P-M`)
- **Dayanak:** Decompile 1.21.11 `GameRenderer.pick` + `ProjectileUtil.getEntityHitResult` (`AttackAim.java`, `AttackAimCheck.java`). Vuruşun tikteki bakış (`TickLook`) ışını, vuruşun sahip olabileceği hiçbir poz gözünden hedefin hiçbir kutu durumuna değmiyorsa (`off-aim`) ya da araya kesin durduran tam/kısmi outline bloku giriyorsa (`through-blocks`) ihlal üretilir.
- **Bilgi Sınırı:** `attack_range` eşyalarında `yRotLast` (kafa yaw'ı) olasılığı ayrı ayrı sınanır; duvar engeli yalnızca `certainOutlineHit` ile kesin durduran bloklarda (ve saldırı sonrası `acknowledgements` değişikliklerinden bağımsız) ileri sürülür.
- **Dışlanan Durumlar:** `AttackReachCheck` kapsam çekilme nedenlerinin tamamı (`eye`, `target`, `creative/spectator`, `reach unknown`, piston/shulker itmesi), ayrıca bakışın henüz kesinleşmediği teleport/binek geçiş tikleri (`TickLook`).
- **Atlatma / Sınır:** Tikin sonuna kadar bakışı geciktiren silent-aim'ler `ClientTickEnd`'de çözülür; birden fazla varlığa tek bakışla vuran KillAura anında elenir.

### 2.3 `use-aim` (`P-M`)
- **Dayanak:** `MultiPlayerGameMode.useItem`, `useItemOn`, `startDestroyBlock`, `continueDestroyBlock` (`UseAimCheck.java`, `BlockClick.java`). `UseItem` dönüşü tikin `TickLook` dönüşüyle bit bit eşleşmelidir; `UseItemOn` ve `START`/`STOP_DESTROY_BLOCK` ise blok outline'ı, girilen yüz, imleç koordinatı ve engelleyici bloklarla uyumlu olmalıdır.
- **Dışlanan Durumlar:** `TickLook` bilinmediğinde (binek sürüşü veya aktif teleport/rotasyon penceresi), elde `attack_range` eşyası varken blok tıklamaları, dünya sınırı çarpışmaları, spectator modu ve `AttackReachCheck` itme istisnaları (`mayHaveMoved`). `ABORT_DESTROY_BLOCK` kazılan bloğu taşıdığı için yargılanmaz.
- **Atlatma / Sınır:** Scaffold / air-place / yanlış yüz yerleştirme (`face` / `cursor` / `blocks`) ihlalleri `ARMED` modda `CANCEL_IMPOSSIBLE_BLOCK_ACTION` ile iptal edilir.

### 2.4 `break-time` (`P-M`)
- **Dayanak:** Decompile 1.21.11 `MultiPlayerGameMode` (`BreakTimeCheck.java`, `Digging.java`). Başlatılan kırmanın bitişi (`STOP_DESTROY_BLOCK`), her tikteki tüm çarpanların (haste/conduit/mining fatigue efektleri, `block_break_speed`, su altı göz kontrolü, `certainlyOffGround` -> `÷ 5`, client tag'leri `ClientTags`) olası **en büyük** değerleriyle hesaplanan IEEE-754 `float` toplamı `1.0F`'a ulaşmadan gönderilemez.
- **Bilgi Sınırı / Pay:** `0` ek tolerans (`maxProgress - 1.0F`); IEEE-754 float toplamının monotonluk özelliği kesindir.
- **Dışlanan Durumlar:** Blok durumu bilinmeyen/oturmamış chunk, üzerinde önceki blok tahmini veya devam eden başka blok eylemi bulunması, yama yapılmış/bilinmeyen alet eşyası, kazı hızı efekti penceresinin belirsizliği, creative/spectator modu.
- **Atlatma / Sınır:** Bir tik bile erken kırma yakalanır; ancak hileci her tick kazı durdurup başlatarak (no-slow break değil, yarım bırakma) anlık kırılmayan blokta iptal ederse ilerleme kesilmez ama kırma tamamlanmaz.

### 2.5 `tick-framing` (`P-M`)
- **Dayanak:** `LocalPlayer.sendPosition` / `LocalPlayer.tick` ve `Minecraft.tick` (`TickFramingCheck.java`, `MoveReplies.java`). İki `ClientTickEnd` arasında vanilla istemci en fazla **1** normal hareket paketi gönderebilir; ayrıca eşleşen `AcceptTeleportation(id)` sonrası ilk `PosRot` cevabı, beklemedeki sunucu `PlayerRotation` token'ı kadar `Rot`-only yanıt ve varlık ışınlanma (`entityTeleport`) token'ı kadar `PosRot` yanıtı muaf tutulur.
- **Bilgi Sınırı:** `MAX_TOKENS = MoveReplies.MAX_TOKENS`; yanıtların `onGround`/`horizontalCollision` bayraklarına bakılmaz (gereksiz yanlış-pozitifi önlemek için yalnızca şekil + ID eşlenir). Paket varışında yargılanır; dolayısıyla istemcinin `ClientTickEnd` göndermeyi bırakması kaçış sağlamaz.
- **Dışlanan Durumlar:** İlk `ClientTickEnd` görülmeden önceki bağlantı başlangıcı; `MoveReplies.saturated()` taşma durumu; kabul edilemez pencere (`Gap`/çevirici/kaybedilmiş olay).
- **Atlatma / Sınır:** Ekstra hareket paketini ekstra `ClientTickEnd` içine gizlemeye çalışan client'ları doğrudan `client-clock` yakalar.

### 2.6 `client-clock` (`P-M`)
- **Dayanak:** `Minecraft.runTick(true)` + `DeltaTracker.advanceTime` + `PacketProcessor.processQueuedPackets` (`ClientClockCheck.java`). İstemci saat sıçraması/donma sonrasında kare başına en fazla `min(10, m)` tick koşup fazlasını atar; bir frame içindeki ping drenajı dikkate alındığında iki transaction yanıtı (`i` ve `j`) arasındaki `ClientTickEnd` sayısı sunucu saati üzerinde $(r_j - s_i) \cdot (1 + 0.01) / 50\text{ ms} + 22$ sınırını aşamaz.
- **Bilgi Sınırı:** `SLACK_TICKS = 22.0`, `DRIFT = 0.01` (%1 — normal kuvars saat hatasının ~100 katı).
- **Varsayımlar:** İstemcinin monoton saati gerçek zamandan %1 içinde ve sunucu saati `ServerClock` duvar saatinden %0,5 içinde çalışır (`VAL-3`).
- **Dışlanan Durumlar:** `ServerClock` %0,5 kayma sınırını aştığında aday reddedilir (`clock.trusted() == false`).
- **Atlatma / Sınır:** `%1` altı mikro-timer (`1.005x`) kasten %1 kuvars payının altında kalır (algoritmik sınır). `+5%` (`~30 sn`) ve `+50%` (`~1,5 sn`) timer deterministik yakalanır; ağ gecikmesi/donma sıçramaları 22 tick payı nedeniyle sıfır yanlış pozitif üretir.

### 2.7 `transaction-order` (`P-M`)
- **Dayanak:** Tek sıralı TCP akışı + `PacketProcessor.processQueuedPackets()` (`TransactionOrderCheck.java`).
  1. `ping-after-later-teleport-accept`: Aynı ana iş parçacığı drenajında işlenen önceki `Ping` yanıtı, kendisinden sonra gönderilen bir `PlayerPosition` için dönen `AcceptTeleportation` paketinden sonraya kalamaz.
  2. `ping-delayed-past-keepalive`: Ağ iş parçacığında işlenen `KeepAlive` yanıtı döndüğünde aynı TCP akışında önündeki `Ping` zaten sokete girmiştir; aktif karedeki en fazla 10 yetişme tick'i + 2 yazma-sırası payı (`MAX_TICKS_AFTER_KEEPALIVE = 12`) içinde `Pong` dönmelidir.
- **Dışlanan Durumlar:** `PlayerLoaded` öncesi, `Login`/`Respawn` sıfırlamaları, `Pong`'u tamamen düşüren ama hiç cevaplamayan durumda `SKIPPED_TRANSACTION` (bu durum `SessionRegistry` anomalisi olarak izlenir; sahte gecikme cezası kesilmez).
- **Atlatma / Sınır:** Anti-KB/blink için seçici olarak `Pong` tutarken `KeepAlive` veya yeni `AcceptTeleportation` geçiren istemcileri 12 tick içinde yakalar.

### 2.8 `action-grammar` (`P-M`)
- **Dayanak:** `Minecraft.handleKeybinds` -> `level.tickEntities()` -> `ClientTickEnd` sırası (`ActionGrammarCheck.java`, `MenuScreen.java`).
  1. Spectator değilken her `ATTACK` ve her `STAB` sonrasında (ağ iş parçacığının `KeepAlive`/`ChunkBatchReceived` cevapları hariç) gelen ilk paket `Swing(MAIN_HAND)` olmalıdır.
  2. Tikin kendi hareket paketinden sonra (`MoveReplies` yanıtları hariç) aynı tik içinde saldırı/etkileşim/kullanım/kazı/bırakma/hotbar eylemi gelemez.
  3. Sunucunun kesin açtığı bir menü (`MenuScreen`) ekrandayken eylem veya yatay yürüme tuşu (`W/A/S/D`) gönderilemez.
- **Dışlanan Durumlar:** Spectator saldırısında el sallamama istisnası; sunucuya `toggleSprint`/`toggleCrouch`/auto-jump tuşlarının (`jump`, `sneak`, `sprint`) ekran altında tutulması.

### 2.9 `prediction-sequence` (`P-M`)
- **Dayanak:** `MultiPlayerGameMode.startPrediction` + `BlockStatePredictionHandler.currentSequenceNr++` (`PredictionSequenceCheck.java`). Tahminlenen paketler (`UseItemOn`, `UseItem`, `START_DESTROY_BLOCK`, `STOP_DESTROY_BLOCK`) sıradaki numarayı (veya yeni dünya oluşmuşsa `1` sayısını) taşır; tahminlenmeyen eylemler (`ABORT_DESTROY_BLOCK`, bırakma, el değiştirme, STAB) `0` taşır.
- **Dışlanan Durumlar:** İlk paket öncesi, `Gap` / kayıp olay veya `UNKNOWN` eylem görüldüğünde seviye takibi sıfırlanır.

### 2.9b `packet-bounds` (`P-M`)
- **Dayanak:** Decompile 1.21.11 `ServerboundMovePlayerPacket`, `Inventory.isHotbarSlot`, `ProjectileUtil.getEntityHitResult`, `ServerboundUseItemOnPacket` ve `ContainerClick` kuralları (`PacketBoundsCheck.java`).
  1. `non-finite-movement`: `MovePlayer` veya `MoveVehicle` içinde `NaN` / `±Infinity` koordinat veya açı.
  2. `invalid-hotbar-slot`: `SetCarriedItem` yuvasının `0..8` dışına çıkması.
  3. `self-interaction`: `InteractEntity` paketinin oyuncunun kendi `selfId` varlık kimliğini hedeflemesi.
  4. `invalid-block-cursor`: `UseItemOn` vuruş noktasının sonlu olmaması veya blok imleç ofsetinin `[-1.0001, 2.0001]` zarfı dışına çıkması.
  5. `invalid-container-slot` ve `invalid-container-swap`: `ContainerClick` yuva indeksinin `< -1 && != -999` olması veya `SWAP` tıklamasında `button` alanının `0..8` ve `40` dışına çıkması.
- **Dışlanan Durumlar:** `Login` öncesi (`selfId < 0`) ve kabul edilemez pencereler (`Gap`/çevirici).

### 2.9c `velocity-response` (`P-A`, v2)
- **Dayanak:** `SetEntityMotion(vy >= 0.12)` ve `Explosion(playerKnockback.y >= 0.20)` sonrası ping onayı alındığında yerde duran, uçmayan/ölü olmayan, özel yerçekimi/ölçek/levitasyon/yavaş-düşme etkisi taşımayan ve tam `0.6x0.6` hitbox ayak izinin üstü (`dy = 0..2`) oturmuş `AIR` (çözülmemiş `UseItem` yok) olan survival/adventure oyuncunun dikey yükselişini sıfırlayan (`ignored-vertical-knockback`) veya `%65` eşiğinin altında (`onGround=false` taklidiyle dahi) kısmi bastıran (`partial-vertical-knockback`) ve `3x3x3` açık havada yakında itici yokken yatay itkiyi (`< 0.08` izdüşüm) bastıran (`ignored-horizontal-knockback`) istemciler (`VelocityCheck.java` v2).
- **Dışlanan Durumlar:** Onay öncesi kenardan adım atıp düşme (`y < baseY`), hitbox kenarına değen tavan/blok, yaratıcı/izleyici/binek/uçuş/ölüm/elytra, varsayılan dışı `minecraft:gravity` / `minecraft:scale`, çakışan/aşağı yönlü patlama ve `Gap`/çevirili pencere.

### 2.9d `inventory-protocol` (`P-M`)
- **Dayanak:** Vanilla `Minecraft.tick()` içinde aynı client tick içerisinde ekran kapatma (`ContainerClose`) olmadan bir `ContainerClick` ardından `InteractEntity`/`UseItem`/`UseItemOn`/blok kırma/koşma başlatma (`action-during-container-click`), yatay hareket tuşu basımı (`walk-during-container-click`, InvWalk/AutoTotem) veya açık bir `OpenScreen`/`MountScreenOpen` olmadan `containerId > 0` üzerine tıklama (`unopened-container-click`) (`InventoryActionCheck.java`).

### 2.9e `interact-at-box` (`P-M`)
- **Dayanak:** `InteractEntity(INTERACT_AT)` yerel hedef sapmasının, hedef varlığın `EntitySizes` olası boyut zarfının dışına çıkması (`non-finite-interact-at`, `out-of-bounds-interact-at`) (`InteractAtBoxCheck.java`).

### 2.9f `use-item-state` (`P-M`)
- **Dayanak:** `handleKeybinds()` ve `sendPosition()` tik-içi gönderim sırası ile `LocalPlayer.isUsingItem()` tikler-arası kalkan/dürbün durumu (`UseSlowdownCheck.java` v2). Aynı tikte `RELEASE_USE_ITEM` sonrasında saldırı/kullanım veya `START_SPRINTING`/`STOP_SPRINTING` sonrasında kullanım/bırakma (`release-then-act`, `sprint-then-use`); ayrıca doğrulanmış `minecraft:shield` veya `minecraft:spyglass` `UseItem` kullanımı aktifken bırakma (`RELEASE_USE_ITEM`), el değiştirme, envanter/atış eylemi, bekleme süresi (`Cooldown`), sunucu veri/can/ışınlanma/dünya değişikliği olmadan sonraki tiklerde saldırı/kazı/kullanım veya koşma başlatma (`attack-while-using`, `use-while-using`, `sprint-while-using`) ihlalleri üretir (AutoBlock `KeepUsing` / NoSlow).
- **Dışlanan Durumlar:** Elde eşya kesin değilken (`mayBeNone` veya farklı `ClientInventory.View` durumları), tüketilebilir/rastgele bitebilen eşyalar (yiyecek, iksir, yay, üçlü mızrak), bekleme süresi penceresi, creative/spectator ihtimali ve binek/teleport pencereleri iddiadan çekilir.

### 2.9g `timer-balance` ve `ping-spoof` (`S` — Asla Cezalandırmaz)
- **`timer-balance` (`TimerBalanceCheck.java`):** En az 4 döngü boyunca yavaşlatma (`<= 15` TPS) ardından hızlı boşaltma (`>= 24.5` TPS) yapan osilasyonlu bakiye zamanlayıcı (`oscillating-timer-balance`).
- **`ping-spoof` (`PingSpoofCheck.java`):** `KeepAlive` ağı RTT'sine kıyasla `>= 10` ardışık aktif transaction'da `+180 ms` üzeri gecikmeli `Ping` şişirmesi (`sustained-ping-rtt-inflation`, Backtrack) veya yüksek şişirme sonrasında saldırı (`<= 3` tik) anında `>= 120 ms` ani düşüş (`combat-rtt-drop`). İkisi de `AdmissibilityTracker.evaluateTranslatorSafe` kullanır ve asla otomatik atmaz/yasaklamaz.

### 2.10 `movement` (`P-M`)
- **Dayanak:** Decompile 1.21.11 `OnFoot` + `Colliders` + araç motorları (`Boat`, `Horse`, `ItemSteering`) + `Border` + dünya tahminleri (`whynotac-physics`, `MovementCheck.java`, `MovementTracker.java`, [whynotac-analysis README § movement](../whynotac-analysis/README.md#movement-movementcheck-movementmovementtracker)). Oyuncunun olası durum kümesi (maks. `64` hipotez) `x86_64` ve `AARCH64` JVM `Math.cos` tablo/intrinsic farklılıkları dâhil bit bit ilerletilir; kayıp sonrası durağan çapa (`settleAnchor`) veya düz/zıplamalı koşu çapası (`runAnchor`) ile yeniden kurulur. Tek-durumlu tam küpler, sütunlar (`_log` vb.), kuru yarım bloklar (`_slab`) ve aynı tür kuru çift-basamak birleştirme (`type=double`), düz kuru merdivenler (`_stairs`), 4-yönlü etkileşimsiz tam küpler (`*_glazed_terracotta`, `carved_pumpkin`, `jack_o_lantern`), açık havada kuru çit/duvarlar (`_fence`, `_wall`), blok değiştirmeyen `UseItem` temizlemeleri (`resolveItemUseNoChange`) ve deterministik `water_bucket` / `lava_bucket` / ayrık kaynak `bucket` ışın tahminleri (`bucketPlacement`) izlenmeye devam eder.
- **Ek Alt Kurallar:** Sürücülük olmadan veya kontrolsüz/eyersiz araçta sürücü paketi (`MoveVehicle`/`PaddleBoat`/binek zıplaması), uçamayan durumda `PlayerAbilities`, eşya kullanırken saldırı/yeni kullanım, `PlayerInput` / koşma bayrağı çelişkileri.
- **Dışlanan Durumlar:** Piston/shulker itmeleri, çevresi çarpışabilir varlık yakınlığı (`<= 1,5` blok), `can_float_while_ridden`/etiket belirsizliği (`ClientTags`), çözülmeyen karmaşık blok kullanımı, hipotez sınırı (`> 64`) taşması, `Gap` / sunucu saati veya replika bilinmezliği.
- **Atlatma / Sınır:** Yanlış-pozitif vermemek için iddiadan çekilme alanları geniştir; bu yüzden `ARMED` modda `SETBACK_POSITION` ile birleşik ve `tick-framing`/`client-clock` ile birlikte işletilmelidir.

### 2.11 `click-pattern` (`S` — Asla Cezalandırmaz)
- **Dayanak:** `ClientTickEnd` çerçevelemesiyle TCP birleşmesinden bağımsız tıklama aralığı istatistiği (`ClickPatternCheck.java`, `RunningMoments`).
- **Sinyaller ve Eşikler:**
  - `sustained-cps`: `40` tikte `>= 80` savurma (`40+ CPS`) veya `>= 3` saldırılı `>= 50` savurma (`25+ CPS`).
  - `constant-interval`: `60` ardışık hızlı aralıkta (`mean <= 3.2` tik) `sampleStdDev <= 0.14` (bimodal butterfly için `kurtosis <= -1.4` dışlanır).
  - `macro-periodicity`: `60` aralıkta en az `56/58` periyot-2 veya `55/57` periyot-3 kilitlenmesi.
- **Dışlanan Durumlar:** Kazı ticks (`digging`), `UseItem`/`UseItemOn` ve mızrak dürtmesi (`STAB`) dışlanır. `ActionPolicy` gereği `NEUTRALIZE`, `KICK`, `TEMP_BAN` her koşulda **yasaktır**.

### 2.12 `aim-pattern` (`S` — Asla Cezalandırmaz)
- **Dayanak:** `TickLook` istemci dönüşleri + `MouseTurnModel` ayrık fare kafesi (`AimPatternCheck.java` v2).
- **Meşru Muafiyetler (Önceden Dışlanır):** Elde dürbün (`spyglass`), büyük `|yaw|` ULP çözünürlük kaybı (`ulp > 0.001F`), `±90°` pitch sıkıştırması, binek/teleport/kamera dönüşleri, ve sinematik kamera / gamepad / HiDPI fare (yalnızca savaş dışında kararlı fare kafesi `>= 18/20` oturmuşsa `combat-lattice-break` etkinleşir).
- **Sinyaller:** `hit-and-snapback` (`>= 25°` dönüş + 1–2 tickte `<= 5°` geri dönüş, 120 tikte `>= 3`), `robotic-tracking` (`25` tikte `mean >= 1.5°`, `sampleStdDev <= 0.015°`), `low-entropy-aim` (`32` hareketli savaş tikinde ≥ `3` saldırılı pencerede `0,25°` kuantize hız kutularının `≤ 2` kutu ve `≤ 1,05 bit` Shannon entropisinde kilitlenmesi) ve `combat-lattice-break` (`15` tikte `>= 12` sapma). `ActionPolicy` gereği ceza tetiklemez.

### 2.13 `container-timing` (`S` — Asla Cezalandırmaz)
- **Dayanak:** `ClientTickEnd` çerçevelemesiyle envanter ve sandık tıklamaları (`ContainerTimingCheck.java`).
- **Sinyaller:**
  - `rapid-container-transfer`: Sunucu menüsünde (`containerId > 0`) `<= 10` client tick içinde `>= 10` **farklı** yuvaya (`slot >= 0 && slot != lastSlot`) hızlı aktarım tıklaması (`ChestStealer`).
  - `constant-container-cadence`: `>= 14` ardışık farklı yuva tıklaması aralığında (`1..6` tick, `mean <= 3.5`) `sampleStdDev <= 0.10` tick robotik ritim.
- **Dışlanan Durumlar:** Aynı yuvaya tekrarlı tıklama/sürükleme (`slot == lastSlot`), yuva dışı bırakma (`slot < 0`), creative/spectator ihtimali ve `Gap`/çevirili pencere. `ActionPolicy` gereği asla ceza veya etkisizleştirme tetiklemez.

### 2.13b `scaffold-pattern` (`S` — Asla Cezalandırmaz)
- **Dayanak:** `TickLook` istemci dönüşleri + `UseItemOn` yatay/alt yüz köprü yerleştirmeleri (`ScaffoldPatternCheck.java`).
- **Sinyaller:**
  - `place-and-snapback`: `face != BlockFace.UP` olan blok yerleştirmesinde `>= 22°` tek-tik dönüş yapıp `1..2` tik içinde eski bakışa `<= 6°` hatayla geri dönülmesi (`120` tikte `>= 3` tekrar — TellyBridge / snap-Scaffold).
  - `constant-bridge-cadence`: `>= 12` ardışık farklı blok konumuna yatay yerleştirmede (`1..6` tik, `mean <= 4.5`) `sampleStdDev <= 0.10` tik robotik ritim.
- **Dışlanan Durumlar:** Üst yüze kule çıkma (`BlockFace.UP`), aynı bloğa tekrarlı tıklama (`pos.equals(lastBridgePos)`), binek/teleport/creative/spectator durumları. `ActionPolicy` gereği asla ceza veya etkisizleştirme tetiklemez.

### 2.14 `design-probe` (`D` — Asla Cezalandırmaz)
- **Dayanak:** Rastgele zamanlanan yem sonda (`ProbeScheduler` + `EValueProcess`, `DesignProbeEngine.java`). Ville eşitsizliği altında her yem deneyi sıfır-hipotez çarpışma oranı $p_0$ ile test martingali $M_t = \prod \frac{\text{outcome}}{p_0}$ veya bahis e-süreci biriktirir; yalnızca $M_t \ge 1 / \alpha$ geçtiğinde ve pencere kabul edilebilir olduğunda `DESIGN_STATISTIC` üretir.
- **İzinli Eylemler (`ActionPolicy`):** Yalnızca `ALERT`, `REVIEW` ve (yalnızca `ARMED` modda) `HIDE_INFORMATION`. `NEUTRALIZE`, `KICK` ve `BAN` sınıfsal olarak **yasaktır**.

---

## 3. `ARMED` Terfi Kapısı Kontrol Listesi (`docs/POLICY.md`)

1. Gerçek istemci CI kapısı (Xvfb + XTest resmi `1.21.11` istemcisi) — **Geçti** (5/5 CI işi yeşil).
2. 100 botlu yük testinde `0` yanlış kanıt ve `GREEN` sağlık — **Geçti** (`tools/load/run_load.sh /tmp/wnac-load-100 whynotac 100 30`: `100/100` bot bağlı, `4.809/4.809` saldırı `IN_REACH`, `casesOpened=0`, `lostEvents=0`, `listenerErrors=0`, `0,345` analiz CPU çekirdeği, `3,40 MB` heap, yük altında ve sonrasında `GREEN`).
3. Sentetik hile/bypass ve uç meşru davranış benchmark'ı — **Tamam** (`CheatDetectionAndBypassTest`: 12 kontrol için tespit gecikmesi, etkisizleştirme `CANCEL_IMPOSSIBLE_HIT`/`CANCEL_IMPOSSIBLE_BLOCK_ACTION`/`SETBACK_POSITION`, asenkron `SecondVerifier` yeniden doğrulaması, `Gap`/çevirici reddi, 240 Hz çok-kareli fare birikimi, 10s donma + 600ms lag ve toggle-sprint/crouch GUI altı `0` yanlış pozitif doğrulandı).
4. Açık kaynak canlı hile istemcileriyle saha tespit oranı (`DATA-02`) — **Sahip onaylı izole VM hile kayıtları bekliyor**.
5. Tek yanlış pozitif kanıtta otomatik düşüş (`CircuitBreaker` + `FalseBanBudget`) — **Kodlandı ve mutasyon testleriyle (`MutationResilienceTest`) doğrulandı**.
