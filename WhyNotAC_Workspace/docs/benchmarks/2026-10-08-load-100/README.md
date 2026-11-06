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

> **Düzeltme (2026-10-09):** Bu belgedeki thread CPU değerleri ilk yayımlandığında 2 kat yüksekti. JFR
> `jdk.ThreadCPULoad` yükü sunucunun bağlı olduğu CPU'lara (taskset, 2) göre verir; araç ise makinenin 4
> çekirdeğiyle çarpıyordu. Thread toplamları her koşuda süreç CPU'sunun 2 katıydı. Aşağıdaki değerler
> düzeltildi; bu klasördeki JSON dosyalarındaki `threadCpuCores` ham hâliyle (2 kat) duruyor.

Thread başına CPU (çekirdek, JFR `jdk.ThreadCPULoad`). Süreç toplamı JIT derleyicisinin ±0.08 çekirdeklik
oynaması yüzünden gürültülüdür; bileşen maliyeti için Netty ve WhyNotAC thread'lerine bakın.

| Varyant | Netty I/O | Ana thread | WhyNotAC analiz | Kayıt I/O | MSPT (1 dk ort.) |
|---|---|---|---|---|---|
| baseline (eklenti yok) | 0.203 | 0.385 | - | - | 27.9 |
| whynotac (hepsi açık) | 0.294 | 0.383 | 0.058 | 0.006 | 32.8 |
| whynotac-noping (tick başına transaction yok) | 0.254 | 0.388 | 0.037 | 0.005 | 30.0 |
| whynotac-noedge (yalnız oyun görünümü) | 0.257 | 0.374 | 0.053 | 0.006 | 21.4 |
| whynotac-norec (kayıt kapalı) | 0.273 | 0.381 | 0.048 | 0.000 | 30.6 |

100 oyuncu için WhyNotAC'ın toplam maliyeti yaklaşık **0.15 çekirdek**, yani oyuncu başına saniyede
yaklaşık 1.5 ms CPU:

- Netty +0.09 çekirdek:
  - tick başına transaction yaklaşık 0.04 (saniyede 2.000 ping ve 2.000 pong: her ping ayrı bir
    thread uyandırması, yazma ve flush),
  - ağ kenarı yaklaşık 0.04,
  - kalanı oyun görünümü ve oturum kuyruğu.
- Analiz thread'leri 0.05 çekirdek.
- Kayıt 0.005 çekirdek.

Ana thread etkilenmiyor: tick başına transaction döngüsü 100 oyuncu için tick başına 0.34 ms CPU
harcıyor (bu ölçüm doğrudan thread CPU zamanından, düzeltme gerektirmiyor).

## Sonuç ve sıradaki işler

- Gözlem katmanı doğru: yükte bile dürüst oyuncuya karşı hiçbir yanlış bulgu yok.
- Henüz hiçbir kontrol çalışmadan 1.5 ms/oyuncu/saniye (ilk yayımlanan 2.9 ms 2 kat yüksekti), "dünyanın en iyisi" için fazla. Yol haritasındaki
  optimizasyonlar:
  - tick başına ping'i tick'in kendi flush'ına bindirmek ve RTT'yi flush anından ölçmek;
  - analiz thread'inde olay başına ayırmayı azaltmak;
  - kenarda modellenmeyen paketler için kodlamayı atlamak.
- Hedef: aynı test düzeneğinde oyuncu başına saniyede 1 ms'nin altı.
