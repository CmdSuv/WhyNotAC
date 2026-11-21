# WhyNotAC — İleri Mühendislik Yol Haritası (Faz 7 – Faz 12: Tam Kapsam ve Derinleştirme Planı)

**Tarih:** 2026-11-17  
**Kapsam:** Faz 1 – Faz 6 ile tamamlanan **Minecraft `1.21.11` (Protokol `774`)** çekirdeğinin üzerine inşa edilecek, kod tabanındaki tüm mevcut kapsam çekilmelerini (`NO_CLAIM` / `Lose`), yeni nesil hile/atlatma vektörlerini, 2. nesil istatistiksel/AI modellerini, kalıcı hile korpusunu (`DATA-02`), kurumsal operasyon özelliklerini ve çoklu-sürüm (`PROD-14`: `1.21.11` + `26.3+`) geçişini en ince teknik detayına kadar tanımlayan uygulama planıdır.  
**Normatif Üst Kural:** Tüm maddeler [POLICY.md](POLICY.md) ve [ROADMAP.md](ROADMAP.md) kapı kurallarına tabidir: hiçbir istatistiksel (`S`) veya deneysel (`D`) kontrol doğrudan ceza (`NEUTRALIZE`/`KICK`/`BAN`) veremez; her yeni deterministik (`P-A`/`P-M`) kural birim testleri + `RegressionReplayTest` (`0` yanlış pozitif) + canlı E2E + gerçek istemci (`realclient`) kapısından geçmek zorundadır.

---

## Mevcut Sistemin Dürüst Röntgeni: Neler Bitti, Nerede Boşluk ve İddia Çekilmesi (`NO_CLAIM`) Var?

Faz 1–6 itibarıyla `105.000+` satırlık kod tabanında **15 yerleşik kontrol + `design-probe`** çalışmakta ve dürüst trafikte `0` yanlış pozitif vermektedir. Ancak kodu satır satır incelediğimizde şu **6 teknik sınırlılık alanı** tespit edilmiştir:

1. **Hareket Takipçisinde (`MovementTracker.java`) İddiadan Çekilme (`Outcome.NO_CLAIM` / `Lose`) Noktaları:**
   - **Çok durumlu blok yerleştirmeleri (`MovementTracker.java:2304`):** `resolveBlockUses` yalnızca tek durumlu tam küp (`FULL_BLOCK`) koyan eşyaları (`stone`, `dirt`, `cobblestone` vb.) çözüyor; basamak (`slab`), merdiven (`stairs`), çit (`fence`), duvar (`wall`) veya yönlü kütük (`log`) konduğunda `"not a block item placing one full block"` diyerek hareket takibini bırakıyor.
   - **Su/Lav kovası kullanımları (`MovementTracker.java:2068`):** `ItemPrototypes.UseKind.CHANGES_BLOCKS` (`water_bucket`, `lava_bucket`, `powder_snow_bucket`, `lily_pad`) kullanıldığında `"an item use that may change a block where WhyNotAC cannot place it"` ile takip kesiliyor (ör. MLG water-bucket inişleri).
   - **Yakındaki canlı varlıkların itmesi (`MovementTracker.java:1392-1409`, `ClientEntities.java:2537-2576`):** Oyuncunun kutusuna `ENTITY_MARGIN = 1.5` blok mesafede çarpışabilir/itebilir bir canlı varlık varsa `"an entity that may push the player is near"` nedeniyle hareket iddiası çekiliyor. Yakın mesafe PvP savaşlarında bu durum hileciye `MovementCheck` açısından kör pencere açabilir.
   - **Soğuma süresi paketi (`MovementTracker.java:440, 2241`):** `minecraft:cooldown` paketi henüz `ClientboundPacket.Unmodeled` olarak geçtiği için bir kez görüldüğünde `cooldownMayRun = true` kalıyor ve sonraki blok/eşya kullanımlarının çözümünü engelliyor.
   - **Açken yemek yeme bitişi (`MovementTracker.java:1676`):** `ClientboundSetHealthPacket` (`food`, `saturation`) modellenmediği için `FINISH_USING_ITEM` geldiğinde `"eaten while hungry"` ile iddia bırakılıyor.
   - **Binekten iniş (`BoatRide.java`, `HorseRide.java`):** Binekten inildiği tikte konum doğrudan yaya hipotezine (`OnFoot`) aktarılmadığı için yeniden çapa (`settleAnchor` / `runAnchor`) kurulana kadar ~`20` tiklik boşluk oluşuyor.
2. **PvP Sırasında `MovementTracker` Çekildiğinde Bağımsız Anti-KB (`Velocity`) Eksikliği:**
   - Rakip oyuncu `1.5` blok yakındayken `MovementTracker` varlık itmesi nedeniyle çekildiğinde, oyuncuya uygulanan `SetEntityMotion` (knockback) paketinin ilk tikteki dikey ($\Delta y$) ve yatay ($\Delta x, \Delta z$) etkisini varlık itmesinden bağımsız denetleyen özel bir `velocity-response` kontrolü henüz yoktur.
3. **İstemci-İçi Envanter (`E` Tuşu) Hareket Kısıtı (`InvMove` / `AutoTotem`):**
   - `ActionGrammarCheck` yalnızca **sunucunun açtığı** menüleri (`ClientboundOpenScreenPacket` -> `MenuScreen`) bilir. Oyuncu kendi envanterini (`containerId == 0`) açtığında sunucuya paket gitmez; ancak oyuncu `ContainerClick(containerId == 0)` gönderdiği tikte aynı anda koşuyor, zıplıyor veya saldırıyorsa bu durum decompile `1.21.11` `InventoryScreen` altında imkânsızdır.
4. **Dengeleyici Saat (`Balance Timer`) ve `FakeLag` / `Backtrack` Boşluğu:**
   - `ClientClockCheck` `SLACK_TICKS = 22.0` başlangıç/donma payı verir. Önce istemci saatini `0.3x` hızına düşürüp 22 tiklik açığı biriktiren, ardından `2.0x` hızla patlama yapan "Balance Timer" hileleri tek yönlü üst sınır kontrolünden kaçabilir.
5. **Etiketli `.wnrec` Hile Korpusu (`DATA-02`) ve Otomatik Yakalama Hızı (`TTD`) Kıyaslaması:**
   - Dürüst oyuncu korpusu (`regression/*.wnrec`) mevcuttur, ancak `.wnrec` formatında kalıcı hile kayıtları (`cheat-corpus/*.wnrec`) ve her kontrolün kaçıncı tikte yakaladığını ölçen `java -jar WhyNotAC.jar benchmark` CLI aracı eksiktir.
6. **Operasyonel Canlı Akış (`Webhook` / `JSONL`), Sıcak Yenileme (`/whynotac reload`) ve Çoklu-Sürüm (`26.3+`):**
   - Harici SIEM/Discord entegrasyonu için yapılandırılabilir olay akışı, sunucu kapatmadan `/whynotac reload` ve ViaVersion arkası ikinci kenar (`SecondEdge`) katmanı sırada beklemektedir.

---

## Faz 7 — Fizik ve Dünya Replikasında İddia Çekilmelerinin (`NO_CLAIM` / `Lose`) Kapatılması

Amaç: Dürüst trafikte `0` yanlış-pozitif kuralından tek bit bile taviz vermeden, `MovementTracker` ve `ClientWorld`'ün "bilinmiyor" diyerek çekildiği meşru oyun durumlarını deterministik olarak modellemek ve hareket kontrolünün kapsama oranını (`explained / totalTicks`) en üst düzeye çıkarmak.

### `PH7-01` — `ClientboundSetHealthPacket` ve `ClientboundCooldownPacket` Modellemesi
- **Sorun Noktaları:**
  - [`MovementTracker.java#L440`](../whynotac-analysis/src/main/java/dev/whynotac/analysis/movement/MovementTracker.java#L440) ve [`#L2241`](../whynotac-analysis/src/main/java/dev/whynotac/analysis/movement/MovementTracker.java#L2241): `minecraft:cooldown` `Unmodeled` olduğu için bir kez gelince oturum sonuna dek `cooldownMayRun = true` kalıyor.
  - [`MovementTracker.java#L1676`](../whynotac-analysis/src/main/java/dev/whynotac/analysis/movement/MovementTracker.java#L1676): `FINISH_USING_ITEM` geldiğinde oyuncunun açlık seviyesi (`food <= 6`) bilinemeyince takip kesiliyor.
- **Teknik Çözüm:**
  1. `whynotac-protocol` içinde `ClientboundPacket.SetHealth(float health, int food, float saturation)` ve `ClientboundPacket.Cooldown(String cooldownGroup, int durationTicks)` kayıtlarını ekle (`WireDecoder` ve `PacketTranslator` içine bağla).
  2. `SelfState` içinde `food` (`[foodLow, foodHigh]`) ve aktif `cooldowns` (`Map<String, Integer>`, her client tick'inde `1` azalan sayaç) durumlarını tut.
  3. Yemek bittiğinde (`FINISH_USING_ITEM`) `ItemPrototypes` içinden besin puanını (`nutrition`) ekleyerek `foodAllowsSprint()` (`food > 6`) geçişini bit-tam hesapla; `cooldown` süresi `0` olduğunda `cooldownMayRun` bayrağını temizle.
- **Kabul Kapısı:** Ender incisi / kalkan soğuması sonrası blok koyma ve açken yemek yeme birim testlerinde `0` `NO_CLAIM` (`Outcome.EXPLAINED`).

### `PH7-02` — Çok-Durumlu Blok Yerleştirme (`Slab`, `Stairs`, `Fence`, `Directional`) Çözümü
- **Sorun Noktası:** [`MovementTracker.java#L2304`](../whynotac-analysis/src/main/java/dev/whynotac/analysis/movement/MovementTracker.java#L2304) (`!states.has(state, BlockStates.FULL_BLOCK)`).
- **Teknik Çözüm:**
  1. Decompile `1.21.11` `SlabBlock.getStateForPlacement`, `StairBlock.getStateForPlacement` ve `RotatedPillarBlock.getStateForPlacement` kurallarını `ClientWorld.placementState(UseItemOn use, float playerYaw, float playerPitch, boolean fluidSource)` olarak kodla:
     - `SlabBlock`: Tıklanan yüz `UP` ise `bottom`, `DOWN` ise `top`, yan yüz ise `use.location().y() - use.position().y() > 0.5` durumuna göre `top`/`bottom` (veya aynı tür basamağı `double` tam bloğa tamamla).
     - `RotatedPillarBlock` (`_log`, `hay_block`, `quartz_pillar`): Tıklanan yüzün eksenine (`X`, `Y`, `Z`) göre durumu seç (`oyuncu dönüşünden bağımsız!`).
  2. `inTheWay(Box, VoxelShape)` kontrolünü tam küp yerine yerleştirilen durumun gerçek çarpışma şekli (`BlockStates.collisionShape(state)`) ile çalıştır.
- **Kabul Kapısı:** Basamak ve kütük yerleştirerek yapılan köprü/kule testlerinde `MovementTracker` takibi hiç kopmadan `Outcome.EXPLAINED` döner.

### `PH7-03` — Kova (`WaterBucket` / `LavaBucket` / `EmptyBucket`) Blok Değişimi Tahmini
- **Sorun Noktası:** [`MovementTracker.java#L2068`](../whynotac-analysis/src/main/java/dev/whynotac/analysis/movement/MovementTracker.java#L2068) (`ItemPrototypes.UseKind.CHANGES_BLOCKS`).
- **Teknik Çözüm:**
  - `UseItem` (veya `UseItemOn` sonrası `UseItem`) sırasında oyuncunun elinde `minecraft:water_bucket`, `minecraft:lava_bucket`, `minecraft:powder_snow_bucket` veya `minecraft:bucket` varsa, `TickLook` bakış ışını üzerinden `ClipContext.Fluid.NONE` (dolu kova) veya `ClipContext.Fluid.SOURCE_ONLY` (boş kova) ile hedef hücreyi (`BlockPos`) bul ve `ClientWorld` tahmin tablosuna su/lav kaynağı veya hava durumunu ekle.
- **Kabul Kapısı:** Yüksekten düşerken altındaki bloğa su kovası koyup (`MLG water bucket`) geri toplama senaryosunda `MovementTracker` kesintisiz `EXPLAINED` kalır.

### `PH7-04` — Binek-Yaya (`Dismount`) Kesintisiz Hipotez Devri
- **Sorun Noktası:** [`BoatRide.java`](../whynotac-analysis/src/main/java/dev/whynotac/analysis/movement/BoatRide.java) ve [`HorseRide.java`](../whynotac-analysis/src/main/java/dev/whynotac/analysis/movement/HorseRide.java) binek sürüşünü bit-tam izler, ancak inildiği anda yaya hipotez kümesi sıfırlanıp `settleAnchor`/`runAnchor` beklenir.
- **Teknik Çözüm:**
  - Sunucudan `SetPassengers` (oyuncuyu araçtan çıkaran paket) + `PlayerPosition` (veya istemci `shift` inişi) geldiğinde, iniş konumunu ve sıfırlanmış hızı doğrudan yeni `OnFoot` `Hypothesis` başlangıcı olarak ata.
- **Kabul Kapısı:** Tekne ve attan indikten sonraki ilk tikten itibaren `0` `UNTRACKED` boşluk.

### `PH7-05` — Yakın Varlık İtmesi (`Entity.push`) Zarflama Motoru
- **Sorun Noktası:** [`MovementTracker.java#L1392-L1409`](../whynotac-analysis/src/main/java/dev/whynotac/analysis/movement/MovementTracker.java#L1392-L1409).
- **Teknik Çözüm:**
  - Yakında **en fazla 2** canlı varlık (`MAX_PUSHERS = 2`) varken ve bu varlıkların olası konum zarfları dar (`<= 0.05` blok) iken, decompile `1.21.11` `Entity.push` vektörünü (`dx, dz`, `d = max(|dx|, |dz|) >= 0.01`, `dd = sqrt(d)`, `0.05F * (1 - ...) / dd`) hesaplayıp hipoteze uygula; yalnızca zarf çok genişse veya `> 2` varlık varsa `NO_CLAIM` dön.
- **Kabul Kapısı:** 1v1 PvP yakın dövüşünde çarpışma mesafesinde bile `MovementTracker` takibinin korunması.

---

## Faz 8 — Yeni Nesil Deterministik (`P-A` / `P-M`) Savaş, Sahte-Gecikme ve Envanter Kontrolleri

Amaç: Mevcut 15 kontrolün yanına, özellikle rekabetçi PvP sunucularında en çok kullanılan **Anti-KB (`Velocity`), `InvMove` / `AutoTotem`, `NoSlow`, `Backtrack` / `PingSpoof` ve `Balance Timer`** hilelerini yakalayan **6 yeni deterministik kontrol** eklemek.

### `PH8-01` — `VelocityCheck` (`velocity-response`, `P-A` / `P-M`, `MOVEMENT_PHYSICS`)
- **Neden Gerekli?** 1v1 PvP'de rakip oyuncu `1.5` blok içindeyken `MovementTracker` varlık itmesi nedeniyle çekildiğinde, oyuncunun aldığı knockback'i (`SetEntityMotion`) `%0` veya `%50` oranında silen **Velocity / Anti-KB** hilelerini yakalayacak bağımsız bir zarf kontrolü gerekir.
- **Matematiksel Dayanak (Decompile `1.21.11` `ClientPacketListener.handleSetEntityMotion` + `LivingEntity.aiStep`):**
  1. `SetEntityMotion(selfId, vx, vy, vz)` paketi iki `Ping` (`p_before`, `p_after`) arasına sandviçlenir.
  2. İstemci `p_after` transaction'ını onayladığı tikte (`t_kb`), eğer başının üstünde tavan bloğu yoksa (`ClientWorld` üzerinde `[playerBox.move(0, vy, 0)]` engelsiz), bal/örümcek ağı/merdiven/su/lav içinde değilse ve binek sürmüyorsa:
     - İlk tikteki dikey yer değiştirme $\Delta y = y_{t_{\text{kb}}} - y_{t_{\text{kb}}-1}$ değeri, yerçekimi ve `jump-reset` olasılıkları hesaba katıldıktan sonra en az $v_y - \epsilon$ olmalıdır!
     - Yatayda ise istemcinin `W/A/S/D` + `sprint` + `attack slowdown (0.6)` + `entity push` maksimum karşı-ivmesi çıkarıldığında kalan minimum yatay yer değiştirme sınırının altına inilmesi (`%0..%65` yatay Anti-KB) `ADVANTAGE_PROOF` (`P-A`) üretir ve `SETBACK_POSITION` uygular.
- **Kabul Kapısı:** `VelocityCheckTest` (`%0` dikey/yatay KB, `%50` KB, tavan altı muafiyeti, su/örümcek ağı muafiyeti, meşru `jump-reset` muafiyeti) + `RegressionReplayTest` (`0` yanlış pozitif).

### `PH8-02` — `InventoryActionCheck` (`inventory-protocol`, `P-M`, `PACKET_GRAMMAR`)
- **Dayanak (Decompile `1.21.11` `InventoryScreen` + `Minecraft.handleKeybinds`):**
  1. **`click-while-acting` (`InvMove` / `AutoTotem`):** Oyuncu `ContainerClick` gönderdiği tikte (`containerId == 0` oyuncu envanteri dâhil!), istemcide bir `AbstractContainerScreen` açıktır. Ekran açıkken `Minecraft.handleKeybinds()` **çalışmaz** ve ekran açılışında `KeyMapping.releaseAll()` çağrılır. Dolayısıyla **aynı client tick içinde** `ContainerClick` ile birlikte `InteractEntity`, `UseItem`, `UseItemOn`, `PlayerAction(START_DESTROY_BLOCK / STOP_DESTROY_BLOCK / STAB)`, `PlayerCommand(START_SPRINTING)` veya pozitif ileri/yatay tuş değişimi (`PlayerInput` içinde yeni `forward/backward/left/right = true` geçişi) gönderilemez!
  2. **`unopened-container-click`:** `containerId > 0` olan bir `ContainerClick` veya `ContainerClose`, sunucunun açtığı aktif `OpenScreen(containerId)` penceresi (veya son kapanan `containerId` uçuş penceresi) dışında gönderilemez.
- **Kabul Kapısı:** `InventoryActionCheckTest` + `RegressionReplayTest` (`0` yanlış pozitif).

### `PH8-03` — `UseSlowdownCheck` (`use-item-state`, `P-M`, `PACKET_GRAMMAR`)
- **Dayanak (Decompile `1.21.11` `Minecraft.handleKeybinds` + `LocalPlayer.aiStep`):**
  - Aynı client tick içinde hem `UseItem` gönderip hem de `PlayerAction(RELEASE_USE_ITEM)` göndermek (`instant-use-release`: `NoSlow` / `FastUse` / `SwordBlock` paket döngüsü) vanilla `handleKeybinds` içinde imkânsızdır (`releaseUsingItem` yalnızca `keyUse.isDown() == false` iken çalışır, `startUseItem` ise `keyUse.consumeClick()` ile çalışır ve aynı tikte önce `consumeClick` döngüsü, sonra `!isDown()` kontrolü yalnız `isUsingItem()` önceden açıkken ve o tikte `keyUse` bırakılmışsa çalışır — ancak birçok `NoSlow` hilesi her tik `RELEASE_USE_ITEM` -> `MovePlayer` -> `UseItem` sırasıyla gönderir!).
  - Ayrıca oyuncu eşya kullanmaya başladıktan (`UseItem` onaylı ve `RELEASE_USE_ITEM` / el değişimi yokken) sonraki tiklerde `PlayerCommand(START_SPRINTING)` gönderemez (`canStartSprinting()` içinde `!this.isUsingItem()` şartı vardır!).
- **Kabul Kapısı:** `UseSlowdownCheckTest` + `RegressionReplayTest` (`0` yanlış pozitif).

### `PH8-04` — `TimerBalanceCheck` (`timer-balance`, `P-M` / `S`, `CLOCK_AND_FRAMING`)
- **Dayanak:** `ClientClockCheck` yalnızca üst sınırı ($N \le \frac{\Delta t \cdot 1.01}{50\text{ ms}} + 22$) denetler.
- **Teknik Çözüm:**
  - İki yönlü kayan saat kredisi (`clockBalanceTicks`) takibi: İstemci donma (`Gap` / tek tikte `> 200 ms` boşluk) olmadan sürekli olarak gerçek zamandan `%20+` yavaş (`< 16` tick/sn) akıp hemen ardından gerçek zamandan `%15+` hızlı (`> 23` tick/sn) patlama yapıyorsa (`oscillating-timer-balance`), `ClientClockCheck` içine alt-üst zarf salınımı kuralı olarak eklenir.

### `PH8-05` — `BacktrackAndPingSpoofCheck` (`ping-spoof`, `S` / `P-M`, `CLOCK_AND_FRAMING`)
- **Dayanak:**
  1. `KeepAlive` (istemci ağ iş parçacığında anında yanıtlanır) ile `Ping` (ana oyun iş parçacığında yanıtlanır) arasındaki RTT farkı ($\text{RTT}_{\text{ping}} - \text{RTT}_{\text{keepalive}}$): Normalde en fazla 1 kare süresi (`~50..150 ms`) kadardır. `Backtrack` / gelen paket geciktirme hileleri yalnızca oyun paketlerini (`Ping` + `MoveEntity`) kuyrukta tutup `KeepAlive`'ı hemen yanıtladığında veya saldırı anında RTT'yi yapay olarak `300 ms -> 20 ms` düşürdüğünde (`combat-rtt-drop`) yakalanır.

### `PH8-06` — `InteractAtBoxCheck` (`interact-at-box`, `P-M`, `RAYCAST_AIM`)
- **Dayanak:** `ServerboundInteractPacket` (`Kind.INTERACT_AT`) içindeki yerel `Vec3d location` koordinatının, hedef varlığın o tikteki olası boyutlarının (`EntitySizes`) `[-width/2 - 0.1, +width/2 + 0.1] x [0, height + 0.1]` zarfı içinde olduğunu doğrulamak.

---

## Faz 9 — `whynotac-ai` 2. Nesil İstatistiksel ve Martingal Evrimi

Amaç: [POLICY.md](POLICY.md) cezasızlık sınırını (`S`/`D` asla ceza vermez) koruyarak, `whynotac-ai` katmanını basit tek-değişkenli Z-skorundan **çok-değişkenli kovaryans (Mahalanobis), büyüme-optimal (GRO/Kelly) martingal ve oturum-içi toggle tespiti** seviyesine taşımak (`8.5/10 -> 9.8/10`).

### `PH9-01` — Çevrimiçi Kovaryans Matrisi ve Mahalanobis Uzaklığı (`AccountBaselineModel` v2)
- **Teknik Çözüm:**
  - `whynotac-math` içinde `RunningCovarianceMatrix` (Welford/Pébay çok-değişkenli çevrimiçi kovaryans ve `merge` desteği) oluştur.
  - `(click_interval_ticks, combat_aim_step_deg, combat_aim_jerk_deg, attack_snap_ratio)` dörtlü vektörü üzerinde Tikhonov regülarizasyonlu ($\Sigma + \lambda I$) Mahalanobis uzaklığı $D_M = \sqrt{(x - \mu)^T (\Sigma + \lambda I)^{-1} (x - \mu)}$ hesapla.

### `PH9-02` — Büyüme-Optimal (Kelly / GRO) Adaptif Martingal (`EValueProcess` v2)
- **Teknik Çözüm:**
  - `EValueProcess` içinde bir önceki $t-1$ sondanın gözlenen vuruş oranından ($\hat{p}_{t-1} = \frac{\text{hits} + 0.5}{\text{obs} + 1.0}$) bir sonraki adımın $\lambda_t$ bahis kesrini $\lambda_t = \text{clip}\left(\frac{\hat{p}_{t-1} - p_0}{p_0(1 - p_0)},\; 0,\; \frac{0.95}{p_0}\right)$ olarak güncelle (`predictable plug-in betting strategy`).
  - $\lambda_t$ yalnızca $t-1$ geçmişine bağlı olduğu için $\mathbb{E}_{H_0}[1 + \lambda_t(X_t - p_0) \mid \mathcal{F}_{t-1}] = 1$ eşitliği **kesin olarak korunur** (Ville eşitsizliği %100 geçerli kalır) ve KillAura yakalama hızı `%40` artar.

### `PH9-03` — Oturum-İçi Hile Açma/Kapama (`IntraSessionToggleDetector`)
- **Teknik Çözüm:**
  - `BehavioralFeatureExtractor` içinde tüm oturum özetinin (`RunningMoments`) yanına `600` tiklik (`30` sn) çift-tamponlu kayan pencere (`recentWindow` vs `priorSessionWindow`) ekle; oyuncu oturum ortasında aniden makro/aimbot açtığında `intraSessionShiftZ` üret.

### `PH9-04` — Vaka Dosyalarına (`dossier.json`) Tam AI Telemetrisi ve `Tool explain` Genişletmesi
- **Teknik Çözüm:**
  - `CaseStore` vaka JSON dosyasına `aiPriorityScore`, `aiIntrinsicRisk`, `aiDominantSignal`, `aiBaselineRmsZ`, `aiTopFeature` ve `aiProbeWealth` alanlarını yazar; `java -jar WhyNotAC.jar explain <dossier.json>` bu verileri insan incelemesi için açıklar.

---

## Faz 10 — Kalıcı `.wnrec` Hile Korpusu (`DATA-02`), Benchmark CLI ve 500-Bot Ölçek Testi

### `PH10-01` — Kalıcı `.wnrec` Hile Korpusu ve `java -jar WhyNotAC.jar benchmark`
- **Teknik Çözüm:**
  - `whynotac-analysis` içinde `dev.whynotac.analysis.cli.BenchmarkRunner` oluştur ve `Tool.main` içine `benchmark` alt komutunu bağla:
    ```bash
    java -jar WhyNotAC.jar benchmark [regression-dir]
    ```
  - Bu komut:
    1. Tüm dürüst `regression/*.wnrec` oturumlarını **15 kontrol + `design-probe`** ile oynatır (`False Positives = 0`, `ns/tick`, `margins` doğrular).
    2. Ardından yerleşik/kaydedilmiş **16 hile senaryosunu** (`Reach-3.15`, `Reach-4.2`, `Silent-KillAura`, `Wall-HitAura`, `NoSwing`, `FastBreak`, `Scaffold-OffFace`, `TellyScaffold`, `Fly-Vertical`, `Speed-Hop`, `NoFall`, `SplitTick`, `Timer-1.5x`, `Blink-KeepAlive`, `AutoClicker`, `ChestStealer`, `BadPackets`) gerçek `.wnrec` akışıyla çalıştırır.
    3. Her senaryo için **Tespit Durumu (`PASS`/`FAIL`), İlk Yakalama Gecikmesi (`TTD ticks` / `ms`), Üretilen Kanıt Sınıfı (`P-A`/`P-M`/`S`/`D`) ve Özet JSON/Tablo** çıktısı üretir.

### `PH10-02` — `LEGITIMATE-BEHAVIOUR.md` Kalan Uç Senaryoların Otomatik Testi (`VAL-13`)
- **Teknik Çözüm:**
  - `CheatDetectionAndBypassTest` (veya `LegitimateEdgeCatalogueTest`) içine:
    1. Koşarak ve zıplayarak ayağın altına blok koyup ilerleme (`sprint-jump bridging`),
    2. Saldırı bekleme süresiyle (`attack cooldown` `12` tick) bir varlığa sürekli basılı tutma,
    3. Geçişli koşma/çömelme (`toggleSprint`/`toggleCrouch`) açıkken tekne ve ata binip inme,
    4. `1000 ms` ağ gecikmesi patlaması (`20` tick'lik paket yığınının tek milisaniyede gelmesi)
    senaryolarını ekleyip **15 kontrolün tamamında `0` yanlış pozitif** doğrulamak ve `docs/LEGITIMATE-BEHAVIOUR.md` tablosunu güncellemek.

### `PH10-03` — 500 Botluk Mega-Ölçek Stres Testi (`tools/load`)
- **Teknik Çözüm:**
  - `tools/load/run_load.sh` ile yüksek yoğunluklu oturumlarda `SessionRegistry` ve `Replicas` bellek izini ölçmek ve 500 eşzamanlı bağlantıda `lostEvents = 0`, `listenerErrors = 0`, `GREEN` sağlık doğrulamak.

---

## Faz 11 — Kurumsal Operasyon, Gerçek Zamanlı JSONL/Webhook Akışı ve Canlı Yönetim

### `PH11-01` — Gerçek Zamanlı Yapılandırılabilir `alerts-stream.jsonl` ve Webhook Çıkışı
- **Teknik Çözüm:**
  - `whynotac-paper` içinde `AlertStreamExporter` oluştur (`observability.alert-stream-file: "alerts-stream.jsonl"` ve opsiyonel `observability.webhook-url`):
    - Açılan her vakayı (`VerdictCase`), uygulanan her yaptırımı (`VerdictDecision`) ve devre-kesici/sağlık geçişini tek-satır JSON (`timestamp`, `event`, `player`, `check`, `class`, `lifecycle`, `action`, `aiScore`, `certificateSha256`) olarak asenkron diske/webhook'a yazar.

### `PH11-02` — Sıcak Yapılandırma Yenileme (`/whynotac reload`)
- **Teknik Çözüm:**
  - `VerdictEngine` içinde `updateConfig(VerdictConfig newConfig)` atomik (`volatile VerdictConfig`) güncellemesini ekle.
  - `/whynotac reload` komutu çalıştırıldığında `config.yml` yeniden okunur, `VerdictConfig` güncellenir, `AuditLedger` içine imzalı `CONFIG_RELOAD` kaydı düşülür ve aktif oyuncu oturumları kesintisiz devam eder.

### `PH11-03` — Hazır Grafana Panosu (`deploy/monitoring/grafana-dashboard.json`)
- **Teknik Çözüm:**
  - `MetricsExporter` tarafından sunulan tüm `whynotac_*` metriklerini (sağlık durumu, açık oturumlar, saniyedeki paket/vaka hızı, transaction döngü süresi $\mu\text{s}$, en küçük dürüst paylar `whynotac_check_min_margin`) görselleştiren hazır Grafana pano şablonu.

---

## Faz 12 — Çoklu-Sürüm (`PROD-14`: `1.21.11` + `26.3+`) ve İkinci Kenar (`SecondEdge`) Mimarisi

### `PH12-01` — ViaVersion Arkası Güvenli Nedensellik Katmanı (`AdmissibilityTracker.evaluateTranslatorSafe`)
- **Teknik Çözüm:**
  - Bir oyuncu ViaVersion üzerinden farklı bir protokolle bağlandığında (`TRANSLATOR != none`), fiziğe ve paket şekline duyarlı kontroller (`movement`, `attack-reach`, `attack-aim`, `use-aim`, `break-time`, `tick-framing`, `action-grammar`) kesin olarak devre dışı kalmaya devam eder.
  - Ancak `TRANSACTION_ORIGIN == CLIENT` olduğu sürece çeviricinin bozmadığı **saf nedensellik ve yapısal sınır kontrolleri** (`transaction-order` `KeepAlive`/`Ping` sırası ve `packet-bounds` `NaN`/`Infinity` kontrolü) için `evaluateTranslatorSafe` kapısı tanımlanır.

### `PH12-02` — Çoklu-Protokol Tablo Yönlendiricisi (`ProtocolSpec` -> `VersionedTables`)
- **Teknik Çözüm:**
  - [`SupportedProtocols`](../whynotac-protocol/src/main/java/dev/whynotac/protocol/version/SupportedProtocols.java) içindeki her `ProtocolSpec` girişini kendi `BlockStates`, `EntitySizes` ve `ItemPrototypes` sağlayıcısıyla ilişkilendirerek yeni bir Minecraft sürümü (`26.3+`) eklendiğinde oturum bazlı tablo seçimi yapılmasını sağlamak.

---

## Özet Uygulama Sırası ve Tamamlanma Durumu (`2026-11-18`)

| Faz | Paket Kodu | Başlık | Çıktı / Hedef Sınıf | Doğrulama Kapısı & Durum |
|---|---|---|---|---|
| **Faz 7** | `PH7-01..05` | Açken yemek hipotezi (`FOOD_EITHER`), tam `ClientboundCooldownPacket` decoding & sayaçlı soğuma, çok-durumlu sütun/kütük & `SlabBlock` yerleştirmesi ve `dismountAnchor` kesintisiz binek-yaya devri | `SelfState`, `MovementTracker`, `BlockStates`, `ClientWorld`, `PacketTranslator` | **TAMAMLANDI** (`MovementCheckTest`, `ProtocolSurfaceTest`, `RegressionReplayTest` `0` FP) |
| **Faz 8** | `PH8-01..06` | `VelocityCheck` (dikey + yatay Anti-KB), `InventoryActionCheck` (InvWalk + açılmamış konteyner tıklaması), `UseSlowdownCheck`, `TimerBalanceCheck`, `PingSpoofCheck` (RTT şişirme + `combat-rtt-drop`), `InteractAtBoxCheck` | `whynotac-analysis/checks/*` (**21** yerleşik kontrol + `design-probe`) | **TAMAMLANDI** (Tüm birim testleri + `CheatDetectionAndBypassTest` + Canlı E2E) |
| **Faz 9** | `PH9-01..04` | `RunningCovarianceMatrix` + Tikhonov Mahalanobis uzaklığı, Kelly/GRO adaptif martingal, oturum-içi toggle tespiti, AI dossier JSON & `Tool explain` | `whynotac-math`, `whynotac-ai/*`, `CaseStore`, `Tool.explain` | **TAMAMLANDI** (`RunningCovarianceMatrixTest` + `AiAdvisorTest`) |
| **Faz 10** | `PH10-01..03` | `WhyNotAC.jar benchmark` CLI (`regression/` `0` FP + kalıcı `cheat-corpus/*.wnrec` `0` FN + **20** senaryo TTD matrisi) + **500 eşzamanlı oturum** ölçek testi (`0` düşen olay) + `VAL-13` testleri | `BenchmarkRunner`, `cheat-corpus/`, `CheatDetectionAndBypassTest` | **TAMAMLANDI** (`BenchmarkRunnerTest` 20/20 + 6/6 `.wnrec` + 500-oturum PASS) |
| **Faz 11** | `PH11-01..03` | Gerçek zamanlı `alerts-stream.jsonl` + opsiyonel async `observability.webhook-url` HTTP POST, `/whynotac reload` sıcak yenileme (`CONFIG_RELOAD` ledger), Grafana `whynotac_*` panosu | `AlertStreamExporter`, `WhyNotCommand`, `deploy/monitoring/grafana-dashboard.json` | **TAMAMLANDI** (`AlertStreamExporterTest` + `PaperVerdictBridgeTest`) |
| **Faz 12** | `PH12-01..02` | `TransactionOrderCheck`, `TimerBalanceCheck`, `PingSpoofCheck` ve `PacketBoundsCheck` alt-kural seçici `evaluateTranslatorSafe` + çoklu-sürüm `SupportedProtocols.VersionedTables` yönlendiricisi | `AdmissibilityTracker`, `SupportedProtocols`, `PacketBoundsCheck` | **TAMAMLANDI** (`AdmissibilityTrackerTest`, `PacketBoundsCheckTest`, `SupportedProtocolsTest`) |
| **Faz 13** | `PH13-01..03` | `UseSlowdownCheck` v2 tikler-arası kalkan/dürbün `AutoBlock` denetimi, `StairBlock` (`_stairs`) düz yerleşim çözümü, blok-değiştirmeyen `UseItem` dünya temizleme (`resolveItemUseNoChange`), deterministik `water_bucket`/`lava_bucket`/`bucket` ışın tahmini (`bucketPlacement`) ve **22 senaryo + 7 `.wnrec` korpusu** | `UseSlowdownCheck`, `ClientWorld`, `ClientInventory`, `SelfState`, `MovementTracker`, `BenchmarkRunner` | **TAMAMLANDI** (`UseSlowdownCheckTest`, `ClientWorldTest`, `MovementCheckTest`, `BenchmarkRunnerTest`, `RegressionReplayTest` `0` FP) |
| **Faz 14** | `PH14-01..04` | `VelocityCheck` v2 (kısmi dikey Anti-KB, `Explosion` itkisi, tam `0.6x0.6` ayak-izi hava & özel yerçekimi/uçuş muafiyeti), `SlabBlock` `type=double` birleştirme + kuru slab tepkisizliği + 4-yönlü tam küpler + izole kuru `_fence`/`_wall`, 12-kontrol canlı ARMED `run_cheat_e2e.sh` ve **25 senaryo + 8 `.wnrec` korpusu** | `VelocityCheck`, `ClientWorld`, `MovementTracker`, `cheat_probe.py`, `run_cheat_e2e.sh`, `BenchmarkRunner`, `deploy/configs/*` | **TAMAMLANDI** (`VelocityCheckTest`, `ClientWorldTest`, `MovementCheckTest`, `BenchmarkRunnerTest`, `RegressionReplayTest` `0` FP, canlı 12-kontrol E2E) |
| **Faz 15** | `PH15-01..05` | Hayatta kalma sıfır-sertlikli `START_DESTROY_BLOCK` anında-kırma (`DigPredictions`/`Digging`/`ClientWorld`/`ClientSelf`), kuru izole `iron_bars`/`*_glass_pane` + `*_chain` + `end_rod`, `PacketBoundsCheck` v4 (`invalid-pitch`, `duplicate-hotbar-slot`, konteyner tuş sınırları), `WhyNotAcVelocityPlugin` kaynak-yankı düzeltmesi + ana-iş-parçacığı `sendPluginMessage` + çift-yönlü canlı `run_proxy_e2e.sh`, **28 senaryo + 9 `.wnrec` korpusu** ve `SOUNDNESS.md` tam tablo eşitlemesi | `DigPredictions`, `Digging`, `ClientWorld`, `ClientSelf`, `PacketBoundsCheck`, `WhyNotAcVelocityPlugin`, `WhyNotAcPlugin`, `run_proxy_e2e.sh`, `BenchmarkRunner`, `SOUNDNESS.md` | **TAMAMLANDI** (`DigPredictionsTest`, `ClientWorldTest`, `MovementCheckTest`, `PacketBoundsCheckTest`, `WhyNotAcVelocityPluginTest`, `BenchmarkRunnerTest`, `RegressionReplayTest` `0` FP, canlı E2E'ler) |
| **Faz 16** | `PH16-01..05` | `VelocityCheck` v3 (vanilla PvP **Jump-Reset** `0` FP koruması, `minecraft:speed` / sürtünme / `soul_soil` muhafızı, durağan kısmi yatay Anti-KB `partial-horizontal-knockback`), `PingSpoofCheck` v2 (100-bot yük testinde keşfedilen `combat-rtt-drop` kuyruk-boşalması/duraksama FP'sinin `150 ms` tik-aralığı muhafızı + `ka + 60 ms` tam boşaltma + `2x` tekrar koşuluyla giderilmesi), `PaperVerdictBridge` sıcak yenileme/kapanış görünürlük & iptal temizliği, `AlertStreamExporter` tam RFC 8259 JSON kaçışı, **30 senaryo + 9 `.wnrec` korpusu**, `%99,94 EXPLAINED` hareket denetimi ve tam dokümantasyon/bağlantı sürüm hazır denetimi | `VelocityCheck`, `PingSpoofCheck`, `PaperVerdictBridge`, `AlertStreamExporter`, `WhyNotAcPlugin`, `BenchmarkRunner`, `README.md`, `SOUNDNESS.md`, `OPERATOR-GUIDE.md` | **TAMAMLANDI** (`VelocityCheckTest`, `PingSpoofCheckTest`, `AlertStreamExporterTest`, `PaperVerdictBridgeTest`, `BenchmarkRunnerTest` 30/30, `RegressionReplayTest` `0` FP, 100-bot canlı yük testi `0` FP, 3 canlı E2E + 5/5 CI) |
| **Faz 17** | `PH17-01..05` | `2026-10-06-weakness-analysis.md` kalan tüm teknik zayıflıkların kökten kapanışı: `SerialMailbox` oturum başına `2 ms` adil zaman bütçesi (`PlayerSession.analysisNanos`/`budgetYields`), `AiAdvisor` `Gap` patlaması kör etme telemetrisi (`sessionGapBursts`) + sınırlı vaka haritası (`MAX_TRACKED_CASES=10_000`), `TransactionOrderCheck` v2 (`skipped-transaction` + `duplicate-transaction` `P-M` kanıtları), `AuditLedger` gerçek `FileChannel.force(true)` + $O(1)$ RAM doğrulaması, `ReviewWorkflow` disk kalıcılığı (`reviews.jsonl`), `RunningCovarianceMatrix` + `SubjectProfileStore` kovaryans kalıcılığı, `BanWaveQueue` sıcak yeniden yükleme + sınırlı iptal haritası, `PaperNetworkBridge` uzak `GDPR_FORGET` yerel silme kancası, `DECISIONS.md` sunucu-türü presetleri (`paper-pvp-practice`, `paper-smp`), `AnalysisMutationResilienceTest`, **32 senaryo + 10 `.wnrec` korpusu** ve yeni bit-bit **`docs/reviews/2026-11-21-weakness-analysis.md`** denetim raporu | `SerialMailbox`, `PlayerSession`, `TransactionOrderCheck`, `AiAdvisor`, `AccountBaselineModel`, `RunningCovarianceMatrix`, `AuditLedger`, `ReviewWorkflow`, `SubjectProfileStore`, `StorageManager`, `BanWaveQueue`, `VerdictEngine`, `PaperNetworkBridge`, `MetricsExporter`, `WhyNotAcPlugin`, `BenchmarkRunner`, `deploy/configs/*`, `docs/reviews/2026-11-21-weakness-analysis.md` | **TAMAMLANDI** (`PlayerSessionTest`, `TransactionOrderCheckTest`, `AnalysisMutationResilienceTest`, `ReviewWorkflowTest`, `SubjectProfileStoreTest`, `AiAdvisorPersistenceTest`, `BanWaveQueueTest`, `PaperVerdictBridgeTest` 5/5 preset, `BenchmarkRunnerTest` 32/32 + 10/10 `.wnrec`, `RegressionReplayTest` `0` FP, 3 canlı E2E + 5/5 CI) |
