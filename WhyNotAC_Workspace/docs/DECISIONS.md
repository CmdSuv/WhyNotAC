# Kararlar

Proje sahibinin kararları ve onlardan çıkan çalışma kuralları, tarih sırasıyla. Bir yol haritası adımı
bu dosyayla çelişirse bu dosya geçerlidir.

## 2026-10-07: Tek koşulsuz kural "dünyanın en iyisi"

Sahibin sözleri: "Her şeye izin var. İlk prompta tamamen uymak zorunda değilsin. Koşulsuz uyulması
gereken tek şey: kusursuza olabildiğince yakın, dünyadaki en iyi olması."

| Konu | Karar | Uygulamadaki anlamı |
|---|---|---|
| Ana kural | Kusursuza olabildiğince yakın, dünyanın en iyisi | İlk brief'teki diğer maddeler artık kural değil, varsayılan yön. "En iyi" ölçülebilir olmalı: tespit oranı, yanlış pozitif oranı, performans, rakiplerle aynı koşullarda karşılaştırma. |
| Yanlış ban | "En iyi"nin parçası | Masum oyuncuyu banlayan bir sistem en iyi olamaz. Sıfır yanlış ban artık bir yasak değil, bir kalite ölçütü. Otomatik ceza yalnızca kanıtlanmış (fiziksel olarak imkânsız) durumlarda ve ölçülmüş hata sınırıyla verilir. |
| Önlem (mitigation) | İzinli | Geri alınabilir, sessiz önlemler kullanılabilir: setback, hasar iptali, bilgi gizleme (anti-ESP, anti-xray). Bunlar ceza değildir. Yanlış pozitifte verdikleri zarar sınırlıdır ve ölçülür. |
| Sunucu türleri | PvP, practice, SMP: hepsi | Dövüş, hareket ve dünya/envanter kontrolleri aynı derinlikte. Varsayılan ayar setleri sunucu türüne göre ayrı olacak. |
| Kimlik | Online ve offline mode birlikte | Online: Mojang'ın doğruladığı kimlik. Offline (cracked): kimlik doğrulanmamış. Bu durumda profiller isim + bağlantı ile tutulur ve AuthMe/nLogin/LibreLogin gibi giriş eklentilerinin onayı ayrı bir güven düzeyi olur. Doğrulanmamış bir kimliğe uzun ömürlü profil ve otomatik ceza bağlanmaz; yetkili onayı gerekir (`ClientFact.Identity`). |
| Client tarafı | İzinli (isteğe bağlı) | Çekirdek tespit yalnızca sunucuya dayanır ve tek başına çalışır. Gönüllü bir companion mod (ör. turnuvalar için) ek sinyal verebilir. Değerlendirmesi eksik analizinde; zorunlu client taraması yok. |
| Sürüm | Şimdilik 1.21.11 | Bütün derinlik 1.21.11'e. Mimari çok sürüme izin verecek şekilde kalır: protokol tabloları, codec'ler ve fizik sabitleri sürüme bağlı modüllerde. |

## 2026-10-06: İlk brief (artık varsayılan yön)

- Tamamen sunucu taraflı, Paper 1.21.11. Şüphede izle, kanıt biriktir, asla yanlış banlama.
- Polar ve Minemen AGC'den ilham, Grim gibi katı değil.
- Bütün mekanikler modellenir (mace, riptide, wind charge, spear, knockback, ...). 1e-6 hassasiyet
  ve ters kuvvet hesabı.
- Çok katmanlı yapay zekâ, ping/jitter telafisi, honeypot'lar, ghost client tespiti.
- Proje gizli (private repo). Hukuki görüş gerekmiyor. Açık kaynak hilelerin izole bir VM'de
  kaydedilmesine izin var.
