# Faz 2b-6 Raporu: Varlıkların boyutları ve seçilebilirlik

**Amaç:** Reach ve hitbox kontrollerinin ihtiyaç duyduğu üçüncü şey, client'ın her varlığa verdiği
**boyut** ve client'ın o varlığı **seçip seçemeyeceği**. Varlığın kutusu, konumunun etrafında bu boyuttur.
Boyut türe, poza, bebek olup olmamaya, ölçek özelliğine ve bazı türlerde verilerine göre değişir. Bu fazda
kurallar oyunun kendi sınıflarından çıkarıldı. Replika her varlığın boyutunu ve seçilebilirliğini client'ın
işlediği sırayla izliyor. Sonuç hem oyunun kendi metotlarıyla hem de sunucunun hesabıyla karşılaştırılıyor.

## Kurallar (decompile edilmiş 1.21.11)
- **Canlı olmayan varlık:** her pozda türünün boyutu (`Entity.getDimensions`). Interaction genişliğini ve
  yüksekliğini, area effect cloud yarıçapının iki katını verisinden alır.
- **Uyuyan canlı:** 0,2 x 0,2, sabit (`LivingEntity.SLEEPING_DIMENSIONS`).
- **Diğer canlılar:**
  - pozun varsayılan boyutu, ölçek özelliğiyle çarpılır (`getDefaultDimensions(pose).scale(getScale())`);
  - varsayılan boyut, türün boyutunun yaş ölçeğiyle çarpımıdır. Yaş ölçeği yetişkinde 1, bebekte 0,5;
    yunus 0,65, deve 0,45, armadillo 0,6, mutlu ghast 0,2375 ve kaplumbağa 0,3.
- **Kendi bebek boyutu olan 13 sınıf:** zombi (husk, drowned ve zombi köylü dahil), tavuk, inek, at,
  lama, panda ve diğerleri sabit bir `BABY_DIMENSIONS` kullanır. Deve oturunca, keçi uzun atlayışta sabit
  bir boyut alır.
- **Verisiyle boyutlanan 7 sınıf:**
  - slime ve magma cube: boyutuyla çarpılır;
  - phantom: `1 + 0,15 x boyut` ile;
  - pufferfish: şişme durumu 0 ise 0,5, 1 ise 0,7, diğerlerinde 1 ile;
  - salmon: türünün ölçeğiyle (0,5 / 1 / 1,5; kimlik sıkıştırılır);
  - zırh askısı: marker ise 0 x 0 ve sabit, küçükse kendi bebek boyutu;
  - warden: kazarken ya da çıkarken genişliği aynı, 1 yüksek ve sabit;
  - sniffer: kazarken kendi kazma boyutu.
- **Oyuncu ve mannequin:** her poz için bir boyut: ayakta, çömelmiş, yüzerken, uçarken, ölürken.
- **Ölçek özelliği:**
  - 0,0625 ile 16 arasına sıkıştırılır;
  - mutlu ghast'ta en çok 1, shulker'da en çok 3, ender ejderhasında her zaman 1;
  - sabit bir boyut hiçbir ölçekle değişmez.
- **Bebek bayrağı:** türün sınıfına göre farklı bir veri alanından okunur (`AgeableMob`, `Zombie`,
  `Piglin`, `Zoglin`). Kurbağa, papağan ve zombi nautilus hiç bebek olmaz. Client'ta `AgeableMob` yaşını
  bu bayraktan okur (sunucuda kendi sayacından).

## Seçilebilirlik
Client'ın nişangâhı bir varlığı ancak `isPickable()` ise ve varlık spectator değilse seçebilir
(`GameRenderer.pick`, `ProjectileUtil.getEntityHitResult`).
- **Seçilebilir:** canlılar, tekneler, vagonlar, TNT, düşen bloklar, asılı varlıklar, interaction, end
  crystal ve shulker mermisi (125 tür).
- **Koşullu:**
  - zırh askısı marker değilse;
  - oyuncu spectator değilse.
- **Seçilemez:** ender ejderhasının kendisi; yalnızca parçaları seçilir.
- **Mermiler:** sunucunun gönderdiği bir etikete (`redirectable_projectile`) bağlıdır. Bu etiket izlenmediği
  için seçilebilirlikleri iddia edilmez (18 tür).
- **Seçme yarıçapı:** kutu bu yarıçap kadar büyütülür. Seçilebilen bir mermide 1, diğer her şeyde 0.
- **Spectator:** oyuncu listesinin oyun modu client sırasıyla izlenir. Client'taki oyuncu varlığı ilk
  baktığı PlayerInfo nesnesini saklar (`AbstractClientPlayer.getPlayerInfo`). Bu yüzden bir PlayerInfo
  varlık dururken kaldırılırsa o varlığın durumu "bilinmiyor" sayılır.

## Ne yapıldı
- **Boyut tablosu:** `entity-sizes.tsv` (157 tür) ve `player-poses.tsv` elle yazılmadı.
  - `EntitySizesDataTest`, boyutları, bebek ve poz boyutlarını, bebek bayrağının ve boyut verilerinin
    indekslerini, ilk değerlerini ve sabitlerini oyunun sınıflarından okur. Sabitler pufferfish ölçekleri,
    salmon türleri, zırh askısı bayrakları ve sniffer kazma durumudur.
  - İlgili metotları geçersiz kılan sınıfların kümesi kayıtlı listeyle karşılaştırılır. Metotlar şunlar:
    `getDefaultDimensions`, `getDimensions`, `makeBoundingBox`, `getAgeScale`, `sanitizeScale`, `isBaby`,
    `isPickable`, `getPickRadius`, `isSpectator`. Oyun güncellemesi yeni bir sınıf eklerse test o
    sınıfın adıyla durur.
- **Oyunla karşılaştırma (`EntitySizesGameTest`):**
  - Her kural oyunun kendi `getDimensions(pose)` ve `isPickable()` metotlarıyla, bit bit karşılaştırılır.
  - Kapsam: her tür, her poz, bebek ve yetişkin, aralığın içinde ve dışında 11 ölçek. Ayrıca her türün
    verisinin alabileceği değerler: zırh askısı bayraklarının 256 değeri, her sniffer durumu, aralık dışı
    slime ve phantom boyutları, şişme durumları ve salmon türleri, interaction ve cloud float'ları.
  - Toplam 100.000'den fazla durum.
  - Varlıklar oyunun sınıflarıdır: kurucusu çalıştırılmadan oluşturulur. Oyunun boyut metotlarının
    okuduğu alanlar elle doldurulur: tür, `Entity` kurucusunun tanımladığı veri, özellikler ve
    `isClientSide` diyen bir dünya nesnesi.
  - Yeni oluşan bir varlığın boyutu, ilk verisiyle tablonun verdiği boyuta eşit.
- **`EntitySizes`** (`whynotac-world`):
  - kuralları uygular;
  - boyuttan kutuyu `EntityDimensions.makeBoundingBox` ile aynı float işlemleriyle kurar;
  - seçilebilirliği verir.
- **Protokol:** veri değerlerine sniffer durumu eklendi (`SNIFFER_STATE`, kimliğiyle; kayıtta sıra numarası
  sona eklendi ve dondurulmuş sıra testine girdi).
- **Replika:**
  - bebek bayrağını (`set_entity_data`), ölçek özelliğini (`update_attributes`), türün boyut verisini ve
    oyuncuların oyun modunu client sırasıyla izler;
  - her olası durum bir boyut ve bir seçilebilirlik taşır;
  - durumlar uyuşmazsa sonuç "bilinmiyor" olur.
- **Ölçek değeri:** `AttributeInstance.calculateValue` ile aynı sırada hesaplanır.
  - Bir işlem türünde iki ya da daha çok değiştirici varsa değer "bilinmiyor" sayılır. Client onları
    hash map sırasıyla toplar ve kayan noktalı toplam sıraya bağlıdır.
- **Boyut iddia edilmeyen 8 tür:**
  - client tarafında animasyonla büyüyen kutup ayısı ve shulker;
  - kutusu konumunun etrafında olmayan wind charge'lar, asılı varlıklar ve leash knot.
- **`/whynotac entities`:** boyutları ve seçilebilirliği sunucunun kendi hesabıyla karşılaştırır.

## Doğrulama

| Test | Sonuç |
|---|---|
| Üretici | 157 tür ve 7 oyuncu pozu oyunun sınıflarından birebir; geçersiz kılan sınıf kümeleri kayıtlı listeyle aynı |
| Oyunla karşılaştırma | 100.000'den fazla tür/poz/bebek/ölçek/veri durumunda boyut, her türde ve 256 zırh askısı bayrağında seçilebilirlik ve seçme yarıçapı oyunun kendi metotlarıyla bit bit eşit |
| Birim | Oyuncu pozları, bebek kuralları, poza özel boyut, ölçek sınırları, veriye bağlı boyutlar, seçilebilirlik, kutu hesabı (10 senaryo). Replikada: bebek bayrağı ve slime boyutu işlenmeden iki boyut da mümkün; başka serileştiricideki değer boyut değil; zırh askısı küçük, sonra marker ve ölçek; interaction'ın iki float'ı; spectator, oyun modu değişince iki durum, listede olan profil yeniden eklenince oyun modu korunur (`putIfAbsent`), PlayerInfo kaldırılınca "bilinmiyor"; mermi ve ejderha (7 senaryo). 1000 tohumlu simülasyon değişmedi |
| E2E (gerçek Paper) | Sunucunun `getDimensions(getPose())` ve `isPickable() && !isSpectator()` hesabıyla: 16 boyut eşit, 0 farklı; 11'i türün varsayılanından farklı (bebekler, ölçekli husk, slime, magma cube, phantom, pufferfish, salmon, marker, interaction, cloud). Seçilebilirlik 16 eşit (marker ve cloud seçilemez), 0 farklı, 2 bilinmiyor. 84 E2E kontrolü geçiyor |
| Gerçek client | Değiştirilmemiş 1.21.11 client'ı: 12 boyut eşit (4'ü türün varsayılanından farklı: bebek köylü, slime, interaction...), 0 farklı; seçilebilirlik 12 eşit, 0 farklı |

## Bulunan ve düzeltilen hatalar
- **Peaceful zorluk:** E2E ve gerçek client testleri "peaceful" zorlukta çalışıyordu.
  - Bu zorlukta düşman mob oluşturulamaz, bu yüzden testlerdeki husk aslında hiç yoktu. Faz 2b-5
    raporundaki "6/6" husk'ı içermiyordu.
  - Testler artık varlık bölümünden önce zorluğu "easy"ye alıyor. Doğal düşman doğmasını da kapatıyorlar:
    1.21.11'de bu bir oyun kuralı, eski sunucu ayarı etkisiz.
  - Konum karşılaştırması husk'larla birlikte 11/11 eşit.
- **Dünya karşılaştırmasında yarış (CI):** gerçek client işi bir kez 1944 bölümden birini farklı buldu.
  - Neden: bir koyun çimi yemiş ve sunucu bloğu değiştirmişti, ama değişiklik tick'in sonunda
    gönderiliyor. `/whynotac world` arada çalıştı: tek seferlik karşılaştırma paketi yolda yakaladı.
  - `/whynotac world` artık varlık karşılaştırması gibi turlarla çalışıyor. Bir fark ancak sunucunun
    bölümü bir tur (iki tick) boyunca değişmeden kaldıysa sayılıyor. Son tura kadar değişmeye devam eden
    bölümler ayrıca `changing` olarak raporlanıyor.
- **Paper'ın `isPickable`'ı:** Paper/Bukkit, canlıların `isPickable()` metoduna sunucu tarafı bir alan
  ekliyor (`collides`, `setCollidable` API'si). Vanilla client bu alanı bilmiyor.
  - Oyunla karşılaştırma testi bu farkı bayt koduyla buldu ve doğruladı.
  - `/whynotac entities`, bir eklentinin çarpışmasını kapattığı canlıların seçilebilirliğini
    karşılaştırmıyor.

## Sınırlar ve sonraki adımlar
- **Animasyonla değişen boyutlar:**
  - kutup ayısının boyutu client'ta ayağa kalkarken tick tick büyür (en çok iki kat yükseklik);
  - shulker'ın kutusu client'ta açılma miktarıyla değişir.
  Her ikisi de client tick'leriyle birlikte izlenmeli. Sunucu bu animasyonu çalıştırmaz, bu yüzden
  doğrulama gerçek client'ın kendi kaydıyla yapılmalı.
- **Kutusu konumunun etrafında olmayanlar:** wind charge'lar (0,15 aşağıda), asılı varlıklar ve leash
  knot (bloğa ve yöne göre).
- **Mermilerin seçilebilirliği:** sunucunun gönderdiği varlık etiketleri (`update_tags`) kaydedilirse
  iddia edilebilir.
- **Ender ejderhası:** client'ta parçaları (`EnderDragonPart`) ayrı kutulardır, ayrıca modellenmeli.
