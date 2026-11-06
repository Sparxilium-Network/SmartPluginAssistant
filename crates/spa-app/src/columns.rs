//! Installed-plugin table columns: which are shown, their order and widths. Stored values are
//! normalized rather than rejected so a layout from another version never blocks startup.

use serde::{Deserialize, Serialize};

/// Known column ids with default widths (logical pixels), in default order.
pub const COLUMNS: [(&str, f32); 7] = [
    ("file", 220.0),
    ("version", 110.0),
    ("source", 90.0),
    ("state", 70.0),
    ("update", 140.0),
    ("updated", 130.0),
    ("checked", 130.0),
];
pub const MIN_WIDTH: f32 = 48.0;
pub const MAX_WIDTH: f32 = 800.0;

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct ColumnSetting {
    pub id: String,
    pub visible: bool,
    pub width: f32,
}

pub fn defaults() -> Vec<ColumnSetting> {
    COLUMNS.iter().map(|(id, width)| ColumnSetting { id: (*id).into(), visible: true, width: *width }).collect()
}

fn default_width(id: &str) -> f32 {
    COLUMNS.iter().find(|(known, _)| *known == id).map_or(120.0, |(_, w)| *w)
}

pub fn clamp_width(id: &str, width: f32) -> f32 {
    if width.is_finite() {
        width.clamp(MIN_WIDTH, MAX_WIDTH)
    } else {
        default_width(id)
    }
}

/// Drops unknown and duplicate ids, appends missing columns, clamps widths and keeps at least
/// one column visible.
pub fn normalize(columns: Vec<ColumnSetting>) -> Vec<ColumnSetting> {
    let mut result: Vec<ColumnSetting> = Vec::new();
    for column in columns {
        if COLUMNS.iter().any(|(id, _)| *id == column.id) && !result.iter().any(|c| c.id == column.id) {
            let width = clamp_width(&column.id, column.width);
            result.push(ColumnSetting { width, ..column });
        }
    }
    for column in defaults() {
        if !result.iter().any(|c| c.id == column.id) {
            result.push(column);
        }
    }
    if !result.iter().any(|c| c.visible) {
        result[0].visible = true;
    }
    result
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn normalizes_unknown_missing_and_invalid_columns() {
        let stored = vec![
            ColumnSetting { id: "checked".into(), visible: false, width: 5.0 },
            ColumnSetting { id: "bogus".into(), visible: true, width: 100.0 },
            ColumnSetting { id: "checked".into(), visible: true, width: 300.0 },
            ColumnSetting { id: "file".into(), visible: false, width: f32::NAN },
        ];
        let columns = normalize(stored);
        assert_eq!(columns.len(), COLUMNS.len());
        assert_eq!((columns[0].id.as_str(), columns[0].width, columns[0].visible), ("checked", MIN_WIDTH, false));
        assert_eq!((columns[1].id.as_str(), columns[1].width), ("file", 220.0));
        assert!(columns[2..].iter().all(|c| c.visible), "missing columns are appended visible");
        let hidden = defaults().into_iter().map(|c| ColumnSetting { visible: false, ..c }).collect();
        assert!(normalize(hidden)[0].visible, "at least one column stays visible");
    }
}
