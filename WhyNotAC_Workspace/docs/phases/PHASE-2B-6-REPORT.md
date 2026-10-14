# Faz 2b-6 Raporu: Varlıkların boyutları

**Amaç:** Reach ve hitbox kontrollerinin ihtiyaç duyduğu üçüncü şey, client'ın her varlığa verdiği
**boyut**. Varlığın kutusu, konumunun etrafında bu boyuttur. Boyut türe, poza, bebek olup olmamaya ve
ölçek özelliğine göre değişir. Bu fazda boyut kuralları oyunun kendi sınıflarından çıkarıldı. Replika her
varlığın boyutunu client'ın işlediği sırayla izliyor ve sonuç sunucunun kendi hesabıyla karşılaştırılıyor.

## Kurallar (decompile edilmiş 1.21.11)
- **Canlı olmayan varlık:** her pozda türünün boyutu (`Entity.getDimensions`).
- **Uyuyan canlı:** 0,2 x 0,2, sabit (`LivingEntity.SLEEPING_DIMENSIONS`).
- **Diğer canlılar:**
  - pozun varsayılan boyutu, ölçek özelliğiyle çarpılır (`getDefaultDimensions(pose).scale(getScale())`);
  - varsayılan boyut, türün boyutunun yaş ölçeğiyle çarpımıdır. Yaş ölçeği yetişkinde 1, bebekte 0,5;
    yunus 0,65, deve 0,45, armadillo 0,6, mutlu ghast 0,2375 ve kaplumbağa 0,3.
- **Kendi bebek boyutu olan 13 sınıf:** zombi (husk, drowned ve zombi köylü dahil), tavuk, inek, at,
  lama, panda ve diğerleri sabit bir `BABY_DIMENSIONS` kullanır. Deve oturunca, keçi uzun atlayışta sabit
  bir boyut alır.
- **Oyuncu ve mannequin:** her poz için bir boyut: ayakta, çömelmiş, yüzerken, uçarken, ölürken.
- **Ölçek özelliği:**
  - 0,0625 ile 16 arasına sıkıştırılır;
  - mutlu ghast'ta en çok 1, shulker'da en çok 3, ender ejderhasında her zaman 1;
  - sabit bir boyut hiçbir ölçekle değişmez.
- **Bebek bayrağı:** türün sınıfına göre farklı bir veri alanından okunur (`AgeableMob`, `Zombie`,
  `Piglin`, `Zoglin`). Kurbağa, papağan ve zombi nautilus hiç bebek olmaz.

## Ne yapıldı
- **Boyut tablosu:** `entity-sizes.tsv` (157 tür) ve `player-poses.tsv` elle yazılmadı.
  - `EntitySizesDataTest`, boyutları, bebek ve poz boyutlarını ve bebek bayrağının veri indeksini oyunun
    sınıflarından okur.
  - Her türün boyutunun hangi sınıftan geldiğini reflection ile bulur.
  - İlgili metotları (`getDefaultDimensions`, `getDimensions`, `makeBoundingBox`, `getAgeScale`,
    `sanitizeScale`, `isBaby`) geçersiz kılan sınıfların kümesi kayıtlı listeyle karşılaştırılır. Oyun
    güncellemesi yeni bir sınıf eklerse test o sınıfın adıyla durur.
- **`EntitySizes`** (`whynotac-world`):
  - kuralları uygular;
  - boyuttan kutuyu `EntityDimensions.makeBoundingBox` ile aynı float işlemleriyle kurar.
- **Replika:**
  - bebek bayrağını (`set_entity_data`) ve ölçek özelliğini (`update_attributes`) client sırasıyla izler;
  - her olası durum bir boyut taşır.
- **Ölçek değeri:** `AttributeInstance.calculateValue` ile aynı sırada hesaplanır.
  - Bir işlem türünde iki ya da daha çok değiştirici varsa değer "bilinmiyor" sayılır. Client onları
    hash map sırasıyla toplar ve kayan noktalı toplam sıraya bağlıdır.
- **Boyut iddia edilmeyen türler:** boyutu takip edilmeyen veriye bağlı 18 tür. Bunlar:
  - slime ve phantom boyutu;
  - pufferfish şişmesi;
  - zırh askısı bayrakları;
  - kazan ya da çıkan warden ve sniffer;
  - client tarafında animasyonla büyüyen kutup ayısı;
  - asılı ve veriyle boyutlanan varlıklar.

## Doğrulama

| Test | Sonuç |
|---|---|
| Üretici | 157 tür ve 7 oyuncu pozu oyunun sınıflarından birebir; geçersiz kılan sınıf kümeleri kayıtlı listeyle aynı |
| Birim | Oyuncu pozları, bebek kuralları, poza özel boyut, ölçek sınırları, kutu hesabı (8 senaryo). Replikada: bebek bayrağı işlenmeden iki boyut da mümkün, sonra yalnız bebek; tam ölçek değeri ve sırası belirsiz iki değiştirici; canlı olmayan varlıkta ölçek yok; çömelen oyuncu; slime için iddia yok (5 senaryo). 1000 tohumlu simülasyon değişmedi |
| E2E (gerçek Paper) | `/whynotac entities` boyutları sunucunun kendi `getDimensions(getPose())` hesabıyla karşılaştırır. 7 eşit, 0 farklı. Eşitlerin 3'ü türün varsayılanından farklı: bebek husk, 1,5 ölçekli husk, bebek tavuk. 4 varlığın boyutu iddia edilmedi (zırh askısı, slime, kendi fiziğiyle hareket eden eşyalar). 84 E2E kontrolü geçiyor |
| Gerçek client | Değiştirilmemiş 1.21.11 client'ı: 6 eşit (bebek köylü dahil), 0 farklı |

**Bulunan ve düzeltilen hata:** E2E ve gerçek client testleri "peaceful" zorlukta çalışıyordu. Bu zorlukta
düşman mob oluşturulamaz, bu yüzden testlerdeki husk aslında hiç yoktu. Faz 2b-5 raporundaki "6/6"
husk'ı içermiyordu. Testler artık varlık bölümünden önce zorluğu "easy"ye alıyor. Doğal düşman doğmasını
da kapatıyorlar: 1.21.11'de bu bir oyun kuralı, eski sunucu ayarı etkisiz. Konum karşılaştırması
husk'larla birlikte 11/11 eşit.

## Sınırlar ve sonraki adımlar
- **Duruma bağlı boyutlar:** slime ve phantom boyutu, pufferfish, zırh askısı, warden ve sniffer pozları
  gerekli veri alanları izlenerek eklenecek. Kutup ayısının boyutu client'ta bir animasyonla büyüdüğü için
  zaman içinde ayrıca izlenmeli.
- **Seçilebilirlik:** client'ın bir varlığı hedefleyip hedefleyemeyeceği (`isPickable`), seçme yarıçapı
  (`getPickRadius`) ve spectator oyuncular. Oyun modu oyuncu listesinden zaten kaydediliyor.
- **Ender ejderhası:** client'ta parçaları (`EnderDragonPart`) ayrı kutulardır, ayrıca modellenmeli.
