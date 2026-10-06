# Oyuncu Bilgilendirme Metni Şablonu / Player Privacy Notice Template

> Sunucu yöneticisi için şablondur: köşeli parantezli alanları doldurun ve metni kendi aydınlatma
> metninize uyarlayın. WhyNotAC'ın yaptığı tam olarak aşağıda yazanlardır. Metni, oyuncular
> kaydedilmeden **önce** görebilecekleri bir yerde yayınlayın (web sitesi, Discord, giriş mesajı).
>
> Template for server operators: fill in the bracketed fields and adapt it to your own notice. What
> WhyNotAC does is exactly what is written below. Publish it where players can read it **before**
> they are recorded (website, Discord, join message).

---

## Türkçe

**Hile karşıtı sistem kayıtları — [SUNUCU ADI]**

Bu sunucu, hile tespiti için WhyNotAC kullanır. Oyun oturumunuz boyunca istemcinizin sunucuya
gönderdiği ve sunucunun size gönderdiği oyun paketlerinin bir kısmı kaydedilir.

**Kaydedilenler**
- Konum, bakış yönü (yaw/pitch), zeminde olma bilgisi ve tuş girdileri (ileri/geri/zıplama/eğilme/koşma)
- Oyun eylemleri: vuruş, varlık etkileşimi, eşya kullanma, blok kırma/koyma, envanter tıklamaları,
  seçili slot
- Sunucunun size gönderdiği ve hareketinizi etkileyen paketler: savrulma (knockback), ışınlanma,
  patlama itmesi, iksir etkileri, nitelikler, yetenekler
- Zamanlama: paketlerin sunucuya ulaştığı an ve gecikme ölçümleri (ping, jitter)
- Bağlandığınız Minecraft protokol sürümü

**Kaydedilmeyenler**
- IP adresiniz, kullanıcı adınız, sohbet mesajlarınız ve komutlarınız
- Bilgisayarınızdaki dosyalar, işlemler veya modlar: WhyNotAC yalnızca sunucu tarafında çalışır,
  cihazınızı taramaz

**Takma adlandırma.** Kayıtlarda Minecraft UUID'niz yerine, yalnızca bu sunucuda saklanan gizli bir
anahtarla üretilmiş bir takma ad (HMAC-SHA256) bulunur. Anahtar olmadan bir kayıt size
bağlanamaz.

**Amaç.** Hilelerin tespiti ve hile karşıtı sistemin doğrulanması. Özellikle dürüst oyuncuların
yanlışlıkla cezalandırılmasının önlenmesi.

**Hukuki sebep.** [Örn. KVKK m.5/2-f meşru menfaat / GDPR Art. 6(1)(f), sunucu yöneticisi
belirler]

**Saklama süresi.** Kayıtlar en fazla [14] gün saklanır ve sonra otomatik olarak silinir. Bir
ceza veya itiraz incelemesi için gerekli kayıtlar, inceleme sonuçlanana kadar ayrıca
saklanabilir: [süre].

**Aktarım.** Kayıtlar [SUNUCU KONUMU / BARINDIRMA SAĞLAYICISI] üzerinde tutulur ve
[kimseyle paylaşılmaz / şu taraflarla paylaşılır: ...].

**Haklarınız.** Kayıtlarınıza erişim, kopyasını alma ve silinmesini isteme haklarına sahipsiniz.
Talebinizi Minecraft kullanıcı adınızla birlikte [İLETİŞİM ADRESİ] adresine iletin. Talepler
[30] gün içinde yanıtlanır.

**Veri sorumlusu.** [AD / UNVAN, ADRES, İLETİŞİM]

---

## English

**Anti-cheat recordings — [SERVER NAME]**

This server uses WhyNotAC to detect cheating. During your play session, part of the game traffic
between your client and the server is recorded.

**What is recorded**
- Position, view direction (yaw/pitch), on-ground state and movement keys
  (forward/back/jump/sneak/sprint)
- Game actions: attacks, entity interactions, item use, block breaking/placing, inventory clicks,
  selected hotbar slot
- Packets the server sends you that affect your movement: knockback, teleports, explosion push,
  potion effects, attributes, abilities
- Timing: when packets reached the server, and latency measurements (ping, jitter)
- The Minecraft protocol version you connected with

**What is not recorded**
- Your IP address, username, chat messages or commands
- Files, processes or mods on your computer: WhyNotAC runs only on the server and never scans
  your device

**Pseudonymisation.** Recordings do not contain your Minecraft UUID. They contain a pseudonym
(HMAC-SHA256) derived with a secret key kept only on this server. Without that key, a recording
cannot be linked to you.

**Purpose.** Detecting cheats and validating the anti-cheat. In particular, preventing honest
players from being punished by mistake.

**Legal basis.** [e.g. GDPR Art. 6(1)(f) legitimate interest. The operator decides.]

**Retention.** Recordings are kept for at most [14] days and are then deleted automatically.
Recordings needed for a pending punishment or appeal may be kept until it is resolved: [period].

**Transfers.** Recordings are stored at [SERVER LOCATION / HOSTING PROVIDER] and are
[not shared with anyone / shared with: ...].

**Your rights.** You can ask to access, receive a copy of, or delete your recordings. Send your
request together with your Minecraft username to [CONTACT ADDRESS]. Requests are answered within
[30] days.

**Controller.** [NAME / LEGAL ENTITY, ADDRESS, CONTACT]
