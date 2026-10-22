# whynotac-analysis — Kontroller ve davranış dedektörleri

**Sözleşme:** Zaman çizelgesi ve fizik kanıtları üzerinde çalışan, her biri bir **istatistik ve
bilgi sınırı** ile birlikte `Evidence` üreten dedektörler. Cezalandırmaz.

## Mevcut kontroller (hepsi SHADOW)

| Kimlik | Sınıf | Ne kanıtlar |
|---|---|---|
| `tick-framing` | P-M | Bir client tick'inde vanilla'nın gönderebileceğinden fazla hareket paketi |
| `client-clock` | P-M | Client'ın kendi saatinin izin verdiğinden fazla tick |
| `attack-reach` | P-A | Hiçbir vanilla client'ın erişemeyeceği bir varlığa saldırı |
| `attack-aim` | P-M | Tikin bakışının kutusuyla hiç kesişemeyeceği bir varlığa saldırı (bakmadan vuran ya da tek bakışla birden çok varlığa vuran client) ya da bakışın ancak onu kesin durduran bir bloğun ardında değdiği varlığa saldırı (duvar arkasından vuran client) |
| `action-grammar` | P-M | Ardından ana elin sallanması gelmeyen saldırı; tikin kendi hareket paketinden sonra gelen saldırı, eşya kullanma, blok kırma ya da hotbar seçimi; sunucunun açtığı bir menü ekrandayken eylem ya da yürüme tuşu |
| `movement` | P-M | Yaya hareket: hiçbir vanilla client'ın gönderemeyeceği konum, bayrak ya da koşma/girdi paketi; eşya kullanırken saldırı, etkileşim ya da yeni kullanım |

### `action-grammar` (`ActionGrammarCheck`)

- **Client'ın kuralı (1.21.11, decompile edilmiş client):** client'ın eylemleri tikin başında gelir. Saldırı ve
  varlıkla etkileşim (`ServerboundInteractPacket`), bloğa ve havaya eşya kullanma (`UseItemOn`, `UseItem`), blok kırma,
  kullanılan eşyayı bırakma, eşya atma, el değiştirme ve mızrak dürtmesi (`PlayerAction`) ile hotbar seçimi
  (`SetCarriedItem`): her birinin tek göndericisi var. Her gönderici yalnızca `Minecraft.handleKeybinds`'te (tuşların
  sıradaki tıkları, basılı tutulan kullanma tuşu), hotbar seçimi ayrıca tikin en başında çalışır. Bunların hepsi tikin
  hareket paketini gönderen `tickEntities`'ten önce gelir.
- **Saldırıdan sonra el sallama:** saldırıdan hemen sonra ana el sallanır (`Swing(MAIN_HAND)`; saldırıda oyuncu
  izleyiciyse sallanmaz, dürtmede hep sallanır). Arada yalnızca client'ın ağ iş parçacığının kendi yanıtları
  (`KeepAlive`, `ChunkBatchReceived`) olabilir.
- **İki kanıt:** dürtmeden ya da izleyici olamayan oyuncunun saldırısından sonraki ilk paket ana elin sallanması
  değil; ya da aynı tikte, yalnızca tikin kendisinin olabilecek bir hareket paketinden sonra bu eylemlerden biri
  (hareketten sonra saldıran ya da blok koyan, yani "post" sırasıyla çalışan bir client). Işınlama, döndürme ve varlık
  ışınlaması yanıtları tick-framing'in haklarıyla (`MoveReplies`) ayrılır. İkinci kural tikin başlangıcını ister: ilk
  `ClientTickEnd`'den önce iddia yoktur. Bu build'in tanımadığı bir `PlayerAction` iddia dışıdır.
- **Menü ekrandayken (`MenuScreen`):** `handleOpenScreen` vanilla bir menü tipinin ekranını her zaman açar; ekran,
  client kendisi kapatana (her yolu `ContainerClose` gönderir: escape ya da envanter tuşu, hata ayıklama seçenekleri
  tuşu, portal, ekran altında ölüm) ya da sunucu onu kapatan veya örten bir şey gönderene (sandık kapatma, binek ekranı,
  yeniden doğma, giriş, oyun sonu ve demo olayı, tabela editörü, kitap, ölüm, diyalog, kaynak paketi isteği,
  yapılandırma, aktarma, bağlantı kesme) kadar kalır. Client'ın `OpenScreen`'den sonra gönderilmiş bir transaction'ı
  yanıtlamasından o ana kadar `handleKeybinds` çalışmaz; tuşlar da bırakılmış kalır (`setScreen` hepsini bırakır,
  ekran açıkken tuş ve fare basışları ekrana gider, tuş eşlemelerine değil). Bu aralıkta tuş aşamasının bir eylemi
  (tikin başında da giden hotbar seçimi hariç) ya da ileri, geri, sol, sağ tuşu kanıttır ("envanterde yürüme");
  zıplama, eğilme ve koşma (otomatik zıplama ve aç/kapa seçenekleri) iddia dışıdır. Tanınmayan menü tipi iddia
  dışıdır. Gerçek client'ın sandık şeridi bunu sınar: sandık ekranı açıkken W basılı tutulur, hiçbir hareket tuşu
  ve eylem sunucuya ulaşmaz; escape'ten sonra A yeniden yürür.
- **İzleyici:** `Player.isSpectator()` oyuncunun önbelleğe aldığı oyuncu listesi girdisinin modunu okur
  (`ClientSelf.mayBeSpectator`); client'ın işleyip işlemediği belli olmayan bir değişiklik ya da bilinmeyen mod
  sallanma kuralını kapatır.
- **Sınamalar:** `ActionGrammarCheckTest` vanilla dizilerinin (ağ iş parçacığının araya giren yanıtları, bir tikte
  iki tık, ışınlama ve döndürme yanıtlarından sonra saldırı, hareketten önce her eylem) hiç kanıt üretmediğini ve her
  kuralın, her eylemin ihlalini ister; menü kuralında da açılmadan önceki bir transaction'ın yanıtı, her örten paket,
  hotbar ve zıplama istisnaları sınanır; her kural ve istisnası için negatif kontrol yakalanır. Gerçek client
  oturumunun kaydı (2 saldırı, 8 eşya kullanımı, 5 bırakma, 5 hotbar seçimi) ve yük testinin bot kayıtları 0 kanıt,
  0 ret verir; gerçek client doğrulayıcısı aynı sırayı ayrıca kendisi de denetler. E2E sondası el sallamadan bir
  saldırıyla vakayı açar. Botlar saldırı ile sallamayı tek yazımda gönderir (vanilla'da pong da ana iş
  parçacığındandır, araya giremez); sonda hotbar seçimini ve tıklarını tikin hareketinden önce gönderir.

### `attack-aim` (`AttackAimCheck`, `AttackAim`)

- **Client'ın kuralı (1.21.11):** client yalnızca tikin başındaki seçimin (`GameRenderer.pick`,
  `LocalPlayer.raycastHitResult`) bulduğu varlığa saldırır. Her seçim gözden çıkan bir ışın boyuncadır: vanilla seçim
  bakış vektörüyle (`getViewVector(1)`: güncel eğim ve yön), kutu seçme yarıçapı kadar büyütülmüş ya da göz kutunun
  içinde; `attack_range` bileşenli eşyada önce kafanın bakışıyla (`getHeadLookAngle`: güncel eğim ve kafanın yönü),
  kutu `hitbox_margin` kadar büyütülmüş.
- **Hangi bakış:** client dışarıdan bir araç değilse seçim ile tikin `sendPosition`'ı arasında hiçbir şey onu
  döndürmez: fare her karede, paketler karelerin arasında döndürür. Bu yüzden seçimin bakışı, tikin kendi hareket
  paketinin taşıdığı dönüştür; bu paket, saldırıdan sonra aynı tikteki ilk hareket paketidir. Pakette dönüş ya da
  böyle bir paket yoksa dönüş, `sendPosition`'ın en son dönüşle gönderdiği paketten beri değişmemiştir
  (`yRotLast`). Işınlama ve döndürme yanıtları onu değiştirmez; oyuncuyu döndürdülerse `sendPosition` yeni dönüşü
  gönderir. Kontrol, `yRotLast`'ın olabileceği her dönüşü tutar (`MoveReplies` hangi paketlerin yanıt olabileceğini
  söyler; en çok 8, sonra kesin bir kendi paketine kadar bilinmez). Kafanın yönü bir önceki tikin `aiStep`'indeki
  yöndür, yani o tikten sonraki `yRotLast`; ışınlama bakışın yönünü değiştirir, kafanınkini değil.
- **Geometri:** göz, pozların göz yüksekliklerinden birindedir (`ClientSelf.eyes()`, `Avatar.POSES`: ayakta 1.62,
  eğilirken 1.27, 0.6 boylu pozlarda 0.4, hepsi oyuncunun olası ölçekleriyle; uyurken 0.2 ve ölürken 1.62 sabit); x ve
  z ±2·10⁻⁴. Eğim de bu yüzden sayılır: iki göz yüksekliğinin arasında kalan bir kutuya düz bakış değmez. Bir gözden yön `v` ile çıkan ışın hedef kutusu `B`'ye ancak ve ancak orijinden `v` ile çıkan ışın
  `B - S`'e (gözden kutuya bütün farkların kutusu) değdiğinde değer. Tek bir ışın-kutu (slab) sınaması, 10⁻⁴ pay ile.
  Işın sınırsızdır (client'ın parçaları onun üzerinde); ne kadar uzağa erişildiği `attack-reach`'in işidir. Hedefin
  olası her durumu ve olası her bakış için ışın değmiyorsa saldırı kanıttır; bulgu, bakışın kutudan en az ne kadar
  uzaktan geçtiğini (`missBy`) söyler (`reason=look`).
- **Bloklar (`reason=blocks`):** client'ın seçimi (`GameRenderer.pick`) varlığı ancak bakış ona, `Level.clip`'in
  (`ClipContext.Block.OUTLINE`) çarptığı bloktan daha yakında değiyorsa seçer. Blok taraması
  (`BlockGetter.traverseBlocks`) ışının geçtiği hücreleri sırayla gezer ve outline şekline ışının parçasının herhangi
  bir yerinde değen ilk hücrede durur (`AABB.clip`: bir kutuya dışarıdan girdiği yer). `ClientWorld.certainOutlineHit`
  bunun kesin olduğu en yakın `t`'yi verir: client'ın o hücrede tutabileceği her durumun (`possibleStates`) outline'ında,
  gözlerin kutusunun yarı boyu artı 10⁻⁶ kadar küçültülünce merkezden çıkan ışının girdiği ve hiçbir ışının içinden
  başlamadığı bir kutu var; ışınların daha önce yaklaştığı hiçbir hücre bilinmeyen ya da outline'ı hücresinden
  taşabilecek bir durumda olamaz (`BlockStates.outlineInCell`: kutusu hücresinden taşan bambu, kürsü, piston kafası,
  testi bitkisi; konumuyla kayan (`OFFSET`) çiçekler ve damlataş; açılırken büyüyen shulker kutusu). Hücresinde kalan
  her outline'a ise o hücrenin içinde, yani bu hücreden önce değilir. Outline'lar resmi client'ın kayıt defterinden
  (`BlockDataDump`), boş dünya ve bağlam için alınır; client'ınki hiçbir zaman daha küçük değildir (ışık bloğu elde ışık
  yokken boş, iskele elde iskele yokken kendi şekli). Hiçbir varlığın kutusu bakışa bu bloktan önce değmiyorsa (her
  poz gözü, her olası hedef kutusu ve her olası bakış için) saldırı kanıttır; bulgu bloğu (`wall`) ve bakışın orada
  hedeften en az ne kadar önce durduğunu (`stoppedBefore`) söyler. `attack_range` eşyasıyla iddia yok (o yolun kendi
  blok taraması var). Bloklar client'ın saldırı anında tutabileceklerinden alınır: saldırıdan sonra gelen bir
  onay (client'ın bir değişikliği işlediği) varsa iddia yok.
- **İddia yok:** giriş ya da yeniden doğuştan sonra client `PlayerLoaded` diyene kadar (yeni oyuncu o zamana kadar
  hareket göndermez), sonra kendi dönüşünü gönderene kadar bakış bilinmez (saldırıdan sonra kendi dönüşü gelirse
  bilinir); binekteyken (yolcunun dönüş paketleri `yRotLast`'ı değiştirmez); izleyici; göz ya da hedef kutusu
  sınırlanamıyor; envanterin erişimi bilinmiyor; oyuncuyu pozisyonundan sonra bir piston, shulker kutusu ya da
  shulker itmiş olabilir (`attack-reach` ile aynı koşul, `AttackReachCheck.mayHaveMoved`).
- **Sınamalar:** `AttackAimCheckTest` bakış vektörünü, ışın-kutu sınamasını, bakış boyunca ve dışındaki saldırıyı,
  dönüşsüz tiklerde son gönderilen bakışı, yanıt olabilecek bir paketin iki bakışı da tutmasını, ışınlama yanıtının
  `yRotLast` sayılmamasını (bakış onu izlerse client kendi paketinde gönderirdi), `attack_range` eşyasında kafanın
  bir önceki tikteki yönünü, tek bakışla iki yandaki varlıklara saldırıyı, saniyede bir bulguyu, piston,
  izleyici ve yüklenmemiş client istisnalarını ve anlaşılamayan paketle reddi sınar; duvar arkasındaki saldırıyı, bir
  pozun gözünün duvarın üstünden bakmasını, `attack_range` eşyasını, client'ın henüz almamış ya da saldırıdan sonra
  onayladığı duvarı da. `ClientWorldTest` blok sorgusunu sınar: tam blok, gözlerin kutusu, yalnızca outline (yarım
  basamak, ışık bloğu), hücresinden taşabilen ya da kayan bloklar (her iki yönde), client'ın her olası durumu, bilinmeyen
  hücre, ışının içinden başladığı kutu. Her kural için negatif kontrol yakalanır (11 mutasyon). E2E sondası her
  saldırısında faresini hedefe döndürür (tam fare adımlarıyla) ve bakışını bir tik önceden çevirip husk'a vurarak vakayı
  açar; boş elle, iki blok yüksekliğindeki taş duvarın arkasındaki lamaya bakıp vurarak ikinci vakayı (`reason=blocks`)
  açar; yük botları da saldırdıkları ineğe döner.

### `movement` (`MovementCheck`, `movement/MovementTracker`)

- **Hipotez kümesi:** client'ın kendi oyuncusu tick tick, olabileceği durumların kümesi olarak izlenir
  (`Hypothesis`: fizik durumu, oyuncunun geri kalanı, client'ın en son gönderdikleri, sunucunun göremediği
  seçenekler). Her durum `OnFoot` ile bir tick ilerletilir; yalnızca client'ın gönderdiğinin aynısını gönderen
  durumlar kalır: konum bit bit, bayraklar, koşma ve girdi paketleri, 2·10⁻⁴ eşiği ve 20 tikte bir hatırlatma.
- **Tick'ler:** her `ClientTickEnd` bir tick'i kapatır. Client paketleri yalnızca tick'ler arasında işler ve
  transaction'ı işlediği anda yanıtlar: tick'ten önce yanıtlanan bir transaction'dan önce gönderilen paket
  uygulanmıştır, ilk yanıtsızdan sonra gönderilen uygulanmamıştır, aradakiler için her önek bir hipotezdir.
- **Sunucunun gönderdikleri:** ışınlama, `SetEntityMotion` (set), patlama itmesi (add), öznitelikler, açlık
  (`SetHealth`), kendi bayrak ve duruş yankısı (`SetEntityData`), yetenekler, oyun modu, efektler, dünya sınırı,
  binek ve kamera.
- **Efektler:** adımın okuduğu beşi client'ın tuttuğu güçle izlenir (`OnFoot.Effects`): zıplama gücü
  (zıplamada ve otomatik zıplamanın erişiminde), yavaş düşüş (yükselmezken yerçekimi en çok 0.01, düşüş mesafesi
  sıfırlanır), havada süzülme (yerçekiminin yerine), körlük (koşulamaz) ve yunus lütfu (suda yavaşlama 0.96). Client bir efekti kendisi bitirmez:
  süresini sayar ama sunucu kaldırana kadar tutar, bu yüzden varlık ve güç yalnızca sunucunun paketlerinden
  gelir. Hız ve yavaşlık özniteliğe sunucu üzerinden etki eder.
- **Hız değiştiricileri (`SpeedAttribute`, `ModifierTable`):** client değiştiricileri işlem başına fastutil
  `Object2ObjectOpenHashMap` içinde tutar ve değeri o tablonun sırasıyla çarpar; sıra değerin son bitleridir.
  Tablo birebir kopyalanır (32 yuva, `HashCommon.mix(Identifier.hashCode())`, doğrusal yoklama, `shiftKeys`
  silmesi, son yuvadan geriye yineleme): hız ve yavaşlık efektleri, eklentilerin değiştiricileri ve koşma
  birlikte kesin. `ModifierTableTest` gerçek fastutil tablosuyla 2000 rastgele ekleme/silme dizisinde aynı sırayı
  ister. Tabloyu büyütecek (25.) değiştirici ve bilinmeyen işlem iddia dışıdır.
- **Su:** hipotez yüzme bayrağını ve gözün suda olup olmadığını taşır (`PlayerState.swimming`, `eyeInWater`); adım
  ikisini de dünyadan yeniden hesaplar. Sunucunun kendi yüzme bayrağı yankısı (`SetEntityData`) client'ın bayrağının
  yerine geçer ve izlenir. Su hareket verimliliği özniteliği (derinlik gezgini) varsayılanın dışındaysa iddia yoktur.
- **Görünmeyen seçenekler:** çift W penceresi (0..10) ve otomatik zıplama maske olarak taşınır, yalnızca
  okunduklarında dallanır; maskeleri dışında aynı durumlar birleşir.
- **Eşya kullanımı (`Using`):** client'ın kendi kullanımı (`LocalPlayer.startedUsingItem`), elindeki yığının
  `use_effects`'iyle (`ItemPrototypes`: oyunun tablosu; varsayılan 0.2 yavaşlatma, koşma başlamaz; mızraklar koşar,
  yavaşlamaz). Tuş eylemleri oyuncunun tick'inden önce: `UseItem` kullanımı başlatabilir ya da başlatmayabilir
  (kullanımı olmayan eşya, bekleme süresi, tokken yemek), ikisi de izlenir; bırakma durdurur. Tick başında
  `updatingUsingItem`: el aynı eşyayı tutuyorsa sürer (etkileri elin şimdiki yığınından), tutmuyorsa durur;
  client o tick elleriyle bir şey yaptıysa (seçim, tık, bırakma, etkileşim) durmuş da olabilir. Sunucunun
  canlı varlık bayrakları client'ın kullanımını eşitler (`onSyncedDataUpdated`); bayrakla başlayan kullanım
  elin o anki yığınını alır. Bitiş olayı (9) kullanımı bitirir (aç karnına yemek iddiayı bitirir: client'ın kendi
  açlığı koşmayı sunucudan önce belirler). Çapa her olası kullanımı dener: hiçbiri ya da ellerdeki her yığın.
  İddia yok: blok değiştirebilen kullanım (kova, nilüfer), kendi kendine bitebilen kullanım (fırça, kese),
  bırakılan trident (riptide oyuncuyu client'ta iter), bloğa kullanım (`UseItemOn`).
- **Kullanırken eylem yok:** `handleKeybinds` oyuncu bir eşya kullanırken yalnızca bırakır (kullanma tuşu
  kalkmışsa) ve saldırı ile kullanma tuşlarının tıklarını boşa harcar; saldırı, mızrak dürtmesi, varlıkla etkileşim
  ve yeni kullanım yalnızca kullanmayan bir durumun `startAttack`/`startUseItem`'ından gelir. Bunlardan birini içeren
  bir tick, tikin başında eşya kullanan hipotezlerle açıklanmaz; hepsi kullanıyorsa tick kanıttır ("eşya
  kullanırken saldırdı, etkileşti ya da yeni bir kullanım başlattı": kalkanla bloklarken ya da yerken vuran client).
  Bırakmayla saldırı aynı tikte de kanıttır (bırakan tik tıkları yine harcar); bırakmadan sonraki tikte saldırı
  vanilla'dır. Bulgunun özeti nedeni söyler (`reason`). Gerçek client'ın kalkan şeridi client'ın bu davranışını sınar:
  kalkan kalkıkken saldırı tuşuna üç kez basılır, client ne sallama ne saldırı ne de kullanım gönderir; bıraktıktan
  sonraki tık sallar.
- **Varlık itmeleri (`ClientEntities.pushers`, `ClientTeams`):** client'ta oyuncuyu yalnızca başka canlıların
  tick'i iter (`LivingEntity.pushEntities`; client'ta `pushableBy` yalnız yerel oyuncuyu kabul eder): kutusu
  oyuncununkiyle kesişen canlı, `Entity.push(Entity)` ile hareketine en çok 0.05 ekler. Yarasa, zırh askısı,
  papağan, vex ve ejderha oyuncuyu itmez. Client varlıklarını tick listesine katıldıkları sırayla tick'ler
  (`EntityTickList`). Oyuncudan sonra tick'leyenlerin itmesi oyuncunun kendi tick'inin bıraktığı duruma, öncekilerinki
  tick'ten önceki paketlerden sonraki duruma uygulanır. Oyuncu girişte ve her yeniden doğuşta listeye en sona
  katılır. Oyuncular eklenince katılır (hep tick'lenirler), diğerleri eklendiklerinde ya da sonra. Bu yüzden
  oyuncudan sonra eklenen her varlık ondan sonra tick'ler. Oyuncu ancak canlıysa, izleyici değilse ve
  tırmanılabilir bir blokta değilse itilir. Takımlar client'ın skor tablosundan gelir (`SetPlayerTeam`; Paper
  `collisions.enable-player-collisions` kapalıyken her takımı NEVER yazar, çevirici bunu da izler). Karar
  `pushableBy`'ın kurallarıyla verilir ve client'ın o tick tutabileceği her takım durumunda aynı olmalıdır. Bir
  oyuncunun ya da evcil bir hayvanın takımı adından bilinmez: her takım denenir. Birden çok iticinin sırası
  izlenmez, her sıra bir hipotezdir (en çok 3 itici).
- **İddia yok (küme düşer):** OnFoot'un kapsamı dışı (toz kar, lav hızı bilinmeyen boyutta lav akıntısı...), çarpışan ya da kendi yoluyla iten bir
  varlık yakında (tekneler, vagonlar, shulker, mutlu ghast), oyuncunun kutusuna değebilecek bir canlının konumu,
  boyutu, tick'i ya da tick sırası kesin değil (yeniden doğuştan önce eklenmiş, oyuncu olmayan bir canlı da
  böyle), itip itmediğine takımlar kesin karar vermiyor, hareketi farklı etkileyebilecek yerleşmemiş blok (tutabileceği
  her durum adım için aynıysa, ör. çimenin yayıldığı toprak, blok bilinir: `ClientWorld.possibleStates`,
  `OnFoot.sameForMovement`), piston, dünya sınırı yakın,
  hız dışında bir öznitelik, uçma, binek, başka kamera, sunucunun döndürmesi, okunamayan paket.
- **Çapalar:** yeni oyuncu (giriş ya da varlık verisi tutmayan yeniden doğuş: client'ın varsayılanları) ve
  duran oyuncu: 12 tik tuşsuz, en az 3 tik arayla bit bit aynı iki konum paketi, arada gönderim yok, sonda yerde.
  Arada 0.003'ten hızlı her yatay hareket gönderim eşiğini aşardı; durum dünyadan hesaplanır ve bir tik daha
  çalıştırılarak doğrulanır.
- **Doğrulama:** `tools/realclient/MovementTruth.java` takipçiyi gerçek client oturumunun kaydı üzerinde
  çalıştırır: açıklanamayan tick 0 olmalı, açıklanan ve hareket paketi gönderen her tick'te client'ın kendi
  durumu hipotezlerden biri olmalı (gönderdiği andaki haliyle, ondan sonra tick'leyenlerin itmesinden önce), ve en
  az bir itme izlenmiş olmalı. Son oturum (otomatik zıplama açık; efekt, eşya kullanımı, tırmanma, sekme ve
  köylülere yürüyen itme şeridiyle: biri, ikisi birden, içine konan biri, hiç çarpışmayan takımda biri; çit, duvar
  ve kapı üstü; havuz, akıntı, şelale, su altında yüzme ve sürünme; ağlar, çalılar, bal ve kabarcık sütunları;
  Overworld'de ve Nether'de lav havuzu ve akıntısı; iskele kulesi ve köprüsü): 9498 tikin 9429'u iddia edildi, 0
  açıklanamayan, 5328/5328 tick'te client'ın durumu kümede, 37 itme izlendi. Kalanlar uzak ışınlamalardan ve Nether'e geçişten sonra
  client'ın henüz işlemediği chunk'lardır (bir önceki oturumda 32 tik de konumu kesin bilinmeyen, dünyayla doğmuş
  bir koyunun yakınlığıydı). Eşdeğer durumlar izlenmeden önce buz pistinin altında çimenin yayılması
  da 98 tik daha iddiasız bırakıyordu. Bir parkur hatası da öğreticiydi: ateş direnci oyuncu magmanın üstündeyken
  kalkınca sunucu hasarla birlikte oyuncunun hızını gönderir (`SetEntityMotion`); takipçi bunu izlemez, iddiadan
  çekilir ve oyuncu durunca yeniden bağlanır. İtmeyi hiç uygulamayan, oyuncudan sonra tick'leyenlerinkini bir sonraki tick'in başına koyan ya
  da 0.05'i float yerine double alan model açıklanamayan tick verir. Efekt modellerinin bilinçli
  hataları (zıplama gücü, yavaş düşüş, havada süzülme, körlük) takipçide de açıklanamayan tik ya da kümede
  bulunmayan client durumu verir. `MovementCheckTest` aynı adımı koşan sentetik bir
  client'la (hız değiştiricileri gerçek fastutil tablosunda): dürüst hareket, belirsiz pencerede savrulma, çift
  W, hız ve yavaşlık efektiyle koşma, zıplama gücü, yavaş düşüş, havada süzülme, körlük, yemek (sonuna kadar),
  kılıcın arkasından kalkan ve bırakması, mızrakla koşma, çapadan önce kaldırılmış kalkanla yürüme, bir köylünün ve
  iki köylünün itmesi ve hiç çarpışmayan takımda itilmemek açıklanır; client'ın belki işlemediği bir takım değişikliği
  iddiayı yalnızca karar verdiği yerde bitirir; altındaki toprak çimene dönerken (iki durum adım için aynı) izlenir,
  havaya dönebilecek blokta iddia yoktur; bir havuza yürüyüp batan, su altında yüzen, yükselen ve shift'le batan oyuncu
  izlenir, suyun çekişi olmadan yerinde süzülmek kanıttır; ağlardan ve çalılardan yürüyerek geçen, kabarcık sütununun
  açık tepesinden fırlayıp geri düşen oyuncu izlenir, ağın içinde onu yok sayarak yürümek kanıttır; bir lav
  kanalında akıntıya karşı yürüyen ve akıntıda duran oyuncu izlenir, akıntıyı yok saymak kanıttır. 0.01 blok fazla hareket, havada asılı kalma, uygulanmayan savrulma,
  yok sayılan yavaşlık, verilmemiş zıplama gücüyle zıplama, körken koşma, yerken tam hızla yürüme ve itmeye karşı
  durmak kanıttır. `ClientPushersTest` ve `ClientTeamsTest` itici sorgusunu (sıra, tick, belirsizlik) ve takım
  kurallarını tek tek sınar.
- **Maliyet:** gerçek client oturumunda tick başına ortalama 1.33 hipotez, ≈ 60 µs.

## Planlanan aileler

- **Aim:** `MouseTurnModel` tersine çevirme ile imleç sayımı uzayına geçiş (hassasiyet tahmini,
  çok-kareli float birikimini hesaba katan kafes uyumu), açısal hız/ivme/jerk dağılımları,
  hedefe kilitlenme ve aşırı-düzeltme imzaları, "mükemmel rastgelelik" testleri
  (entropi, otokorelasyon, spektral düzlük).
- **Tıklama:** saldırı/swing aralıklarının dağılımı (kaydırmalı Welford momentleri, basıklık,
  çarpıklık), donanım makrosu periyodikliği, insan kas sınırları.
- **Zamanlama:** `ClientTickEnd` çerçevelemesiyle Timer/Blink, transaction tutma, paket
  patlamaları.
- **Etkileşim:** erişim/hitbox (bit-tam `viewVector` ışın izleme), görüş hattı, envanter zamanlaması.

Sinematik kamera, dürbün, kesirli imleç ve büyük-yaw çözünürlük kaybı gibi bilinen meşru
istisnalar, dedektör kanıt üretmeden **önce** dışlanır.
