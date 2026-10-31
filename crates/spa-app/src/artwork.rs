//! Bounded raster decoding into small RGBA thumbnails plus a decoded memory cache.

use image::{imageops::FilterType, ImageReader, Limits};
use std::collections::{HashMap, VecDeque};
use std::io::Cursor;
use std::sync::{Arc, Mutex};

pub const THUMBNAIL: u32 = 128;
const MEMORY_ENTRIES: usize = 128;
const MAX_DIMENSION: u32 = 4096;

/// Straight-alpha RGBA8 pixels, `width * height * 4` bytes.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Rgba {
    pub width: u32,
    pub height: u32,
    pub pixels: Arc<Vec<u8>>,
}

/// First frame of PNG/JPEG/GIF/BMP/WebP, scaled to fit `THUMBNAIL` pixels.
pub fn decode(bytes: &[u8]) -> Result<Rgba, String> {
    let mut reader = ImageReader::new(Cursor::new(bytes)).with_guessed_format().map_err(|e| e.to_string())?;
    let mut limits = Limits::default();
    limits.max_image_width = Some(MAX_DIMENSION);
    limits.max_image_height = Some(MAX_DIMENSION);
    limits.max_alloc = Some(64 * 1024 * 1024);
    reader.limits(limits);
    let image = reader.decode().map_err(|e| e.to_string())?;
    let image = if image.width() > THUMBNAIL || image.height() > THUMBNAIL {
        image.resize(THUMBNAIL, THUMBNAIL, FilterType::Triangle)
    } else {
        image
    };
    let rgba = image.to_rgba8();
    Ok(Rgba { width: rgba.width(), height: rgba.height(), pixels: Arc::new(rgba.into_raw()) })
}

#[derive(Default)]
pub struct MemoryCache {
    entries: Mutex<(HashMap<String, Rgba>, VecDeque<String>)>,
}

impl MemoryCache {
    pub fn get(&self, url: &str) -> Option<Rgba> {
        let mut guard = self.entries.lock().unwrap_or_else(|e| e.into_inner());
        let (map, order) = &mut *guard;
        let found = map.get(url).cloned();
        if found.is_some() {
            order.retain(|u| u != url);
            order.push_back(url.to_string());
        }
        found
    }

    pub fn put(&self, url: &str, image: Rgba) {
        let mut guard = self.entries.lock().unwrap_or_else(|e| e.into_inner());
        let (map, order) = &mut *guard;
        if map.insert(url.to_string(), image).is_none() {
            order.push_back(url.to_string());
        }
        while map.len() > MEMORY_ENTRIES {
            if let Some(oldest) = order.pop_front() {
                map.remove(&oldest);
            }
        }
    }

    pub fn clear(&self) {
        let mut guard = self.entries.lock().unwrap_or_else(|e| e.into_inner());
        guard.0.clear();
        guard.1.clear();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn encode(format: image::ImageFormat, width: u32, height: u32) -> Vec<u8> {
        let image = image::RgbaImage::from_pixel(width, height, image::Rgba([10, 20, 30, 255]));
        let mut bytes = Cursor::new(Vec::new());
        image.write_to(&mut bytes, format).unwrap();
        bytes.into_inner()
    }

    #[test]
    fn decodes_supported_formats_and_bounds_size() {
        for format in [image::ImageFormat::Png, image::ImageFormat::Gif, image::ImageFormat::Bmp, image::ImageFormat::WebP] {
            let decoded = decode(&encode(format, 300, 150)).unwrap();
            assert_eq!((decoded.width, decoded.height), (128, 64), "{format:?}");
            assert_eq!(decoded.pixels.len(), 128 * 64 * 4);
        }
        assert!(decode(b"<html>").is_err());
    }

    #[test]
    fn memory_cache_evicts_oldest() {
        let cache = MemoryCache::default();
        let image = Rgba { width: 1, height: 1, pixels: Arc::new(vec![0; 4]) };
        for i in 0..=MEMORY_ENTRIES {
            cache.put(&i.to_string(), image.clone());
        }
        assert!(cache.get("0").is_none() && cache.get("1").is_some());
        cache.clear();
        assert!(cache.get("1").is_none());
    }
}
