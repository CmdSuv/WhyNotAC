# Yük testi: 100 bot, 2026-10-08

`tools/load/run_load.sh <dir> <variant> 100 60` (cluster yerleşimi, `LOAD_WARMUP=120`, her varyant MSPT
oturduktan sonra ölçüldü). Sunucu CPU 0-1'e, botlar CPU 2-3'e sabitlendi. Paper 1.21.11, 4 çekirdekli
bulut makinesi. Vanilla'nın locator bar'ı (`gamerule locator_bar false`) kapalı: oyuncu sayısının
karesiyle büyüyen bir maliyeti var ve büyük sunucular da kapatır. Ham sonuçlar bu klasördeki JSON
dosyalarındadır.

## Yanlış pozitif (asıl sonuç)

Her bot dürüst bir vanilla client gibi davranır. Bütün WhyNotAC varyantlarında, 100 dürüst bot ve her
biri yaklaşık 650 bin olay için:

- 0 anomali, 0 sapma (divergence), 0 kayıp olay, 0 dinleyici hatası;
- her ping yanıtlandı, hiçbir bot düşmedi;
- ilk geçersiz denemede (sunucu 0.1 TPS'e çökerken) bile 0 anomali ve 0 sapma.

## Maliyet ayrıştırması

Thread başına CPU (çekirdek, JFR `jdk.ThreadCPULoad`). Süreç toplamı JIT derleyicisinin ±0.15 çekirdeklik
oynaması yüzünden gürültülüdür; bileşen maliyeti için Netty ve WhyNotAC thread'lerine bakın.

| Varyant | Netty I/O | Ana thread | WhyNotAC analiz | Kayıt I/O | MSPT (1 dk ort.) |
|---|---|---|---|---|---|
| baseline (eklenti yok) | 0.406 | 0.770 | - | - | 27.9 |
| whynotac (hepsi açık) | 0.588 | 0.766 | 0.115 | 0.011 | 32.8 |
| whynotac-noping (tick başına transaction yok) | 0.507 | 0.776 | 0.073 | 0.009 | 30.0 |
| whynotac-noedge (yalnız oyun görünümü) | 0.513 | 0.747 | 0.105 | 0.011 | 21.4 |
| whynotac-norec (kayıt kapalı) | 0.545 | 0.761 | 0.096 | 0.000 | 30.6 |

100 oyuncu için WhyNotAC'ın toplam maliyeti yaklaşık **0.29 çekirdek**, yani oyuncu başına saniyede
yaklaşık 2.9 ms CPU:

- Netty +0.18 çekirdek:
  - tick başına transaction yaklaşık 0.08 (saniyede 2.000 ping ve 2.000 pong: her ping ayrı bir
    thread uyandırması, yazma ve flush),
  - ağ kenarı yaklaşık 0.075,
  - kalanı oyun görünümü ve oturum kuyruğu.
- Analiz thread'leri 0.1 çekirdek.
- Kayıt 0.01 çekirdek.

Ana thread etkilenmiyor: tick başına transaction döngüsü 100 oyuncu için tick başına 0.34 ms CPU
harcıyor.

## Sonuç ve sıradaki işler

- Gözlem katmanı doğru: yükte bile dürüst oyuncuya karşı hiçbir yanlış bulgu yok.
- Henüz hiçbir kontrol çalışmadan 2.9 ms/oyuncu/saniye, "dünyanın en iyisi" için fazla. Yol haritasındaki
  optimizasyonlar:
  - tick başına ping'i tick'in kendi flush'ına bindirmek ve RTT'yi flush anından ölçmek;
  - analiz thread'inde olay başına ayırmayı azaltmak;
  - kenarda modellenmeyen paketler için kodlamayı atlamak.
- Hedef: aynı test düzeneğinde oyuncu başına saniyede 1 ms'nin altı.
