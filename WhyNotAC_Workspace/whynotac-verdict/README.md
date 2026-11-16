# whynotac-verdict — Kanıt birleştirme, ikinci doğrulama ve karar

**Sözleşme:** Tüm analiz kontrollerinden gelen `Evidence` kayıtlarını bağımlılık gruplarına
(`DependencyGroup`) ayırarak üstel sönümlü olarak birleştirir, `ActionPolicy` matrisi sınırları
içinde operatör yapılandırmasına (`VerdictConfig`, `CFG-01`) göre karar üretir ve sertifikalar:

- **Bağımlılık grupları (`VER-01`..`VER-04`):** Aynı fiziksel/protokol mekanizmasını paylaşan
  kontroller aynı grupta doygunluğa ulaşır; farklı gruplardan gelen deterministik kanıtlar (`P-A`, `P-M`)
  birikir. `S`, `D` ve `C` sınıfları hiçbir zaman yaptırım puanı üretmez.
- **Bağımsız ikinci doğrulama (`VER-05`, `VER-06`):** `P-A` geçici ban adayları, ayrı bir `P-A`
  bağımlılık grubuyla pekiştirilmedikçe arka planda izole bir `SessionRegistry` ve taze replika
  örneğiyle kayıttan (`SecondVerifier`) yeniden çalıştırılarak `fingerprint` eşleşmesi doğrulanır.
- **Kanıt sertifikaları ve bütçe/devre kesici (`VER-07`, `VER-08`, `VER-12`):** Her karar
  SHA-256 bütünlüklü `EvidenceCertificate` taşır; `FalseBanBudget` yanlış ban/yeniden-doğrulama
  hatasında otomatik geçici banı kilitler; `CircuitBreaker` spike veya manuel kill-switch anında
  tüm yaptırımları `SHADOW` moduna düşürür.
- **Yumuşak hafifletme (`NEUTRALIZE`):** Açıkça `ARMED` edilmiş deterministik kontroller ve
  `verdict.mitigations: true` durumunda imkânsız vuruş/blok iptali veya geri çekme (`MitigationKind`)
  uygulanabilir; varsayılan profil tamamen `SHADOW` olarak kalır.
