# WhyNotAC — Yol haritası

**Tarih:** 2026-10-15. **Kaynaklar:** iki denetim. Birincisi denetim ve araştırma çalışmasıdır
(2026-10-07, 231 madde). İkincisi doğrulama ve ürün boşluk analizidir (2026-10-09, 60 madde). Madde
kimlikleri (`PHY-M3`, `VAL-10` gibi) bu çalışmalardan gelir. Bu dosya sırayı ve kapıları belirler.
Normatif kurallar [POLICY.md](POLICY.md)'dedir; ikisi çelişirse POLICY geçerlidir.

## Bugünkü durum

| Katman | Durum | Ayrıntı |
|---|---|---|
| Ağ kenarı: bayt düzeyinde dinleme, uzlaştırma, transaction sandviçi | Tamam | [Faz 2b-1](phases/PHASE-2B-1-REPORT.md) |
| Client kimliği, topoloji, beyanlar (güven düzeyleriyle) | Kısmen | Çevrilmiş client'lar için ikinci kenar katmanı yok (`NET-01`) |
| Client'ın dünyası, varlıkları, envanteri, kendi oyuncusu (client sırasıyla) | Tamam | [2b-3](phases/PHASE-2B-3-REPORT.md), [2b-5](phases/PHASE-2B-5-REPORT.md), [2b-6](phases/PHASE-2B-6-REPORT.md), [2b-7](phases/PHASE-2B-7-REPORT.md), [2b-8](phases/PHASE-2B-8-REPORT.md) |
| Kanıt, politika, vaka, dosya, bağımsız `verify` | Tamam | [Slice 0](phases/SLICE-0-REPORT.md) |
| Kontroller: `tick-framing` (P-M), `client-clock` (P-M), `attack-reach` (P-A), `movement` (P-M) | SHADOW | Dürüst trafikte 0 vaka: E2E, gerçek client, yük (yük botları vanilla fiziğiyle yürümediği için `movement` orada ayrı sayılır) |
| Kayıt, takma ad, veri sahibi talepleri | Tamam | [docs/privacy](privacy/README.md) |
| Gerçek client, E2E, interop, yük testi | CI'da / araçta | `tools/realclient`, `tools/e2e`, `tools/load` |
| Hareket fiziği (`whynotac-physics`) | Yaya kısmı tamam | Çarpışma çekirdeği resmi client'ın koduyla, yaya hareket adımı (otomatik zıplama, hız değiştiricileri ve efektler dahil) gerçek client'ın parkuruyla bit bit aynı; sunucu tarafı `movement` kontrolü SHADOW'da, gerçek client oturumunda 0 açıklanamayan tick |
| Kalıcı depolama, karar, yapay zekâ modülleri | Planlı | `whynotac-storage`, `whynotac-verdict`, `whynotac-ai` |

## Kapı kuralı

Her adım bir kapıyla biter. Kapı, aşağıdakilerin hepsinin CI'da yeşil olmasıdır:
- birim ve uyum testleri;
- E2E (yalın ve interop);
- gerçek client;
- dürüst trafikte 0 vaka (E2E, gerçek client, yük testi).

Yeni bir kontrol SHADOW başlar. ARMED'a yalnızca POLICY'deki koşullarla geçer.

## Faz 3 — Hareket fiziği (çekirdek)

Hareketle ilgili her P-A kanıtı bu motora dayanır. Motor, client'ın tick'ini bit bit yeniden üretir.
Bilinmeyen her şeyi sınırlı bir küme olarak taşır; tek bir toplu tolerans kullanmaz.

1. **Tick'in yeniden kurulması** (`PHY-A1`, `PHY-M17`, `PHY-M18`): paketlerin hangi client
   tick'inde uygulandığı, gönderim kuralları (2·10⁻⁴ eşiği, 20 tikte bir hatırlatma), yüklenme ve
   tick hızı. Var olan tick-framing ve öz-oyuncu replikasının üzerine kurulur.
2. **Çarpışma çekirdeği** (`PHY-A4`, `F8`): client'ın kendi çarpışma kodu bit bit uygulanır
   (Paper'ın Moonrise çarpışması değil). Şekiller oyunun kendisinden alınır. Basamak çıkma ve
   destek bloğu dahildir. Uyum testleri `whynotac-conformance`'tadır.
3. **Girdi ve yürüme** (`PHY-M1`–`PHY-M4`, `PHY-A8`, `PHY-M15`): tuşlar ve hareket vektörü,
   koşma durum makinesi, sürtünme, yerçekimi özniteliği, etkiler, öznitelik değiştirici sırası.
4. **Küme motoru** (`PHY-A5`, `PHY-A7`): kesin hızlı yol, aralık aritmetiğiyle kapsama, sınırda
   dallanma. Platforma bağlı kayan nokta noktaları kayıt altındadır.
5. **Ortam** (`PHY-M5`–`PHY-M9`, `PHY-M19`, `PHY-M20`, görev #33): duruş ve boyut, tırmanma,
   blok içi etkiler, sekme, akışkanlar, iten bloklar ve varlıklar, dünya sınırı.
6. **İtmeler** (`PHY-M10`–`PHY-M14`, `PHY-M21`, `PHY-M22`): sunucu hızı ve patlamalar, elytra ve
   roket, riptide, mızrak, binekler, sunucunun client'ı ezen durumları.
7. **Bu motorun üzerindeki kontroller:** hareket kümesi dışı konum (P-A), savrulmanın uygulanması
   (`C6`), zemin bayrağı, birikimli tick sayısı (`M5`, `VER-19`).

**Faz 3 kapısı:** gerçek client'ın tick başına doğruluk kaydı (`PHY-A9`) var. Bir senaryo
matrisinde, client'ın her tikteki durumu motorun kümesinin içinde olmalıdır. Sunucu içi oyuncu
fiziğiyle diferansiyel fuzz testi de yapılmalıdır (`PHY-A10`).

## Faz 3b — Savaş ve dünya etkileşimi (fizikle paralel)

- Varlık kutularının kalanı (görev #37): animasyonlar, kaydırılmış kutular, ejderha parçaları.
- Tick dilbilgisi ve tick başına tek isabet (`G1`, `G2`), eylem bağlamı (`G3`).
- Duvar arkasından vuruş ve ışın üzerinde engel (`C2`).
- Blok erişimi ve yüz doğrulama (`W2`), kırma ilerlemesi (`W1`), yerleştirme (`W3`).
- Fiziğe dayananlar: kritik vuruş (`C7`), topuz (`C8`).

## Faz 4 — Doğrulama ve veri

- **Regresyon kaydı ve replay kapısı** (`VAL-10`, kritik): CI kayıtları saklanır. Her kontrol
  sürümü bunların hepsinde yeniden oynatılır. Canlı ve replay eşitliği kapıdır.
- **Meşru davranış kataloğu** (`E1`, `DATA-01`, `VAL-13`): vanilla seçenekleri, erişilebilirlik
  yolları, izinli makrolar. Gerçek client senaryo matrisi.
- **Paylar** (`VAL-4`): her kanıt, dürüst trafiğin sınıra ne kadar yaklaştığını raporlar.
- **Saat varsayımı** (`VAL-3`): sunucu saatinin kayması izlenir.
- **Test teknikleri:** fuzz (`TEST-01`), özellik tabanlı testler (`TEST-02`), mutasyon (`TEST-03`),
  deterministik simülasyon (`TEST-04`).
- **Hile verisi** (`DATA-02`): yalnızca sahibin onayıyla, izole bir sanal makinede, açık kaynak
  client'larla kaydedilir.

## Faz 5 — Karar ve uygulama

- **Bağımsız yeniden doğrulama** (`VER-05`, `VER-06`): ikinci bir motor ve vanilla kodunda yeniden
  çalıştırma. Bir P-A kanıtı, eylem tetiklemeden önce bunlardan geçer.
- **Kanıt sertifikaları** (`VER-07`) ve **yanlış ban bütçesi defteri** (`VER-08`).
- **Uygulama hattı** (`VER-12`, `CFG-01`): eylemler gecikmeli ve dağıtılmış gelir. Yürütme anında
  yeniden doğrulanır. Devre kesici, genel kapatma anahtarı ve tipli, doğrulanmış yapılandırma vardır.
- **Depolama** (`STO-01`–`STO-03`): sabit vaka deposu, değiştirilemez dosyalar (hash zinciri ve
  imza). Dosyalar derlemeye bağlanır (`PROD-13`).
- **İnceleme ve itiraz** (`ADM-02`, `VER-15`): dört göz, itiraz akışı, sade dilde açıklama.
- **Yapay zekâ** (`AI-00`, `AI-05`): yalnızca vaka seçer ve sonda planlar. Hiçbir zaman eylem
  tetiklemez (POLICY).

## Faz 6 — İşletme ve dağıtım

- **Tedarik zinciri:**
  - ~~CI action'ları SHA'ya sabitlenir, en az yetkili token kullanılır (`REL-01`).~~ Yapıldı.
  - Gradle bağımlılıkları doğrulanır (`REL-02`).
  - Sürümler imzalı ve tekrarlanabilir olur, SBOM eklenir (`REL-03`).
- **Gözlemlenebilirlik:** metrikler ve sağlık modeli (`OBS-01`). Performans hedefi gerçek arazide
  oyuncu başına < 1 ms/s (görev #27).
- **Ağ:** çevrilmiş client'lar için ikinci kenar katmanı (`NET-01`), Velocity eşlik eklentisi
  (`NET-22`), ağ çapında profiller yalnızca doğrulanmış kimliklerle.

## Sahibin kararını bekleyenler

| Konu | Neden önemli | Kaynak |
|---|---|---|
| Sürüm stratejisi: yalnız 1.21.11 mi, sonraki sürümler de mi? | Hedef sürüm yaşlanıyor; her sürüm fiziğin yeniden doğrulanması demek | Zayıflık #14, `PROD-14` |
| Satış ve lisans | Minecraft EULA'sı ve dağıtım kanalları satışı ve gizlemeyi sınırlar; hukuk görüşü gerekir | Zayıflık #15, `PROD-9`, `PROD-11` |
| Davranışsal profil (hesap devri, alt hesap bağlama) | Büyük olasılıkla özel nitelikli (biyometrik) veri; öneri: yapılmasın | `PROD-4` |
| Otomatik ceza | Anlamlı insan incelemesi, itiraz edilebilir açıklama gerekir | `PROD-5`, `PROD-19` |

## Denetimden kapatılanlar

| Madde | Çözüm |
|---|---|
| Zayıflık #1, #4, #6–#10, #12, #13 | [Zayıflık analizi, durum bölümü](reviews/2026-10-06-weakness-analysis.md) |
| `ROB-01` boş çerçeve | Kenar, boş çerçeveyi oyunun decoder'ı gibi yok sayar (`WireObserver`) |
| `F1`, `F3`, `F4` replikalar | Varlık, dünya, envanter ve öz-oyuncu replikaları (Faz 2b-3, 2b-5–2b-8) |
| `PHY-A9` gerçek client doğruluk kaydı | `tools/realclient` javaagent kaydedicisi |
| `C1` erişim (ilk P-A) | `attack-reach` (Faz 2b-8); kalan kutular görev #37 |
| `REL-01` CI sabitleme | Her action bir commit'e sabit (yorumda o anki etiketi var); token yalnızca okuyabilir |
| `PROD-1`, `PROD-2`, `PROD-3` vakalar ve günlükler | `/whynotac privacy` kayıtları ve vakaları birlikte kapsar. Vaka saklama süresi saatte bir uygulanır, sahipsiz dosya kalmaz. Günlüklerde IP adresi yok |
