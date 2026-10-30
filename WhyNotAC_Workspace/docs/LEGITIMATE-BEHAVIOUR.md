# Meşru davranış kataloğu (`VAL-13`, `E1`, `DATA-01`)

Vanilla client'ın ayarları, yavaş makineler ve oyuncunun kendi alışkanlıkları kontrolleri yanıltmamalıdır. Bu katalog
her davranışın neyi zorladığını, nasıl sınandığını ve sonucunu tutar. Sınanmamış olan da burada açıkça durur: bir
davranış sınanana kadar kontrollerin onu doğru karşıladığı varsayılmaz.

Sınama yeri: `tools/realclient/run_realclient.sh`. Bu araç değiştirilmemiş resmi client'ı Xvfb altında gerçek tuş ve
fare girdisiyle oynatır. Her koşuda bütün bacaklar çalışır ve bütün doğrulamalar uygulanır. Bacaklar yürüyüş, rastgele
yürüyüş, kazma, saldırı, kalkan, envanter, süzülme ve roket, tekne ve diğer binekler, sınır ve Nether'dir. Doğrulamalar
"hiç vaka açılmadı" ve "hareket kontrolü sahiplendiği her tiki açıkladı" kapılarını da kapsar.

## Sınananlar

| Davranış | Neyi zorlar | Nasıl sınandı | Sonuç |
|---|---|---|---|
| Kare başına birden çok tik (yavaş makine) | `tick-framing` (bir karenin tikleri art arda), `client-clock` (bir karede 10 tike kadar), hareket | CI'daki client yazılımla çizer: kare başına 2,1-2,3 tik (yaklaşık 9 kare/s). `REALCLIENT_MAX_FPS=10` ile yerel koşu (2026-10-30): kare başına 4,6 tik (46.000 tik) | 0 vaka, 53 doğrulamanın hepsi geçti; hareket motoru 19.919 tikte client'la bit bit aynı, 0 açıklanamayan tik; client'ın saati saniyede 20,00 tik |
| Otomatik zıplama (`autoJump:true`) | Hareket: client kendi zıplar | CI'da her koşu (seçeneklerde açık) | Motor ve hareket kontrolü otomatik zıplamayı modeller; 0 açıklanamayan tik |
| Fare duyarlılığı 0,83 | Dönüşlerin float hesabı | CI'da her koşu | Her yaw ve pitch adımı tam olarak bir fare hareketi (vanilla float hesabı); yanlış modelleri reddeden negatif kontroller |
| Sunucunun koyduğu dönüşe client'ın cevabı | Hareket: tikin dışında gelen paket | CI'da her koşu (`/rotate`) | Cevaplar tikin dışında tutuldu, takipçi ikisinden sonra da açıkladı |
| Menü ekranda iken tuş basılı tutmak | `action-grammar` | CI'da her koşu (sandık) | Client ekran açıkken hareket tuşu ve eylem göndermedi |
| Kalkan kaldırılıp tıklamak | `action-grammar`, `movement` | CI'da her koşu | Kalkan kalkıkken saldırı, kullanım ve el sallama yok |

## Sınanmayanlar

Bunlar henüz gerçek client'la sınanmadı:

- Geçişli koşma ve çömelme (`toggleSprint`, `toggleCrouch`): paketler basılı tutmaktan farklı tiklerde değişir.
- Çok yüksek kare hızı ve dikey eşitleme: tik başına birçok kare, dönüşler kareler arasında birikir.
- Fare: uç duyarlılıklar, ham girdi (`rawMouseInput`), ters Y ekseni, ayrık kaydırma.
- Erişilebilirlik: anlatıcı, yüksek kontrast, görüş alanı efektleri, ekran sarsıntısı, ışık titreşimini azaltma.
- İzinli makrolar ve tekrarlar: saldırıyı basılı tutmak (bekleme süresiyle), kullanmayı basılı tutmak, envanterde
  shift-tıklama dizileri.
- Gecikme ve paket kaybı: yüksek ve dalgalı ping, bir süre donan client (çöp toplama ya da sekme değiştirme).
- Farklı işletim sistemleri ve işlemciler: `Math.cos` farkı modellendi (görev #66), ama Windows ve macOS client'ları
  gerçekte koşturulmadı.
