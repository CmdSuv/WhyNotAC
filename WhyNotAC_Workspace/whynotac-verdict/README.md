# whynotac-verdict — Kanıt birleştirme ve karar (Faz 4, planlı)

**Sözleşme:** Tüm katmanlardan gelen `Evidence` kayıtlarını birleştirir ve operatör politikasına
göre bir **karar önerisi** üretir: izle, işaretle, inceleme iste, (açıkça etkinleştirilmişse)
uygula.

- Her kanıtın kaynağı, istatistiği, bilgi sınırı ve bağımlılık grubu vardır. Aynı fiziksel olaydan
  türeyen kanıtlar tek kanıt sayılır.
- Eşikler, oyuncu-ömrü başına yanlış-pozitif olasılığı olarak tanımlanır ve replay ile
  doğrulanır.
- `Gap`, bilinmeyen client sürümü veya analiz edilemeyen pencereler kanıtı **sıfırlar**.
- Her karar, insan tarafından okunabilir bir kanıt dosyasıyla birlikte gelir.
