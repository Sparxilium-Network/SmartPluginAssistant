# 應用程式圖示

- 2026-11-11：使用者放入 `assets/icon.png`（1254×1254 RGBA，草方塊加拼圖），要求作為應用程式圖示。
- 唯一來源為 `assets/icon.png`，衍生檔不進 Git：`crates/spa-desktop/build.rs` 產生 `OUT_DIR/window-icon.png`（256）與 `OUT_DIR/app.ico`（16/24/32/48/64/128/256，PNG 壓縮框）。
- exe 圖示：只在 `CARGO_CFG_TARGET_OS == windows` 時用 `winresource` 嵌入；其他平台只有視窗圖示。build-dependency `image`（png, ico）、`winresource`。
- 視窗圖示必須是 Slint 編譯期嵌入的圖片：先試過 Rust 端 `Image::from_rgba8` 設 `icon` 屬性，但 i-slint-backend-winit 1.18.1 以 `ImageCacheKey` 判斷圖示是否變更，記憶體影像沒有 cache key，結果永遠不套用（WM_GETICON 回 0）。
- 改法：`with_library_paths({"generated": OUT_DIR})`，`app.slint` 寫 `icon: @image-url("@generated/window-icon.png")`；Slint 的 `@image-url` 支援 `@library/` 路徑。
- 不直接 `@image-url` 原始 1254px PNG：避免把 688 KB 原檔嵌進執行檔。
