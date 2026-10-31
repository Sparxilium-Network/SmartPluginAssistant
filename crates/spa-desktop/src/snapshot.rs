//! Offscreen UI probe: drives the real window and controller with Slint's software renderer and
//! writes PNG snapshots. Used for visual verification without a display session.

use crate::{CardRow, MainWindow};
use slint::platform::software_renderer::{MinimalSoftwareWindow, RepaintBufferType};
use slint::platform::{EventLoopProxy, Platform, WindowAdapter};
use slint::{ComponentHandle, EventLoopError, Model, PlatformError, Rgb8Pixel};
use std::collections::VecDeque;
use std::path::{Path, PathBuf};
use std::rc::Rc;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

type Queue = Arc<Mutex<VecDeque<Box<dyn FnOnce() + Send>>>>;

struct Proxy(Queue);

impl EventLoopProxy for Proxy {
    fn quit_event_loop(&self) -> Result<(), EventLoopError> {
        Ok(())
    }

    fn invoke_from_event_loop(&self, event: Box<dyn FnOnce() + Send>) -> Result<(), EventLoopError> {
        self.0.lock().unwrap_or_else(|e| e.into_inner()).push_back(event);
        Ok(())
    }
}

struct OffscreenPlatform {
    window: Rc<MinimalSoftwareWindow>,
    queue: Queue,
    start: Instant,
}

impl Platform for OffscreenPlatform {
    fn create_window_adapter(&self) -> Result<Rc<dyn WindowAdapter>, PlatformError> {
        Ok(self.window.clone())
    }

    fn new_event_loop_proxy(&self) -> Option<Box<dyn EventLoopProxy>> {
        Some(Box::new(Proxy(self.queue.clone())))
    }

    fn duration_since_start(&self) -> Duration {
        self.start.elapsed()
    }
}

pub struct Probe {
    window: Rc<MinimalSoftwareWindow>,
    queue: Queue,
    width: u32,
    height: u32,
    output: PathBuf,
}

/// Installs the offscreen platform; call before creating any Slint component.
pub fn install(output: &Path, width: u32, height: u32) -> Probe {
    let window = MinimalSoftwareWindow::new(RepaintBufferType::NewBuffer);
    window.set_size(slint::PhysicalSize::new(width, height));
    let queue: Queue = Arc::default();
    slint::platform::set_platform(Box::new(OffscreenPlatform {
        window: window.clone(),
        queue: queue.clone(),
        start: Instant::now(),
    }))
    .expect("platform not yet initialized");
    std::fs::create_dir_all(output).expect("snapshot directory");
    Probe { window, queue, width, height, output: output.to_path_buf() }
}

impl Probe {
    fn pump(&self) {
        loop {
            let next = self.queue.lock().unwrap_or_else(|e| e.into_inner()).pop_front();
            match next {
                Some(task) => task(),
                None => break,
            }
        }
        slint::platform::update_timers_and_animations();
    }

    /// Processes events until `done` holds or the timeout passes.
    pub fn wait(&self, ui: &MainWindow, timeout: Duration, done: impl Fn(&MainWindow) -> bool) -> bool {
        let deadline = Instant::now() + timeout;
        while Instant::now() < deadline {
            self.pump();
            if done(ui) {
                return true;
            }
            std::thread::sleep(Duration::from_millis(30));
        }
        false
    }

    pub fn settle(&self, ui: &MainWindow, duration: Duration) {
        self.wait(ui, duration, |_| false);
    }

    pub fn capture(&self, name: &str) {
        self.pump();
        self.window.request_redraw();
        let mut buffer = vec![Rgb8Pixel::default(); (self.width * self.height) as usize];
        self.window.draw_if_needed(|renderer| {
            renderer.render(&mut buffer, self.width as usize);
        });
        let bytes: Vec<u8> = buffer.iter().flat_map(|p| [p.r, p.g, p.b]).collect();
        let path = self.output.join(format!("{name}.png"));
        image::RgbImage::from_raw(self.width, self.height, bytes)
            .expect("buffer size")
            .save(&path)
            .expect("write snapshot");
        println!("snapshot {}", path.display());
    }
}

fn loaded_artwork(ui: &MainWindow) -> usize {
    let cards = ui.get_cards();
    (0..cards.row_count()).filter_map(|i| cards.row_data(i)).filter(|c: &CardRow| c.loaded).count()
}

/// Scripted walkthrough using live provider APIs in an isolated data directory.
pub fn run(probe: &Probe, ui: &MainWindow) {
    let long = Duration::from_secs(30);
    ui.show().expect("show window");
    probe.settle(ui, Duration::from_millis(300));
    probe.capture("01-empty");

    ui.invoke_new_instance();
    let catalog = probe.wait(ui, long, |ui| !ui.get_inst_loading() && ui.get_inst_loaders().row_count() > 0);
    println!("catalog loaded: {catalog}");
    ui.set_inst_name("Survival 生存".into());
    probe.capture("02-instance-dialog");
    ui.invoke_instance_save();
    probe.wait(ui, long, |ui| ui.get_library_visible());
    probe.settle(ui, Duration::from_millis(500));
    probe.capture("03-library");

    ui.set_tab(1);
    ui.invoke_market_activated(true);
    let cards = probe.wait(ui, long, |ui| ui.get_cards().row_count() > 0);
    println!("modrinth cards: {} ({cards})", ui.get_cards().row_count());
    probe.wait(ui, Duration::from_secs(15), |ui| loaded_artwork(ui) >= 5);
    ui.set_selected_card(0);
    ui.invoke_select_card(0);
    probe.wait(ui, long, |ui| ui.get_releases_state() >= 2);
    probe.settle(ui, Duration::from_secs(1));
    println!("releases: {} state {}", ui.get_releases().row_count(), ui.get_releases_state());
    probe.capture("04-discover-modrinth");

    ui.set_source_index(1);
    ui.invoke_set_source(1);
    probe.wait(ui, long, |ui| ui.get_cards().row_count() > 0);
    probe.wait(ui, Duration::from_secs(15), |ui| loaded_artwork(ui) >= 5);
    ui.set_selected_card(0);
    ui.invoke_select_card(0);
    probe.wait(ui, long, |ui| ui.get_releases_state() >= 2);
    probe.settle(ui, Duration::from_secs(1));
    println!("hangar cards: {} releases {}", ui.get_cards().row_count(), ui.get_releases().row_count());
    probe.capture("05-discover-hangar");

    let before = ui.get_cards().row_count();
    ui.invoke_load_more();
    let more = probe.wait(ui, long, |ui| ui.get_cards().row_count() > before);
    println!("load more: {before} -> {} ({more})", ui.get_cards().row_count());

    if ui.get_selected_release() >= 0 {
        ui.invoke_review(ui.get_selected_release(), false);
        probe.wait(ui, long, |ui| ui.get_dialog() == "plan");
        probe.capture("06-plan");
        // Real install into the isolated data directory, then an update check.
        ui.set_dialog("".into());
        ui.invoke_plan_install();
        let installed = probe.wait(ui, Duration::from_secs(120), |ui| !ui.get_busy() && ui.get_plugins().row_count() > 0);
        println!("installed plugins: {} ({installed}) status: {}", ui.get_plugins().row_count(), ui.get_status());
        ui.set_tab(0);
        ui.invoke_market_activated(false);
        ui.invoke_lib_check_updates();
        probe.settle(ui, Duration::from_millis(300));
        probe.wait(ui, long, |ui| !ui.get_busy());
        println!("update check: {}", ui.get_status());
        probe.capture("06b-library-installed");
        ui.set_tab(1);
        ui.invoke_market_activated(true);
    }

    ui.invoke_open_settings();
    probe.wait(ui, long, |ui| ui.get_dialog() == "settings" && !ui.get_set_usage().is_empty());
    probe.capture("07-settings");
    ui.set_set_theme_index(1);
    ui.set_set_accent_index(2);
    ui.set_set_language_index(1);
    ui.set_dialog("".into());
    ui.invoke_settings_save();
    probe.settle(ui, Duration::from_secs(2));
    probe.capture("08-light-english");
    ui.set_tab(0);
    ui.invoke_market_activated(false);
    probe.settle(ui, Duration::from_millis(500));
    probe.capture("09-library-light-english");
}
