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
| Donan client (her 40 s'de 2,5 s; çöp toplama ya da takılan makine) | `client-clock` (donmadan sonra bir karede 10 tik), `tick-framing`, ping cevaplarının toplu gelmesi, hareket | `REALCLIENT_FREEZE="40 2.5"` ile yerel koşu (2026-10-30): client'ın süreci 23 kez durduruldu | 0 vaka; motor 19.021 tikte client'la bit bit aynı, 0 farklı; hareket kontrolü 16.114 tiki açıkladı, 0 açıklanamayan; bitirilen her blok sınırın içinde. Başarısız olan 3 doğrulama sürücünün varsayımıdır. Client'ın saati 19,06 tik/s: vanilla donmadan sonra en fazla 10 tik yetiştirip gerisini atar, 23 donmada yaklaşık 900 tik. Donma kazmaya ve bir süzülmeye denk geldiği için o bacaklar beklenenden az oynandı |
| Erişilebilirlik ve görüntü: görüş alanı 110, görüş ve ekran efektleri, hasar sarsıntısı ve karanlık efekti kapalı, şimşek parlaması gizli, yüksek kontrast, altyazılar | Bunlar yalnızca çizimi değiştirmeli; paketlere bir etkisi varsa kontroller onu görür | `REALCLIENT_OPTIONS` ile yerel koşu (2026-10-31) | 0 vaka; 53 doğrulamanın hepsi geçti; motor 19.912 tikte client'la bit bit aynı, 0 farklı; hareket kontrolü 17.288 tiki açıkladı, 0 açıklanamayan. Aynı koşuda istenen 8 chunk'lık çizim mesafesi sınanmış sayılmaz: sunucunun görüş mesafesi 3 olduğundan client yine 81 chunk (9×9, kenar payıyla) aldı |
| Geniş görüş (sunucu ve client 8 chunk) ve uç ping (her yönde 200 ms, ±100 ms; gidiş-dönüş 600 ms'ye kadar) | Dünya replikası (çok daha fazla chunk), uzun pencereler, sunucunun bineği düzeltmesi, hareket | `REALCLIENT_VIEW_DISTANCE=8 REALCLIENT_LATENCY="200 100"` ile yerel koşu (2026-10-31) | 0 vaka. Dünya replikası 329 chunk'ın 7.896 bölümünün hepsinde sunucuyla aynı. Hareket kontrolü 17.426 tiki açıkladı, 0 açıklanamayan; sürülen teknenin 825 tikinin hepsini açıkladı. Motorla client arasında 1 tikte fark çıktı: tekneden inen oyuncu, client'ın dünyasından henüz silinmemiş tekneye otomatik zıpladı. Motor yalnızca bloklarla çarpışır. Kontrolün kendi koruması (1,5 blok içinde çarpışabilen varlık varken iddia yok) bu tiki kapsıyordu. Yalnızca doğrulama aracının kapsam kuralı teknenin üstünü kapsıyordu, yanını kapsamıyordu. Kural "teknenin yakını" oldu; aynı kayıtta fark 0, CI kaydında karşılaştırılan tiklerin %1'i kapsam dışına çıktı. Başarısız kalan iki doğrulama sürücünün eşleştirmesidir: paket yığınında aynı tike düşen iki fare hareketi; 600 ms gecikmede sunucunun tekneyi iki kez düzeltmesine client'ın cevapları |
| Geçişli koşma ve çömelme (`toggleSprint`, `toggleCrouch`), kısmen | `movement` (girdi bayrakları basılı tutmaktan başka tiklerde değişir), `action-grammar` | `REALCLIENT_OPTIONS` ile yerel koşu (2026-10-30). Sürücü tuşları basılı tutar; geçişli çömelme açık kaldığı için kazma, sandık, kalkan, süzülme ve binek bacaklarına varılmadı | 0 vaka; motor karşılaştırılan 10.430 tikte client'la bit bit aynı, 0 farklı; hareket kontrolü 4.993 tiki açıkladı, 0 açıklanamayan; rastgele yürüyüşlerde 365 tikte client'ın kendi durumu hipotezlerdeydi. Varılamayan bacaklar bu ayarla sınanmış sayılmaz |

## Sınanmayanlar

Bunlar henüz gerçek client'la sınanmadı:

- Geçişli koşma ve çömelme ile kazma, menü, kalkan, süzülme ve binekler: sürücünün bu ayara göre tuşlara basması
  gerekir.
- Çok yüksek kare hızı ve dikey eşitleme: tik başına birçok kare, dönüşler kareler arasında birikir.
- Fare: uç duyarlılıklar, ham girdi (`rawMouseInput`), ters Y ekseni, ayrık kaydırma.
- Erişilebilirlik: anlatıcı (bu ortamda ses yok).
- İzinli makrolar ve tekrarlar: saldırıyı basılı tutmak (bekleme süresiyle), kullanmayı basılı tutmak, envanterde
  shift-tıklama dizileri.
- Daha uç bağlantılar: 600 ms üstü gidiş-dönüş, bağlantı zaman aşımına yaklaşan donmalar (10 s ve üstü). TCP paket kaybı
  gecikme olarak görünür; gecikmeli koşu bunun ılımlı hâlini kapsar.
- Farklı işletim sistemleri ve işlemciler: `Math.cos` farkı modellendi (görev #66), ama Windows ve macOS client'ları
  gerçekte koşturulmadı.
