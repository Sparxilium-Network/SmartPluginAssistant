//! Slint frontend. It only maps `UiEvent` view models onto Slint models and forwards user
//! actions to the `spa_app::Controller`; no application behaviour lives here.

#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

slint::include_modules!();

mod snapshot;

use slint::{
    CloseRequestResponse, Color, ComponentHandle, Image, Model, ModelRc, Rgba8Pixel, SharedPixelBuffer, SharedString,
    VecModel,
};
use spa_app::artwork::Rgba;
use spa_app::context::{builtin_providers, default_data_dir};
use spa_app::i18n::{tr, trf};
use spa_app::settings::{ACCENTS, LANGUAGES};
use spa_app::{AppContext, AppSettings, Controller, InstanceForm, ReleasesVm, SettingsVm, UiEvent};
use std::path::{Path, PathBuf};
use std::rc::Rc;
use std::sync::Arc;

struct Args {
    data_dir: PathBuf,
    smoke: bool,
    snapshot: Option<PathBuf>,
}

fn parse_args() -> Args {
    let mut data_dir = None;
    let mut smoke = false;
    let mut snapshot = None;
    for arg in std::env::args().skip(1) {
        if let Some(value) = arg.strip_prefix("--data-dir=") {
            data_dir = Some(PathBuf::from(value));
        } else if let Some(value) = arg.strip_prefix("--snapshot=") {
            snapshot = Some(PathBuf::from(value));
        } else if arg == "--smoke" {
            smoke = true;
        }
    }
    let home = dirs::home_dir().unwrap_or_else(|| PathBuf::from("."));
    Args { data_dir: data_dir.unwrap_or_else(|| default_data_dir(&home)), smoke, snapshot }
}

fn init_logging(data_dir: &Path) {
    let logs = data_dir.join("logs");
    let _ = std::fs::create_dir_all(&logs);
    let file = logs.join("app.log");
    if std::fs::metadata(&file).is_ok_and(|m| m.len() > 5 * 1024 * 1024) {
        let _ = std::fs::rename(&file, logs.join("app.1.log"));
    }
    let config = simplelog::ConfigBuilder::new().add_filter_ignore_str("ureq").add_filter_ignore_str("rustls").build();
    if let Ok(output) = std::fs::OpenOptions::new().create(true).append(true).open(&file) {
        let _ = simplelog::WriteLogger::init(log::LevelFilter::Info, config, output);
    }
}

// ---------- model helpers ----------

fn vec_model<T: Clone + 'static>(model: &ModelRc<T>) -> &VecModel<T> {
    model.as_any().downcast_ref::<VecModel<T>>().expect("models are VecModel")
}

fn set_rows<T: Clone + 'static>(model: ModelRc<T>, rows: Vec<T>) {
    vec_model(&model).set_vec(rows);
}

fn strings(values: Vec<String>) -> Vec<SharedString> {
    values.into_iter().map(SharedString::from).collect()
}

fn image(rgba: &Rgba) -> Image {
    Image::from_rgba8(SharedPixelBuffer::<Rgba8Pixel>::clone_from_slice(&rgba.pixels, rgba.width, rgba.height))
}

fn rgb(hex: u32) -> Color {
    Color::from_rgb_u8((hex >> 16) as u8, (hex >> 8) as u8, hex as u8)
}

fn with_alpha(color: Color, alpha: u8) -> Color {
    Color::from_argb_u8(alpha, color.red(), color.green(), color.blue())
}

fn apply_appearance(ui: &MainWindow, dark: bool, accent: &str) {
    // (accent, hover, text on accent) for dark and light surfaces.
    let (main, hover, on) = match (accent, dark) {
        ("blue", true) => (0x5aa2ff, 0x7fb7ff, 0x08223f),
        ("blue", false) => (0x2f6fd6, 0x3d80ea, 0xffffff),
        ("violet", true) => (0xa98bff, 0xbea8ff, 0x22124a),
        ("violet", false) => (0x7552d9, 0x8665e6, 0xffffff),
        (_, true) => (0x42c6ab, 0x64ddc1, 0x092a24),
        (_, false) => (0x138a72, 0x17a386, 0xffffff),
    };
    let theme = ui.global::<Theme>();
    theme.set_dark(dark);
    theme.set_accent(rgb(main));
    theme.set_accent_hover(rgb(hover));
    theme.set_on_accent(rgb(on));
    theme.set_selected(with_alpha(rgb(main), if dark { 0x38 } else { 0x2a }));
    theme.set_badge_bg(with_alpha(rgb(main), if dark { 0x30 } else { 0x24 }));
    theme.set_badge_text(if dark { rgb(hover) } else { rgb(main) });
    ui.invoke_apply_scheme();
}

fn show_message(ui: &MainWindow, title: String, text: String, error: bool) {
    ui.set_message_title(title.into());
    ui.set_message_text(text.into());
    ui.set_message_error(error);
    ui.set_message_open(true);
}

fn set_artwork(ui: &MainWindow, url: &str, picture: Option<&Rgba>) {
    let cards = ui.get_cards();
    for index in 0..cards.row_count() {
        let Some(mut row) = cards.row_data(index) else { continue };
        if row.icon_url == url {
            row.loaded = picture.is_some();
            row.artwork = picture.map(image).unwrap_or_default();
            cards.set_row_data(index, row);
        }
    }
    let mut detail = ui.get_detail();
    if detail.visible && detail.icon_url == url {
        detail.loaded = picture.is_some();
        detail.artwork = picture.map(image).unwrap_or_default();
        ui.set_detail(detail);
    }
}

/// Renders one presenter event. Runs on the Slint event loop thread.
fn apply(ui: &MainWindow, event: UiEvent) {
    match event {
        UiEvent::Busy(busy) => ui.set_busy(busy),
        UiEvent::Status(text) => ui.set_status(text.into()),
        UiEvent::Message { title, text, error } => show_message(ui, title, text, error),
        UiEvent::Confirm(text) => {
            ui.set_confirm_text(text.into());
            ui.set_dialog("confirm".into());
        }
        UiEvent::Appearance { dark, accent } => apply_appearance(ui, dark, &accent),
        UiEvent::Language(_) => {
            let tr = ui.global::<Tr>();
            tr.set_revision(tr.get_revision() + 1);
        }
        UiEvent::Instances { items, selected } => {
            set_rows(
                ui.get_instances(),
                items.into_iter().map(|i| InstanceRow { name: i.name.into(), detail: i.detail.into() }).collect(),
            );
            ui.set_selected_instance(selected);
        }
        UiEvent::Library(None) => ui.set_library_visible(false),
        UiEvent::Library(Some(vm)) => {
            ui.set_library_visible(true);
            ui.set_library_title(vm.title.into());
            ui.set_library_environment(vm.environment.into());
            ui.set_library_summary(vm.summary.into());
            ui.set_library_empty(vm.empty);
            ui.set_selected_count(vm.selected_count as i32);
            ui.set_selected_text(trf("library.selected", &[&vm.selected_count]).into());
            let rows = vm
                .plugins
                .into_iter()
                .map(|p| PluginRow {
                    file: p.file_name.into(),
                    version: p.version.into(),
                    source: p.source.into(),
                    state: p.state.into(),
                    update: p.update.into(),
                    enabled: p.enabled,
                    linked: p.linked,
                    has_update: p.has_update,
                    checked: p.checked,
                })
                .collect();
            set_rows(ui.get_plugins(), rows);
        }
        UiEvent::MarketSources { names, selected } => {
            set_rows(ui.get_sources(), strings(names));
            ui.set_source_index(selected);
        }
        UiEvent::MarketReset => {
            set_rows(ui.get_cards(), Vec::new());
            ui.set_selected_card(-1);
        }
        UiEvent::MarketAppend(cards) => {
            let model = ui.get_cards();
            let model = vec_model(&model);
            for card in cards {
                model.push(CardRow {
                    key: card.key.into(),
                    title: card.title.into(),
                    author: card.author.into(),
                    description: card.description.into(),
                    provider: card.provider.into(),
                    initial: card.initial.into(),
                    icon_url: card.icon_url.unwrap_or_default().into(),
                    loaded: card.artwork.is_some(),
                    artwork: card.artwork.as_ref().map(image).unwrap_or_default(),
                });
            }
        }
        UiEvent::MarketStatus(status) => {
            ui.set_market_status(status.text.into());
            ui.set_market_loading(status.loading);
            ui.set_market_failed(status.failed);
            ui.set_can_load_more(status.can_load_more);
        }
        UiEvent::Artwork { url, image } => set_artwork(ui, &url, image.as_ref()),
        UiEvent::Detail(None) => {
            ui.set_detail(DetailData::default());
            set_rows(ui.get_releases(), Vec::new());
            ui.set_releases_state(0);
            ui.set_selected_release(-1);
        }
        UiEvent::Detail(Some(d)) => {
            if let Some(index) = (0..ui.get_cards().row_count())
                .find(|i| ui.get_cards().row_data(*i).is_some_and(|c| c.key == d.key.as_str()))
            {
                ui.set_selected_card(index as i32);
            }
            ui.set_detail(DetailData {
                visible: true,
                title: d.title.into(),
                author: d.author.into(),
                provider: d.provider.into(),
                description: d.description.into(),
                initial: d.initial.into(),
                icon_url: d.icon_url.unwrap_or_default().into(),
                loaded: d.artwork.is_some(),
                artwork: d.artwork.as_ref().map(image).unwrap_or_default(),
                external: d.external,
            });
        }
        UiEvent::Releases(state) => match state {
            ReleasesVm::Loading => {
                set_rows(ui.get_releases(), Vec::new());
                ui.set_selected_release(-1);
                ui.set_releases_state(1);
            }
            ReleasesVm::Ready(releases) => {
                let first = if releases.is_empty() { -1 } else { 0 };
                let rows = releases
                    .into_iter()
                    .map(|r| ReleaseRow {
                        label: r.label.into(),
                        detail: r.detail.into(),
                        notice: r.notice.into(),
                        verified: r.verified,
                        installable: r.installable,
                    })
                    .collect();
                set_rows(ui.get_releases(), rows);
                ui.set_selected_release(first);
                ui.set_releases_state(2);
            }
            ReleasesVm::Failed(error) => {
                set_rows(ui.get_releases(), Vec::new());
                ui.set_releases_error(error.into());
                ui.set_releases_state(3);
            }
        },
        UiEvent::Plan(plan) => {
            ui.set_plan_summary(plan.summary.into());
            ui.set_plan_manual(plan.manual.into());
            ui.set_plan_needs_confirm(plan.needs_confirmation);
            ui.set_plan_accepted(false);
            ui.set_dialog("plan".into());
        }
        UiEvent::InstanceDialog(vm) => {
            ui.set_inst_editing(vm.editing);
            ui.set_inst_name(vm.name.into());
            ui.set_inst_directory(vm.directory.into());
            ui.set_inst_prerelease(vm.prerelease);
            set_rows(ui.get_inst_loaders(), Vec::new());
            set_rows(ui.get_inst_versions(), Vec::new());
            set_rows(ui.get_inst_extras(), Vec::new());
            ui.set_inst_loader_index(-1);
            ui.set_inst_version_index(-1);
            ui.set_dialog("instance".into());
        }
        UiEvent::Catalog(catalog) => {
            ui.set_inst_status(catalog.status.into());
            ui.set_inst_loading(catalog.loading);
            ui.set_inst_failed(catalog.failed);
            if !catalog.loading {
                set_rows(ui.get_inst_loaders(), strings(catalog.loaders));
                set_rows(ui.get_inst_versions(), strings(catalog.versions));
                ui.set_inst_loader_index(catalog.loader_index);
                ui.set_inst_version_index(catalog.version_index);
                set_extras(ui, catalog.extras);
            }
        }
        UiEvent::Extras(extras) => set_extras(ui, extras),
        UiEvent::CloseInstanceDialog => {
            if ui.get_dialog() == "instance" {
                ui.set_dialog("".into());
            }
        }
        UiEvent::Settings(vm) => {
            ui.set_set_theme_index(if vm.dark { 0 } else { 1 });
            ui.set_set_accent_index(ACCENTS.iter().position(|a| *a == vm.accent).unwrap_or(0) as i32);
            ui.set_set_language_index(LANGUAGES.iter().position(|l| *l == vm.language).unwrap_or(0) as i32);
            ui.set_set_images(vm.images_enabled);
            ui.set_set_cache_mb(vm.cache_mb as i32);
            ui.set_dialog("settings".into());
        }
        UiEvent::CacheUsage(text) => ui.set_set_usage(text.into()),
        UiEvent::Storage(text) => ui.set_set_storage(text.into()),
    }
}

fn set_extras(ui: &MainWindow, extras: Vec<spa_app::Choice>) {
    let rows = extras.into_iter().map(|c| ChoiceRow { name: c.name.into(), checked: c.checked }).collect();
    set_rows(ui.get_inst_extras(), rows);
}

fn file_stem(ui: &MainWindow) -> String {
    let title: String = ui.get_library_title().into();
    let safe: String =
        title.chars().map(|c| if c.is_alphanumeric() || c == '-' || c == '_' { c } else { '-' }).collect();
    if safe.trim_matches('-').is_empty() {
        "server".into()
    } else {
        safe
    }
}

/// Connects Slint callbacks to controller actions.
fn wire(ui: &MainWindow, controller: &Controller) {
    macro_rules! on {
        ($c:ident, $ui:ident, $setter:ident, || $body:expr) => {
            on!($c, $ui, $setter, | | $body)
        };
        ($c:ident, $ui:ident, $setter:ident, |$($arg:ident),*| $body:expr) => {{
            let controller = controller.clone();
            let weak = ui.as_weak();
            ui.$setter(move |$($arg),*| {
                #[allow(unused_variables)]
                let $ui = weak.unwrap();
                #[allow(unused_variables)]
                let $c = &controller;
                $body
            });
        }};
    }

    on!(c, ui, on_select_instance, |index| c.select_instance(index));
    on!(c, ui, on_new_instance, || c.open_new_instance());
    on!(c, ui, on_edit_instance, || c.open_edit_instance());
    on!(c, ui, on_unregister, || c.unregister_instance());
    on!(c, ui, on_open_folder, || {
        if let Some(dir) = c.server_directory() {
            if let Err(e) = open::that_detached(&dir) {
                show_message(&ui, tr("app.failed"), trf("app.error_detail", &[&e]), true);
            }
        }
    });
    on!(c, ui, on_open_settings, || c.open_settings());
    on!(c, ui, on_open_providers, || show_message(&ui, tr("providers.title"), c.providers_text(), false));

    on!(c, ui, on_lib_filter, |text| c.filter_library(&text));
    on!(c, ui, on_lib_check, |file, checked| c.check_plugin(&file, checked));
    on!(c, ui, on_lib_check_all, |checked| c.check_all(checked));
    on!(c, ui, on_lib_refresh, || c.refresh_library());
    on!(c, ui, on_lib_link, || c.link_plugins());
    on!(c, ui, on_lib_check_updates, || c.check_updates());
    on!(c, ui, on_lib_update, |all| c.update_plugins(all));
    on!(c, ui, on_lib_toggle, || c.toggle_plugins());
    on!(c, ui, on_lib_remove, || c.remove_plugins());
    on!(c, ui, on_lib_import, || {
        let files = rfd::FileDialog::new()
            .set_title(tr("library.import"))
            .add_filter(tr("file.jar"), &["jar", "disabled"])
            .pick_files();
        if let Some(files) = files {
            c.import_jars(files);
        }
    });
    on!(c, ui, on_lib_export, |script| {
        let (key, filter, extension, name) = if script {
            ("library.export_script", "file.script", "sh", "deploy.sh".to_string())
        } else {
            ("library.export_zip", "file.zip", "zip", format!("{}-plugins.zip", file_stem(&ui)))
        };
        let file = rfd::FileDialog::new()
            .set_title(tr(key))
            .add_filter(tr(filter), &[extension])
            .set_file_name(name)
            .save_file();
        if let Some(file) = file {
            c.export(file, script);
        }
    });

    on!(c, ui, on_market_activated, |active| c.market_activated(active));
    on!(c, ui, on_set_source, |index| c.set_source(index));
    on!(c, ui, on_search, |query| c.search(&query));
    on!(c, ui, on_load_more, || c.load_more());
    on!(c, ui, on_retry_market, || c.retry_market());
    on!(c, ui, on_select_card, |index| c.select_project(index));
    on!(c, ui, on_retry_releases, || c.retry_releases());
    on!(c, ui, on_open_page, || {
        if let Some(page) = c.project_page() {
            if let Err(e) = open::that_detached(page) {
                show_message(&ui, tr("app.failed"), trf("app.error_detail", &[&e]), true);
            }
        }
    });
    on!(c, ui, on_review, |index, optional| c.review_install(index, optional));
    on!(c, ui, on_url_submit, |text| {
        if !text.trim().is_empty() {
            c.add_by_url(&text);
        }
    });
    on!(c, ui, on_plan_install, || c.execute_plan());
    on!(c, ui, on_plan_cancel, || c.cancel_plan());
    on!(c, ui, on_confirm_answer, |accepted| c.confirm(accepted));

    on!(c, ui, on_instance_browse, || {
        if let Some(folder) = rfd::FileDialog::new().set_title(tr("instance.directory")).pick_folder() {
            ui.set_inst_directory(folder.to_string_lossy().into_owned().into());
        }
    });
    on!(c, ui, on_instance_refresh, || c.refresh_catalog());
    on!(c, ui, on_instance_cancel, || c.cancel_instance_dialog());
    on!(c, ui, on_instance_loader_changed, |loader| c.loader_changed(&loader));
    on!(c, ui, on_extra_toggled, |index, checked| {
        let model = ui.get_inst_extras();
        if let Some(mut row) = model.row_data(index as usize) {
            row.checked = checked;
            model.set_row_data(index as usize, row);
        }
    });
    on!(c, ui, on_instance_save, || {
        let pick = |model: ModelRc<SharedString>, index: i32| {
            usize::try_from(index).ok().and_then(|i| model.row_data(i)).map(String::from).unwrap_or_default()
        };
        let extras = ui.get_inst_extras();
        c.save_instance(InstanceForm {
            name: ui.get_inst_name().into(),
            directory: ui.get_inst_directory().into(),
            loader: pick(ui.get_inst_loaders(), ui.get_inst_loader_index()),
            version: pick(ui.get_inst_versions(), ui.get_inst_version_index()),
            extras: extras.iter().filter(|e| e.checked).map(|e| e.name.into()).collect(),
            prerelease: ui.get_inst_prerelease(),
        });
    });

    on!(c, ui, on_settings_save, || {
        let index = |i: i32, len: usize| usize::try_from(i).unwrap_or(0).min(len - 1);
        c.save_settings(SettingsVm {
            dark: ui.get_set_theme_index() == 0,
            accent: ACCENTS[index(ui.get_set_accent_index(), ACCENTS.len())].into(),
            language: LANGUAGES[index(ui.get_set_language_index(), LANGUAGES.len())].into(),
            images_enabled: ui.get_set_images(),
            cache_mb: ui.get_set_cache_mb().clamp(16, 512) as u32,
        });
    });
    on!(c, ui, on_settings_clear_cache, || c.clear_image_cache());
    on!(c, ui, on_settings_open_data, || {
        if let Err(e) = open::that_detached(c.data_dir()) {
            show_message(&ui, tr("app.failed"), trf("app.error_detail", &[&e]), true);
        }
    });
}

fn init_models(ui: &MainWindow) {
    ui.set_instances(ModelRc::from(Rc::new(VecModel::<InstanceRow>::default())));
    ui.set_plugins(ModelRc::from(Rc::new(VecModel::<PluginRow>::default())));
    ui.set_sources(ModelRc::from(Rc::new(VecModel::<SharedString>::default())));
    ui.set_cards(ModelRc::from(Rc::new(VecModel::<CardRow>::default())));
    ui.set_releases(ModelRc::from(Rc::new(VecModel::<ReleaseRow>::default())));
    ui.set_inst_loaders(ModelRc::from(Rc::new(VecModel::<SharedString>::default())));
    ui.set_inst_versions(ModelRc::from(Rc::new(VecModel::<SharedString>::default())));
    ui.set_inst_extras(ModelRc::from(Rc::new(VecModel::<ChoiceRow>::default())));
}

fn startup_failure(error: impl std::fmt::Display) -> ! {
    log::error!("Startup failed: {error}");
    eprintln!("{}: {error}", tr("app.startup_failed"));
    rfd::MessageDialog::new()
        .set_level(rfd::MessageLevel::Error)
        .set_title(tr("app.startup_failed"))
        .set_description(trf("app.error_detail", &[&error]))
        .show();
    std::process::exit(1);
}

/// Non-interactive check used by packaging: opens the workspace and builds the window.
fn smoke(controller: &Controller, data_dir: &Path) -> Result<(), Box<dyn std::error::Error>> {
    let providers = controller.provider_ids();
    let result = serde_json::json!({ "success": true, "providers": providers });
    std::fs::write(data_dir.join("smoke-result.json"), serde_json::to_vec_pretty(&result)?)?;
    println!("{result}");
    Ok(())
}

fn main() {
    let args = parse_args();
    init_logging(&args.data_dir);
    log::info!("Starting with data directory {}", args.data_dir.display());
    let ctx = AppContext::open(&args.data_dir, builtin_providers()).unwrap_or_else(|e| startup_failure(e));
    let settings = AppSettings::load(&ctx.db).unwrap_or_else(|e| {
        log::warn!("Using default settings: {e}");
        AppSettings::default()
    });
    spa_app::i18n::set_language(&settings.language);

    let probe = args.snapshot.as_deref().map(|dir| snapshot::install(dir, 1360, 860));
    let ui = MainWindow::new().unwrap_or_else(|e| startup_failure(e));
    ui.global::<Tr>().on_get(|_, key| tr(&key).into());
    init_models(&ui);
    let weak = ui.as_weak();
    let controller = Controller::new(
        ctx,
        settings.clone(),
        Arc::new(move |event| {
            let weak = weak.clone();
            let _ = slint::invoke_from_event_loop(move || {
                if let Some(ui) = weak.upgrade() {
                    apply(&ui, event);
                }
            });
        }),
    );
    if args.smoke {
        if let Err(e) = smoke(&controller, &args.data_dir) {
            startup_failure(e);
        }
        return;
    }
    wire(&ui, &controller);
    {
        let c = controller.clone();
        let weak = ui.as_weak();
        ui.window().on_close_requested(move || {
            if c.is_busy() {
                if let Some(ui) = weak.upgrade() {
                    show_message(&ui, tr("app.title"), tr("app.close_busy"), false);
                }
                CloseRequestResponse::KeepWindowShown
            } else {
                CloseRequestResponse::HideWindow
            }
        });
    }
    controller.start();
    if let Some(probe) = probe {
        snapshot::run(&probe, &ui);
        return;
    }
    ui.window().set_size(slint::LogicalSize::new(settings.width as f32, settings.height as f32));
    if let Err(e) = ui.run() {
        startup_failure(e);
    }
    let size = ui.window().size().to_logical(ui.window().scale_factor());
    controller.shutdown(f64::from(size.width), f64::from(size.height));
    log::info!("Closed");
}

#[cfg(test)]
mod tests {
    #[test]
    fn ui_and_binding_keys_exist_in_catalogs() {
        for source in [include_str!("../ui/app.slint"), include_str!("../ui/widgets.slint"), include_str!("main.rs")] {
            assert_eq!(spa_app::i18n::missing_keys(source), Vec::<String>::new());
        }
    }
}
