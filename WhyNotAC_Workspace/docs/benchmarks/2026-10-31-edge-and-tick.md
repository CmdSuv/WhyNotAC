# Ağ kenarı ve varlık replikasının tiki (2026-10-31, görev #27)

## Ağ kenarının dökümü

Ölçüm 40 botluk bir yük testinde yapıldı (gerçek arazi, cluster düzeni). JFR her milisaniyede bir örnek aldı:
`LOAD_JFR_OPTIONS=method-profiling=max`. Netty thread'lerinde WhyNotAC'ın 358 örneği var. Varsayılan profilde aynı pay
yalnızca 72 örnekti; o ölçümde ping takibinin payı abartılı çıkmıştı. Paylar, WhyNotAC'ın Netty'deki payının yüzdesidir;
bir örnek birden çok satırda sayılabilir.

| Bileşen | Pay |
|---|---|
| Giden paketin oyun görünümü (`WhyNotChannelHandler.write`, `PacketTranslator.outbound`) | %15-18 |
| Gelen paketin kenarda çözülmesi ve uzlaştırılması (`WireObserver.onWireInbound`) | %16 |
| Ping takibi (`TransactionTracker`: allocate %7, onPong %13 ve onun içinde remember %7, onSent %5) | ~%25 |
| Uzlaştırıcı (`Reconciler.expect` %8, `match` %8) | ~%16 |
| Analiz kuyruğuna aktarım (`SerialMailbox.enqueue`: kuyruk, iki sayaç, yürütücü uyandırma) | %10 |
| Paket türünün adı (`PacketTranslator.typeName`) | %5 |

Yapılanlar (ee0cf71):

- Ping takibinin kutulanmış `HashMap`, `HashSet` ve `ArrayDeque`'leri ilkel dizilere geçti: bir halka ve büyüyen, açık
  adresli bir kimlik tablosu (`IdTable`). 40 takipçi iç içe çalışırken bir döngü (allocate, sent, pong) 870-977 ns'den
  504-661 ns'ye indi.
- Paket türünün adı artık `ConcurrentHashMap` aramasıyla değil, sınıf başına bir `ClassValue` yuvasından okunuyor.

Bırakılanlar:

- Uzlaştırıcı paketin içeriğini karşılaştırır. Bu, başka bir eklentinin paketi değiştirmesine karşı güvencenin
  kendisidir. Maliyet içerik anahtarının hash'inde ve eşitliğinde; indeks zaten kuyruk önündeki girdilere bakmıyor.
- Analiz kuyruğuna aktarım: sayaçlardan biri bütün oturumların bellek bütçesidir ve bütün thread'ler ona yazar. Yürütücü
  her boşalmadan sonra yeniden uyandırılır. İkisini de toplu hâle getirmek, bütçenin anlamına dokunur; ayrı bir iş olarak
  kaldı.

## Tekrar oynatma canlıyı neden küçümsüyordu

`ReplayCost` kayıtları birbiri ardına oynatıyordu, bu yüzden her oturumun durumu CPU önbelleğinde sıcak kalıyordu. Canlıda
40 oturumun olayları iç içe gelir. Varlık replikasının tiki (`ClientEntities.onTick`) her tikte her varlığın izine ve
durumuna dokunur; bu iş canlıda soğuk bellekte yapılır. Canlı analiz thread'inde bu tik %33 tutar, kendi döngüsü %18.
Sıralı tekrar oynatmada ise %8,5 tutar.

Yeni `interleaved` kipi bütün oturumların olaylarını, sıradaki olayın zaman damgasına göre birleştirir; her oturumun kendi
sırası korunur (`tools/load/ReplayCost.java ... all interleaved`). 40 botluk bir koşunun 40 kaydında bir istemci tiki şöyle ölçüldü:

| Kip | ns/tik |
|---|---|
| Sıralı | 13.162-14.291 |
| İç içe | 24.031-26.381 |

İki kipte kontrollerin verdiği kanıt aynıdır. Canlı değer hâlâ yaklaşık 3 kat yüksektir: canlıda sunucu thread'i ve Netty
aynı iki çekirdeği ve önbelleği paylaşır. Önbellek etkisi olan değişiklikler bundan sonra iç içe kipte ölçülmelidir.
Daha önce "duran varlık izleri" kesintisi sıralı kipte ölçülüp kazanç göstermediği için geri alınmıştı. Bu sonuç
yeniden değerlendirilmeli.

## Tikin veri yerleşimi

Tik, izleri artık map yerine yoğun bir dizide gezer; tek adayı da doğrudan bir alandan okur. Her iz kendi başına adım
attığı için sıra bir şey değiştirmez. Diğer bütün gezintiler map'in sırasını korur; itenlerin sırası kayan nokta
toplamını etkileyebilir. İç içe kipte beş çift ölçüldü; en iyi değerlerin ortancası 24.236 ns'den 23.116 ns'ye indi
(yaklaşık %5, tek koşuların gürültüsü içinde). Kanıt ve paylar aynıdır.

Asıl kazanç, duran varlıkların tik başına adımını hiç yapmamakta olurdu. Ancak bu "tiklendi mi" bayrağını tik anının
penceresine göre sonradan hesaplamayı gerektirir. Chunk ve donma geçmişi alındılarla budandıkça bu sonradan hesap, tik
anındaki yanıttan sapabilir: bir MAYBE, TRUE ya da FALSE'a dönebilir. Bu güvensiz yönde bir sapmadır. Bu yüzden yapılmadı;
budamadan önce o pencerenin yanıtını saklayan bir tasarım gerekir.
