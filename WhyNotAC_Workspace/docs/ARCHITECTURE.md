# WhyNotAC — Mimari

## Tasarım ilkeleri

1. **Kanıt, ceza değil.** Hiçbir tespit katmanı oyuncuyu doğrudan cezalandıramaz. Tespitler
   *kanıt* üretir. Karar yalnızca `whynotac-verdict` katmanında, istatistiksel bir üst sınırla
   verilir.
2. **Bilgi sınırının altında kanıt yoktur.** Verinin kendisinin taşıyamadığı bir hassasiyet
   (ör. float32 yaw'ın ulp'si, client'ın 2e-4 altı hareketi hiç göndermemesi, LpVec3
   kuantizasyonu) asla "sapma" sayılmaz. Her ölçüm, kendi bilgi sınırıyla birlikte taşınır.
3. **Belirsizlik = kanıt yok.** Kaybolan veri (`Gap`), bilinmeyen client durumu ya da
   yorumlanamayan bir pencere, şüphe değil, **sıfırlanmış kanıt** demektir.
4. **Bit-tam replikasyon.** Fizik ve dönüş hesapları client'ın IEEE-754 işlemlerini aynı sırayla
   yapar ve gerçek oyun sınıflarına karşı bit seviyesinde test edilir. "Yaklaşık doğru" bir
   simülatör, yuvarlama hatasını hile sanır.
5. **Fail-open.** WhyNotAC içindeki bir hata hiçbir oyuncuyu etkilemez: paket her durumda
   değişmeden iletilir, istisna Netty'ye ya da oyun thread'ine sızmaz.
6. **Esnek tahmin, katı sıra değil.** Grim tarzı "tek doğru tahmin + paket sırası" yerine fizik
   katmanı, kanıtla tutarlı **tüm hipotezlerin kümesini** (tuş kombinasyonları, knockback
   penceresi, gönderilmemiş mikro hareket) üretir. Gözlem bu kümenin dışına, bilgi sınırının
   ötesinde düştüğünde kanıt oluşur.

## Katmanlar ve bağımlılık grafiği

```
            ┌─────────────────────────── whynotac-paper ───────────────────────────┐
            │  Netty handler (NMS) · paket çevirici · transaction sandviçi · komut │
            └──────────────┬───────────────────────────────────────────────────────┘
                           │ ServerboundPacket / ClientboundPacket (değişmez kayıtlar)
            ┌──────────────▼────────────── whynotac-protocol ──────────────────────┐
            │  PlayerSession · sıralı zaman çizelgesi · SerialMailbox (aktör)      │
            │  TransactionTracker · LatencyEstimator · TransactionPolicy           │
            │  replay oturumları                                                   │
            └──────────────┬───────────────────────────────────────────────────────┘
                           │ TimelineEvent (oyuncu başına toplam sıra)
                           ├──────────────► whynotac-recording: kayıpsız kayıt (.wnrec),
                           │                takma ad, replay, inspect/dump aracı
                           │                (kayıtlar replay ile aynı dinleyicilere geri akar)
   ┌───────────────────────┼─────────────────────────────┬────────────────────────┐
   ▼                       ▼                             ▼                        │
whynotac-physics     whynotac-analysis              whynotac-ai                    │  (planlı)
hipotez kümeli       aim / tıklama / zamanlama      oyuncu profili, anomali skoru  │
fizik motoru         dedektörleri                   (çevrimiçi öğrenme)            │
   └───────────────────────┴──────────────┬──────────────┘                        │
                                          ▼ Evidence                              │
                                   whynotac-verdict ──► whynotac-storage ◄────────┘
                                   (istatistiksel karar)   (profil/kanıt kalıcılığı)
                                          │
                     tüm katmanlar ──► whynotac-math (bit-tam vanilla sayısal çekirdek)
```

`whynotac-conformance` yalnızca test içerir: `whynotac-math`'ı gerçek 1.21.11 sunucu sınıflarına
(`Mth`, `Vec3`, `LpVec3`, `Entity`) karşı bit-bit karşılaştırır. Hiçbir zaman dağıtılmaz.

## Veri akışı ve thread modeli

```
 TCP ─► [Netty event-loop thread'i]                         [Analiz havuzu]            [Ana thread]
        decode ─► WhyNotChannelHandler.channelRead
                   ├─ System.nanoTime()  (en erken sunucu gözlemi)
                   ├─ PacketTranslator   (NMS → kayıt)
                   ├─ PlayerSession.onInbound (senkron: sıra no, tick no, kuyruğa ekle)
                   └─ paket DEĞİŞMEDEN packet_handler'a ──────────────────────────────► oyun mantığı
                                         │
                                         └─► SerialMailbox ─► TimelineListener'lar (oturum başına seri)
 oyun ─► Connection.send ─► WhyNotChannelHandler.write
                   ├─ bundle'ı aç, çevir, fizik etkili mi? (TransactionPolicy)
                   ├─ evet: Ping(a) ─ paket ─ Ping(b)   (zaman çizelgesi = tel üzerindeki sıra)
                   └─ encoder ─► TCP
```

- Bir bağlantının gelen ve giden trafiği **tek bir event-loop thread'inde** işlenir. Bu yüzden
  `sequence` oyuncu başına toplam bir sıradır ve `nanoTime` tutarlı bir saattir.
- Ağ thread'i analiz beklemez: yalnızca damgalar, sayar, kuyruğa ekler.
- `SerialMailbox` sınırlıdır (varsayılan 16 384 olay). Taşma, sessizce veri kaybı değil, açık bir
  `Gap` olayıdır. Gap'i aşan kanıt geçersizdir.
- Dinleyici hataları izole edilir: biri çökerse diğerleri olayı yine görür, bağlantı etkilenmez.
- Analiz havuzu kapanmışsa teslimat çağıran thread'e düşer: kapanışta hiçbir olay (özellikle
  `Disconnected`) kuyrukta kalmaz.

## Gecikme telafisi: transaction sandviçi

Client, `ClientboundPingPacket`'i oyun thread'inde, diğer tüm paketlerle aynı sırada işler ve
`Pong` ile yanıtlar (decompile ile doğrulandı). Fizik durumunu değiştiren her paket
(knockback, patlama itmesi, teleport, öznitelik, efekt, yetenek, oyun modu, respawn) iki
transaction arasına alınır:

```
sunucu:   ... Ping(a) ─ SetEntityMotion ─ Ping(b) ...
client:   Pong(a) gelene kadar: knockback KESİN uygulanmadı
          Pong(a) … Pong(b) arası: uygulanmış OLABİLİR  → fizik iki hipotezi de taşır
          Pong(b) sonrası: knockback KESİN uygulandı
```

Böylece yüksek ping'li ya da jitter'lı bir oyuncunun "geç" uygulanan knockback'i tahmin edilmez,
**kanıtlanır**. Ping arkasına saklanan bir hile (transaction'ı geciktirme/atlatma) ise protokol
düzeyinde görünür: atlanan transaction `SKIPPED_TRANSACTION`, sahte kimlik
`UNKNOWN_TRANSACTION` anomalisidir (canlı E2E testte ikisi de yakalandı). Ayrıca her sunucu
tick'inde bağımsız bir transaction gönderilerek sürekli RTT/jitter izi tutulur
(RFC 6298 SRTT, RFC 3550 jitter).

## Sıfır yanlış-ban yaklaşımı (verdict katmanı için sözleşme)

Matematiksel olarak hiçbir sistem %0 olasılık **garanti edemez**. WhyNotAC'nin taahhüdü,
olasılığı ölçülebilir ve keyfî derecede küçük tutmaktır:

- Her kanıt bir **istatistik** taşır (ör. "bu gözlemin, tutarlı hipotez kümesinin dışında kalma
  olasılığı p"). Bilgi sınırının altında kalan hiçbir şey kanıt değildir.
- Kanıtlar bağımsızlık varsayımı **açıkça modellenerek** birleştirilir (aynı fiziksel olaydan
  türeyen iki kontrol çift sayılmaz).
- Karar eşiği, oyuncu-ömrü başına yanlış-pozitif olasılığı (ör. ≤ 1e-9) olarak tanımlanır ve
  sürekli olarak ölçülür: temiz oyuncu kayıtları üzerinde geriye dönük oynatma (replay) ile.
- Varsayılan eylem **izleme ve kanıt biriktirme**dir. Otomatik ceza, operatörün açıkça seçtiği
  bir politikadır.

## Modüller

| Modül | Durum | Sorumluluk |
|---|---|---|
| `whynotac-math` | **Faz 1 ✔** | Vanilla bit-tam sayısal çekirdek (`Mth`, `Vec3`, LpVec3, fare modeli, gönderim kuralları), ULP/tolerans, hatasız dönüşümler, Neumaier toplamı, kaydırmalı Welford momentleri |
| `whynotac-protocol` | **Faz 1 ✔** | 1.21.11 paket modeli, oyuncu zaman çizelgesi, aktör posta kutusu, transaction izleme |
| `whynotac-paper` | **Faz 1 ✔** | NMS/Netty paket yakalayıcı, sandviç, `/whynotac inspect` |
| `whynotac-conformance` | **Faz 1 ✔** | Gerçek oyun sınıflarına karşı diferansiyel testler |
| `whynotac-recording` | **Faz 2a ✔** | Çökmeye dayanıklı kayıt formatı, takma adlandırma, deterministik replay, çevrimdışı araç |
| `whynotac-bench` | **Faz 2a ✔** | JMH performans tabanı (`docs/benchmarks/`) |
| `whynotac-physics` | Faz 2 (planlı) | Katmanlı, hipotez kümeli fizik motoru (bkz. modül README) |
| `whynotac-analysis` | Faz 3 (planlı) | Aim/tıklama/zamanlama dedektörleri |
| `whynotac-ai` | Faz 3–4 (planlı) | Kişisel profil, hesap ele geçirme / makro tespiti |
| `whynotac-verdict` | Faz 4 (planlı) | Kanıt birleştirme ve karar |
| `whynotac-storage` | Faz 4 (planlı) | Profil ve kanıt kalıcılığı |

## Bilinen sınırlar (Faz 1)

- Yalnızca **Paper 1.21.11** (CraftBukkit/NMS sınıfları kullanılır). Spigot ve Folia
  desteklenmez/test edilmedi.
- ViaVersion ile başka sürümden bağlanan client'lar paket düzeyinde doğru görünür (ViaVersion
  bayt düzeyinde çevirir), ancak **fizikleri farklıdır**. Faz 2'de bu client'lar ya ayrı
  modellenecek ya da analiz dışı tutulacak.
- `ChannelInitializeListenerHolder` Paper'ın desteklenmeyen iç API'sidir.
- Ölçülen ek yük: ağ thread'inde paket başına ≈ 115 ns / 112 B; 1 000 oyuncu ≈ bir çekirdeğin
  %1.2'si (ayrıntı: `docs/phases/PHASE-2A-REPORT.md`).
