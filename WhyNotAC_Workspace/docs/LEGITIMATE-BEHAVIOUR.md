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
| Gecikmeli ve dalgalı bağlantı (her yönde 100 ms, ±40 ms; gidiş-dönüş 143-260 ms) | Ping cevaplarının ve paketlerin yığın hâlinde gelmesi: `client-clock`, `tick-framing`, replikaların pencereleri, saldırı ve hareket | `REALCLIENT_LATENCY="100 40"` ile yerel koşu (2026-10-30). İki yönü geciktiren bir TCP aracısı (`tools/realclient/latency.py`) kullanıldı; geç kalan bir parça arkasındakileri de bekletir | 0 vaka; 53 doğrulamadan 52'si geçti; motor 19.932 tikte client'la bit bit aynı, 0 farklı; hareket kontrolü 17.557 tiki açıkladı, 0 açıklanamayan; client'ın saati saniyede 20,00 tik. Başarısız olan doğrulama bir kontrol değil, sürücünün varsayımıdır: iki tik arayla verilen iki fare hareketi, bir paket yığınında takılan client'ta aynı tike düştü |
| Geçişli koşma ve çömelme (`toggleSprint`, `toggleCrouch`), kısmen | `movement` (girdi bayrakları basılı tutmaktan başka tiklerde değişir), `action-grammar` | `REALCLIENT_OPTIONS` ile yerel koşu (2026-10-30). Sürücü tuşları basılı tutar; geçişli çömelme açık kaldığı için kazma, sandık, kalkan, süzülme ve binek bacaklarına varılmadı | 0 vaka; motor karşılaştırılan 10.430 tikte client'la bit bit aynı, 0 farklı; hareket kontrolü 4.993 tiki açıkladı, 0 açıklanamayan; rastgele yürüyüşlerde 365 tikte client'ın kendi durumu hipotezlerdeydi. Varılamayan bacaklar bu ayarla sınanmış sayılmaz |

## Sınanmayanlar

Bunlar henüz gerçek client'la sınanmadı:

- Geçişli koşma ve çömelme ile kazma, menü, kalkan, süzülme ve binekler: sürücünün bu ayara göre tuşlara basması
  gerekir.
- Çok yüksek kare hızı ve dikey eşitleme: tik başına birçok kare, dönüşler kareler arasında birikir.
- Fare: uç duyarlılıklar, ham girdi (`rawMouseInput`), ters Y ekseni, ayrık kaydırma.
- Erişilebilirlik: anlatıcı, yüksek kontrast, görüş alanı efektleri, ekran sarsıntısı, ışık titreşimini azaltma.
- İzinli makrolar ve tekrarlar: saldırıyı basılı tutmak (bekleme süresiyle), kullanmayı basılı tutmak, envanterde
  shift-tıklama dizileri.
- Daha uç bağlantılar: 300 ms üstü ping, saniyeler süren donmalar (çöp toplama, sekme değiştirme). TCP paket kaybı
  gecikme olarak görünür; gecikmeli koşu bunun ılımlı hâlini kapsar.
- Farklı işletim sistemleri ve işlemciler: `Math.cos` farkı modellendi (görev #66), ama Windows ve macOS client'ları
  gerçekte koşturulmadı.
