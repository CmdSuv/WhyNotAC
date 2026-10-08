# Kanıt ve eylem politikası (normatif)

Bu dosya, hangi kanıtın hangi eylemi tetikleyebileceğini belirleyen **tek** sözleşmedir. Kod bu
tabloyu veri olarak taşır (`dev.whynotac.analysis.policy.ActionPolicy`). Testler her hücreyi tek tek
dener. Operatör ayarı yasak bir hücreyi açamaz: böyle bir ayar reddedilir ve eklenti uyarır. Başka
bir belge bu dosyayla çelişirse bu dosya geçerlidir. [Kararlar](DECISIONS.md) bu dosyaya bağlıdır.

## İlke

"Sıfır yanlış ban" bir olasılık iddiası değildir. Kişi başına 1e-9 gibi bir hata oranı replay ile
ölçülemez: bunun için milyarlarca temiz oyuncu-ömrü gerekir. Taahhüt bunun yerine şudur:

1. **Otomatik ceza** yalnızca **inşa gereği sağlam** kanıttan gelir. Bu, vanilla client'ın bütün
   belirsizlikleri sınırlanmış haliyle *asla* üretemeyeceği bir gözlemdir: küme üyeliği ve bilgi
   sınırı, yani fırsat başına hata olasılığı sıfır.
2. **Temiz veri yalnızca yanlışlar.** Temiz bir kayıtta bir kontrolün tek bir kanıt üretmesi, o
   kontrolü bütün sunucularda kapatır ve bir regresyon testi olur.
3. **İstatistik asla ceza vermez.** İstatistiksel bulgular, varsayımları açıkça yazılı e-değerlerdir.
   İnceleme ve bağlam için kullanılırlar.
4. **Belirsizlik muafiyet değildir.** Belirsiz bir pencere kanıt üretmez, ama hilenin işine
   yarayacak bir davranışa da izin vermez. Örnek: güvenli olmayan bir hareket, ceza verilmeden
   geri alınır (setback).

## Kanıt sınıfları

| Sınıf | Anlamı | Örnek |
|---|---|---|
| **P-A** avantaj kanıtı | Vanilla fiziği/kuralları altında imkânsız ve oyuncuya avantaj sağlayan bir gözlem; ikinci bir motorla yeniden doğrulanmış | Hiçbir girdi dizisiyle ulaşılamayan bir konum, erişim sınırı dışından vuruş |
| **P-M** değişiklik kanıtı | Vanilla client'ın protokol olarak asla göndermeyeceği bir dizi: client değiştirilmiştir. Hile kanıtı olmak zorunda değildir: meşru ama protokolü bozan bir mod da üretebilir | Bir tick içinde birden fazla hareket paketi (tick framing), atlanan transaction |
| **D** tasarım-kesin istatistik | Geçerliliği deney tasarımından gelen e-değer (rastgele sondalar, honeypot) | Gizli varlığa bakma sıklığı, sahte cevher madenciliği |
| **S** nominal istatistik / yapay zekâ | Modele dayalı sinyal | Aim/tıklama dağılımı, profil sapması |
| **C** bağlam | Kanıt değil, inceleme için bağlam | Ping, sunucu TPS'i, client beyanları |

## Eylem matrisi

✔ izinli · ⚙ yalnızca operatör açarsa · 👤 otomatik değil, yalnızca yetkili kararıyla · ✘ yasak (açılamaz)

Bilgi gizleme: oyuncuya görmemesi gerekeni göndermemek (anti-ESP, anti-xray). Etkisizleştirme: hilenin
etkisini geri almak (setback, hasar iptali, blok geri alma). P-M'de yalnızca değiştirilmiş davranış
etkisizleştirilir.

| Sınıf | Uyarı | İnceleme | Bilgi gizleme | Etkisizleştirme | Kick | Geçici ban | Kalıcı ban |
|---|---|---|---|---|---|---|---|
| **P-A** | ✔ | ✔ | ✔ | ✔ | ⚙ | ⚙ | 👤 |
| **P-M** | ✔ | ✔ | ✔ | ✔ | ⚙ | 👤 | 👤 |
| **D** | ✔ | ✔ | ✔ | ✘ | ✘ | ✘ | ✘ |
| **S** | ✔ | ✔ | ✘ | ✘ | ✘ | ✘ | ✘ |
| **C** | ✘ | ✔ | ✘ | ✘ | ✘ | ✘ | ✘ |
| **SHADOW** | ✔ | ✔ | ✘ | ✘ | ✘ | ✘ | ✘ |

Son satır: SHADOW durumundaki bir kontrolün kanıtı, sınıfı ne olursa olsun yalnızca kaydedilir ve
incelemeye düşer. Uyarısı yalnızca `whynotac.alerts.shadow` yetkisi olanlara gider.

**Geçici ban (P-A, ⚙) koşulları:** Hepsi birden sağlanmalı. Biri eksikse hücre 👤 olur:
- kimlik `AUTHENTICATED_*` ya da giriş eklentisiyle onaylanmış,
- client profili native,
- pencere kabul edilebilir (aşağıda),
- en az iki bağımsız P-A ya da ikinci motorla yeniden doğrulanmış bir P-A,
- kontrol `ARMED` durumda.

## Kimlik katmanları

| Katman | Kaynak | Uzun ömürlü profil | Otomatik geçici ban |
|---|---|---|---|
| `AUTHENTICATED_MOJANG`, `AUTHENTICATED_BY_PROXY` | Online mode, Velocity modern forwarding, Floodgate | ✔ | ⚙ (P-A koşullarıyla) |
| `AUTH_PLUGIN_CONFIRMED` | Offline mode + AuthMe/nLogin/LibreLogin girişi (planlı) | ✔ (giriş sonrasından itibaren) | ⚙ (P-A koşullarıyla) |
| `CLAIMED_BY_PROXY`, `UNAUTHENTICATED` | Bungee legacy, offline mode, giriş öncesi | ✘ yalnızca oturum | ✘ yalnızca yetkili kararıyla |

Doğrulanmamış bir kimliğin kanıtı oturumla sınırlıdır. Başkasının adıyla girip onu banlatmak bu
yüzden mümkün değildir.

## Client profilleri ve kabul edilebilirlik

Bir pencere ancak aşağıdakilerin hepsi doğruysa kanıt üretebilir. Biri eksikse sonuç
"kabul edilemez" olur ve nedenleriyle kaydedilir. Eksik bilgi temiz sayılmaz.

- Gözlem kenarda: `Observation` inbound=`WIRE`, outbound=`WIRE`, pencere boyunca.
- `NATIVE_PROTOCOL` = 774 (`FORMAT_BOUND`), `TRANSLATOR` = none, `TRANSACTION_ORIGIN` = `CLIENT`.
- `BEDROCK` yok, topoloji `GEYSER_LOCAL` değil.
- Pencerede `Gap` (kayıp olay) ya da `Untranslatable` yok.

Çevrilmiş (ViaVersion) ve Bedrock client'lar, kendi modelleri yazılana kadar yalnızca S/C sınıfı
bağlam üretir.

## Yaşam döngüsü

Her yeni kontrol **SHADOW** başlar: kanıt üretir ve kaydeder, hiçbir eylem tetiklemez.
**ARMED**'a geçmesi için:
- gerçek client CI oturumlarında, 100 botluk yük testinde ve bütün temiz kayıtlarda 0 kanıt,
- açık kaynak hile kayıtlarında ölçülmüş tespit oranı,
- yazılı bir `soundness` notu: varsayımlar, dışlanan durumlar, bilgi sınırı.

Temiz bir kayıtta tek bir yanlış kanıt kontrolü otomatik olarak **SHADOW**'a geri alır.
