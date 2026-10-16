# whynotac-physics — Katmanlı hipotez-kümesi fizik motoru (Faz 3)

**Sözleşme:** Bir oyuncunun zaman çizelgesinden (`TimelineEvent`) client'ın her tick'i için,
gözlenen kanıtla tutarlı **tüm durumların kümesini** üretir. Bir gözlem (bildirilen konum, yer
bayrağı, çarpışma bayrağı) bu kümenin dışına, bilgi sınırının ötesinde düşerse
`MovementEvidence` yayınlar. Hiçbir zaman ceza kararı vermez.

## Neden tek tahmin değil, küme?
Sunucu client'ın bazı durumlarını **bilemez**: 2e-4 altındaki gönderilmeyen hareket, knockback'in
iki transaction arasındaki hangi tick'te uygulandığı, kare/tick hizalaması. Tek bir "doğru tahmin"
seçmek bu belirsizliği yok sayar ve yanlış-pozitif üretir. Hipotez kümesi belirsizliği taşır ve
ancak **hiçbir** meşru hipotez gözlemi açıklayamadığında kanıt doğar.

## Katmanlar
1. **Girdi katmanı:** `PlayerInput` (gerçek tuşlar) → `forward/strafe` impulse'ları (×0.98),
   eğilme/eşya kullanma yavaşlatmaları, sprint kuralları.
2. **Ortam katmanı:** blok sürtünmesi (buz, mavi buz, slime, bal, ruh kumu), sıvılar (su/lav akışı,
   kabarcık sütunu), toz kar, örümcek ağı, tatlı çilek çalısı, merdiven/sarmaşık, kaynak
   blokları; client'ın gördüğü dünya (transaction onaylı blok güncellemeleri).
3. **Hareket katmanı:** `LivingEntity.travel` / `aiStep` bit-tam replikası: yerçekimi ve
   `gravity` özniteliği, sürüklenme 0.98/0.91, zıplama (`jump_strength`), adım (`step_height`),
   `movement_efficiency`, `water_movement_efficiency`, `scale`, VoxelShape çarpışmaları.
4. **Etki katmanı (transaction pencereli):** `SetEntityMotion` (LpVec3 kuantize, **set**),
   `Explode.playerKnockback` (tam double, **add**), teleportlar, efektler (levitation,
   slow falling, jump boost, speed, dolphin's grace).
5. **1.21.11 savaş mekanikleri:** Mace (düşüş mesafesi, smash, Wind Burst ile yukarı fırlatma),
   Wind Charge (patlama itmesi), Trident Riptide (yaw/pitch ve seviyeden hesaplanan itme, yağmur/su
   koşulu), Spear (`KineticWeapon`/`PiercingWeapon` bileşenleri, `LUNGE` büyüsü, `STAB` eylemi),
   Elytra (süzülme, roket itmesi), binekler.

Her formül `docs/reference/vanilla-1.21.11.md` gibi decompile edilmiş kaynaktan okunacak ve
`whynotac-conformance` içinde gerçek oyun koduna karşı diferansiyel test edilecek.

## Durum
- **Çarpışma çekirdeği (tamam):** `VoxelShape.collide`, `Shapes.collide`, `Shapes.joinIsNotEmpty` (birleştiricilerle),
  `Shapes.create`, `BlockCollisions`, `Entity.collide` (basamak çıkma dahil). `tools/realclient` içindeki
  `CollisionConformance`, bunları resmi client'ın kendi koduyla her CI koşusunda yeni bir tohumla, rastgele
  vakalarda bit bit karşılaştırır. Yerelde 200.000 denemede 3,4 milyon karşılaştırma yapıldı, 0 fark çıktı.
  Toleransa iki bilinçli hata eklendiğinde test 409 ve 234 fark buldu.
- **Bilinmeyen şekiller iddia üretmez:** dünyaya bağlı şekiller (hareketli piston, shulker kutusu),
  oyuncuya bağlı şekiller (toz kar, iskele), kaydırılmış şekiller ve bilinmeyen bloklar `Colliders.Unknown`
  fırlatır. Sıvılar oyuncu için boş şekildir.
- **Yaya hareket adımı (`OnFoot`, tamam):** bir `LocalPlayer.tick()` bit bit. Girdi vektörü ve kare düzeltmesi,
  eğilme (önceki tikin shift'i) ve kenardan geri çekilme (bu tikin shift'i), koşma başlatma ve durdurma, zıplama
  ve `noJumpDelay`, sürtünme bloğu (destek bloğundan), dürtü, `Entity.move` (çarpışma, basamak, bayraklar,
  destek bloğu, düşüş mesafesi, hız sıfırlama, blok hız çarpanı), yerçekimi ve sürüklenme, sonraki tikin duruşu.
  - **Doğrulama:** `tools/realclient/MovementTruth`, gerçek client'ın kaydettiği her tiki başındaki durumdan
    çalıştırır ve sonundaki durumla bit bit karşılaştırır. Her tik, client'ın o tikte gönderdiği hareket paketi
    kayıtta geldiğinde replikanın tuttuğu dünyada değerlendirilir. Koşu CI'da her oturumda tekrarlanır.
  - **Parkur:** gerçek client oturumu artık bir parkur da koşar:
    - basamak çıkma (yarım blok, blok, blok üstünde yarım blok) ve düşüş;
    - buzda yürüme, koşma ve kayma; ruh kumu;
    - açıyla yaklaşılan duvar;
    - platform kenarına eğilerek yürüme ve kenarda shift bırakıp inme;
    - alçak tavanın altında zıplama;
    - çift W ile koşma; çift W penceresinde basılan shift;
    - merdiven.
  - **Son oturum:** 1805 tikin 1803'ü bit bit eşit, 0 fark. 2 tik kapsam dışı (duvarda boğulma itmesi denetimi).
  - **Negatif kontroller (bilinçli hatalar, hepsi yakalandı):**

    | Değişiklik | Fark |
    |---|---|
    | Sürüklenme sabitinin 7. hanesi | 33 |
    | Buzun sürtünmesini yok saymak | 89 |
    | Ruh kumunun hız çarpanını yok saymak | 34 |
    | Kenardan geri çekilmeyi kaldırmak | 24 |
    | Hız sıfırlama eşiği | 2 |
    | Eğilme yavaşlatmasının, kenar korumasının ve koşma tetikleyicisinin shift zamanlaması | 1'er |
  - **Otomatik zıplama (`LocalPlayer.updateAutoJump`, tamam):** seçenek açıkken bir blok yüksekliğindeki basamağa
    yürüyen oyuncunun bir sonraki tikte zıplaması, client'ın geometrisiyle bit bit (bakış yönü, baş üstü boşluğu,
    ileriye süpürülen kutu, ilk kesişen kutunun yüksekliği). Gerçek client oturumu artık seçenek açık koşar; iki
    bacak tek bloklu basamağa önden ve açıyla yürür. **Son oturum:** 1956 tikin 1952'si bit bit eşit, 0 fark;
    otomatik zıplama 3 kez tetiklendi. Negatif kontroller: yükselme sınırı (1.2 → 0.9), karşılaştırma (`>` → `>=`)
    ve ileri bakış mesafesinin yarıya inmesi testi bozar; 0.51'lik çizgi yüksekliği bu düz basamakta ayırt edilmez.
  - **Efektler (`OnFoot.Effects`, tamam):** zıplama gücü (`getJumpBoostPower`, seviye başına `0.1F`; otomatik
    zıplamanın erişimi seviye başına `0.75F`), yavaş düşüş (`getEffectiveGravity`: yükselmezken yerçekimi en çok
    `0.01`; `aiStep` düşüş mesafesini sıfırlar), havada süzülme (`travelInAir`: dikey hız seviye başına `0.05`'e
    çekilir, yerçekiminin yerine) ve körlük (`isMobilityRestricted`: koşma başlamaz, süren durur). Gerçek client
    oturumu dört bacak koşar: zıplama gücü II ile koşarak zıplama ve iki blokluk duvara otomatik zıplama, yavaş
    düşüşle zıplama ve altı blokluk düşüş, bir saniyelik havada süzülme II, körken koşma tuşu ve çift W.
    **Son oturum:** 2557 tikin 2553'ü bit bit eşit, 0 fark; 504 tik efekt altında. Negatif kontroller (hepsi
    yakalandı): zıplama gücü `0.1F` → `0.11F` (3 fark), yavaş düşüş sınırı `0.01` → `0.02` (177), havada süzülme
    `0.05` → `0.06` (20), körlüğü yok saymak (22), düşüş mesafesini sıfırlamamak (55).
  - **Eşya kullanımı (`OnFoot.ItemUse`):** kullanılan eşyanın `use_effects`'i: girdi `speedMultiplier` ile
    çarpılır (`modifyInput`, 0.98'den sonra, eğilmeden önce); `canSprint` değilse koşma başlamaz ve çift W penceresi
    kapanır (`isSlowDueToUsingItem`), süren koşma sürer. Gerçek client oturumu sağ tuşla dört bacak koşar: koşarken
    ve yürürken yenen altın elma, kılıcın arkasından kaldırılan kalkan (koşma tuşu, çift W ve eğilme kalkan
    kalkıkken; W'ye dokunup kısa bir kalkan, sonra pencere içinde yine W), koşma başlarken şarj edilen mızrak, yürürken
    çekilen yay. **Son oturum:** 3163 tikin 3153'ü bit bit eşit, 0 fark; 200 tik eşya kullanılırken. Negatif
    kontroller (hepsi yakalandı): çarpan ×1.05 (197 fark), kullanırken koşma başlatabilmek (21), pencereyi
    kapatmamak (1), kullanımı yok saymak (156); çarpanın eğilmeyle sırası bu veride ayırt edilmez.
  - **Hız özniteliği:** `OnFoot.Speed` ile dışarıdan verilir (sunucunun gönderdiği taban ve değiştiriciler); tick
    içindeki `setSprinting` çağrıları onu da değiştirir (`Speed.setSprinting`, sonucu `Step.speed`).
    Koşma değiştiricisinin özniteliğde olup olmadığı (`PlayerState.sprintModifier`) bayraktan ayrı izlenir: sunucu
    ikisinden birini tek başına değiştirebilir (kendi bayrak ve öznitelik yankısı).
  - **Birim testi:** `OnFootTruthTest` oturumdan hareket eden tikleri yeniden oynatır: düz dünyada 195, kodla
    yeniden kurulan parkurda 544, otomatik zıplamalı basamaklarda 166, efekt altında 504, eşya kullanılırken 200,
    tırmanma alanında 499 tik.
  - **Tırmanma (`onClimbable`, `handleOnClimbable`):** ayaktaki blok tırmanılabilir (merdiven, sarmaşıklar) ya da
    aynı yöne bakan bir merdivenin üstünde açık bir tuzak kapı: hareket tik başına 0.15'e tutulur, düşüş mesafesi
    sıfırlanır, shift aşağı kaymayı durdurur; hareketten sonra yatay çarpışma ya da zıplama dikey hızı 0.2 yapar.
    İskele kapsam dışı (şekli oyuncuya bağlı).
  - **Bloktan itilme (`LocalPlayer.moveTowardsClosestSpace`):** kutunun dört köşesinden (genişliğin 0.35'i) biri
    boğan bir bloğun sütunundaysa (`collidesWithSuffocatingBlock`; blok verisindeki `SUFFOCATING`, client'ın kendi
    kaydından) oyuncu en yakın boş yana 0.1 itilir. Merdiven ve tuzak kapı boğmaz: onlara yaslanmak itilme değildir.
  - **Son oturum (tırmanma ve itilme bacaklarıyla):** 3635 tikin 3617'si bit bit eşit, 0 fark. Negatif kontroller
    (hepsi yakalandı): 0.15 → 0.16 (7 fark), tırmanışta 0.2'yi yok saymak (120), shift'le tutunmayı yok saymak (44),
    itilme 0.1 → 0.11 (1), merdiven üstü tuzak kapıyı yok saymak (18).
  - **Varlıkların itmesi:** tick'ler arasında olur (başka bir canlının tick'i oyuncunun hareketine ekler), adımın
    dışındadır: `MovementTracker` uygular (bkz. whynotac-analysis). Kayıt cihazı oyuncunun durumunu kendi tick'inin
    başında alır, itmeler dahil; adım bu yüzden onlarla da bit bit aynıdır. **Son oturum (köylülere yürüyen itme
    şeridiyle):** 4110 tikin 4093'ü bit bit eşit, 0 fark.
  - **Kapsam dışında `Colliders.Unknown`:**
    - sıvı ve içinde hareket etkisi olan bloklar (kabarcık sütunu, bal, ağ, böğürtlen, toz kar), iskele;
    - slime ve yatak sekmesi; çit ve duvar üstü;
    - yüklenmemiş chunk, sürünme;
    - bilinmeyen düşüş mesafesinin (NaN) okunduğu her yer.

    Varsayılan öznitelikler (hız dışında) varsayılır; adımın okuduğu efektler (`OnFoot.Effects`: zıplama gücü,
    havada süzülme, yavaş düşüş, körlük) dışarıdan verilir. Katı varlıklar ve dünya sınırı çağıranın
    sorumluluğundadır.
- **Sunucu tarafı kontrol (`MovementCheck`, P-M, SHADOW):** `whynotac-analysis` içinde; bkz. o modülün README'si.
