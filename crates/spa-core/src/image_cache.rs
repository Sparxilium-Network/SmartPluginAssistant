//! Bounded persistent raster bytes in `cache/images`. No UI dependencies.
//!
//! Requests are coalesced and run on a small worker pool. `clear` bumps a generation fence so
//! downloads already in flight cannot write deleted images back.

use crate::download::{agent, get_following};
use crate::{fail, json, paths, Result};
use sha2::{Digest, Sha256};
use std::collections::HashMap;
use std::fs;
use std::io::Read;
use std::path::{Path, PathBuf};
use std::sync::mpsc::{sync_channel, Receiver, SyncSender, TrySendError};
use std::sync::{Arc, Mutex};
use std::time::{Duration, SystemTime};

pub const MAX_IMAGE_BYTES: u64 = 4 * 1024 * 1024;
pub const DEFAULT_LIMIT: u64 = 128 * 1024 * 1024;
const MAX_LIMIT: u64 = 512 * 1024 * 1024;
const MAX_AGE: Duration = Duration::from_secs(7 * 24 * 60 * 60);
const WORKERS: usize = 4;
const QUEUE: usize = 80;

pub type ImageResult = std::result::Result<Arc<Vec<u8>>, String>;
pub type Callback = Box<dyn FnOnce(ImageResult) + Send>;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct Stats {
    pub bytes: u64,
    pub files: usize,
}

struct DiskState {
    generation: u64,
    limit: u64,
    enabled: bool,
}

struct Inner {
    directory: PathBuf,
    disk: Mutex<DiskState>,
    pending: Mutex<HashMap<String, Vec<Callback>>>,
    agent: ureq::Agent,
}

struct Job {
    url: String,
    epoch: u64,
}

pub struct ImageCache {
    inner: Arc<Inner>,
    sender: Mutex<Option<SyncSender<Job>>>,
}

fn key(url: &str) -> String {
    hex::encode(Sha256::digest(url.as_bytes()))
}

fn validate(bytes: &[u8]) -> Result<()> {
    if bytes.len() < 12 || bytes.len() as u64 > MAX_IMAGE_BYTES {
        return fail("Invalid image length");
    }
    let png = bytes.starts_with(&[137, b'P', b'N', b'G']);
    let jpeg = bytes.starts_with(&[255, 216, 255]);
    let gif = bytes.starts_with(b"GIF");
    let bmp = bytes.starts_with(b"BM");
    let webp = bytes.starts_with(b"RIFF") && &bytes[8..12] == b"WEBP";
    if !(png || jpeg || gif || bmp || webp) {
        return fail("Response is not a supported raster image");
    }
    Ok(())
}

impl Inner {
    fn disk(&self) -> std::sync::MutexGuard<'_, DiskState> {
        self.disk.lock().unwrap_or_else(|e| e.into_inner())
    }

    fn files(&self) -> Result<Vec<PathBuf>> {
        paths::reject_links(&self.directory)?;
        let mut result = Vec::new();
        for entry in fs::read_dir(&self.directory)? {
            let entry = entry?;
            let name = entry.file_name().to_string_lossy().into_owned();
            let owned = name.len() == 68
                && name.ends_with(".img")
                && name[..64].bytes().all(|b| matches!(b, b'0'..=b'9' | b'a'..=b'f'));
            if !owned {
                continue;
            }
            if !entry.file_type()?.is_file() {
                return fail("Unexpected image cache entry");
            }
            result.push(entry.path());
        }
        Ok(result)
    }

    /// Least recently used entries go first.
    fn trim(&self, limit: u64) -> Result<()> {
        let mut entries = Vec::new();
        let mut total = 0;
        for file in self.files()? {
            let meta = fs::metadata(&file)?;
            total += meta.len();
            entries.push((meta.modified().unwrap_or(SystemTime::UNIX_EPOCH), meta.len(), file));
        }
        entries.sort_by_key(|(modified, _, _)| *modified);
        for (_, size, file) in entries {
            if total <= limit {
                break;
            }
            fs::remove_file(&file)?;
            total -= size;
        }
        Ok(())
    }

    fn read(&self, url: &str, epoch: u64) -> Result<Vec<u8>> {
        spa_api::http::validate_url(url)?;
        let file = paths::child(&self.directory, &format!("{}.img", key(url)))?;
        let mut stale = None;
        {
            let _disk = self.disk();
            if file.exists() {
                let cached = (|| -> Result<(Vec<u8>, bool)> {
                    if fs::metadata(&file)?.len() > MAX_IMAGE_BYTES {
                        return fail("Cached image exceeds limit");
                    }
                    let bytes = fs::read(&file)?;
                    validate(&bytes)?;
                    let modified = fs::metadata(&file)?.modified()?;
                    let fresh = SystemTime::now().duration_since(modified).unwrap_or_default() < MAX_AGE;
                    Ok((bytes, fresh))
                })();
                match cached {
                    Ok((bytes, true)) => {
                        // Sliding freshness window: a hit keeps the entry recently used.
                        let _ =
                            fs::File::options().write(true).open(&file).and_then(|f| f.set_modified(SystemTime::now()));
                        return Ok(bytes);
                    }
                    Ok((bytes, false)) => stale = Some(bytes),
                    Err(e) => {
                        log::warn!("Discarding invalid cached image {}: {e}", file.display());
                        let _ = fs::remove_file(&file);
                    }
                }
            }
        }
        let downloaded = self.download(url).and_then(|bytes| validate(&bytes).map(|_| bytes));
        let bytes = match (downloaded, stale) {
            (Ok(bytes), _) => bytes,
            (Err(e), Some(stale)) => {
                log::debug!("Using previous image after refresh failure: {e}");
                return Ok(stale);
            }
            (Err(e), None) => return Err(e),
        };
        let disk = self.disk();
        if disk.generation == epoch && disk.enabled {
            json::write_bytes(&file, &bytes)?;
            self.trim(disk.limit)?;
        }
        Ok(bytes)
    }

    fn download(&self, url: &str) -> Result<Vec<u8>> {
        let response = get_following(&self.agent, url, "image/png,image/jpeg,image/webp,image/gif,image/bmp")
            .map_err(|e| crate::Error::Message(format!("Image download failed: {e}")))?;
        let mut bytes = Vec::new();
        response.into_reader().take(MAX_IMAGE_BYTES + 1).read_to_end(&mut bytes)?;
        if bytes.len() as u64 > MAX_IMAGE_BYTES {
            return fail("Image exceeds byte limit");
        }
        Ok(bytes)
    }
}

fn worker(inner: Arc<Inner>, jobs: Arc<Mutex<Receiver<Job>>>) {
    loop {
        let job = match jobs.lock().unwrap_or_else(|e| e.into_inner()).recv() {
            Ok(job) => job,
            Err(_) => return,
        };
        let result = inner.read(&job.url, job.epoch).map(Arc::new).map_err(|e| e.to_string());
        let callbacks = inner.pending.lock().unwrap_or_else(|e| e.into_inner()).remove(&job.url);
        for callback in callbacks.into_iter().flatten() {
            callback(result.clone());
        }
    }
}

impl ImageCache {
    pub fn new(root: &Path, limit: u64) -> Result<Self> {
        if limit == 0 {
            return fail("Image cache limit must be positive");
        }
        let inner = Arc::new(Inner {
            directory: paths::directory(&root.join("cache").join("images"))?,
            disk: Mutex::new(DiskState { generation: 0, limit, enabled: true }),
            pending: Mutex::new(HashMap::new()),
            agent: agent(Duration::from_secs(25)),
        });
        let (sender, receiver) = sync_channel(QUEUE);
        let receiver = Arc::new(Mutex::new(receiver));
        for index in 0..WORKERS {
            let (inner, receiver) = (inner.clone(), receiver.clone());
            std::thread::Builder::new().name(format!("spa-image-{index}")).spawn(move || worker(inner, receiver))?;
        }
        Ok(Self { inner, sender: Mutex::new(Some(sender)) })
    }

    /// The callback runs on a worker thread (or immediately on rejection).
    pub fn load(&self, url: &str, callback: Callback) {
        let mut pending = self.inner.pending.lock().unwrap_or_else(|e| e.into_inner());
        if let Some(waiting) = pending.get_mut(url) {
            waiting.push(callback);
            return;
        }
        let epoch = {
            let disk = self.inner.disk();
            if !disk.enabled {
                drop(pending);
                callback(Err("Images disabled".into()));
                return;
            }
            disk.generation
        };
        let sender = self.sender.lock().unwrap_or_else(|e| e.into_inner()).clone();
        let job = Job { url: url.to_string(), epoch };
        let sent = match sender {
            Some(sender) => match sender.try_send(job) {
                Ok(()) => Ok(()),
                Err(TrySendError::Full(_)) => Err("Image queue is full"),
                Err(TrySendError::Disconnected(_)) => Err("Image cache closed"),
            },
            None => Err("Image cache closed"),
        };
        match sent {
            Ok(()) => {
                pending.insert(url.to_string(), vec![callback]);
            }
            Err(message) => {
                drop(pending);
                callback(Err(message.into()));
            }
        }
    }

    pub fn stats(&self) -> Result<Stats> {
        let _disk = self.inner.disk();
        self.stats_locked()
    }

    fn stats_locked(&self) -> Result<Stats> {
        let files = self.inner.files()?;
        let mut bytes = 0;
        for file in &files {
            bytes += fs::metadata(file)?.len();
        }
        Ok(Stats { bytes, files: files.len() })
    }

    /// Deletes only owned hash-named files and fences in-flight requests. Returns prior usage.
    pub fn clear(&self) -> Result<Stats> {
        let mut disk = self.inner.disk();
        disk.generation += 1;
        let before = self.stats_locked()?;
        for file in self.inner.files()? {
            fs::remove_file(file)?;
        }
        log::info!("Cleared {} cached images ({} bytes)", before.files, before.bytes);
        Ok(before)
    }

    pub fn discard(&self, url: &str) -> Result<()> {
        let _disk = self.inner.disk();
        let file = paths::child(&self.inner.directory, &format!("{}.img", key(url)))?;
        match fs::remove_file(file) {
            Err(e) if e.kind() != std::io::ErrorKind::NotFound => Err(e.into()),
            _ => Ok(()),
        }
    }

    pub fn configure(&self, enabled: bool, limit: u64) -> Result<()> {
        if limit == 0 || limit > MAX_LIMIT {
            return fail("Invalid cache limit");
        }
        let mut disk = self.inner.disk();
        if disk.enabled != enabled {
            disk.generation += 1;
        }
        disk.enabled = enabled;
        disk.limit = limit;
        self.inner.trim(limit)
    }

    pub fn enabled(&self) -> bool {
        self.inner.disk().enabled
    }
}

impl Drop for ImageCache {
    fn drop(&mut self) {
        // Workers exit once the queue closes; in-flight requests finish on their own.
        self.sender.lock().unwrap_or_else(|e| e.into_inner()).take();
    }
}
