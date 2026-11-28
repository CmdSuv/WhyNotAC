# Faz 2b-7 Raporu: Envanter replikası (eldeki eşya ve menzili)

**Amaç:** Reach kontrolünün ihtiyaç duyduğu dördüncü şey, client'ın saldırırken elinde ne tuttuğu. Bir
eşyanın `minecraft:attack_range` bileşeni varsa client o eşyayla başka bir menzille vurur (mızraklar,
eklentilerin özel silahları). Bu fazda client'ın hotbar'ı, yedek eli, seçili slotu ve açık menüsü client'ın
işlediği sırayla izleniyor. Sonuç sunucunun envanteriyle, sentetik bir client ve gerçek oyun client'ı ile
karşılaştırılıyor.

## Saldırı menzili neyi okur (decompile edilmiş 1.21.11)
- **İsabet tikin başında hesaplanır:** `GameRenderer.pick`, `Minecraft.tick`'in başında, hotbar tuşlarından
  önce çalışır. Kullandığı eşya `getActiveItem()`: bir eşya kullanılıyorsa o, değilse ana el.
  - `attack_range` yoksa: normal seçme, `entity_interaction_range` (hayatta kalmada 3, yaratıcıda 5) içinde.
  - Varsa: kendi ışını. Işın en az `min_reach` uzaklıktan başlar, `max_reach + max(0, hareket · bakış)`
    uzaklıkta biter. `hitbox_margin` kutuları genişletir. Işın bir şeye çarpmazsa normal seçmeye düşer.
- **Kontrol saldırı anındaki ana elle yapılır** (`startAttack`): eşyanın `attack_range`'i varsa isabet
  noktası göze en çok `max_reach + hitbox_margin` uzaklıkta olmalı. Yoksa ek kontrol yok.
  - Saldırı yalnızca client bir eşya kullanmıyorken olur. O tikte isabeti de ana el belirlemiştir; yedek el
    saldırı menzilini hiç etkilemez.
  - Aynı tikte hotbar tuşuna basıldıysa isabeti eski slot, kontrolü yeni slot belirler. Bu yüzden replika
    "isabet anında" seçili olabilecek her slotu ayrıca tutar (son tik sonundan beri seçilen her slot).
- **Mızraklar** `piercing_weapon` taşır. Client onlarla saldırı paketi değil `STAB` eylemi gönderir.
- **Sonuç:** menzili küçük bir eşya biterse el boşalır ve menzil büyür. Replika bu yüzden "kullanılmış
  olabilir" durumunu açıkça tutar: o slotun menzil kümesine "yok" da girer.

## Paket işleyicileri (`ClientPacketListener`)
- **Konteyner 0 her zaman oyuncunun envanter menüsüdür.** Başka bir menü açıkken bile konteyner 0'a gelen
  slot ve içerik envantere yazılır. Diğer kimlikler yalnızca açık menüye yazılır, gerisi yok sayılır.
- **Kısa içerik listesi:** listenin ötesindeki slotlara dokunmaz.
- **Binek menüsü:** varlık client'ta at ya da nautilus olarak yoksa hiçbir şey değişmez. Replika üç olasılığı
  da tutar: at menüsü, nautilus menüsü, değişiklik yok. Client'ın ilk tıklaması doğru olanı seçer.
- **Yeniden doğma:** her zaman yeni, boş bir oyuncu: seçili slot 0, kendi envanter menüsü.
- **Giriş:** bağlantının ilk girişi her zaman yeni oyuncudur. Sonraki girişte client oyuncuyu koruyabilir
  (yeniden yapılandırma olmadan), bu yüzden replika iki olasılığı da tutar.
- **Oyun modu:** giriş ve yeniden doğma paketlerine eklendi (kayıt etiketleri 48 ve 49).
- **Seçili slot:** sunucunun `SetHeldSlot`'u ve client'ın kendi `SetCarriedItem`'ı.

## Menü düzenleri
`menu-layouts.tsv` elle yazılmadı. `MenuLayoutsDataTest`, her menü türünün client menüsünü
(`MenuType.create`, `MenuScreens.create`'in yaptığı gibi), envanter menüsünü ve binek menülerini kurar ve
hangi slotun oyuncunun envanteri olduğunu okur.
- Sandık ve benzerleri: oyuncu slotları sonda, önce 27 ana slot, sonra 9 hotbar slotu.
- **Crafter:** sonuç slotu hotbar'dan sonra gelir (hotbar 36..44, sonuç 45). "Son dokuz" kuralı burada yanlış
  olurdu.
- **Lectern:** oyuncu slotu yoktur.
- **At menüsü:** semer, zırh, `3 x sütun` sandık slotu, sonra oyuncu. Test bu formülü 0..16 sütun için
  oyunla karşılaştırır.
- **Nautilus menüsü:** sütun sayısı ne olursa olsun sandık slotu yoktur.
- Okuyucu iş akışı aynı tabloya kaynak koddan bağımsız olarak ulaştı.

## Client'ın kendi değişiklikleri
- **Tıklama:** menüde değiştirdiği her slotu hash'lenmiş haliyle bildirir (eşya, adet, yama bileşenlerinin
  türleri ve her değerin CRC32C hash'i). İstisna: bir sandıkta yedek elle yer değiştirme (`SWAP` 40).
  Sandıkta yedek el slotu olmadığı için bu değişiklik pakette yoktur; yedek el bilinmiyor sayılır.
- **Yaratıcı mod:** değişiklikleri slot slot `SetCreativeModeSlot` ile gönderir, ama yalnızca hâlâ yaratıcıyken.
  - Yaratıcı ekranı açıkken mod değişirse sonraki düzenlemeler gönderilmez. Bu yüzden yaratıcıdan çıkışta eller
    unutulur.
  - Yaratıcı ekranının menüsü de 0 kimliğini kullanır. Kapanana kadar o menüdeki tıklamalar okunmaz.
- **Bırakma tuşu (Q):** seçili yığını küçültür, ama hangi slot olduğunu söylemez. Aynı tikin hotbar tuşu bir
  sonraki tikte gönderilir. Bu yüzden her hotbar slotu "boşalmış olabilir" işaretlenir.

## Eşya kullanımı (`VanillaUseEffects`)
Bir sınıflandırma iş akışı 66 blok, 53 varlık ve 51 eşya sınıfını ve bileşen kodunu okudu. Sorulan şuydu:
client tarafında (`isClientSide`) oyuncunun envanteri nasıl değişiyor?
- **Bloklar:** yalnızca çiçek saksısı, kovan, pasta ve respawn anchor. Hepsi ya yığını küçültür ya da düz bir
  eşya verir. Sandık, raf, kitaplık, kazan, jukebox ve lectern değişiklikleri yalnızca sunucuda olur.
- **Varlıklar:** yığını küçültür, kova doldurur ya da kalıntı bırakır. Kovadaki balığın bileşenleri varlıktan
  gelir, hiçbiri menzil değildir. Zırh askısı ve eşya çerçevesi takasları yalnızca sunucuda olur.
  - **Allay istisnası:** eli boşken allay'in eşyasını geri almak başka yerden bir yığın getirir
    (`Inventory.add`, bildirilmez). Bu durumda her boş hotbar slotu bilinmiyor sayılır. Replika sunucunun
    allay olarak duyurduğu varlık kimliklerini hatırlar; kimlikler hiç yeniden kullanılmaz.
- **Eşyalar:** küçültür ya da kova, şişe veya kase gibi menzili olmayan bir yığına dönüştürür.
  - **Zırhlar ve elytra** (30 eşya): `Item.use` onları oyuncunun zırh slotuyla yer değiştirir. Tek eşyada el
    eski zırhı alır, bu yüzden bilinmiyor sayılır. Yığında eski zırh ilk boş slota gider.
  - **Kendi yamasından gelen kalıntı** (`use_remainder`): her şey olabilir, bu yüzden bilinmiyor sayılır.
- **Sonuç:** her kullanım "aynı yığın ya da yok", "bilinmiyor" ya da "yok ve envantere bilinmeyen bir yığın"
  olur.

## Hash çözümleme
Bir tıklama bileşenlerin değerini değil hash'ini taşır. Replika sunucunun gönderdiği her tam yığının menzil
değerini, client'ın hesaplayacağı hash ile hatırlar.
- Hash, client'ın kendi üreteci gibi bileşenin kodlaması üzerinde CRC32C ile hesaplanır. `attack_range`
  kayıt öğesi içermez, bu yüzden client'ın eklediği kayıt bağlamı sonucu değiştirmez.
- Tıklamayla taşınan özel menzilli bir yığın bu hash ile bilinen değere çözülür.
- `InventoryPacketViewsTest`, 200 rastgele menzilde çeviricinin hash'ini client'ın üreteciyle karşılaştırır.
  Gerçek client testi de aynı şeyi gerçek tıklamalarla gösterir.

## Doğrulama
- **Birim testleri:** `ClientInventoryTest` (29 test) ve `VanillaUseEffectsTest`.
- **Veri testleri:** `MenuLayoutsDataTest` ve `ItemPrototypesDataTest` (takas slotları dahil).
- **`/whynotac inventory <oyuncu>`:** replikanın elleri sunucunun envanteriyle iki tiklik turlarla
  karşılaştırılır: dokuz hotbar yığını, yedek el, seçili slot, açık menü. Ayrıca replikanın ana el menzil
  kümesinin sunucununkini içerip içermediğine bakılır.
- **E2E:** probe hotbar 2'yi seçer ve kılıcı iki tıklamayla taşır. Sonuç `matches=true`, 10/10 slot eşit,
  `mainHand=4.5+0.25` (çubuğun kendi menzili).
- **Gerçek client:** probe'a bir kılıç, özel menzilli bir çubuk ve kalkan verilir. Sürücü envanter ekranında
  gerçek fare tıklamalarıyla çubuğu hotbar 1'den hotbar 4'e taşır ve 5 tuşuyla seçer.
  - Client çubuğun menzilini kendisi hash'ledi (`rangeHash 1555799349`).
  - Replika 10/10 slotta sunucuyla eşleşti ve ana elin menzilini bu hash'ten çözdü (`mainHand=4.5+0.25`).
    Çeviricinin hesabı gerçek client'ınkiyle aynı.

## Sınırlar
- **Ana envanter (9..35) ve imleç izlenmez.** Saldırıyı yalnızca ana el, isabeti de yalnızca ana el belirler;
  bir yığın hotbar'a ancak bildirilen bir tıklamayla ya da sunucunun paketiyle girer.
- **Kullanılmış yığın:** küçülen yığın tutulur, "yok" olasılığı eklenir. Sunucunun paketi gelince kesinleşir.
- **Hash çakışması:** aynı oturumda farklı iki menzil değerinin 32 bitlik hash'i çakışırsa yanlış çözülür.
  Olasılığı ihmal edilebilir; sunucunun düzeltmesi de bir tur sonra gelir.
