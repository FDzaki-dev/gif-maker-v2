# Changelog — GifMaker

## v1.2 (versionCode 3)
- **Video preview player**: video yang dipilih sekarang bisa diputar langsung di layar, dengan tombol play/pause dan seekbar untuk scrub. Preview otomatis loop hanya di bagian yang sedang di-trim, jadi terlihat persis apa yang bakal jadi GIF.
- **Release build benar-benar release**: CI (`.github/workflows/build.yml`) sekarang menjalankan `assembleRelease`, bukan `assembleDebug` — output APK sudah di-minify (R8) dan di-shrink resource-nya.
- **Signing key persisten**: ditambahkan `app/keystore/release.keystore.jks` + `signingConfig` khusus, supaya tiap build CI ditandatangani dengan sertifikat yang SAMA. Tanpa ini, tiap build GitHub Actions akan pakai debug-keystore baru yang beda tiap kali → HP akan menolak update ("package conflicts with an existing package"). Catatan: ini keystore pribadi untuk sideload, BUKAN untuk publish ke Play Store.

## v1.1 (versionCode 2)
- R8 minification + resource shrinking diaktifkan untuk build release.
- Tombol **Batalkan** saat proses render GIF berlangsung.
- Progress bar di-throttle (~40 update, bukan tiap frame) dan dipindah ke Main thread — render 240 frame tidak lagi membanjiri UI dengan recomposition.
- Guard `OutOfMemoryError` — video terlalu besar menampilkan pesan jelas, bukan crash.
- Guard sisa penyimpanan sebelum mulai render.
- File output otomatis dihapus kalau render dibatalkan/gagal.

## v1.0 (versionCode 1)
- Rilis awal: pilih video, trim, atur FPS & lebar, estimasi ukuran, custom GIF encoder (GIF89a, median-cut quantization, LZW), Studio/riwayat GIF, share, ekspor ke galeri publik.
