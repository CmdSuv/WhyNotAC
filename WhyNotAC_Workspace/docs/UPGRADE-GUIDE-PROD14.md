# Sürüm Yükseltme ve Çoklu-Sürüm Stratejisi (`PROD-14`: `1.21.11` -> `26.3+`)

Bu belge, WhyNotAC'nin neden **Minecraft `1.21.11` (Protokol `774`)** ile bit-tam sabitlendiğini ve [`ROADMAP.md`](ROADMAP.md#L204-L212) (`PROD-14`) kapsamındaki hedef sürüm kararına göre güncel **Minecraft `26.3`** (ve sonraki sürümler) üzerine taşınırken dokunulması gereken tüm katmanları listeler.

> **Önemli mühendislik ilkesi ([`POLICY.md`](POLICY.md)):** Yalnızca Gradle `paper-dev-bundle` sürümünü yükseltmek geçersizdir; yeni sürümün resmi istemci fiziği, tel formatı ve blok/kutu verisi decompile/regresyon kapısından geçmeden `P-A` veya `P-M` kanıtı üretilemez.

---

## 1. Sabitlenmiş `1.21.11` Yüzeylerinin Tam Envanteri

Yeni sürüme geçilirken aşağıdaki her modüldeki sürüm bağımlılıkları sırayla güncellenmeli ve her modülün kendi uyum testi yeşile dönmelidir:

| Katman / Dosya | `1.21.11` Sabiti | Yeni Sürüm (`26.3+`) İçin Yapılacak |
|---|---|---|
| `gradle/libs.versions.toml` | `minecraft = "1.21.11"`, `paper-dev-bundle = "1.21.11-R0.1-20260511.115010-91"`, `fastutil = "8.5.18"` | Hedef Paper zaman-damgalı dev bundle + istemcinin kullandığı `fastutil` sürümü sabitlenir. |
| `whynotac-protocol` | Protokol `774`, hardcoded paket ID'leri ve codec'ler (`WireDecoder`, `ClientboundPacket`, `ServerboundPacket`) | Mojang data generator `reports/packets.json` çıktısından yeni paket ID ve alan değişiklikleri aktarılır. |
| `whynotac-world` | `BlockStates`, `EntitySizes`, `ItemPrototypes`, `MenuLayouts` (`1.21.11` tabloları) | Mojang `reports/blocks.json`, `registries.json`, menü şablonları yeniden çıkarılır (`*DataTest` ile doğrulanır). |
| `whynotac-physics` | Decompile 1.21.11 `OnFoot`, `Colliders`, `Boat`, `Horse`, `ItemSteering` | Yeni sürüm istemcisinin hareket/çarpışma/kanca değişiklikleri aktarılır; 50.000 rastgele çarpışma testi (`whynotac-conformance`) geçilir. |
| `whynotac-paper` | `PacketTranslator` (NMS paket eşlemeleri), `EngineTags` sabitleri, `ServerVersion` kontrolleri | Yeni NMS sınıf/alan adları güncellenir; `UpdateTagsTranslationTest`, `ChunkDecoderConformanceTest` ve *DataTest'ler geçirilir. |
| `tools/realclient` & `tools/e2e` | `fetch_client.py` (`VERSION = "1.21.11"`), `probe_client.py` (`PROTOCOL = 774`), `run_e2e.sh` / `run_proxy_e2e.sh` (`paper-1.21.11-132.jar`) | Yeni sürümün resmi istemcisi ve sabit SHA'lı Paper sunucusu indirilir; `MovementTruth` + XTest koşusu **0 fark** verene dek kalibre edilir. |
| `regression/*.wnrec` | `1.21.11` gerçek istemci oturumları | Mevcut korpus sürüm etiketli alt dizinde (`regression/1.21.11/`) korunur, yeni sürüm korpusu eklenir. |

---

## 2. Önerilen Geçiş Adımları

1. **Mojang Sürüm & Paper Bundle Tespiti:** Güncel Mojang manifestinde yayınlı son kararlı sürüm `26.3` (`snapshot: 26.4-snapshot-8` — 2026 yeni numaralandırma düzeni) olduğundan önce hedef Paper API + Mojang client `mappings` mevcudiyeti doğrulanır.
2. **Protokol Soyutlama (`NATIVE_PROTOCOL`):** Eğer `1.21.11` ile yeni sürümün aynı anda desteklenmesi istenirse, `AdmissibilityTracker`'daki tek sabit `NATIVE_PROTOCOL = 774` kontrolü desteklenen doğrulanmış sürüm kümesine (`Map<Integer, ProtocolSpec>`) çevrilir ve her oturum yalnızca kendi sürümünün replikası + fizik motoruyla değerlendirilir.
3. **Kapı Koşulu (`ROADMAP.md` § Kapı kuralı):** Yeni sürümde hiçbir `P-A`/`P-M` kontrolü `real client ground truth` (gerçek istemci oracle + 50.000 çarpışma fuzzing + 0 açıklanamayan tick) + canlı E2E + 100 botluk yük testi geçmeden `ARMED` adayı sayılmaz.
