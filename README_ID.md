# Firdaus Sticker AI — project Android (v1.3)

## Fitur
- Pilih foto dari galeri, lalu **potong (crop)** sebelum dijadikan stiker
  (mode Bebas / 1:1, putar 90°)
- Mode: Full Picture / Make It Transparent / Cartoon & Transparent
  - Transparan: ML Kit Subject Segmentation (berjalan di perangkat, tanpa backend)
  - Kartun: efek kartun di perangkat (haluskan warna, posterisasi, garis tepi)
- Teks opsional: pilihan font, tebal/miring, ukuran, warna
- Teks bisa **digeser** dengan jari pada gambar, **dimiringkan**, **dilengkungkan**, dibuat **bergelombang**, atau **mengikuti garis yang digambar sendiri**
- Simpan stiker sebagai PNG 512x512 transparan ke galeri (Pictures/FirdausStickerAI)
- **Paket stiker WhatsApp otomatis**
  1. Buat stiker, tekan "Tambah stiker ini ke paket" (ulangi minimal 3x, maksimal 30)
  2. Tekan "Tambahkan paket ke WhatsApp" lalu konfirmasi di WhatsApp
  - Stiker otomatis dikonversi ke WebP 512x512 (<100 KB), ikon paket (tray) PNG 96x96

## Catatan
- minSdk 24 (Android 7.0), dibutuhkan oleh ML Kit Subject Segmentation
- Emoji stiker default: 😀

## Build lewat GitHub Actions
Workflow ada di `.github/workflows/build-apk.yml`. Hasil APK ada di Actions > run terbaru > Artifacts.

### Upload dari HP (tanpa folder)
Upload satu file `project.zip` ke root repo. Workflow akan membuka zip tersebut dan membangun APK dari isinya.
