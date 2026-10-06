# whynotac-analysis — Davranış dedektörleri (Faz 3, planlı)

**Sözleşme:** Zaman çizelgesi ve fizik kanıtları üzerinde çalışan, her biri bir **istatistik ve
bilgi sınırı** ile birlikte `Evidence` üreten dedektörler. Cezalandırmaz.

Planlanan aileler:
- **Aim:** `MouseTurnModel` tersine çevirme ile imleç sayımı uzayına geçiş (hassasiyet tahmini,
  çok-kareli float birikimini hesaba katan kafes uyumu), açısal hız/ivme/jerk dağılımları,
  hedefe kilitlenme ve aşırı-düzeltme imzaları, "mükemmel rastgelelik" testleri
  (entropi, otokorelasyon, spektral düzlük).
- **Tıklama:** saldırı/swing aralıklarının dağılımı (kaydırmalı Welford momentleri, basıklık,
  çarpıklık), donanım makrosu periyodikliği, insan kas sınırları.
- **Zamanlama:** `ClientTickEnd` çerçevelemesiyle Timer/Blink, transaction tutma, paket
  patlamaları.
- **Etkileşim:** erişim/hitbox (bit-tam `viewVector` ışın izleme), görüş hattı, envanter zamanlaması.

Sinematik kamera, dürbün, kesirli imleç ve büyük-yaw çözünürlük kaybı gibi bilinen meşru
istisnalar, dedektör kanıt üretmeden **önce** dışlanır.
