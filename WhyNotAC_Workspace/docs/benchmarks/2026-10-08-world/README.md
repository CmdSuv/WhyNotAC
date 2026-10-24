# Yük testi: client'ın dünyası ve iki kontrol, 2026-10-08

`LOAD_WARMUP=120 LOAD_LEVEL_TYPE=normal tools/load/run_load.sh <dir> <variant> 100 60`: 100 bot, cluster
yerleşimi, görüş 4, sabit seed (`LOAD_SEED`, varsayılan 20261008), Paper 1.21.11, 4 çekirdekli bulut
makinesi. Sunucu CPU 0-1'de, botlar CPU 2-3'te. `whynotac-flat.json` aynı testin düz dünyadaki
yanlış pozitif koşusudur. Ham sonuçlar bu klasördeki JSON dosyalarındadır.

İlk gerçek arazi karşılaştırması geçersizdi: her koşu rastgele seed ile yeni bir dünya üretiyordu.
Eklentili koşu bağlantı başına saniyede 550 paket gönderdi, eklentisiz koşu 325, ama bayt olarak
eklentili koşu daha azdı (87 MB'a karşı 232 MB). Fark arazi ve mob yoğunluğundandı, WhyNotAC'tan değil.
Seed artık sabit; iki varyant aynı araziyi ve aynı mob'ları görür.

## Yanlış pozitif (asıl sonuç)

| Koşu | Vaka | Reddedilen aday | Kayıp olay | Sapma | Anomali | Dinleyici hatası |
|---|---|---|---|---|---|---|
| Düz dünya, 100 bot, 735.500 client tick'i | 0 | 0 | 0 | 0 | 0 | 0 |
| Gerçek arazi, 100 bot, 669.587 client tick'i | 0 | 0 | 0 | 0 | 0 | 0 |

Kontroller: tick framing v2 ve client saati. Düz dünya koşusu saat kontrolünün ilk hâliyle (12 tick
pay) yapıldı. Bu, düzeltilmiş hâlden (22 tick) daha katıdır ve yine 0 bulgu verdi. Gerçek arazi koşusu
düzeltilmiş hâlle yapıldı.

Her iki gerçek arazi koşusunda da bazı botlar vanilla'nın "flying is not enabled" kontrolüyle atıldı:
eklentisiz koşuda 17, eklentili koşuda 12. Botlar fizik yapmadan tepelerde sabit yükseklikte daire
çiziyor. Bu WhyNotAC'tan değil, bot modelinden kaynaklanıyor.

## Maliyet

> **Düzeltme (2026-10-09):** Bu belgedeki thread CPU değerleri ilk yayımlandığında 2 kat yüksekti. JFR
> `jdk.ThreadCPULoad` yükü sunucunun bağlı olduğu CPU'lara (taskset, 2) göre verir; araç ise makinenin 4
> çekirdeğiyle çarpıyordu. Thread toplamları her koşuda süreç CPU'sunun 2 katıydı. Aşağıdaki değerler
> düzeltildi; bu klasördeki JSON dosyalarındaki `threadCpuCores` ham hâliyle (2 kat) duruyor.

Thread başına CPU (çekirdek, JFR `jdk.ThreadCPULoad`). Sunucu iki çekirdekte doymuş durumda, bu yüzden ek
CPU doğrudan tick süresine yansıyor.

| Varyant | Netty I/O | Ana thread | WhyNotAC analiz | Kayıt I/O | MSPT (1 dk ort.) | TPS |
|---|---|---|---|---|---|---|
| baseline (eklenti yok) | 0,329 | 0,537 | - | - | 48,4 | 17,0 |
| whynotac | 0,526 | 0,472 | 0,130 | 0,039 | 68,4 | 13,8 |

Gerçek arazide WhyNotAC yaklaşık **0,37 çekirdek** harcıyor: 100 oyuncu için oyuncu başına saniyede
3,7 ms CPU. Düz dünyada bu 0,15 çekirdekti (`docs/benchmarks/2026-10-08-load-100`). Farkın kaynağı tek bir
sıcak nokta değil, gerçek arazinin getirdiği paket hacmi:
- Netty +0,20 çekirdek: kenar tap'leri, pipeline'daki ek yazma adımları ve tick başına transaction;
- analiz +0,13 çekirdek: chunk'ları client'ın okuduğu gibi çözmek ve replika;
- kayıt +0,04 çekirdek: chunk baytları kayda olduğu gibi giriyor.

Replika: 100 oyuncunun dünyası için 20,0 MB (8.836 paylaşılan bölüm). Paylaşım olmadan ilk ölçüm 252,7 MB
idi (başka bir seed). Oyuncular çıkınca 0'a döndü.

## Sonuç

- Doğruluk: iki kontrol de dürüst trafikte, iki arazide ve yükte 0 yanlış bulgu verdi.
- Performans: gerçek arazide oyuncu başına 3,7 ms/s (ilk yayımlanan 7,4 ms 2 kat yüksekti), "dünyanın en iyisi" için çok fazla. Hedef aynı
  düzenekte oyuncu başına saniyede 1 ms'nin altı. Yapılacaklar: ping'i tick'in kendi flush'ına
  bindirmek, kenarda yalnız gereken paketleri çözmek, chunk'ları tembel çözmek (yalnız fizik soruyorsa),
  kayıtta chunk'ları sıkıştırmak ya da içerik adresli saklamak.
