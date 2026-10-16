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
- **Sırada:** yaya hareket adımı (girdi, koşma, zıplama, sürtünme, yerçekimi). Doğrulama gerçek client'ın
  tick başına kaydıyla yapılacak.
