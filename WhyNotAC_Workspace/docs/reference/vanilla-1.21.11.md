# Vanilla 1.21.11 — Doğrulanmış Referans

Bu belgedeki her bilgi, Mojang'ın resmi 1.21.11 client/server jar'larından (SHA-1 doğrulamalı,
Mojang mapping'leriyle yeniden adlandırılıp Vineflower ile decompile edilmiş) ve Paper 1.21.11
build 132 dev-bundle'ından **birebir okunmuştur**. Hafızadan yazılmış tek bir sabit yoktur.
Kod içinde atıf yapılan her davranış, `whynotac-conformance` test paketiyle gerçek oyun
sınıflarına karşı bit seviyesinde test edilir.

| Artefakt | Kaynak | Doğrulama |
|---|---|---|
| client.jar 1.21.11 | piston-data.mojang.com | SHA-1 `ba2df812c2d1…` |
| client mappings | piston-data.mojang.com | SHA-1 `031a68bebf55…` |
| Paper 1.21.11 | fill.papermc.io build 132 | SHA-256 `5ffef465eeeb…` |
| Paper dev-bundle | repo.papermc.io `1.21.11-R0.1-SNAPSHOT` | paperweight 2.0.0-beta.24 |
| Protokol | `version.json` | **774** |

## 1. Sayısal çekirdek (`net.minecraft.util.Mth`)

- `sin(double)` = `SIN[(int)((long)(x * 10430.378350470453) & 65535L)]`. Önce **long'a kesme**
  (sıfıra doğru), sonra maske. `cos` aynı tablo, `+16384.0` ofsetli.
- Tablo `Math.sin` ile doldurulur (StrictMath değil). Bu JVM'de `Math.sin` ile `StrictMath.sin`
  65 536 değerin **2 197'sinde** double düzeyinde farklı sonuç verir. Ancak float'a çevrilince
  **0 fark** kalır. Her tablo değeri en yakın float yuvarlama sınırına en az **2 502 double-ulp**
  uzaktadır. Java şartnamesi `Math.sin` için en fazla 1 ulp hata tanıdığından, her uyumlu JVM'de
  (Windows/x86, macOS/ARM, Linux) tablo **bit-bit aynıdır**. Bu, `VanillaMathTest` içinde kalıcı
  bir kanıt testidir.
- `EPSILON = 1.0E-5F` (float). `Mth.equal(double,double)` bu **float** epsilon'u double'a
  genişleterek karşılaştırır, yani eşik 1e-5 değil `9.99999974737875E-6`'dır.
- `clamp(double)` = `x < min ? min : Math.min(x, max)`: NaN girişi NaN olarak geçer.
- `wrapDegrees` float sürümü float `%` ile çalışır; sonuç `[-180, 180)` aralığındadır.

## 2. Vektör (`net.minecraft.world.phys.Vec3`)

- `normalize()`: uzunluk `< 1.0E-5F` (genişletilmiş float) ise `ZERO` döner.
- `subtract` aslında `add(-x,-y,-z)` olarak yazılmıştır (IEEE'de birebir eşdeğer).
- `equals` `Double.compare` kullanır: `-0.0 ≠ 0.0`, `NaN == NaN`.
- `directionFromRotation` ve `Entity.calculateViewVector`: açı argümanları **float** aritmetiğiyle
  hesaplanır, bileşen çarpımları (`sin * cos`) **float çarpımıdır** ve ancak `Vec3`'e girerken
  double'a genişletilir. Çarpımı double'da yapan bir port 4e-12 mertebesinde sapar (mutasyon
  testiyle gösterildi).
- `Entity.getInputVector`: `lengthSqr < 1.0E-7` ise sıfır, `> 1.0` ise normalize, sonra
  `scale(speed)`. Yaw dönüşümünde `double * float` çarpımları kullanılır.

## 3. Fare → dönüş (client: `MouseHandler.turnPlayer` → `Entity.turn`)

Her **render karesinde** (tick'te değil) çalışır:

```
d = sensitivity * 0.6F + 0.2F        // double; 0.6F = 0.6000000238418579, 0.2F = 0.20000000298023224
e = d*d*d
çarpan = e * 8.0                     // dürbünle (spyglass, 1. şahıs) bakarken: e
dönüş = imleçDelta * çarpan          // double; invertMouse ise eksi
artış = (float) dönüş * 0.15F        // float çarpım
yaw  += artış                        // float toplama, asla sarılmaz (wrap edilmez)
pitch = Math.clamp((pitch + artış) % 360F, -90F, 90F)
```

Sonuçlar:
- %100 hassasiyet tam 1.0x değildir: çarpan `1.0000000894069698`'dir.
- Bir tick'teki dönüş, birden çok karenin float toplamıdır. Her toplama, o anki yaw büyüklüğünün
  çözünürlüğüne yuvarlanır. Dolayısıyla "GCD/kafes" yalnızca **yaklaşıktır**. Canlı E2E testte
  sayım toplamı 0 olan bir döngü sonunda yaw `0` yerine `-2.98e-8`'de kaldı.
- Sinematik kamera (`smoothCamera`) `SmoothDouble`'dan geçer ve kafes değildir. İmleç deltası
  GLFW double'ıdır, bazı platformlarda kesirli olabilir. Bunların her biri, masum oyuncuyu
  "aimbot" gibi gösterebilecek klasik yanlış-pozitif kaynaklarıdır.
- `Entity.setYRot/setXRot` sonlu olmayan (NaN/∞) değerleri yok sayar.

## 4. Client'ın gönderim kuralları (`LocalPlayer.tick` / `sendPosition`)

Tick içi sıra: `PlayerInput` (yalnızca tuşlar değiştiyse) → sprint `PlayerCommand` (değiştiyse) →
`PosRot | Pos | Rot | StatusOnly` (veya hiçbiri) → … → `ClientTickEnd`.
Saldırı/kullanım paketleri (`handleKeybinds`) aynı tick'in hareket paketinden **önce** gelir.

- Pozisyon yalnızca `lengthSquared(Δ) > (2.0E-4)²` ise **veya** 20 tick geçtiyse gönderilir.
  Yani vanilla client **2e-4'ten küçük hareketi hiç bildirmez**. Sunucu tarafında son bildirilen
  konum etrafında 2e-4 yarıçaplı, protokolün kendisinden gelen bir belirsizlik küresi vardır.
- Dönüş, float farkı `!= 0.0` ise gönderilir (her değişiklik).
- Binekteyken her tick `Rot` gönderilir; client bineği kontrol ediyorsa ek olarak `MoveVehicle`.

## 5. Ağ paketleri (protokol 774)

| Paket | 1.21.11 ayrıntısı |
|---|---|
| `ServerboundMovePlayerPacket` | bayrak baytı: bit0 onGround, bit1 horizontalCollision. Pos/Rot yoksa nesne `0.0` saklar. WhyNotAC bunları `NaN` olarak modeller. |
| `ServerboundPlayerInputPacket` | 7 bit: W,S,A,D,zıplama,eğilme,sprint. Client'ın **bastığı tuşlar**. |
| `ServerboundClientTickEndPacket` | client tick sınırı (1.21.2+). |
| `ServerboundPlayerActionPacket.Action` | `…, SWAP_ITEM_WITH_OFFHAND, STAB`: **STAB = 1.21.11 mızrak (spear) dürtmesi**. |
| `ServerboundPlayerCommandPacket.Action` | `STOP_SLEEPING, START/STOP_SPRINTING, START/STOP_RIDING_JUMP, OPEN_INVENTORY, START_FALL_FLYING` (eğilme artık Input paketinde). |
| `ServerboundUseItemPacket` | el, sequence, **yaw, pitch** (eylemdeki bakış). |
| `ServerboundUseItemOnPacket` | imleç ofsetleri **float**: konum = `(double)blokX + floatOfset`. |
| `ServerboundInteractPacket` | Paper `getEntityId()` / `isAttack()` ekler. |
| `ClientboundSetEntityMotionPacket` | **LpVec3** kayıplı kodlama (bkz. §6). Client hızını **değiştirir** (`lerpMotion` → set). |
| `ClientboundExplodePacket` | `Optional<Vec3> playerKnockback` tam double. Client hızına **ekler** (`addDeltaMovement`). Rüzgâr topu / mace Wind Burst yolu budur. |
| `ClientboundPlayerPositionPacket` | `PositionMoveRotation(pos, delta, yRot, xRot)` + `Set<Relative>`. |
| `ClientboundPingPacket` / `ServerboundPongPacket` | client pong'u **oyun thread'inde**, diğer paketlerle **aynı sırada** işler. Transaction sandviçi bu yüzden geçerlidir. |
| `ClientboundBundlePacket` | `packet_handler` önündeki bir handler bundle'ı bütün olarak görür. Alt paketleri kendisi açmalıdır. |
| `PacketType.id()` | 1.21.11'de `ResourceLocation` → **`Identifier`** olarak yeniden adlandırıldı. |

## 6. LpVec3 (knockback kuantizasyonu)

```
s = ceil(max|bileşen|)                     // 2 bit + s>3 ise VarInt devam
bileşen → round((c/s * 0.5 + 0.5) * 32766) // 15 bit
çözüm   = min(q, 32766) * 2 / 32766 - 1, sonra * s
max|bileşen| < 3.051944088384301E-5 → tek bayt 0 → ZERO
```

Eksen başına hata en fazla `s/32766`'dır (normal knockback için ≈ **3.05e-5**). Bu, 1e-6 analiz
ızgarasının ~30 katıdır. Bu yüzden fizik katmanı sunucunun hesapladığı değeri değil
`LpVec3.quantize(v)` değerini, yani client'ın gerçekten uyguladığı vektörü kullanır.
1 000 000 vektörde gerçek `net.minecraft.network.LpVec3` ile **bayt bayt** aynıdır.

## 7. Paper ağ hattı (pipeline)

`ServerConnectionListener` kanal başlatma sırası (head → tail):
`[FlushConsolidation] timeout, [legacy_query], splitter, FlowControl, decoder, prepender,
outbound_config→encoder, hackfix, packet_handler`. Ardından `ChannelInitializeListenerHolder.callListeners`
çağrılır (auto-read henüz kapalıdır). Protokol kurulumunda `bundler` "decoder"ın, `unbundler`
ise "encoder"ın arkasına eklenir. `Connection.channel` ve
`ServerCommonPacketListenerImpl.connection` `public`, `Connection.getPlayer()` mevcuttur.
`ChannelInitializeListenerHolder` Paper'ın **resmî olarak desteklenmeyen** iç API'sidir.
Sürüm geçişlerinde ilk kontrol edilecek yer burasıdır.
