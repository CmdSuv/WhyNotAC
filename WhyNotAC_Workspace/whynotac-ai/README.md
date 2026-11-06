# whynotac-ai — Kişisel profil ve anomali modeli (Faz 3–4, planlı)

**Sözleşme:** Oyuncu başına, birleştirilebilir (`RunningMoments.merge`) özet istatistiklerden
oluşan bir davranış profili tutar. Yeni oturumun bu profilden sapmasını kalibre edilmiş bir
skorla ölçer (hesap ele geçirme, bot/makro devri). Ham paket saklamaz.

İlkeler:
- **Açıklanabilirlik:** her skor, katkı veren özelliklere ayrıştırılabilir olmalı. "Model öyle
  dedi" bir kanıt değildir.
- **Kalibrasyon:** skorlar temiz oyuncu replay'leri üzerinde ölçülmüş yanlış-pozitif oranına
  bağlanır. Kalibre edilmemiş bir model verdict katmanına kanıt veremez.
- **Sunucu tarafı ve çevrimiçi:** eğitim/çıkarım sunucuda, oyun thread'inden bağımsız çalışır.
- **Aktif sonda (honeypot):** yalnızca oyunu meşru oyuncu için **gözle görülür biçimde
  değiştirmeyen** sentetik uyarıcılar (ör. client'ın tepki vermesi gerekmeyen, sadece hilenin
  tepki vereceği durumlar). Her sonda ayrı bir tasarım incelemesinden geçer.
