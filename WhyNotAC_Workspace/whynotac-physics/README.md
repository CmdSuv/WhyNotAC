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
   Elytra (süzülme ve roket itmesi: tamam), binekler.

Her formül `docs/reference/vanilla-1.21.11.md` gibi decompile edilmiş kaynaktan okunacak ve
`whynotac-conformance` içinde gerçek oyun koduna karşı diferansiyel test edilecek.

## Durum
- **Çarpışma çekirdeği (tamam):** `VoxelShape.collide`, `Shapes.collide`, `Shapes.joinIsNotEmpty` (birleştiricilerle),
  `Shapes.create`, `BlockCollisions`, `Entity.collide` (basamak çıkma dahil). `tools/realclient` içindeki
  `CollisionConformance`, bunları resmi client'ın kendi koduyla her CI koşusunda yeni bir tohumla, rastgele
  vakalarda bit bit karşılaştırır. Yerelde 200.000 denemede 3,4 milyon karşılaştırma yapıldı, 0 fark çıktı.
  Toleransa iki bilinçli hata eklendiğinde test 409 ve 234 fark buldu.
- **Bilinmeyen şekiller iddia üretmez:** dünyaya bağlı şekiller (hareketli piston, shulker kutusu),
  oyuncuya bağlı şekiller (toz kar ve iskele yalnızca oyuncunun bağlamı verilmezse; toz kar botlar bilinmeden üstündeyken),
  kaydırılmış şekiller ve
  bilinmeyen bloklar `Colliders.Unknown` fırlatır. Sıvılar oyuncu için boş şekildir.
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
    tırmanma alanında 499, sekme alanında 756, iç-blok şeritlerinde 1588, lav şeritlerinde 494, Nether'de 396,
    iskele şeridinde 491 tik.
  - **Tırmanma (`onClimbable`, `handleOnClimbable`):** ayaktaki blok tırmanılabilir (merdiven, sarmaşıklar) ya da
    aynı yöne bakan bir merdivenin üstünde açık bir tuzak kapı: hareket tik başına 0.15'e tutulur, düşüş mesafesi
    sıfırlanır, shift aşağı kaymayı durdurur; hareketten sonra yatay çarpışma ya da zıplama dikey hızı 0.2 yapar.
  - **İskele (`ScaffoldingBlock.getCollisionShape`):** şekli oyuncunun bağlamından gelir (`Colliders.Context`: bağlamın
    kurulduğu andaki, yani hareketten önceki ayak yüksekliği ve shift). Ayaklar bloğun tepesinin 1.0E-5F altından
    yüksekse ve shift basılı değilse kararlı şekil (tablodaki: boş bağlam "üstte" sayılır); değilse, desteklenmeyen
    bir alt blokta (`distance` 0 değil, `bottom`) ayaklar tabanın 1.0E-5F altından yüksekse tabanın levhası
    (`Block.column(16, 0, 2)`, iki katlı karın çarpışmasıyla aynı çağrı); değilse boş. Çarpışma, destek bloğu ve
    `noCollision` aynı bağlamı okur. İskele tırmanılabilir; içinde shift aşağı kaymayı durdurmaz, oyuncu shift ile
    iner. Boğma denetimi iskeleyi atlar (boş bağlamdaki şekli tam blok değildir). **Son oturum (yerde beş blokluk bir
    kule ve tepesinden asılı üç blokluk bir köprü: kuleye yürüyüp içinde zıplayarak tepesine tırmanma; köprünün
    üstünden shift ile asılı bloğun tabanına inip orada yürüme; asılı bir bloğun içinden tabanına düşme, orada shift
    ile durma ve yana yürüyüp düşme; kulenin tepesinden shift ile yere kadar inme):** şeridin 491 tikinin hepsi bit
    bit eşit: 303'ü iskelenin içinde, 119'u kulenin tepesinde, 159'u asılı bir bloğun tabanında, 34'ü tırmanma, 39'u
    shift ile iniş. Tüm oturum: 9467 tikin 9408'i eşit, 0 fark. Negatif kontroller (hepsi yakalandı): inerken de
    kararlı şekil, levhayı yok saymak, levhayı her blokta vermek (desteklenen ya da `bottom` olmayan), levhayı aşağıdan
    da vermek, tepe eşiğinden 1.0E-5F'yi düşürmek, iskelede shift ile kaymayı durdurmak, iskeleyi tırmanılmaz saymak,
    iskeleyi hep kararlı saymak, bağlama shift'i vermemek. Tepe eşiğinde `>` ile `>=` bu veride ayırt edilmez (ayaklar
    tepe ile onun 1.0E-5F altı arasında hiç durmaz).
  - **Bloktan itilme (`LocalPlayer.moveTowardsClosestSpace`):** kutunun dört köşesinden (genişliğin 0.35'i) biri
    boğan bir bloğun sütunundaysa (`collidesWithSuffocatingBlock`; blok verisindeki `SUFFOCATING`, client'ın kendi
    kaydından) oyuncu en yakın boş yana 0.1 itilir. Merdiven ve tuzak kapı boğmaz: onlara yaslanmak itilme değildir.
  - **Son oturum (tırmanma ve itilme bacaklarıyla):** 3635 tikin 3617'si bit bit eşit, 0 fark. Negatif kontroller
    (hepsi yakalandı): 0.15 → 0.16 (7 fark), tırmanışta 0.2'yi yok saymak (120), shift'le tutunmayı yok saymak (44),
    itilme 0.1 → 0.11 (1), merdiven üstü tuzak kapıyı yok saymak (18).
  - **Sekme (`updateEntityMovementAfterFallOn`, `stepOn`):** dikey çarpışmadan sonra ayağın altındaki blok
    (`getOnPosLegacy`) dikey hızı sıfırlar; slime bloğu düşen oyuncuyu geri sektirir (hız tersine, canlı için çarpan
    1.0), yatak da 0.66'sıyla. Shift sekmeyi bastırır (`isSuppressingBounce`); yukarı doğru hızı sekme blokları olduğu
    gibi bırakır. Yerde, slime bloğunun adımı dikey hızı 0.1'in altındaki ve sinsi yürümeyen oyuncuyu yatayda
    `0.4 + |y|·0.2` ile yavaşlatır. **Son oturum (slime pedi ve yatakla):** 4856 tikin 4838'i bit bit eşit, 0 fark.
    Negatif kontroller (hepsi yakalandı): slime sekmesi ×0.99 (61 fark), yatak 0.66 → 0.67 (94), slime adımı
    0.4 → 0.41 (18), shift'in sekmeyi bastırmasını yok saymak (77), shift'in yavaşlamayı durdurmasını yok saymak (34).
  - **Varlıkların itmesi:** tick'ler arasında olur (başka bir canlının tick'i oyuncunun hareketine ekler), adımın
    dışındadır: `MovementTracker` uygular (bkz. whynotac-analysis). Kayıt cihazı oyuncunun durumunu kendi tick'inin
    başında alır, itmeler dahil; adım bu yüzden onlarla da bit bit aynıdır. **Son oturum (köylülere yürüyen itme
    şeridiyle):** 4110 tikin 4093'ü bit bit eşit, 0 fark.
  - **Çit, duvar ve çit kapısı üstü (`getOnPos`):** destek bloğu çitse ve uzaklık en çok 0.5'se, duvar ya da çit
    kapısıysa her uzaklıkta, altındaki blok olarak bloğun kendisi okunur (`BlockTags.FENCES`, `BlockTags.WALLS`,
    `FenceGateBlock`; 1.21.11'de tam olarak bu adlarla biten bloklar, istemcinin etiket dosyalarıyla karşılaştırıldı).
    1.5 yüksekliğindeki şekiller çarpışma çekirdeğinin "büyük şekil" yolundan geçer. **Son oturum (sunucunun bağladığı
    çit hattı, tek direk, alçak duvar hattı ve kapalı kapılar; üstünde yürüme, kenara sinsi yürüme, zıplayarak düşme,
    yerden çite yürüyüp zıplama):** şeridin 590 tikinin hepsi bit bit eşit, 318'i bir çitin, duvarın ya da kapının
    üstünde biter. Tüm oturum: 5458 tikin 5440'ı eşit, 0 fark. Eski davranış (bu bloklarda `Unknown`) testi düşürür:
    268 tik, üstte hiç.
  - **Su (`baseTick`, `travelInWater`):** tikin başında `updateIsUnderwater` bir önceki tikin gözünü okur
    (`LocalPlayer.isUnderWater`), `updateFluidHeightAndDoFluidPushing(WATER, 0.014)` kutunun (0.001 içeri) hücrelerinde
    suyun kutunun dibinden yüksekliğini ve akışların ortalamasını bulur (yükseklik 0.4'ün altındaysa onunla ölçeklenir;
    oyuncunun itmesi normalleştirilmez, yavaşken en az 0.0045), `updateFluidOnEyes` gözün hücresindeki suyun üstünü,
    `updateSwimming` yüzmeyi. Akış `FlowingFluid.getFlow` birebir: alçak komşuya doğru (geçilebilir komşunun altındaki suya
    da), düşen suda sağlam bir yüzün (ya da üstündekinin) yanında aşağı; sağlam yüzler istemciden dökülen tablodan
    (`STURDY_*`, Paper'ın kayıt defteriyle de aynı). Sığ suda koşu başlamaz ve durur, yüzen koşu `shouldStopSwimSprinting`
    ile durur, shift suda batırır (−0.04), zıplama su yüksekliği eşiği (0.4) aşınca sıvıda zıplar (+0.04). Yüzerken bakışın
    eğimi dikey hızı çeker (`Player.travel`). `travelInWater`: yavaşlama 0.8 (koşarken 0.9, yunus lütfuyla 0.96), girdi 0.02,
    tırmanılabilir duvarda 0.2, `getFluidFallingAdjustedMovement`, duvarda sudan çıkış (0.3). Hareket sırasında suya girilirse
    `checkFallDamage` suyu yeniden bulur ve iter. Su dışındaki yüzme duruşu sürünmedir: girdi sinsi yürüme gibi yavaşlar. **Son oturum (üç blok derin havuz: içine
    yürüme, zıplayarak geçme, shift'le batma, su altında yüzme (düz, aşağı, yukarı, yunus lütfuyla), duvarından çıkma;
    akıntısına karşı yürünen, içinde durulan ve koşulan bir kanal; duvar dibinde bir şelale; bir blok yüksek tünelden
    sürünerek çıkma):** şeritlerin 1077 tikinin hepsi bit bit eşit: 849'u suda, 153'ü yüzerek, 605'inde göz suda, 170'i
    kanalın akıntısında, 142'si şelalede, 123'ü sürünerek. Tüm oturum: 6531 tikin 6504'ü eşit, 0 fark. Negatif kontroller
    (hepsi yakalandı): yavaşlama 0.8 → 0.81, itme 0.014 → 0.015, düşen suyun aşağı akışı yok, yüzmede bakışın çekişi yok,
    sürünmede yavaşlama yok, hareket içinde suyun yeniden bulunması yok, batma 0.04 → 0.05, sıvıda zıplama 0.04 → 0.05,
    yunus lütfu 0.96 → 0.95, sığ suda koşu.
  - **İçeriden etki eden bloklar (`checkInsideBlocks`):** tikin sonunda (`applyEffectsFromBlocks`) kutu (1.0E-5F içeri)
    tikin hareketleri boyunca süpürülür: `Entity.move`'un kaydettiği her hareket, istenen hareket varsa eksen eksen
    (`Direction.axisStepOrder`: önce y, sonra x ve z'den büyüğü sona), hareket yoksa tikin başladığı yerden. Hücreler
    `BlockGetter.forEachBlockIntersectedBetween`'in sırasıyla gelir (`InsideBlocks`): başlangıç kutusunun hücreleri (adım
    0), bir köşeden çıkan ışının ızgara yürüyüşü (`addCollisionsAlongTravel`, `AABB.clip`, oyunun tuhaf köşe seçimi
    `getFurthestCorner` dahil), bitiş kutusunun hücreleri; hareket başına 16 adım. Her blok bir tikte bir kez, karşılaşma
    sırasıyla etki eder: örümcek ağı bir sonraki hareketi tutar (`makeStuckInBlock` 0.25, 0.05, 0.25; dokuma efektiyle
    0.5, 0.25, 0.5; düşüş unutulur), tatlı çilek çalısı da (0.8, 0.75, 0.8; yaş 0 dışında sunucuda can yakar), bal
    yanından düşeni yavaşlatır (`isSlidingDown`, `doSlideMovement`), kabarcık sütunu kutunun değdiği hücrede yukarı
    iter ya da aşağı çeker (üstü boşsa güçlü: `onAboveBubbleColumn`, değilse `onInsideBubbleColumn`). Başka hiçbir bloğun
    `entityInside`'ı client'ın hareketini değiştirmez (kaktüsün ve kamp ateşinin hasarı sunucudadır). **Son oturum
    (iki blok yüksek ağlara yürüme, koşma, zıplama, beş bloktan düşme, dokuma efektiyle yine; çalılardan yürüyerek,
    koşarak, zıplayarak geçme; bal sütununun yanından kayma ve ona zıplama; cama çevrili, ruh kumu üstünde yükselen
    (açık tepesinden fırlayıp geri düşme, shift, zıplama) ve magma üstünde aşağı çeken sütunlar; dört blok derin bir
    havuzda yan yana yükselen ve çeken sütun sıraları arasından yüzme ve koşarak yüzme):** şeritlerin 1588 tikinin
    hepsi bit bit eşit: 233'ünde ağ, 134'ünde dokumalı ağ, 147'sinde çalı tutuyor, 18'i bal kayması, 92'si yükselen
    sütunda, 81'i onun üstünde, 112'si çeken sütunda, 66'sı havuzun sütunları arasında. Tüm oturum: 8097 tikin 8054'ü
    eşit, 0 fark. Negatif kontroller (hepsi yakalandı): ağ 0.25 → 0.26, dokumayı yok saymak, çalı 0.8 → 0.81, bal
    kayması −0.05 → −0.06, kenar mesafesi 0.4375 → 0.45, üstte itiş 0.1 → 0.11, içte itiş 0.06 → 0.07, çekiş 0.03 →
    0.04 (içte ve üstte), kutunun değmesini yok saymak, üstteki suyu yok saymak, ağda düşüşü sıfırlamamak, yalnız bitiş
    kutusunu taramak, bir bloğun tikte iki kez etki etmesi, hareketi eksenlere bölmemek. Bu veride ayırt edilmeyenler:
    x ile z'nin sırası (yatay hareket hep x yönünde), hızlı bal kaymasının eşiği (−0.13'e yakın düşüş yok) ve 16 adımlık
    bütçe (yaya hareketi tikte 16 hücreyi geçmez).
  - **Lav (`travelInLava`):** tikin başında yükseklikler unutulur, suyun akıntısından sonra lavınki aynı yordamla
    bulunur; itişi boyutun lav hızıyla ölçeklenir (`gameplay/fast_lava` ortam özniteliği: Nether'de 0.007, başka yerde
    0.0023333333333333335; boyut tipinin özniteliklerinden, giriş ve yeniden doğuş paketiyle kayda girer, bir zaman
    çizelgesi onu değiştirebiliyorsa bilinmez). Akış `FlowingFluid.getFlow` lav için de birebir (aynı türden komşular,
    lavın kendisi sağlam yüz değil). Lavdayken düşüş tikin başında yarılanır. `travelInLava`: girdi 0.02, hareket, lav
    yüksekliği zıplama eşiğini aşmıyorsa 0.5, 0.8, 0.5 ve `getFluidFallingAdjustedMovement`, aşıyorsa 0.5; yerçekiminin
    dörtte biri; duvarda lavdan çıkış. Zıplama, lavda ve eşiğin üstündeyse sıvıda zıplar (+0.04). **Son oturum
    (Overworld: üç blok derin, cam çevrili bir lav havuzuna yürüme, zıplayarak geçme, içinde zıplama ve koşma; akıntısı
    üç blok giden bir kanala akıntıya karşı yürüme, içinde durma ve zıplama. Nether'de bir cam kabuk içinde aynısı,
    kanalda hızlı lav sekiz blok akar):** Overworld şeritlerinin 494 tikinin hepsi bit bit eşit (406'sı lavda, 129'u
    akıntıda), Nether'in 396 tikinin hepsi de (307'si lavda, 131'i hızlı akıntıda). Tüm oturum: 8944 tikin 8889'u eşit,
    0 fark. Negatif kontroller (hepsi yakalandı): derin lavda 0.5 → 0.51, sığ lavda 0.8 → 0.81, yerçekiminin beşte biri,
    yavaş itiş 0.0024, hızlı itiş 0.0071, Nether'i yavaş saymak, lavda zıplama 0.05, düşüşü yarılamamak, girdi 0.025,
    lavı hava gibi geçmek. Eşikte `<=` ile `<` bu veride ayırt edilmez (lav yüksekliği tam 0.4 olmaz).
  - **Eşdeğer durumlar (`OnFoot.sameForMovement`):** adımın her yerde aynı okuduğu blok durumları: aynı çarpışma
    geometrisi, bayraklar, sürtünme, hız ve zıplama çarpanları, hiçbirinde sıvı yok. Adımın ya da `Colliders`'ın adıyla
    okuduğu bloklar (tırmanılabilir, içinde etkili, slime, yatak, tuzak kapı, çit, duvar, su, lav, hareketli piston)
    yalnızca aynı durumla eşdeğerdir. Client'ın henüz işlemediği bir değişiklik eşdeğer durumlar arasındaysa blok
    bilinir. `SameForMovementTest` çimen/toprak, tarla nemi ve yaprak mesafesi gibi eşdeğerleri ister; delik, yol
    bloğu, çiğnenen tarla, buz, eriyen buz, sarmaşık ve sıvı tutan yaprak eşdeğer değildir. `MovementTruth` de
    yalnızca client'ın işlediği kanıtlanmış dünyayı okur: paketi gönderen tick'te takipçinin okuduğu gibi; ondan
    önceki gönderimsiz tick'lerde ise, önceki paketin geldiği anda yanıtlanmış son transaction'dan sonra değişen
    blok, chunk ya da görüş aralığı bilinmez. Uzak bir ışınlamadan sonra client chunk'ı işlemeden bir tick
    düşebilir. Destek bloğu hâlâ eski, yüklü chunk'ta olduğu için yerçekimi normaldir. Motor bu tick'i artık
    kanıtsız karşılaştırmaz.
  - **Elytra ile süzülme (`travelFallFlying`, `updateFallFlyingMovement`):** `LocalPlayer.aiStep` yeni bir zıplama
    basışında (önceki tikte tuş basılı değilken), tırmanmıyorken `tryToStartFallFlying`'i dener: zaten süzülmüyor,
    havada, binekte değil, havada süzülme efekti yok, kuşanılmış bir süzülme eşyası var (`Conditions.glider`:
    `canGlideUsing`, kırılmak üzere değil), suda değil. Başarınca client `START_FALL_FLYING` gönderir; bayrak ve duruş
    (`FALL_FLYING`: 0.6 × 0.6, göz 0.4) aynı tikte geçer (`Step.startedGliding`). Hareket sıvılardan sonra
    `travelFallFlying`'dir: tırmanılabilir bir blokta (`can_glide_through` dışında: sarmaşıklar) süzülme biter ve tik
    havada geçer; değilse bakış vektörü, eğimin `Math.cos`'unun karesi, alçalma, tırmanma (`3.2`), bakışa doğru yatay
    yaklaşma ve `0.99F, 0.98F, 0.99F`, client'ın sırasıyla. Süzülürken blok hız çarpanı 1, sudan çıkmışken koşma
    başlamaz, `checkFallDistanceAccumulation` dikey hız −0.5'in üstündeyken düşüşü 1'e indirir. Süzülmeyi yalnızca
    sunucunun ortak bayrakları (7. bit) bitirir: client yere değdikten sonra da süzülerek kayar. Sıvıya süzülmek kapsam
    dışıdır. Roket itmesi adımın dışındadır: oyuncuya bağlı roket oyuncudan sonra tick'ler (`FireworkRocketEntity.tick`)
    ve hareketine bakışına doğru ekler; takipçi bunu adımın bıraktığı duruma uygular (`MovementTracker.rocketBoost`).
    - **Platformun kosinüsü (`PlatformCos`):** oyun burada tablo değil `Math.cos` kullanır ve HotSpot onu CPU'ya
      göre bir intrinsic yapar. `X86_64` (`stubGenerator_x86_64_cos.cpp`, Intel'in libm'i: π/32 indirgemesi, 64
      girişli tablo, SSE şeritleri) ve `AARCH64` (`macroAssembler_aarch64_trig.cpp`: fdlibm çekirdekleri, fused
      multiply-add ile) komut komut aktarıldı, `FDLIBM` `StrictMath.cos`'tur. [−π/2, π/2]'deki her float eğimde
      (2 140 282 808 girdi) `X86_64` bu makinenin x86_64 HotSpot'uyla, `AARCH64` QEMU altında koşan aarch64 HotSpot
      (Temurin 21.0.13) ile bit bit aynıdır. Platformlar birbirinden farklıdır: x86_64 fdlibm'den 1 397 840, aarch64
      239 002, ikisi birbirinden 1 362 054 girdide. Adım platformu dışarıdan alır (`Conditions.cosine`, varsayılan
      `X86_64`); süzülen bir tik bunu okuduğunu söyler (`Step.readCosine`).
    - **Düşüşü sıfırlayan ışın (`Entity.move`):** düşerken bir blok ya da daha uzun bir hareket, ayaktan hareket
      boyunca (en çok 8) `FALLDAMAGE_RESETTING` bloklarına (`fall_damage_resetting`: tırmanılabilirler, çalı, ağ; tam
      blok) ve suya bir ışın atar, değerse düşüş sıfırlanır. Işının geçebileceği her hücrede (hareketin sınırları
      içinde) ikisi de yoksa ışın hiçbir şeye değmez ve adım sürer; varsa `Unknown`. Önceden böyle her hareket
      `Unknown`'du: süzülmeler ve 1 bloğu aşan her düşüş.
    - **Son oturum (göğüste elytra; 30 blok yukarıdan 20° aşağı bakarak düşüp havada zıplamayla süzülme ve yere
      iniş; yerden zıplayıp tepesinde ikinci basışla kısa bir süzülme; W basılıyken 20 bloktan süzülme; x86_64'ün
      kosinüsünün fdlibm'inkinden ayrıldığı 34.2°'lik dalış, süzülürken koşma tuşlarıyla; 15° yukarı bakarak süzülme;
      10° aşağı bakarak süzülürken bir roket):** altı süzülmenin 272 tikinin hepsi bit bit eşit (roketin ittiği tikler
      dahil: itme bir sonraki tikin başlangıç durumundadır). Tüm oturum: 12 094 tikin 11 916'sı eşit, 0 fark. Negatif
      kontroller (beş süzülmelik bir oturumda, hepsi yakalandı): sürüklenme 0.99 → 0.98 (162 fark), kaldırma 0.75 → 0.76 (162), alçalma 0.1 → 0.11 (162), bakışa
      dönüş 0.1 → 0.11 (162), tırmanma 3.2 → 3.3 (13), düşüşü 1'e indirmemek (96), basılı tutulan zıplamayla ya da yerde
      başlamak (1'er), süzülme duruşunu almamak (162), süzülürken koşmaya başlayabilmek (18), x86_64 yerine fdlibm'in
      kosinüsü (17). Süzülürken blok hız çarpanı bu veride ayırt edilmez (yer çimen); `GlideTest` ruh kumunda ister.
  - **Kapsam dışında `Colliders.Unknown`:**
    - su ve lav dışındaki sıvılar, ayağında deri bot olup olmadığı bilinmeyen oyuncunun üstündeki toz kar, düşen
      bir sıvının ya da bir kabarcık sütununun yanında/üstünde şekli dünyaya bağlı bir blok, hızı bilinmeyen bir boyutta
      lav akıntısı;
    - yüklenmemiş chunk (suyun yakınında, client'ın tutup tutmadığı kesin olmayan chunk da), su hareket verimliliği
      özniteliği (derinlik gezgini);
    - bilinmeyen düşüş mesafesinin (NaN) okunduğu her yer.

    Varsayılan öznitelikler (hız dışında) varsayılır; adımın okuduğu efektler (`OnFoot.Effects`: zıplama gücü,
    havada süzülme, yavaş düşüş, körlük, yunus lütfu, dokuma) dışarıdan verilir. Katı varlıklar çağıranın
    sorumluluğundadır.
  - **Dünya sınırı (`Border`, `Conditions.border`):** `Entity.collectColliders` sınırın şeklini, oyuncu ona yakınken
    (`isInsideCloseToBorder`: kutunun büyük yatay boyunun iki katından yakın, en az 1, ve o kadar içeride) varlıklardan
    sonra, bloklardan önce ekler; `noCollision` da onu sayar (kenardan geri çekilme, duruşa sığma, sudan çıkış), destek
    bloğu ve boğulma saymaz. Şekil `Shapes.join(INFINITY, box(floor(minX), -∞, floor(minZ), ceil(maxX), ∞,
    ceil(maxZ)), ONLY_FIRST)`'tür: sınırın kutusu tam bloğa yuvarlanır, dışarısı dolu; kutusu inceyse her yer. Sınırlar
    `getMinX()` ve diğerleridir (kısmi tik 0, hareket eden sınırda bir önceki boyut, mutlak en büyüğe kırpılmış).
    `WorldBorderConformanceTest` (whynotac-paper) sınırları, şekli hücre hücre, uzaklığı, içerde olmayı ve bir varlığın
    yakın olmasını (`isInsideCloseToBorder`), oyuncu
    kutularının şekille `collide`'ını ve `joinIsNotEmpty`'sini oyunun kendi `WorldBorder`'ına karşı, lerp'in her
    adımında bit bit sınar. Gerçek client (truth ajanı client'ın sınırını hareketin gördüğü haliyle kaydeder): 20
    genişliğinde sabit sınıra yürüme, zıplama, kenarı boyunca ve köşesine koşma, sonra ona yaslıyken 80 tikte büyüyen
    ve geri çekilirken 60 tikte küçülen sınır: sınırın yanındaki 321 tikin hepsi bit bit eşit, 196'sı sınıra çarpan.
    Sınır verilmeyen motor tam o 196 tikte ayrılır.
  - **Toz kar (`Colliders.powderSnow`, `Conditions.leatherBoots`):** `PowderSnowBlock.getCollisionShape` oyuncuya göre:
    2.5'ten fazla düşmüşse 0.9 yüksekliğinde düşme levhası; değilse ayağında deri bot (`canEntityWalkOnPowderSnow`:
    oyuncu `powder_snow_walkable_mobs` değildir) varken, bloğun üstündeyse (`entityBottom > y + 1 - 1.0E-5F`) ve
    inmiyorsa tam blok (`Shapes.block()`'un kendisi: `BlockCollisions` onu kutu olarak sınar); yoksa hiçbir şey. Boğmaz.
    `checkInsideBlocks` onun içine şekli boşken ya da tam blokken hemen, düşme levhasındayken yalnızca hareket o levhaya
    değerse girer (`collidedWithShapeMovingFrom`); girişte `FREEZE` `isInPowderSnow`'u açar, ayakların bloğu toz karsa
    (`getInBlockState`) oyuncuyu tutar (`makeStuckInBlock(0.9F, 1.5, 0.9F)`, düşüş sıfırlanır). Bir sonraki tik
    `wasInPowderSnow` olur: botluysa ve çarpışıyor ya da zıplıyorsa hız 0.2 ile tırmanır. Donma ve yavaşlaması sunucunun
    (`tryAddFrost`): hıza özniteliğiyle gelir. Bot bilinmiyorsa yalnızca kutunun değdiği, sonucu botlara bağlı bir toz
    kar `Unknown`'dır. `PowderSnowConformanceTest` (whynotac-paper) şekli düşüş, ayak yüksekliği, shift ve botlara göre
    hücre hücre ve kutu kutu, `Shapes.block()` olup olmadığını ve bir hareketin içine girip girmediğini oyunun kendi
    bloğuyla ve oyuncu sınıfıyla, dünyanın uzak köşelerinde bit bit sınar (Paper'ın `Shapes.block()`'u bir
    `ArrayVoxelShape`'tir, client'ınki `CubeVoxelShape`: hücreleri, koordinatları ve kutusu aynı). Gerçek client (truth
    ajanı `isInPowderSnow`'u ve ayaktaki deri botu da kaydeder; donmanın hızı kaydedilen hızdan çözülür:
    `RecordedSpeed`, 0.1F + donma, koşma değiştiricisiyle ya da değil): bir derin şerit ve iki derin çukurda botsuz
    batma ve duvara karşı zıplama, beş bloktan düşüş, deri botla üstünde yürüme ve koşma, shift ile batıp duvarda
    tırmanma: toz kardaki 511 tikin hepsi bit bit eşit, 282'si donmuşken. `truth-powdersnow.jsonl.gz`
    (`OnFootTruthTest`) bu oturumun 712 tikidir: 507'si toz karda, 505'i tutulmuş, 99'u botla üstünde, 14'ü botla
    tırmanan, 2'si düşme levhasına iniş, 282'si donmuş. Negatif kontroller (15'i de yakalandı): levha 2.5'te başlasın,
    üst sınır dahil olsun, inmek ya da botlar yok sayılsın, kutunun değmediği hücre de atlansın, her zaman girilsin,
    ayaklar içinde değilken tutsun, `isInPowderSnow` açılmasın, bu tikin bayrağıyla ya da botsuz tırmansın; envanter
    zırh slotunu yazmasın, yer değiştirmede ayağı korusun, kendi ekipman paketini yok saysın; takipçi botları vermesin ya
    da hep var saysın.
- **Sunucu tarafı kontrol (`MovementCheck`, P-M, SHADOW):** `whynotac-analysis` içinde; bkz. o modülün README'si.
