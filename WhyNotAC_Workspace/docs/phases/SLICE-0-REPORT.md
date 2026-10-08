# Slice 0: ilk kontrol, uçtan uca

**Amaç:** Tek, ucuz ve kanıtlanabilir bir kontrolü bütün zincirden geçirmek: kanıt, kabul
edilebilirlik, politika, karar (SHADOW), kalıcı kayıt, uyarı, inceleme komutu, kanıt dosyası
(dossier) ve sunucudan bağımsız yeniden doğrulama. Böylece fizik motoru gibi büyük katmanlar
yazılmadan önce bütün arayüzler gerçek veriyle ve gerçek zamanlamayla denenmiş olur. Bu, eksik
analizinin ilk önerisidir (STATE-1).

## Kontrol: tick framing (P-M)

Vanilla 1.21.11 client'ı bir client tick'inde en fazla bir hareket paketi gönderir. Decompile edilmiş
client'taki bütün `ServerboundMovePlayerPacket` gönderen yerler:

| Yer | Ne zaman |
|---|---|
| `LocalPlayer.sendPosition()` (binerken `LocalPlayer.tick()`) | tick başına bir kez, ardından `ClientTickEnd` |
| `ClientPacketListener.handleMovePlayer` | sunucu teleport'una yanıt: AcceptTeleportation + PosRot |
| `ClientPacketListener.handleRotatePlayer` | sunucunun bakış yönü paketine yanıt: Rot |
| `ClientPacketListener.handleTeleportEntity` | oyuncunun kaldırılmış aracının teleport'una yanıt: PosRot |

Kural: iki `ClientTickEnd` arasında en fazla bir hareket paketi, artı bu aralıkta client'ın işlemiş
olabileceği her tetikleyici sunucu paketi için bir yanıt.

- **Yanıt hakkının başlangıcı:** tetikleyici paket gönderildiği an.
- **Yanıt hakkının sonu:** client'ın, tetikleyiciden sonra gönderilmiş bir transaction'ı onayladığı
  aralığın sonu. Yanıt, aynı karede o pong'dan önce gönderilir.
- **Harcama:** haklar yalnızca gereken aralıkta harcanır. Bir tick'in kendi paketi, bir yanıtın
  hakkını tüketemez.

Bu kural sağlamdır: vanilla client onu hiçbir koşulda ihlal edemez. İhlal eden client değiştirilmiştir.
Bu hile kanıtı olmak zorunda değildir: protokolü bozan meşru bir mod da üretebilir. Bu yüzden sınıfı
P-M'dir (POLICY.md). Bir tick içinde birden çok konum göndererek düşme yüksekliği uyduran mace hileleri
ve tıklayarak teleport eden hileler tam olarak bunu yapar.

Kabul edilebilirlik (POLICY.md): kenarda gözlem, native 774, çevirici yok, transaction'ları client
yanıtlıyor, Bedrock değil, pencerede kayıp ya da çevrilemeyen olay yok. Doğrulanmamış bilgi temiz
sayılmaz.

## Zincir

```
TickFramingCheck ──Evidence──► ShadowVerdict ──Case──► CaseStore (SQLite + dossier JSON)
  (analiz thread'i)            ActionPolicy             Alerts (whynotac.alerts.shadow)
                               SHADOW: yalnız           /whynotac cases · /whynotac case <id>
                               uyarı + inceleme         java -jar WhyNotAC.jar verify <dossier>
```

- **`ActionPolicy`**, POLICY.md'deki matrisin veri halidir. Bir test belgedeki tabloyu ayrıştırır ve
  koddaki tabloyla hücre hücre karşılaştırır, yani ikisi birbirinden kopamaz. Operatör yasak bir
  hücreyi açamaz.
- **Dossier**, kanıtı, kabul edilebilirliği, izin verilen eylemleri, oturum kaydının yolunu
  (dossier'ye göreli, mutlak yedekli) ve bir parmak izini içerir.
  `java -jar WhyNotAC.jar verify <dossier>` kontrolü kayıt üzerinde, sunucudan bağımsız olarak
  yeniden çalıştırır ve aynı parmak izini ister.
- **Kalıcılık:** tek bir yazıcı thread'i, sınırlı kuyruk. Kuyruk doluysa vaka düşürülür ve sayılır,
  analiz thread'i asla beklemez. `cases.retention-days` (varsayılan 90) eski vakaları ve
  dossier'leri siler: vakalar oyuncuyu adlandırır, yani kişisel veridir.

## Doğrulama

| Test | Sonuç |
|---|---|
| Birim | 20 yeni test. Politika (her hücre, belgeyle eşlik, istatistik asla ceza vermez, doğrulanmamış kimlik asla otomatik banlanmaz) ve kontrolün sağlamlık durumları (aynı aralıkta yanıt ve tick paketi, farklı karelerde yanıt ve pong, hakkın süresinin dolması, doygunluk, beyan edilmiş protokolün sayılmaması, Bedrock, kayıp olay, kenar kaybı, parmak izi kararlılığı) |
| E2E (gerçek Paper) | Sonda tick 200'de iki hareket paketi gönderir. Sonuç: tam olarak 1 vaka (P-M, 2 paket, yanıt hakkı 0), SHADOW, izinler yalnızca ALERT + REVIEW, konsolda shadow uyarısı, `/whynotac cases` listeliyor. `verify` kaydı sunucudan bağımsız tekrar oynatıp aynı kanıtı buldu. Offline mode kimlik `UNAUTHENTICATED` kaldı |
| Dürüst trafik | Gerçek client, ViaVersion + PacketEvents ile interop E2E ve 100 botluk yük testi: 0 vaka (CI'da da kontrol edilir) |

## Sınırlar ve sonraki adımlar

- Kontrol SHADOW'da. ARMED'a geçiş için POLICY.md'deki ölçütler gerekiyor: temiz kayıtlarda 0, hile
  kayıtlarında ölçülmüş tespit oranı, yazılı `soundness` notu.
- `TeleportEntity` yalnızca varlık kimliğiyle modellendi ve her varlık için temkinli olarak yanıt hakkı
  verir. Aracı izleyen bir model bu hakkı yalnızca oyuncunun kaldırılmış aracına daraltacak.
- Etkisizleştirme (setback) henüz yok: kontrol yalnızca kanıt üretir.
- Yeni kontroller aynı arayüzleri kullanacak: `TimelineListener` + `AdmissibilityTracker` +
  `EvidenceSink`. Bunlar bu dilimle sabitlendi.
