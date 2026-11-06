# Faz 2b-8 Raporu: Kendi oyuncunun durumu ve saldırı mesafesi kontrolü

**Amaç:** İlk P-A kontrolü (`attack-reach`): client'ın gönderdiği bir saldırının, hiçbir vanilla 1.21.11
client'ın ulaşamayacağı bir uzaklıktan yapıldığını kanıtlamak. Kontrol SHADOW'dadır: kanıt kaydedilir,
vaka açılır, oyuncuya hiçbir şey yapılmaz. Bunun için dördüncü replika eklendi: client'ın kendi oyuncusu
(`ClientSelf`). Göz konumu, binek, kamera, oyun modu, `entity_interaction_range` ve ölçek client'ın
işlediği sırayla izleniyor.

## Saldırı nasıl karar verilir (decompile edilmiş 1.21.11)
- **İsabet tikin başında** (`GameRenderer.pick(1.0F)`, `Minecraft.tick` içinde hotbar tuşlarından önce)
  kamera varlığının gözünden hesaplanır.
  - `attack_range` yoksa normal seçme kullanılır. Hedefin kutusu seçme yarıçapıyla genişletilir
    (mermiler için 1). İsabet noktası `entity_interaction_range`'den kesinlikle yakın olmalıdır
    (`filterHitResult`).
  - Varsa eşyanın kendi ışını kullanılır. Bu ışın özniteliğe bağlı değildir, isabet noktası kutunun
    kendisi üzerindedir. Işın bir şeye çarpmazsa normal seçmeye düşülür.
- **Kontrol saldırı anında** (`startAttack`) ana eldeki eşyayla yapılır. `attack_range` varsa isabet
  noktası oyuncunun gözüne en çok `max_reach + hitbox_margin` uzaklıkta olmalıdır
  (`AttackRange.isInRange`); yaratıcı modda `max_creative_reach` geçerlidir.
- **Alt sınır:** isabet noktası hangi yoldan bulunursa bulunsun, gözün genişletilmiş kutuya uzaklığı
  isabet noktasının uzaklığından küçüktür. Kontrol bu alt sınırı kullanır (`AttackReach`).
- **Karar:** saldırı, isabetteki ve kontroldeki olası eşyaların her çifti, her göz konumu ve hedefin her
  durumu için erişim dışındaysa erişim dışıdır.

## Gözün yeri (`ClientSelf`)
- **Son gönderilen konum:** isabet bir önceki tikin hareketinden sonraki konumdan yapılır. O tik
  `sendPosition` ile biter. `sendPosition`, konum son gönderilenden 2·10⁻⁴'ten fazla değişmişse yeni
  konumu gönderir. Bu yüzden göz, son gönderilen konumun 2·10⁻⁴ yakınındadır.
- **Bildirimsiz hareket ettirenler:** kod okuyucular, son gönderilen konumdan sonra oyuncuyu yanıt
  göndermeden hareket ettiren her yolu buldu:
  - binek (binicinin yerini binek belirler; inen oyuncu bulunduğu yerde kalır),
  - yeni oyuncu (giriş, yeniden doğma),
  - blok tahmininin geri alınması (`BlockChangedAck`),
  - oyuncunun kendi kimliğine gelen ışınlama ve senkron paketleri,
  - okunamayan bir paket.

  Bunlardan biri client tarafından son konum gönderiminden sonra işlenmiş olabiliyorsa göz bilinmez.
  `PlayerPosition` buna dahil değildir: işleyicisi yeni konumu hemen gönderir.
- **Gözün yüksekliği:** önbellekteki bir alandır; duruşun boyutlarından ve son `refreshDimensions`
  anındaki ölçekten gelir.
  - Değerler: ayakta 1.62, eğilmiş 1.27, yüzerken, süzülürken ve dönerek saldırırken 0.4 (hepsi ölçekle
    çarpılır); uyurken 0.2, ölürken 1.62 (ölçek fark etmez).
  - Duruş izlenmez; göz bu zarfın herhangi bir yerinde olabilir. Bu, yatay erişimi hiç etkilemez.
  - Yeni bir ölçek, client yüklendikten sonraki ilk tikin sonunda etkili olur. O ana kadar eski değer de
    olası kalır.
- **Oyun modu:** `Player.isCreative()` ve `isSpectator()`, client'ın oyuncu listesindeki kendi girdisini
  okur. Bu girdi, oyuncunun önbelleğe aldığı nesnedir; silinip yeniden eklenirse takip edilmez.
  `MultiPlayerGameMode` modu ayrıca izlenir. Oyun modu olayı `floor(param + 0.5)` ile çözülür, aralık
  dışındaki değer hayatta kalma modudur. Sonraki bir giriş oyuncuyu koruyabilir.
- **Kamera:** `set_camera` modellendi (protokol, kayıt etiketi 52, çevirici). Kamera başka bir varlıksa
  isabet onun gözünden yapılır; göz bilinmez.
- **Öznitelikler:** `entity_interaction_range` canlı okunur. Her önekteki değerin aralığı alınır
  (`AttributeValues`). Yeniden doğmada değiştiriciler yalnızca sunucu söylerse korunur, yoksa yalnızca
  taban değerler kalır.

## Hedefin kutusu
- **Hedefin durumları:** `ClientEntities`'in olası her durumu ayrı değerlendirilir. Herhangi bir durumda
  hedef yoksa, bilinmiyorsa, konumu ya da boyutu bilinmiyorsa veya seçilemiyorsa kontrol iddiada bulunmaz.
- **Yeni düzeltme:** başka bir varlığın ölçeği değiştiğinde client kutusunu hemen değil, o varlığın bir
  sonraki tikinin sonunda yeniler (`refreshDirtyAttributes`). Replika ölçeği hemen uyguluyordu. Artık
  kutu yenilenene kadar boyut bilinmiyor sayılıyor (`shownScale`).
- **Mermiler:** istemci mermileri kendisi hareket ettirir (`ClientMotion`). Konumları bilinmez, bu yüzden
  iddia yoktur.
- **Ejderha parçaları:** replikada yoktur; iddia yoktur.

## Oyuncuyu iten bloklar ve varlıklar
Bazı bloklar ve varlıklar oyuncunun tikinden sonra çalışır ve onu iter:
- pistonun hareketli bloğu ve bal (`PistonMovingBlockEntity.moveCollidedEntities`),
- açılan shulker kutusu,
- shulker (`onPeekAmountChange`).

Bunlar son gönderilen konumdan sonra oyuncuyu kaydırabilir. Kontrol iki durumda iddiada bulunmaz:
- **Bloklar:** oyuncunun kutusunun 2 blok çevresinde şu bloklardan biri varsa ya da bilinmiyorsa:
  piston, yapışkan piston, piston başı, hareketli piston, herhangi bir shulker kutusu
  (`ClientWorld.mayMoveEntitiesIn`). Böyle bir bloğa giden ya da ondan dönen bir değişiklik, client son
  konumu gönderdikten sonra işlenmiş olabiliyorsa da durum aynıdır.
- **Shulker:** 12 blok içinde bir shulker olabiliyorsa (bir itme ve en çok 8 bloğu ışınlanma). Shulker'ın
  bu süre içinde silinmiş olması da sayılır (`ClientEntities.mayHaveNear`).

## Gizlilik düzeltmesi
Kayıtlar yalnızca `Bound` olayındaki UUID'yi takma adla değiştiriyordu. Oyuncu listesindeki ve varlık
ekleme paketlerindeki UUID'ler ham kalıyordu; bunlara oyuncunun kendi UUID'si ve diğer oyuncularınki de
dahildi. Artık kayıttaki her UUID aynı HMAC takma adıyla değiştiriliyor.
- Böylece gizlilik belgesindeki taahhüt doğru hale geldi. Kayıt testi ham baytlarda iki UUID'nin de
  geçmediğini kontrol ediyor.
- `verify`'daki yeniden oynatma, oyuncu listesi girdisini canlıdakiyle aynı şekilde oyuncuya bağlıyor.

## Doğrulama
- **Birim testleri:**
  - `AttackReachTest`: kesin ve dahil sınırlar, seçme yarıçapı, menzil eşyaları, eşya çiftleri.
  - `ClientSelfTest`: 18 test; göz, binek, geri alma, kamera, modlar, öznitelikler, ölçek gecikmesi,
    yüklenme.
  - `AttackReachCheckTest`: replika zinciri ve SHADOW kanıtı.
  - `ClientEntitiesTest`: ölçek gecikmesi.
  - Kayıt gizlilik testi.
- **E2E (106 kontrol):**
  - Probe yanındaki bir husk'a menzil içinden saldırır: vaka açılmaz.
  - 7 blok uzaktaki bir koyuna saldırır: `attack-reach` vakası açılır (P-A, mesafe 6.55, izin verilen
    4.75; elde menzili 4.5 + 0.25 olan çubuk vardı).
  - `verify` kaydı yeniden oynatıp aynı kanıtı buldu.
- **Gerçek client:** sunucu oyuncuyu, hasar almayan ve yapay zekâsı kapalı bir husk'a doğru bakacak
  şekilde yerleştirdi. Gerçek tıklama sonuçları:

  | Silah | Uzaklık | Sonuç |
  |---|---|---|
  | Çubuk | 4.70 | Saldırı gönderildi; denetim sonucu menzil içi, 4.6998 ≤ 4.75 |
  | Kılıç | 2.98 | Saldırı gönderildi; menzil içi, 2.9798 < 3.0 |
  | Kılıç | 3.05 | Client saldırıyı hiç göndermedi |

  `ReachAudit` her saldırıyı kayıttan kontrolle aynı şekilde değerlendirir. İki saldırının hiçbirinde
  kontrol iddiadan kaçınmadı ve vaka açılmadı. Böylece sınırın gerçek client'ın sınırıyla aynı olduğu
  iki yönden gösterilmiş oldu.
- **Yük testi:** botlar, yanlarına çağrılan hareketsiz ineklere saldırır; ineğin kutusu, son gönderdikleri
  konumdaki gözün 2.95 blok yakınında olmalıdır. Kayıtların hepsi `ReachAudit` ile denetlendi
  ([sonuçlar](../benchmarks/2026-10-15-attack-reach/README.md)).
  - 60 bot, sunucu tam kapasitede (TPS 17.3).
  - 5.467 saldırının hepsi değerlendirildi ve menzil içi bulundu; en uzak saldırı 2.941 blokta, en küçük
    pay 0.059.
  - Menzil dışı 0, iddiadan kaçınma 0, vaka 0.
- **`/whynotac self`:** replikanın gözünün dayandığı konumu, menzil ve ölçek aralıklarını ve oyun modunu
  sunucununkiyle karşılaştırır. E2E'de ve gerçek client'ta çalışır.

## Sınırlar (iddia yok)
- Yaratıcı ve izleyici mod. Yaratıcı modda kayıtlı araç çubuğu yüklenince, devre dışı özellikli eşyalar
  sunucuya bildirilmeden ele girebilir.
- Binek, başka kamera, yeni oyuncu.
- Blok geri almasının işlenmiş olabileceği tik (bir tik sürer; sonraki konum gönderimi bunu kapatır).
- Mermiler, ejderha parçaları, boyutu duruma bağlı türler (shulker, kutup ayısı, asılı varlıklar,
  rüzgâr topu), bilinmeyen öznitelik sırası.
- `min_reach` (çok yakından saldırı) denetlenmez.
- Pay: 10⁻⁴ blok yuvarlama.
