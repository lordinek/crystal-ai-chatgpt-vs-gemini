# ChatGPT vs Gemini: Minecraft Kılıç Düellosu

ChatGPT ile Gemini'yi Minecraft sunucusunda kılıçla karşı karşıya getiren bir Paper eklentisi. İki model de aynı anda, aynı bilgilerle (can, mesafe, kalan süre) sorulur. Her biri "saldır, kaç, blokla, iyileş" gibi bir hamle seçer, eklenti o hamleyi oyun içindeki NPC'ye yaptırır. Maç best-of-3 / best-of-5 gider, skor ve maliyet tahmini tutulur.

Ayrıca bir insan oyuncu da modellerden biriyle düello yapabilir (`/yzmac kapis`).

> Bu proje eğlence ve deney amaçlıdır. Sadece kendi (özel) sunucunda kullan.

## Gerekenler

- Java 21
- Paper **1.21.11** sunucusu ([papermc.io](https://papermc.io/downloads/paper))
- En az bir API anahtarı (maçı gerçek modellerle oynatmak için):
  - OpenAI (ChatGPT): <https://platform.openai.com/api-keys>
  - Google Gemini: <https://aistudio.google.com/apikey>

API anahtarı olmadan da `mock` modunda (sahte, rastgele cevaplar) her şeyi ücretsiz deneyebilirsin.

## Kurulum

1. Eklentiyi derle:

   ```
   cd plugin
   ./gradlew build        # Windows: gradlew.bat build
   ```

   Çıkan jar `plugin/build/libs/` altında olur.
2. O jar'ı Paper sunucunun `plugins/` klasörüne at.
3. Sunucuyu bir kere başlat ve kapat. Eklenti `plugins/CrystalAI/llm.yml` dosyasını kendisi oluşturur.
4. Kendini op yap (`op <isim>`). Komutlar `crystalai.admin` yetkisi ister.

## API anahtarını nereye koyacaksın?

Anahtarlar **sadece** sunucundaki şu dosyaya yazılır:

```
<sunucu>/plugins/CrystalAI/llm.yml
```

Dosyayı aç, iki yeri doldur:

```yaml
chatgpt:
  api-anahtari: "BURAYA_OPENAI_ANAHTARI"     # <- OpenAI anahtarını yapıştır
gemini:
  api-anahtari: "BURAYA_GEMINI_ANAHTARI"     # <- Gemini anahtarını yapıştır
```

Tırnakların içindeki `BURAYA_...` yazısını silip kendi anahtarını koy. Sonra oyunda ya da konsolda:

```
/yzmac yenile
```

Ayarlar yeniden okunur. Bu komutu maç sürerken değil, maçlar arasında çalıştır.

Aynı dosyada model adlarını da değiştirebilirsin (`model:` satırları). İki modeli aynı seviyede tut ki maç adil olsun (ikisi de ucuz/hızlı katman gibi). `fiyat-girdi-1m` ve `fiyat-cikti-1m` satırları maliyet tahmini içindir, kullandığın modelin güncel fiyatına göre elle ayarla.

### Anahtar güvenliği

- Anahtarını Discord'a, sohbete, ekran görüntüsüne ya da GitHub'a **koyma**. Videoda `llm.yml` açıkken ekran kaydı alma.
- `llm.yml` sunucu klasöründe kalır. Bu repoda anahtar yoktur ve olmamalıdır. Fork'layıp değişiklik yaparsan `llm.yml`'yi commit'leme.
- Anahtarın sızarsa hemen ilgili panelden sil ve yenisini üret.
- OpenAI ve Google panelinden aylık harcama limiti koy. Eklenti maç boyunca her iki modele yaklaşık 2,5 saniyede bir istek atar, limit seni sürprizden korur.

## Kullanım

| Komut | Ne yapar |
| --- | --- |
| `/yzmac baslat mock 3` | Ücretsiz test maçı, sahte cevaplar, best-of-3 |
| `/yzmac baslat gercek 3` | Gerçek API'lerle best-of-3 |
| `/yzmac izle` | Arenaya izleyici olarak ışınlanırsın |
| `/yzmac durum` | Skor, gecikme, çağrı sayısı, tahmini maliyet |
| `/yzmac durdur` | Seriyi durdurur, NPC'leri siler |
| `/yzmac yenile` | `llm.yml` dosyasını yeniden okur |
| `/yzmac kapis <chatgpt\|gemini>` | Sen seçtiğin modele karşı dövüşürsün |
| `/yzmac arena nokta <isim> <1\|2\|kamera>` | Durduğun yeri kendi arenanın noktası olarak kaydeder |

Önce `mock` ile dene, çalıştığını gör, sonra `gercek`'e geç. Her komutun ayrıntısı `plugin/src/main/java/com/crystalai/plugin/llm/LlmCommand.java` dosyasının başındaki yorumda.

Maç kayıtları (`kararlar.jsonl`, özet, altyazı) `plugins/CrystalAI/yz-mac/` altına yazılır. Bu dosyalar modellerin her kararını içerir, paylaşmadan önce göz at.

## Sorun giderme

- **"api-anahtari yok" hatası:** `llm.yml` içinde anahtar hâlâ `BURAYA_...` ya da boş. Doldur, `/yzmac yenile` yaz.
- **Model cevap vermiyor / zaman aşımı:** `zaman-asimi-ms` değerini artır ya da daha hızlı bir model seç. Cevap gelmezse son hamle sürer.
- **Gemini boş cevap veriyor:** `thinking-budget: 0` ayarı düşünmeyi kapatır. Yeni bir Gemini modeli bunu istiyorsa bu satırı kontrol et.
- **Yetki yok:** `op <isim>` ile kendini yetkilendir.

## Lisans

MIT, `LICENSE` dosyasına bak.
