# Yük testi: saldırı mesafesi kontrolü, 2026-10-15

`tools/load/run_load.sh <dir> whynotac 60 60 cluster`. Kurulum:
- düz dünya, cluster yerleşimi, görüş 4;
- Paper 1.21.11, 4 çekirdekli bulut makinesi;
- sunucu CPU 0-1'de, botlar CPU 2-3'te.

Isınmadan sonra her botun durduğu yere hareketsiz ve hasar almayan bir inek çağrılır. Her bot yarım
saniyede bir ineğe saldırır, ama yalnızca ineğin kutusu ayakta duran gözün yakınındaysa. Göz, botun
sunucuya son gönderdiği konumdadır: vanilla saldırısı bu konumdan seçilir, saldırı tikin hareketinden
önce gelir. Botlar dürüsttür: her saldırıyı bir vanilla client da yapabilirdi. Ölçümden sonra bütün
kayıtlar `tools/realclient/ReachAudit.java` ile, kontrolün yaptığı gibi değerlendirilir.

Ham sonuçlar bu klasördeki JSON dosyalarındadır (`attackReach` alanı denetimin özetidir):
- `60-whynotac-2.5.json`: göz ile kutu merkezi arasında yatayda en çok 2.5 blok;
- `60-whynotac-2.95.json`: göz ile kutu arasında en çok 2.95 blok (vanilla sınırı 3).

## Doğruluk

| Koşu | Değerlendirilen saldırı | Menzil içi | Menzil dışı | İddia yok | En küçük pay | Vaka |
|---|---|---|---|---|---|---|
| 2.5 | 3.417 | 3.417 | 0 | 0 | 0.954 | 0 |
| 2.95 | 5.467 | 5.467 | 0 | 0 | 0.059 | 0 |

- En uzak saldırı 2.941 blokta.
- Her saldırı değerlendirildi; hiçbirinde kontrol iddiadan kaçınmadı. Göz, envanter ve hedef
  replikaları yük altında da her saldırı için bilinir kaldı.
- İki koşuda da: 0 kayıp olay, 0 sapma, 0 anomali, 0 dinleyici hatası, 0 sunucu hareket düzeltmesi; her
  ping cevaplandı; bütün botlar sonuna kadar bağlı kaldı.

## Yük

| Koşu | MSPT ort. | TPS | Sunucu CPU (çekirdek) |
|---|---|---|---|
| 2.5 | 39.7 | 16.0 | 1.36 |
| 2.95 | 35.3 | 17.3 | 1.34 |

Sunucu bu makinede 60 botla tam kapasitedeydi (TPS 20'nin altında). Kontrol bu yüzden gecikmeli
paketler ve sıkışık tik'ler altında da sınandı. Bu koşular bir A/B karşılaştırması değildir; maliyet
ölçümleri `2026-10-09-entities`'tedir.
