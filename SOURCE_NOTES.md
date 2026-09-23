# Kaynak notları

Sağlanan DramaDizilerim bölüm HTML'sinden doğrulanan yapı:

- Bölüm kartlarında `data-url`, `data-title`, `data-season`, `data-episode` bulunuyor.
- Oynatıcı `.lazy-player[data-src]` üzerinden bir embed URL alıyor.
- Sayfa JavaScript'i bu URL'yi iframe `src` olarak kullanıyor.

Eklenti bu açık sayfa yapısını kullanır. Token çözme/üretme veya playback guard atlatma uygulanmaz.
