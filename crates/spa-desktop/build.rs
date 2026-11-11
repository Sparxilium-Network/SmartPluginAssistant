use image::codecs::ico::{IcoEncoder, IcoFrame};
use image::imageops::FilterType;
use image::{DynamicImage, ExtendedColorType};
use std::collections::HashMap;
use std::path::{Path, PathBuf};

/// Sizes stored in the Windows executable icon (Explorer, taskbar, Alt+Tab, shortcuts).
const ICO_SIZES: [u32; 7] = [16, 24, 32, 48, 64, 128, 256];
/// Edge length of the runtime window icon handed to Slint.
const WINDOW_ICON: u32 = 256;

fn main() {
    let out = PathBuf::from(std::env::var("OUT_DIR").expect("OUT_DIR"));
    icons(&out);
    // `@generated/...` in the UI refers to files this script writes into OUT_DIR.
    let config = slint_build::CompilerConfiguration::new()
        .with_style("fluent".into())
        .with_library_paths(HashMap::from([("generated".to_string(), out)]));
    slint_build::compile_with_config("ui/app.slint", config).expect("Slint UI compiles");
}

/// Derives every icon from the single source `assets/icon.png`.
fn icons(out: &Path) {
    let source = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../assets/icon.png");
    println!("cargo:rerun-if-changed={}", source.display());
    let original = image::open(&source).expect("assets/icon.png is a readable PNG");

    // Window icon embedded by Slint (`@image-url("@generated/window-icon.png")`). It must be a
    // compiled-in image: Slint only applies window icons it can identify by a cache key.
    resize(&original, WINDOW_ICON).save(out.join("window-icon.png")).expect("write window icon");

    if std::env::var("CARGO_CFG_TARGET_OS").as_deref() == Ok("windows") {
        let ico = out.join("app.ico");
        let frames: Vec<IcoFrame> = ICO_SIZES
            .iter()
            .map(|&size| {
                let image = resize(&original, size);
                IcoFrame::as_png(image.as_raw(), size, size, ExtendedColorType::Rgba8).expect("encode icon frame")
            })
            .collect();
        let file = std::fs::File::create(&ico).expect("create app.ico");
        IcoEncoder::new(file).encode_images(&frames).expect("write app.ico");
        winresource::WindowsResource::new()
            .set_icon(ico.to_str().expect("UTF-8 OUT_DIR"))
            .compile()
            .expect("embed Windows icon resource");
    }
}

fn resize(image: &DynamicImage, size: u32) -> image::RgbaImage {
    image.resize_exact(size, size, FilterType::Lanczos3).to_rgba8()
}
