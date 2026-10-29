# whynotac-analysis — Kontroller ve davranış dedektörleri

**Sözleşme:** Zaman çizelgesi ve fizik kanıtları üzerinde çalışan, her biri bir **istatistik ve
bilgi sınırı** ile birlikte `Evidence` üreten dedektörler. Cezalandırmaz.

## Mevcut kontroller (hepsi SHADOW)

| Kimlik | Sınıf | Ne kanıtlar |
|---|---|---|
| `tick-framing` | P-M | Bir client tick'inde vanilla'nın gönderebileceğinden fazla hareket paketi |
| `client-clock` | P-M | Client'ın kendi saatinin izin verdiğinden fazla tick |
| `attack-reach` | P-A | Hiçbir vanilla client'ın erişemeyeceği bir varlığa saldırı |
| `use-aim` | P-M | Tikin bakışından başka bir dönüşle eşya kullanımı (`UseItem`: yalnızca sunucu için dönen, sessizce nişan alan client) ya da tikin seçiminin yapamayacağı bir blok tıklaması (`UseItemOn`: bakışın dışında, giremeyeceği bir yüzde, outline'ın dışında (havaya), menzil dışında ya da blokların ardında) ya da kırma (`START`/`STOP_DESTROY_BLOCK`: bakışın değmediği, giremediği yüzden, outline'ı olmayan, uzaktaki ya da duvarın ardındaki blok) |
| `break-time` | P-M | Vanilla client'ın kırma ilerlemesinin izin verdiğinden erken `STOP_DESTROY_BLOCK` (hızlı kırma): her çarpanın client'ın tutabileceği en büyük değeriyle bile float toplamı 1'e ulaşmamışken |
| `prediction-sequence` | P-M | Vanilla client'ın numaralamadığı blok eylemi: tahmin edilen paket (`START`/`STOP_DESTROY_BLOCK`, `UseItemOn`, `UseItem`) bir öncekinin ardından gelen numarayı ya da yeni bir dünyada 1'i taşımıyor (bir modülün kendi kurup gönderdiği paket); tahmin edilmeyen eylem (iptal, atma, bırakma) 0 dışında numara taşıyor |
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
- **Hangi bakış (`TickLook`, `use-aim` ile ortak):** client dışarıdan bir araç değilse seçim ile tikin `sendPosition`'ı arasında hiçbir şey onu
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
  testi bitkisi; outline'ı konumuyla kayan (`MOVING_OUTLINE`, resmi client'ta 64 konumda ölçülür) çiçekler, bambu,
  damlataş ve mangrov fidanı; açılırken büyüyen shulker kutusu). Kısa ot ve eğrelti de ofsetlidir ama outline'ları onu
  yok sayar (`TallGrassBlock.getShape`): seçimi durdururlar ve kesin sayılırlar. Hücresinde kalan
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
  açar; yük botları da saldırdıkları ineğe (yönü ve eğimiyle) döner. Gerçek arazide botlar seçim yapmadan menzildeki
  ineğe vurduğu (yaprakların ve tepelerin ardındakine de) için yük özeti onların `reason=blocks` bulgularını ayrı sayar
  (`throughBlocksCases`); üç tanesi kayıttan tek tek doğrulandı (bakış ile inek arasında akasya ve meşe yaprağı, toprak).

### `use-aim` (`UseAimCheck`)

- **Client'ın kuralı (1.21.11):** `MultiPlayerGameMode.useItem` (tek çağıranı `handleKeybinds`'teki
  `Minecraft.startUseItem`) `ServerboundUseItemPacket`'i oyuncunun o anki `getYRot()` ve `getXRot()`'u ile gönderir. Bu
  ile tikin `sendPosition`'ı arasında oyuncuyu hiçbir şey döndürmez; dönüş, tikin bakışıdır (`TickLook`: tikin kendi
  hareket paketininki, yoksa en son gönderilen), bit bit aynı. Başka bir dönüşle kullanım kanıttır: yalnızca sunucu
  için dönen (atışı, inci ya da iksiri oyuncunun bakışı dönmeden nişanlayan) ya da pakete sahte dönüş yazan client.
- **Bloğa tıklama (`BlockClick`):** `ServerboundUseItemOnPacket`'in bloğu, yüzü ve imleci tikin seçimindendir
  (`LocalPlayer.pick` → `Entity.pick`: gözden `getViewVector(1) * d` boyunca `Level.clip`, `d` blok ve varlık
  menzillerinin büyüğü, `OUTLINE` şekilleri; konum `block_interaction_range`'den yakın değilse ıska). Clip ya gözden
  parçanın binde biri kadar ötedeki nokta şeklin içindeyse o noktayı (`inside`, yüz bakışın en yakın ekseninin tersi),
  ya da `AABB.clip` ile parçanın bir kutuya dışarıdan girdiği noktayı verir (o kutunun yüzünde, parçanın içeri doğru
  geçtiği yüz; eksen boyunca 10⁻⁷'den büyük harekette, diğer iki koordinat 10⁻⁷ içinde). Paket konumu bloğa göre float
  olarak taşır. Tıklama ancak şunlar tutarsa seçimindir: konum bir gözden bakışın doğrusu üzerinde ve ilerisinde
  (`look`), yüz bakışın girebileceği yüz (`face`), konum client'ın orada tutabileceği bir outline'ın o yüzünde
  (`cursor`: havaya ya da olmayan bloğa tıklama; outline'ı `BlockStates.outlineExact` değilse, ışık ve iskele gibi elde
  tutulana, shulker kutusu gibi blok varlığına ya da konuma bağlıysa iddia yok), menzilde (`reach`) ve bakışı ondan
  önce kesin durduran blok yok (`blocks`, `ClientWorld.certainOutlineHitBefore`). Hiçbir bakış ve poz için tutmuyorsa
  kanıttır; bulgu en ileri giden birleşimin nedenini verir. Bloklar client'ın seçiminin gördükleridir: tikin ilk blok
  eyleminin kendi tahminlerinden öncesi (`ClientWorld.possibleStatesBefore`), konumu bilinmeyen bir kullanım tahmini
  (kova, nilüfer) beklerken iddia yok, tıklamadan sonra gelen bir onay varsa dünyadan iddia yok. `attack_range`
  eşyasında (önce kendi ışınıyla seçer), dünya sınırı vuruşunda, izleyicide, göz bilinmezken iddia yok. Sunucunun
  `block_changed_ack`'i oyuncuyu geri alabileceği için göz, ardından gelen ilk konuma kadar bilinmez.
- **Kırma (`BlockClick.judgeDig`):** `START_DESTROY_BLOCK` ve `STOP_DESTROY_BLOCK` tikin seçiminin bloğunu ve yüzünü
  taşır (`MultiPlayerGameMode.startDestroyBlock`/`continueDestroyBlock`, `startAttack` ve `continueAttack`'ten); imleç
  yoktur. Bakış o bloğun outline'ına bir gözden o yüzden dışarıdan girebilmeli (yüz dikdörtgeni boyunca geriye,
  göz kutusuna değen bir ışın; ya da parçanın binde biri outline'ın içindeyse yüz bakışın en yakın ekseninin tersi),
  menzilde ve araya kesin blok girmeden. Nedenler `look` (bakış bloğa değmiyor), `face`, `outline` (client'ın orada
  tutabileceği bir outline yok: havayı kırma), `reach`, `blocks`. İptal (`ABORT_DESTROY_BLOCK`) kırılan bloğu adlandırır,
  seçimi değil: yargılanmaz.
- **İddia yok:** tikin bakışı bilinmiyorsa (`TickLook`) ve oyuncu binekte olabilirse (araç tik içinde onu döndürebilir;
  kullanım anında da, bakışın bilindiği anda da sorulur).
- **Sınamalar:** `UseAimCheckTest` son gönderilen ve tikin kendi dönüşünü, başka dönüşü, tikin kullanımdan sonra
  döndüğü durumu, bit bit karşılaştırmayı, yanıt olabilecek paketi, bilinmeyen bakışı, iki binek durumunu, saniyede
  bir bulguyu ve reddi sınar; tıklamalar için bakışın girdiği yerde, bakış dışında, yanlış yüzde, havaya ve bloğun
  içine, menzil dışında (sunucunun verdiği menzille de), duvarın ardında, gözün içinde olduğu bloktan (`inside`),
  onaydan sonra, client'ın henüz tutabileceği blokla, `attack_range` eşyasıyla, ışık bloğuyla ve konumu bilinmeyen bir
  kullanımdan sonra; kırmalar için girilen ve arka yüzü, bakış dışındaki bloğu, havayı, menzil dışını, duvarın ardını,
  gözün içinde olduğu bloğu, yalnızca bakışa dönük ama girilmeyen yüzü, iptali ve bitişi; her kural için negatif kontrol
  yakalanır (tıklamalar için 12, kırmalar için 8 mutasyon). Gerçek client oturumunun 10
  kullanımı (kalkan, yiyecek, yay), bir bloğa tıklaması ve kırması (toprağı önünden, zemini üstünden ve ardından bir
  sonrakini: 3 başlama, 2 bitiş) vaka açmadı; tıklama ve beş kırma paketi tam olarak değerlendirildi (dünya, göz ve
  bakış biliniyordu) ve tutarlı çıktı. E2E sondası çubuğu önce bakışıyla, sonra bakışından 45° sapmış bir dönüşle
  kullanır; duvara önce oyunun sinüs tablosu ve `AABB.clip` aritmetiğiyle bit bit hesaplanmış imleçle, sonra imleci
  bakışından 0.4 kaydırarak tıklar, duvarı önce bakışının girdiği doğu yüzünden, sonra arka (batı) yüzünden kırmaya
  başlar: yalnızca ikinci kullanım, ikinci tıklama ve ikinci kırma vaka açar.

### `break-time` (`BreakTimeCheck`)

- **Kural (decompiled 1.21.11, `MultiPlayerGameMode`):** `START_DESTROY_BLOCK` ilerlemeyi sıfırlar (hemen kırılan blok
  bitiş göndermez). Saldırı tuşu aynı blokta ve aynı eşya ve bileşenlerle (`sameDestroyTarget`) tutuldukça her tik
  `continueDestroyBlock` `getDestroyProgress`'i ekler, ilk kez başlangıcın kendi tikinde (`continueAttack`, `startAttack`'ten
  sonra); toplam 1'e ulaştığı tik `STOP_DESTROY_BLOCK` gider. Tik başına en çok bir artış vardır ve tuşları işleyen her tik
  `ClientTickEnd` ile biter: bitişe kadar en çok başlangıçtan beri her tik sonu için bir artış, artı bitişin kendi tiki.
- **Artış:** `Player.getDestroySpeed / sertlik / (doğru alet ? 30 : 100)`, float: aletin hızı (`Tool.getMiningSpeed`, aletsiz
  1) 1'in üstündeyse artı `mining_efficiency`; haste ya da conduit power altında `1 + (amp + 1) * 0.2`; madencilik
  yorgunluğu çarpanı; `block_break_speed`; göz sudaysa `submerged_mining_speed`; yerde değilse ÷5. Her çarpan o tikte
  client'ın tutabileceği en büyük değeriyle alınır: bitişteki eşya (başlangıcınkiyle aynı eşya ve bileşenler, yoksa kazı
  yeniden başlardı), bloğun başlangıcın kendi tahmininden önceki durumları, efektler ve öznitelikler (`ClientSelf`), göz
  yalnızca daha hızlıysa suda; bir etiketi adlandıran kural client'ın etiketlerine göre (`ClientTags`: tutup tutmadığı
  kesin değilse ya da etiketler bilinmiyorsa tutabilir sayılır); tikin tuşlarının okuduğu `onGround` kesin `false`
  değilse yerde. Float yuvarlaması tekdüze olduğundan client'ın toplamı bu en büyük artışların toplamını aşamaz.
- **Yerde mi (`ClientSelf.certainlyOffGround`):** tikin tuşları (`handleKeybinds`) önceki tikin hareketinin bıraktığı
  `onGround`'u okur (`Entity.move` yerel oyuncu için her tik yazar); o tikin `sendPosition`'ı onu göndermiştir (her
  hareket paketi taşır, yalnız değişimi `StatusOnly` gönderir). Bu yüzden bayrak bir önceki tikin sonunda
  (`ClientTickEnd`) alınır ve sonraki tikin artışında sayılır. Aradaki paketlerden yalnızca oyuncunun kendi kimliğine
  `TeleportEntity` onu yazar (`EntityPositionSync` ve `MoveEntity` yerel oyuncuyu atlar). Bazı bayraklar client'ın değildir:
  `handleMovePlayer`, `handleRotatePlayer` ve kaldırılmış bineğin ışınlanması `false` ile cevap verir, kendi bayrağına
  dokunmadan. Başka varlık kamerayken hiçbir şey gönderilmez. Bunlardan biri, yeni oyuncu ya da okunamayan paket
  bayraktan sonra client'a gelmiş olabildikçe, ve oyuncu binebildikçe ya da kamera başkası olabildikçe ÷5 sayılmaz.
- **İddia yok:** blok bilinmiyorsa (yerleşmemiş chunk, orada önceki bir tahmin, blokları bilinmeyen eşya kullanımı, kazı
  sırasında başka bir blok eylemi), eşya bilinmiyorsa (izlenmeyen yuva, stack'in kendi `tool` bileşeni, tablonun bilmediği
  eşya), dig-speed efekti izlenmiyorsa, yaratıcı ya da izleyici olabilirse; login ve respawn kazıyı unutur.
- **Sınamalar:** `BreakTimeCheckTest` elle toprağı (15 artış) ve taşı (float toplamıyla 151), elmas kazmayla taşı (6, haste
  II ile 5), onaylanmamış ve onaylanmış yorgunluğu, verimliliği, `block_break_speed`'i, kazı sırasında değişen bloğu, kazı
  sırasındaki tıklamayı, stack'in kendi aletini, yaratıcıyı, iptali, respawn'ı, başka bloğun bitişini ve saniyede en çok
  bir bulguyu sınar; havada elle toprağı (float toplamıyla 76), bayrağın gönderildiği tikten sonraki tikte sayılmasını
  (başlangıç tiki yerde, sonrası havada: 72), ışınlanma ve döndürme cevaplarını, oyuncunun kendi varlığının ışınlanmasını,
  yeni oyuncuyu, bineği ve kaldırılmış bineğin ışınlanmasını, başka kamerayı ve sonrasını da; 41 mutasyonun hepsi
  yakalanır (yerde olmayış için 16). Alet kuralları `item-prototypes.tsv`'nin son sütunudur (sunucunun
  kayıt defterinden, `ItemPrototypesDataTest`). Gerçek client'ın elle kırdığı toprakta üst sınırların toplamı bitişin
  tikinde tam 1.0'dır (client'ın kendi toplamı: 15 artış), çim bloğunda 1.22: ikisi de tam değerlendirildi, vaka yok.
  Zıplarken dümdüz aşağı kazdığı çim (66 artış, 60'ı havada ÷5) ve toprakta (elindeki eşya değişince yeniden başlayan
  kazı: 57 artış, 52'si havada) üst sınırlar 1.000001 ve 1.027'dir (`BreakAudit.java`): bayrak tam client'ın okuduğu tikte
  sayılıyor. E2E
  sondası duvarın taşını elle kazmaya başlar ve bir tik sonra bitirir: `break-time` vakası açılır.

### `prediction-sequence` (`PredictionSequenceCheck`)

- **Kural (decompiled 1.21.11):** client'ın blok değişikliği tahmin ettiği her paket
  `MultiPlayerGameMode.startPrediction`'dan geçer; o, dünyanın `BlockStatePredictionHandler`'ının sıradaki numarasını
  alır (`currentSequenceNr++`) ve pakete yazar: `START_DESTROY_BLOCK` (hayatta kalma ve yaratıcı), `STOP_DESTROY_BLOCK`,
  `UseItemOn`, `UseItem`. Numaraya başka hiçbir şey dokunmaz ve her `ClientLevel` 0'dan başlar: her login'de yeni dünya,
  respawn'da yalnızca boyut değişince (`handleRespawn`). Diğer bütün oyuncu eylemleri (`ABORT_DESTROY_BLOCK`, atma, el
  değiştirme, kullanılan eşyayı bırakma, mızrak dürtmesi) numarasız kurulur ve 0 taşır.
- **Hangi dünyada:** `ClientSelf.levels` client'ın kendi sırasında kaç yeni dünya yapmış olabileceğini aralık olarak
  izler (kesin işlenmiş ve kesin yeni olanlar, işlenmiş olabilecek her şey; boyutu bilinmeyen respawn ya da okunamayan
  paket yalnızca üst ucu artırır). Bir sonraki numara ancak önceki paketteki aralık hâlâ geçerli olabiliyorsa, 1 ancak o
  zamandan beri yeni bir dünya yapılmış olabiliyorsa mümkündür; gönderilen numara aralığı daraltır.
- **İddia yok:** oturumun ilk tahmin edilen paketi ve kayıp olaylardan, okunamayan bir paketten ya da bilinmeyen bir
  eylemden sonraki ilk paket sayımı yeniden başlatır (orada yalnızca 1'in altı iddia edilir: hiçbir dünya öyle
  numaralamaz). Yanlış numaradan sonra sayım gönderilenden sürer. Saniyede en çok bir bulgu.
- **Sınamalar:** `PredictionSequenceCheckTest` vanilla numaralarını (vaka yok), 0 ve tekrar eden numarayı, numaralı
  bırakmayı, başka boyuta respawn'ı (işlenmeden önce eski sayım, sonra 1; kesin işlendikten sonra eski sayım bulgu),
  respawn'dan önceki numaraların hangi dünyayı söylediğini, yeniden başlayıp süren sayımı, aynı boyutta respawn'ı, her
  login'i, bilinmeyen boyutu, okunamayan paketi, kayıp olayları ve saniyede bir bulguyu sınar; `ClientSelfTest` dünya
  aralığını. 17 mutasyonun hepsi yakalanır. İptal hiçbir şey tahmin etmez (`ClientWorld` artık onu tahmin saymaz:
  elindeki eşya değişince yeniden başlayan kazı bilinir kalır). Gerçek client'ın bütün tahminleri 1, 2, ... ve
  nether'e respawn'dan sonra yeniden 1'den numaralandı, vaka açılmadı; E2E sondasının numaralı bırakması vaka açtı.

### `movement` (`MovementCheck`, `movement/MovementTracker`)

- **Etiketler (`EngineTags`):** motor vanilla 1.21.11'in etiketlerini varsayar: `minecraft:climbable` tam olarak
  `OnFoot.CLIMBABLE`, `minecraft:can_glide_through` tam olarak `OnFoot.CAN_GLIDE_THROUGH`,
  `minecraft:fall_damage_resetting` tam olarak `OnFoot.FALL_DAMAGE_RESETTING`, `minecraft:fences` ve `minecraft:walls` adı `*_fence` ve `*_wall` olan bloklar, sıvı etiketleri
  `minecraft:water` ve `minecraft:lava` su/akan su ve lav/akan lav, `minecraft:powder_snow_walkable_mobs` oyuncuyu
  tutmaz, `minecraft:strider_warm_blocks` tam olarak lav, `minecraft:can_float_while_ridden` (varlık türü; derin suda
  sürülen bineği yükseltir) tam olarak `Horse.FLOATS_WHILE_RIDDEN` (at, zombi at, katır, eşek, deve, deve kabuğu; sicil
  numaraları `UpdateTagsTranslationTest`'te oyununkilere bağlı). Client'ın etiketleri (`ClientTags`, sunucunun `update_tags`'i) iddianın çapasından beri bunlardan başka olmuş
  olabiliyorsa bulgu açılmaz (tik açıklanamamış kalır, iddia edilmez). Vanilla sunucuda E2E ve gerçek client'ın
  `/whynotac self` çıktısı `engineTags=vanilla` der.

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
- **Görünmeyen seçenekler:** çift W penceresi (0..10), otomatik zıplama ve client'ın CPU'su (süzülmenin `Math.cos`'u:
  `PlatformCos`, `Hypothesis.cosineMask`) maske olarak taşınır, yalnızca okunduklarında dallanır; maskeleri dışında aynı
  durumlar birleşir. Süzülen bir tikte hâlâ olası platformlar o eğimdeki kosinüslerine göre gruplanır, her farklı
  kosinüs bir adımdır (çoğu eğimde hepsi aynıdır). Bir oturumda CPU değişmez: bir kez ayrılan platform geri gelmez.
- **Süzülme:** client `START_FALL_FLYING`'i tam olarak `tryToStartFallFlying` başardığında gönderir; paket varsa adım
  bir süzülme eşyasıyla, yoksa eşyasız çalışır (envanter gerekmez). Paket tikte birden çoksa, ya da paket
  varken adım süzülmeye başlamıyorsa (yerde, zaten süzülürken) ya da adım başlıyorken paket yoksa tick açıklanmaz.
  Sunucunun ortak bayraklarının 7. biti (`SetEntityData` 0) client'ın süzülmesini başlatır ya da bitirir, işlendiği
  sırayla. Durma çapası, client hâlâ süzülüyor olabildikçe
  (tabanın son işlenen bayrakları süzülme diyorsa ya da client'ın başlattığı süzülmeden sonra bayrak işlenmediyse)
  kurulmaz: süzülen ama duran oyuncu (dik aşağı bakan) yürüyen sanılmaz.
- **Dünya sınırı (`ClientBorder`):** client'ın `ClientLevel`'inin `WorldBorder`'ı, işlediği sırayla: her yeni dünya
  (her giriş, başka boyuta doğuş: `handleRespawn` anahtarları karşılaştırır) yeni, varsayılan bir sınırdır;
  `InitializeBorder` hepsini kurar (merkez, boyut ya da lerp, mutlak en büyük), diğer üç paket birer parçasını. Lerp
  (`MovingBorderExtent`) dünyanın normal koşan her tikinde, varlıklardan sonra bir adım atar
  (`ClientLevel.tick`: `tickRateManager().runsNormally()`, `ClientEntities.lastTickRanNormally`); adımları bitince
  hedefinde sabittir. Adım hareketten önceki sınırla koşar ve çarpışma bir önceki boyutu okur. Kesin değilse
  (`Uncertain`: tik hızı donmuş olabilirken bir lerp, ya da tabanın hangi tikte işlendiğini bilmediği bir lerp) boyut
  iki ucu arasındadır ve adımları kesin koştuktan sonra hedefinde kesinleşir; kesin olmayan sınır yakınsa iddia yoktur.
  Boyut anahtarı bilinmeyen bir yeniden doğuşta sınır `InitializeBorder`'a kadar bilinmez. `MovementCheckTest`: sabit sınıra yürüyüp
  duran, küçülen sınırın içinden geçtiği, büyüyen sınıra yaslanıp onunla ilerleyen, içinden geçen sınır ayağa kalkacak
  yeri aldığı için shift'i bırakınca da çömelik kalan oyuncu izlenir; sınırı yok sayan ya
  da lerp'te bir adım önden giden client kanıttır; dünya donmuş olabilirken lerp'li sınır yakınında iddia yoktur.
- **Toz kar ve botlar (`ClientInventory.wearsOnFeet`):** adım toz karın şeklini ve tutuşunu motorda hesaplar
  (whynotac-physics); ayağındaki deri botu envanter replikası verir: envanter menüsünün 8. slotu, envanterin 36.
  indisi, oyuncunun kendi ekipman paketi (`SetEquipment`; vanilla sunucu kendine göndermez, bir eklenti gönderebilir)
  ve giyilebilir bir eşyanın kullanımla zırhla yer değiştirmesi (bilinmez sayılır, sunucu yeniden gönderene dek).
  Client'ın tutabileceği her durumda bot aynıysa bilinir; değilse yalnızca botların karar verdiği toz kar tikleri
  iddiasız kalır. Donma (`DATA_TICKS_FROZEN`) client'ın hareketini değiştirmez: yavaşlaması sunucunun hız
  özniteliğidir, takipçi onu izler; donan oyuncu artık kapsam dışı değildir. `MovementCheckTest`: botsuz batan, duvara
  karşı zıplayıp çıkamayan, 2.5'ten fazla düşüp levhaya inip batan, botla üstünde yürüyen, shift ile batıp duvarda
  tırmanan ve donarken yürüyen oyuncu izlenir; botsuz üstünde yürüyen ya da içinde tutulmadan hızlı yürüyen client
  kanıttır; bot bilinmiyorsa yalnızca toz karın üstünde iddia yoktur.
- **Havai fişek roketi (`ClientEntities.rocketsBoosting`):** süzülürken kullanılan roketi yalnızca sunucu doğurur;
  client'a eşleme paketinde bağlı olduğu varlıkla gelir (`DATA_ATTACHED_TO_TARGET`, veri 9, yalnızca yapılırken
  atanır). Oyuncudan sonra eklendiği için oyuncudan sonra tick'ler (`FireworkRocketEntity.tick`): oyuncu süzülüyorsa
  (adımın bıraktığı bayrak) hareketine bakışına doğru `l·0.1 + (l·1.5 − d)·0.5` ekler, bir sonraki tikin adımı bunu
  taşır. Client'ta patlamaz: `RemoveEntities` gelene dek iter. Takipçi her tikte oyuncuya bağlı ve o tikte tick'lemiş
  roketleri sayar (donmamış, oyuncunun çevresindeki her chunk tick'liyor) ve itmeyi o kadar kez uygular; itmeler
  hareketin aynı fonksiyonudur, sıraları önemsizdir. Başka bir varlığa bağlı ya da kendi başına uçan roket itmez.
  İddia yok: bağlı olup olmadığı, tick'leyip tick'lemediği ya da var olup olmadığı kesin olmayan bir roket (verisi
  replika onu kaybetmişken gelen, oyuncudan önce eklenmiş, chunk'ı belki tick'lemeyen), ve aynı tikte hem roket hem
  canlı itmesi (tick listesindeki sıraları izlenmez). `MovementCheckTest`: bir, sonra iki, sonra yine bir roketle
  süzülme ve iniş izlenir; client'ın belki henüz tutmadığı bir roket iddiayı oyuncu yeniden durana dek bitirir; roket
  yokken roketin vereceği hız kanıttır; başka varlığa bağlı roket oyuncuyu itmez.
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
  riptide'lı ya da gücü bilinmeyen bırakılan trident (riptide oyuncuyu client'ta iter), bloğa kullanım (`UseItemOn`).
  Trident'in dönme saldırısı gücünü (`EnchantmentHelper.getTridentSpinAttackStrength`) sunucu tarafındaki çevirici her
  tam yığın için büyülerinden hesaplar: sunucunun client'a senkronladığı tanımlarla (bir datapack'in değişiklikleri
  dahil), iki rastgele çekilişte aynı değilse NaN; client'ın kendi hash'lediği yığında ve eski kayıtlarda bilinmez.
  Gücü 0 olan trident'i bırakmak (`TridentItem.releaseUsing`: client onu fırlatır, oyuncuyu hareket ettirmez) yayınki
  gibi sıradan bir bırakıştır ve iddiayı bitirmez; hileli bir client riptide'sız trident bırakarak kontrolü susturamaz.
  Riptide'lı trident'in kullanımı suda ya da yağmurda değilken başlamaz (`TridentItem.use`: `isInWaterOrRain` değilse
  FAIL): client'ın seviyesi kesin yağmursuzsa (hava olaylarından izlenen yağmur seviyesi en çok 0.2: `isRaining` yok,
  yeni seviye 0, yağmurun başı 0, sonu 1, sonra her değişim) ve oyuncunun son tikinin başındaki yerine (hızının iki
  katı kadar geniş) hiçbir sıvı ya da bilinmeyen blok yakın değilse, o kullanım varsayımı kurulmaz. Böyle bir yerde
  bırakıp riptide gibi fırlayan client ("her yerde riptide") açıklanamayan harekettir. `MovementCheckTest`: riptide'sız
  trident 15 tik doldurulup bırakılır, sonrası açıklanır; kuru yerde riptide III'lü trident'i (3.0) bırakıp fırlamak
  bulgu (kural kaldırılınca kırılır); yağmurda aynı bırakış iddiayı bitirir; eşleme kaldırılınca ilk test kırılır.
  `InventoryPacketViewsTest`: riptide I–III 1.5, 2.25 ve 3.0 verir.
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
- **İddia yok (küme düşer):** OnFoot'un kapsamı dışı (botu bilinmeyen oyuncunun altındaki toz kar, lav hızı bilinmeyen boyutta lav
  akıntısı...), çarpışan ya da kendi yoluyla iten bir
  varlık yakında (tekneler, vagonlar, shulker, mutlu ghast), oyuncunun kutusuna değebilecek bir canlının konumu,
  boyutu, tick'i ya da tick sırası kesin değil (yeniden doğuştan önce eklenmiş, oyuncu olmayan bir canlı da
  böyle), oyuncuya bağlı olup olmadığı ya da tick'leyip tick'lemediği kesin olmayan bir havai fişek roketi, aynı tikte
  roket ve canlı itmesi, itip itmediğine takımlar kesin karar vermiyor, hareketi farklı etkileyebilecek yerleşmemiş blok (tutabileceği
  her durum adım için aynıysa, ör. çimenin yayıldığı toprak, blok bilinir: `ClientWorld.possibleStates`,
  `OnFoot.sameForMovement`), piston, kesin bilinmeyen bir dünya sınırı yakın,
  hız dışında bir öznitelik, uçma, binek (sürülen tekne ayrıca izlenir: aşağıda), başka kamera, sunucunun döndürmesi,
  okunamayan paket.
- **Çapalar:** yeni oyuncu (giriş ya da varlık verisi tutmayan yeniden doğuş: client'ın varsayılanları) ve
  duran oyuncu: 12 tik tuşsuz, en az 3 tik arayla bit bit aynı iki konum paketi, arada gönderim yok, sonda yerde.
  Arada 0.003'ten hızlı her yatay hareket gönderim eşiğini aşardı; durum dünyadan hesaplanır ve bir tik daha
  çalıştırılarak doğrulanır.
- **Doğrulama:** `tools/realclient/MovementTruth.java` takipçiyi gerçek client oturumunun kaydı üzerinde
  çalıştırır: açıklanamayan tick 0 olmalı, açıklanan ve hareket paketi gönderen her tick'te client'ın kendi
  durumu hipotezlerden biri olmalı (gönderdiği andaki haliyle, ondan sonra tick'leyenlerin itmesinden önce), ve en
  az bir canlı itmesi ve bir roket itmesi izlenmiş olmalı. Son oturum (otomatik zıplama açık; efekt, eşya kullanımı, tırmanma, sekme ve
  köylülere yürüyen itme şeridiyle: biri, ikisi birden, içine konan biri, hiç çarpışmayan takımda biri; çit, duvar
  ve kapı üstü; havuz, akıntı, şelale, su altında yüzme ve sürünme; ağlar, çalılar, bal ve kabarcık sütunları;
  Overworld'de ve Nether'de lav havuzu ve akıntısı; iskele kulesi ve köprüsü; elytra ile altı süzülme, biri roketle,
  ve inişleri; sabit, büyüyen ve küçülen dünya sınırına yürüme; toz kar şeridi ve çukuru: botsuz batma, beş bloktan
  düşüş, deri botla üstünde yürüme ve koşma, shift ile batıp duvarda tırmanma, donarken yürüme): 12 869 tikin
  12 363'ü iddia edildi, 0 açıklanamayan, 6648/6648 tick'te client'ın durumu kümede, 30 canlı itmesi ve 50 roket itmesi
  izlendi; ne sınır ne toz kar (donmuş tikleri de) hiçbir tiki iddiasız bırakmadı. Kalanlar çoğunlukla uzak ışınlamalardan ve Nether'e geçişten sonra client'ın
  henüz işlemediği chunk'lar ve bloğa eşya kullanımıdır (bir önceki oturumda
  32 tik de konumu kesin bilinmeyen, dünyayla doğmuş bir koyunun yakınlığıydı). İlk elytra oturumunda süzülmenin 34
  tiki de iddiasız kaldı: yay şeridinin bir oku izlenemez olmuştu ve varlık replikası türünü unutup onu "roket
  olabilir" saymıştı; yerinde kaybolan aday artık türünü korur. Eşdeğer durumlar izlenmeden önce buz pistinin altında çimenin yayılması
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
- **Sürülen tekne (`BoatRide`):** oyuncu bir teknenin ilk yolcusu olunca (client'ın `isLocalInstanceAuthoritative`'i)
  client tekneyi kendi fiziğiyle hareket ettirir: her tikte `AbstractBoat.tick` (`PaddleBoat` gönderir), sonra yolcunun
  tick'i dönüşünü ve `MoveVehicle`'ı (teknenin tikten sonraki konumu, dönüşü, yer bayrağı) gönderir. Takipçi teknenin
  olabileceği durumları izler: her tikte her durum `Boat` ile ilerletilir ve gönderilen kürek ile araç paketini bit bit
  vermeyenler düşer; hiçbiri vermezse tick kanıttır ("hiçbir vanilla client tekneyi böyle sürmez": tekneyle uçma ve hız
  hileleri). Tekne bir önceki tikin tuşlarıyla döner ve kürek çeker (`rideTick`'in `setInput`'u). Tekne hâlâ
  interpolasyondaysa (`ServerboundMoveVehiclePacket.fromEntity`) araç paketi teknenin konumunu değil, interpolasyonun
  hedefini ve dönüşlerini taşır; binişte birden çok adımı kalmış bir tekne bunu ilk tiklerinde gösterir. **Binişteki durum:**
  varlık replikası yolcusuz tekneyi client gibi izler (yalnızca interpolasyonla hareket eder, her tikte hızı sıfırlanır)
  ve biniş anındaki durumunu saklar (`ClientEntities.mounted`): konum, dönüş, interpolasyon, yer bayrağı, son tikten beri
  gelen hız. Durum son tikin başladığı yerden hesaplanır (bloklar o zamandan beri değişmemişse:
  `ClientWorld.mayHaveChangedIn`); düşüş, dönüş ve tuş yoktur; tekneyi daha önce tutmuş olabilecek her blok için bir
  durum (yok, örümcek ağı, toz kar). **İddia yok (biniş bitene kadar):** client'ın binişi bir tik önce işlemiş olabilmesi,
  replikanın izlemediği tekne (kaybolmuş ya da daha önce sürülmüş), teknenin ilk tiki, son tikinden beri çevresinde blok
  değişikliği ya da içinden iten bir blok (ağ, toz kar, bal, kabarcık sütunu), son tikinde oyuncunun onu itmiş olabilmesi
  (kutusu teknenin 0.2 genişletilmiş kutusuna değiyorsa), teknenin değebileceği bir varlık (`canVehicleCollide`:
  çarpışılabilen ya da itilebilen her varlık), kesin bilinmeyen dünya sınırı, sunucunun aracı ya da oyuncuyu taşıması
  (`MoveVehicle`, ışınlama), client'ın işleyip işlemediği belirsiz paket, okunamayan paket. Sunucunun `SetEntityMotion`'ı
  hızı, varlık verisi yerçekimsizliği değiştirir. **Doğrulama:** `MovementTruth` her açıklanan biniş tikinde
  client'ın kendi teknesinin (truth ajanının `boatPost`'u) durumlar arasında olmasını ister. Son oturumda 8 binişin 834
  tikinin hepsi baştan sona iddia edildi ve açıklandı, 0 açıklanamayan, 834/834 tikte client'ın teknesi kümede. Negatif
  kontroller (hepsi açıklanamayan tik verdi): ileri itiş 0.04 → 0.041, tuşların hiç dümen çevirmemesi, küreklerin yer
  değiştirmesi, binişte interpolasyonu yok saymak, binişte durumu "havada" saymak. `MovementCheckTest`: sentetik bir
  client'ın durarak, ileri ve sola sürdüğü 40 tik açıklanır; yarım blok yukarıdaki `MoveVehicle` kanıttır; oyuncunun
  itmiş olabileceği teknede iddia yoktur.
- **Sürülen at (`HorseRide`):** oyuncu eyerli bir atın (eşek, katır, iskelet ve zombi at) ilk yolcusu olunca client atı
  kendi fiziğiyle hareket ettirir: her tikte önce `AbstractHorse.tick` (`Horse`, gerçek client'la bit bit), sonra binicinin
  tiki dönüşünü (`MovePlayer.Rot`), bıraktığı binek zıplamasını (`START_RIDING_JUMP`, gücüyle) ve `MoveVehicle`'ı
  gönderir. Atın tiki binicinin son tikinin bıraktığı girdiyi (`xxa`, `zza`: o tikin tuşlarından, yolcuyken eşya ve
  çömelme yavaşlatmaz), binicinin şimdiki dönüşünü (bu tikin `Rot`'u: tikler arasında yalnızca kareler döndürür) ve
  son tikinde bıraktığı zıplamayı (`onPlayerJump`: kayma izni, şaha kalkma `standCounter` 20, bekleyen zıplama) okur.
  Zıplamanın gücü tuştan çıkar (`LocalPlayer.aiStep`: tuşun basılı kaldığı tikler `jumpRidingScale`'i yapar, bırakış
  `floor(scale * 100)` gönderir): bırakılmayan tuşla gönderilen zıplama, gönderilmeyen bırakış ya da başka güç (biniş
  sonrası ilk basıştan itibaren) kanıttır. **Binişteki durum:** varlık replikası yolcusuz atı client gibi izler:
  `AddEntity`/`SetEntityMotion`/ışınlamanın hızı, interpolasyonsuz her tikte ×0.98 ve eksen başına 0.003 altı sıfır
  (`LivingEntity.aiStep`); eyer (`SetEquipment`), at ve mob bayrakları (evcil, şaha kalkma, NoAI), sunucunun gönderdiği
  öznitelikler (hız, zıplama gücü, adım, yerçekimi; gelmeyen türün varsayılanı) ve binişten hemen önce gelen efektler
  (`sendActiveEffects`). Client'ta `pushEntities` yalnızca yerel oyuncuyu iter (`EntitySelector.pushableBy`): replika
  oyuncunun gönderdiği konumlardan kutusunu çıkarır ve atınkine değmiş olabildiği her tikte hızı "bilinmiyor" sayar;
  hızın kurulduğundan beri atın gezdiği kutu da saklanır (su ve lav iter: binişte orada sıvı olmamalı, bloklar
  değişmemiş olmalı). **İddia yok:** binişin bir tik önce işlenmiş olabilmesi, izlenmeyen at (kayıp, daha önce
  sürülmüş), ilk tiki, bilinmeyen hız (oyuncu itmiş olabilir), gezdiği yerde sıvı ya da blok değişikliği,
  eyersiz/evcil olmayan/yavru/ölçekli at, tam bilinmeyen öznitelik, zıplama güçlendirmesi dışında efekt, binişten
  önceki tikte yürüme tuşu, yakında çarpışabileceği varlık, kesin bilinmeyen dünya sınırı, sunucunun atı ya da oyuncuyu
  taşıması, belirsiz ya da okunamayan paket. Binişte at sunucunun son koyduğu yere interpolasyonla gidiyorsa kalan
  adımları replikadan gelir ve `MoveVehicle` interpolasyonun hedefini ve dönüşlerini taşır (`fromEntity`, teknedeki
  gibi). **Doğrulama:** gerçek client oturumunda (CI'daki parkur) `Horse` motoru atın 725 tikinin hepsinde client'la bit
  bit aynı: suda 141 tik, üç zıplama, halka arenadaki basamak ve duvarda 67 yatay çarpışmalı tik dahil. Takipçi aynı
  oturumda 375 tik açıkladı, 0 açıklanamayan; 375/375 tikte client'ın kendi atı (`horsePost`) kümede. İddia edilmeyen
  binişler: havuzda başlayan (141 tik, sıvı), yakında varlık olan (123 tik) ve client'ta binişten hemen önce yeniden
  oluşturulan at (149 tik, ilk tiki). Binişteki interpolasyon da gerçek client'ta doğrulandı: yarım blok ışınlanıp
  hemen binilen atın interpolasyonla geçen tikleri (sonraki koşularda 2 ve 3 tik) motorla bit bit aynı.
  `MovementCheckTest`: sentetik client'ın durup ileri, zıplayarak ve sola sürdüğü 50 tik ve interpolasyon sürerken
  binilen at açıklanır; yarım blok yukarıdaki `MoveVehicle` ve tuşun tuttuğundan güçlü zıplama (100, 30 değil)
  kanıttır; oyuncunun itmiş olabileceği atta iddia yoktur. Replika: `ClientEntitiesTest` hızın yavaşlamasını, itmeyi,
  interpolasyon sırasında korunmasını ve özniteliklerin client'ın aralıklarına kırpılmasını sınar; mutasyonlar (itme kontrolü yok, güç karşılaştırması yok) testleri kırar.
- **Sürülen domuz (`HorseRide`, domuz olarak):** eyerli domuzun ilk yolcusu havuçlu olta tutarken (ana ya da yan elde:
  `Pig.getControllingPassenger`) client domuzu kendi fiziğiyle sürer; tiki atınkiyle aynı `LivingEntity` yolu (`Horse`
  motoru): girdi binicinin tuşlarından bağımsız hep ileri (`getRiddenInput`), dönüş binicininki, adım en az 1, zıplama
  yok, boyutu 0.9. Hızı hız özniteliği × 0.225 × hızlanma çarpanıdır (`getRiddenSpeed`); hızlanmayı client kendisi sayar
  (`ItemBasedSteering`): sunucunun `DATA_BOOST_TIME` değeri client'a her ulaştığında 0'dan başlar (`onSynced`, değer ne
  olursa olsun), domuzu bir oyuncunun kontrol ettiği her tikte hızdan önce bir artar ve toplamı geçince biter; çarpan
  `1 + 1.15·sin(süre/toplam·π)`. Replika domuzun hızlanmasını binişe kadar izler: kimse sürmemişken başlamış ve sayılmamış
  (1), hiç başlamamış (0) ya da bir oyuncu (kendisi dahil) sürmüş olabileceği için bilinmiyor (-1). **İddia yok:** elde
  havuçlu olta kesin değilse ya da yoksa, hızlanma bilinmiyorsa, eyersiz/yavru/ölçekli domuz; atınkilerin geri kalanı da
  geçerli. Domuzla gönderilen binek zıplaması kanıttır (domuz zıplamaz). **Doğrulama:** gerçek client oturumunda (halka
  arenada iki, çim şeritte bir domuz; ikisinde kullanım tuşuyla hızlanma) `Horse` motoru domuzun 423 tikinin hepsinde
  client'la bit bit aynı (228'i hızlanırken; sayılan hızlanma ve `speed` alanı dahil), takipçi 423 tikin hepsini iddia
  edip açıkladı, her birinde client'ın kendi domuzu kümede. `MovementCheckTest`: hızlanmasıyla sürülen 40 tik
  açıklanır, hızlanmanın verdiğinden ileri `MoveVehicle` bulgu, havuçlu oltasız sürüşte iddia yok; `tickBoost` adımı
  çıkarılınca ilk test kırılır. Replika: `ClientEntitiesTest` senkronla başlayan, başka oyuncunun sürmüş olabileceği ve
  hiç başlamamış hızlanmayı sınar.
- **Sürülen strider (`HorseRide`, strider olarak):** eyerli strider'ın ilk yolcusu çarpık mantarlı olta tutarken
  (`Strider.getControllingPassenger`) client onu domuz gibi sürer (hep ileri, dönüş binicininki, adım en az 1, zıplama
  yok, hızlanmayı kendisi sayar), boyutu 0.9 × 1.7. Farkları `Horse` motorunda (`Horse.Strider`): lavın üstünde durur
  (`canStandOnFluid`: kaynak lav bloğu, üstünde lav yoksa ve strider'ın tabanı yarım bloğun üstündeyse
  `LiquidBlock.SHAPE_STABLE` ile çarpışır; `Colliders.Context.standsOnLava`), bulunduğu yerin sıvısı lavken
  `travelInFluid` değil karada gibi yürür, lavın içindeyse tik sonunda yüzer (`floatStrider`: yüzeyin üstünde ve üstü
  lav değilse yerde sayılır, değilse hızı yarıya iner ve 0.05 yükselir), lavda düşüşü sayılmaz (`checkFallDamage`). Tikin
  başında client boğulmasını kendisi belirler (`Strider.tick`, NoAI değilse): bulunduğu ya da altındaki blok
  `strider_warm_blocks`'ta (vanilla: lav) değilse ve son tikinde lav bulmadıysa boğulur; hız özniteliğindeki boğulma
  değiştiricisini (−0.34, `ADD_MULTIPLIED_BASE`) kendisi ekler ya da çıkarır ve hızı öznitelik × 0.35 (boğulurken) ya da
  × 0.55 × hızlanma çarpanıdır. Replika hız özniteliğini sunucunun gönderdiği değiştiricilerle, boğulmanınki hariç ve
  dahil iki değer olarak tutar (`ClientEntities.striderSpeeds`; sıra önemliyse bilinmiyor). `EngineTags`
  `strider_warm_blocks`'u da vanilla'ya bağlar. **İddia yok:** elde çarpık mantarlı olta kesin değilse ya da yoksa, NoAI
  strider (boğulmasını sunucu belirler), hızlanma bilinmiyorsa, eyersiz/yavru/ölçekli strider; atınkilerin geri kalanı da
  (binişte gezdiği yerde lav varsa da: binilmeden önceki tiklerinde lav onu yüzdürür). Strider'la binek zıplaması
  kanıttır. **Doğrulama:** gerçek client parkurunda iki strider ayağı (çimden lav havuzuna, karşı kıyıya ve akan lav
  kanalına; biri kullanım tuşuyla hızlanarak; AI'ı binişten önce açık: sunucu varlığın verisini tikinin sonunda, binişi
  hemen gönderir): `Horse` motoru strider'ın 249 tikinin hepsinde client'la bit bit aynı (84'ü hızlanırken, 133'ü
  boğulurken, 61'i lavın üstünde dururken, 37'si akan lavda batıp yüzerken), takipçi 249 tikin hepsini iddia edip
  açıkladı, her birinde client'ın kendi strider'ı kümede. NoAI'lı binişte (önceki bir koşu) iddia yoktu, motor yine 246
  tikte bit bit aynıydı. `StriderTest`: lavın sabit şekli (kaynak, üstü lav değil, yarım bloğun
  üstünden; oyuncu için hiç), lavda duran strider ve batan at, lavda yüzüp yüzeye çıkış, karada boğulup yavaşlama, son
  tikin lavıyla bir tik daha ısınma, NoAI; mutasyonlar (yarım blok yerine bir blok, `floatStrider`'ın yarıya indirmesi,
  son tikin lavı, lavda yürüme, lavda düşüş) testleri kırar. `MovementCheckTest`: taştan lava sürülen 70 tik açıklanır;
  karada lavdaki kadar hızlı giden strider bulgu; oltasız ve NoAI strider'da iddia yok. `ClientEntitiesTest`: hızlanma ve
  iki hız değeri.
- **Sürülen happy ghast (`HorseRide`, happy ghast olarak):** koşumlu (gövde yuvasında: `isWearingBodyArmor`) happy
  ghast'ın ilk yolcusu oyuncuysa ve ghast yerinde durmuyorsa (`isOnStillTimeout`: istemcide `STAYS_STILL` verisi) client
  onu kendi fiziğiyle uçurur (`Horse.Flying`): girdi binicinin son tikindeki yan adımı ve ileri/geri tuşu (`xxa`, `zza`),
  ileri binicinin bakışı boyunca (`cos`/`-sin` eğim; geri yarı hızla), zıplama tuşu basılıyken 0.5 yukarı, hepsi uçuş hızı
  özniteliğinin 3.9 katıyla; dönüşü her tik binicinin yönüne yolun %8'i (`tickRidden`, eğimi binicininkinin yarısı);
  yer çekimi yok (`travelFlying`: suda, lavda ve havada aynı hız, sonra hızın 0.8, 0.5 ya da 0.91'i), düşüşü hiç
  sayılmaz (`checkFallDamage` boş), boyutu 4 × 4. Replika koşumu, `STAYS_STILL`'i ve uçuş hızını binişe kadar tutar.
  **İddia yok:** koşumsuz, yavru ya da ölçekli ghast, binişte ya da sürüşte yerinde duran ghast (biri üstüne çıkınca
  sunucu bunu açar: client artık sürmez), koşumu değişen; atınkilerin geri kalanı da. Ghast'la binek zıplaması kanıttır;
  koşumsuz ghast'ta `MoveVehicle` eyer kuralıyla kanıttır. **Doğrulama:** gerçek client parkurunda iki ghast ayağı (yukarı bakarak ileri, zıplama
  tuşuyla yukarı, geri ve yana; aşağı bakarak çime iniş; eğim binişten önce verilir): `Horse` motoru ghast'ın 245 tikinin
  hepsinde client'la bit bit aynı (40'ı zıplama tuşuyla yükselirken, 130'u eğimle sürülürken), takipçi 245 tikin hepsini
  iddia edip açıkladı, her birinde client'ın kendi ghast'ı kümede. `HappyGhastTest`: bakış
  boyunca ileri ve yer çekimsiz, aşağı bakınca aşağı, geri yarı hızla, zıplama tuşuyla yukarı, kısa yoldan %8 dönüş, suda
  0.8, düşüş sayılmaz; `MovementCheckTest`: 45 tik ileri, yukarı ve eğimli sürüş açıklanır, hızından ileri `MoveVehicle`
  bulgu, koşumsuz ghast'ta `MoveVehicle` bulgu, yerinde duran ghast'ta iddia yok; mutasyonlar (zıplama tuşu, uçuş hızı,
  yerinde durma, `wrapDegrees`, 0.91F, geri çarpanı, düşüş) testleri kırar.
- **Sürülen nautilus (`HorseRide`, nautilus olarak; zombi nautilus da):** eyerli ve evcil nautilus'un (`isSaddled`
  `canUseSlot`'tan geçer: eyer yalnızca evcil nautilus'ta sayılır; evcillik `TamableAnimal` bayraklarında) ilk yolcusu
  oyuncuysa client onu kendi fiziğiyle sürer (`Horse.Nautilus`): girdi binicinin son tikindeki yan adımı ve ileri/geri
  tuşu, ileri binicinin bakışı boyunca (`cos`/`-sin` eğim; geri yarı hızla, ölçeksiz, zıplama tuşu yok); dönüşü her tik
  binicinin yönüne yolun yarısı (`tickRidden`, eğimi binicininkinin yarısı); hızı hareket hızı özniteliğinin suda 0.0325,
  karada 0.02 katı (`getRiddenSpeed`); suda yer çekimsiz gider ve hızının 0.9'unu tutar (`travelInWater`), hiçbir sıvı onu
  itmez (`isPushedByFluid`); lavda ve karada sıradan yol. Binek zıplaması atılmadır (`executeRidersJump`): binicinin bakışı
  boyunca suda 1.2, karada 0.5 kere zıplamanın ölçeği, öznitelik ve bloğun hız çarpanı; sonra 40 tik bekleme
  (`dashCooldown`). Binici tuşlarını yalnızca bekleme bitmişken işler (`LocalPlayer.aiStep`: `getJumpCooldown() == 0`;
  yoksa ölçek 0, sayaç donar); `DASH` verisi bekleme bitmişken gelirse bekleme yeniden 40 olur (`onSyncedDataUpdated`).
  Binişte bekleme bilinmez (client binişten önce kurmuş olabilir: 0 ile 40 arası): ilk atılmaya kadar bir bırakış zıplama
  gönderebilir de göndermeyebilir de, gücü denetlenmez; client'ın kendi atılmasından sonra bekleme kesindir. **Kanıt:**
  bekleme kesin sürerken gönderilen binek zıplaması ("while its nautilus's dash cools down"), bekleme bitmişken
  zıplamasız bırakış, tuşun tuttuğundan başka güç ve hızından öte `MoveVehicle`. **İddia yok:** evcil değil, eyersiz,
  yavru ya da ölçekli nautilus, eyeri değişen ya da sürüşte evcilliği kalkan; atınkilerin geri kalanı (suyun binişten
  önce itmesi dışında: nautilus'u itmez). **Doğrulama:** gerçek client parkurunda iki nautilus ayağı (teknelerin
  havuzunda yukarı bakarak ileri, sudan çıkıp geri düşerek, atılma, beklemede basılıp bırakılan tuş, bekleme bitince
  ikinci atılma, geri ve yana; çimde yürüme ve atılma; evcil: sahibi oyuncu): `Horse` motoru nautilus'un 252 tikinin
  hepsinde client'la bit bit aynı (144'ü suda, 108'i karada, 3 atılma, biri karada, 30'u eğimle sürülürken; bekleme
  sayacı ve tikin hızı da), takipçi 252 tikin hepsini iddia edip açıkladı, her birinde client'ın kendi nautilus'u kümede.
  `NautilusTest`: suda yer çekimsiz yüzme hızı (zombi nautilus'un özniteliğiyle
  de), bakışla yukarı, geri yarı hızla, yarım dönüş, akıntı itmez (atı iter), karada yürüme hızı ve yer çekimi, bakış
  boyunca suda 1.2 ve karada 0.5 atılma, 40 tik bekleme ve bitince yeniden atılma, `DASH` verisi; `MovementCheckTest`:
  100 tik ileri, iki atılma ve beklemede basılıp bırakılan tuş açıklanır, beklemenin bitişine taşan basılı tuşun eksi
  gücüyle (-80) zayıf atılma açıklanır, `DASH` verisinden sonra zıplamasız bırakış açıklanır, beklemede atılma, başka
  güçte atılma, bekleme bitmişken zıplamasız bırakış ve hızından öte hareket bulgu, evcil olmayan nautilus'ta iddia yok;
  mutasyonlar (0.9, 0.5 karada, itilmezlik, yarım dönüş, bekleme sayacı, suda hız, atılmanın türü, `DASH`, bekleme
  dalı, bilinen sayaç, zıplamasız bırakış, evcillik) testleri kırar.
- **Araçsızken araç paketi:** yürürken bir `MoveVehicle`, `PaddleBoat` ya da binek zıplaması yaya iddiasını bitirirdi;
  hile her tike bir tane ekleyerek kontrolü susturabilirdi. Vanilla client bunları yalnızca bir aracın yolcusuyken
  gönderir (`LocalPlayer.tick`, kök aracı için `handleMoveVehicle` ve `handleTeleportEntity`, sürdüğü teknenin
  `AbstractBoat.tick`'i, zıplayan bineğin `sendRidingJump`'ı, `isServerControlledInventory` iken envanter;
  `STOP_RIDING_JUMP`'ı hiç göndermez) ve araca yalnızca `SetPassengers` ile biner. Takipçi client'ın olabileceği araçları client
  sırasıyla izler (oyuncuyu listeleyen `SetPassengers` ekler, listelemeyen ya da aracın kaldırılması çıkarır, yeni oyuncu
  ve `Respawn` boşaltır; kayıp olay ya da okunamayan paket "bilinmiyor" yapar). Bir önceki tikte kesin araçsızsa ve o
  zamandan beri işlemiş olabileceği hiçbir paket onu bindiremezse, tikin araç paketi kanıttır ("sent what only a
  passenger sends while riding nothing"). Gerçek client oturumunda kural 13506 tikte devredeydi, 0 bulgu; `MovementCheckTest`:
  araçsızken `MoveVehicle`, `PaddleBoat`, binek zıplaması ve araç envanteri bulgu, inişten hemen sonraki (client'ın hâlâ binmiş olabileceği) tik değil.
- **Kontrol edilemeyen araçta sürücü paketi:** `MoveVehicle`'ı client yalnızca kendi kontrol ettiği kök araç için
  (`isLocalInstanceAuthoritative`), `PaddleBoat`'u sürdüğü tekne, binek zıplamasını sürdüğü binek için gönderir. Bir
  aracı oyuncu ancak ilk yolcusuysa ve aracın türü oyuncuya kontrol veriyorsa kontrol eder (`getControllingPassenger`,
  1.21.11: tekne ve sal, `AbstractHorse` (at, eşek, katır, iskelet ve zombi at, deve, lama), `AbstractNautilus`, domuz,
  strider, happy ghast; `Entity`'ninki null, `Mob`'unki yalnızca bir mob). Client'ın oyuncuyu üstünde tutabileceği her
  araç (kesin işlenenler ve bu tikte işlemiş olabilecekleri) böyle olmayan bir türdense (vagon, başka bir oyuncu, binilmeyen
  bir mob) bu paketlerden biri kanıttır ("sent what only a vehicle's controller sends while riding one no player
  controls"); türü bilinmeyen araçta iddia yok. Türü oyuncuya kontrol veren araç da ancak eyerliyse kontrol edilir
  (`isSaddled`: `AbstractHorse`, `AbstractNautilus`, domuz, strider; happy ghast koşum ister: `isWearingBodyArmor`):
  replika bu türlerin eyer (happy ghast'ın gövde) yuvasını aday aday izler (doğuşta boş, `SetEquipment` ile; kayıp ya
  da bilinmeyen eşya "olabilir"), ve client'ın tutabileceği hiçbir durumda eyeri yoksa araç kontrol edilemeyendir
  ("... on a minecraft:horse with no saddle"). Gerçek client oturumunda kural vagonda 72 tik, eyersiz evcil atta 91 tik
  devredeydi (W basılıyken; client hiç `MoveVehicle` göndermedi), 0 bulgu. `MovementCheckTest`: vagondaki yolcunun tikleri
  açıklanamayan değil, vagonda ve eyersiz atta gönderilen `MoveVehicle` bulgu, eyerli lamada değil. `ClientEntitiesTest`:
  dokuz türün doğuştaki, eyer ve koşum takılınca ve eyer çıkarılınca durumu; mutasyonlar (eyer yok sayılmaz, eyer
  uygulanmaz, koşum okunmaz) testleri kırar.
- **Uçamazken yetenek paketi:** aynı şekilde `PlayerAbilities` de iddiayı bitirirdi. `LocalPlayer.aiStep` onu yalnızca
  `mayfly` (izleyicinin uçmaya başlaması, çift zıplamayla uçma) ya da uçarken yere inince gönderir; client ikisini de
  kendisi açamaz (yalnızca sunucunun yetenek paketi, oyun modu, giriş ve yeniden doğma). Uçabilen ya da uçan bir durum
  zaten kapsam dışıdır (iddia yok); öteki durumlar bu paketi gönderemez, hepsi elenirse tik kanıttır ("sent its flight
  abilities while it could not fly"). `MovementCheckTest`: hayatta kalma modunda bulgu, sunucu uçmaya izin verince iddia yok.

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
