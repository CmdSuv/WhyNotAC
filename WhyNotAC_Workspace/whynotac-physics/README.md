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
  - **Birim testi:** `OnFootTruthTest` oturumdan hareket eden tikleri yeniden oynatır: düz dünyada 195, kodla
    yeniden kurulan parkurda 544 tik.
  - **Kapsam dışında `Colliders.Unknown`:**
    - sıvı, tırmanma ve içinde hareket etkisi olan bloklar (kabarcık sütunu, bal, ağ, böğürtlen, toz kar);
    - slime ve yatak sekmesi; çit ve duvar üstü;
    - boğulma itmesi, yüklenmemiş chunk, sürünme, otomatik zıplama geometrisi.

    Varsayılan öznitelikler ve etkisiz oyuncu varsayılır. Katı varlıklar ve dünya sınırı çağıranın sorumluluğundadır.
