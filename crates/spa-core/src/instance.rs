//! UUID-identified instances in the central database index. Unregistering never deletes server files.

use crate::db::Database;
use crate::{fail, now_millis, paths, Result};
use rusqlite::{params, Connection};
use serde::{Deserialize, Serialize};
use spa_api::TargetEnvironment;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};
use uuid::Uuid;

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ServerInstance {
    pub id: Uuid,
    pub name: String,
    /// Absolute normalized server directory.
    pub directory: String,
    pub target: TargetEnvironment,
    pub created_at: i64,
}

pub fn validate_name(name: &str) -> Result<String> {
    let name = name.trim();
    if name.is_empty()
        || name.chars().count() > 100
        || name.chars().any(char::is_control)
        || name == "."
        || name == ".."
    {
        return fail("Invalid instance display name");
    }
    Ok(name.to_string())
}

impl ServerInstance {
    pub fn new(id: Uuid, name: &str, directory: &Path, target: TargetEnvironment, created_at: i64) -> Result<Self> {
        target.validate()?;
        Ok(Self {
            id,
            name: validate_name(name)?,
            directory: paths::normalize(directory)?.to_string_lossy().into_owned(),
            target,
            created_at,
        })
    }

    pub(crate) fn validate(&self) -> Result<()> {
        validate_name(&self.name)?;
        self.target.validate()?;
        if !Path::new(&self.directory).is_absolute() {
            return fail("Instance directory must be absolute");
        }
        Ok(())
    }

    pub fn server_directory(&self) -> PathBuf {
        PathBuf::from(&self.directory)
    }

    pub fn plugins_directory(&self) -> PathBuf {
        self.server_directory().join("plugins")
    }
}

/// Inserts one validated instance row (also used by the legacy JSON import).
pub(crate) fn insert(connection: &Connection, instance: &ServerInstance) -> Result<()> {
    instance.validate()?;
    connection.execute(
        "INSERT INTO instances (id, name, directory, loader, minecraft_version, extra_loaders, allow_prereleases, created_at)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8)",
        params![
            instance.id.to_string(),
            instance.name,
            instance.directory,
            instance.target.loader,
            instance.target.minecraft_version,
            serde_json::to_string(&instance.target.extra_loaders)?,
            instance.target.allow_prereleases,
            instance.created_at,
        ],
    )?;
    Ok(())
}

type Row = (String, String, String, String, String, String, bool, i64);

fn load_all(connection: &Connection) -> Result<Vec<ServerInstance>> {
    let mut statement = connection.prepare(
        "SELECT id, name, directory, loader, minecraft_version, extra_loaders, allow_prereleases, created_at
         FROM instances ORDER BY created_at, rowid",
    )?;
    let rows = statement.query_map([], |r| {
        Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?, r.get(4)?, r.get(5)?, r.get(6)?, r.get(7)?))
    })?;
    let mut result = Vec::new();
    for row in rows {
        let (id, name, directory, loader, minecraft_version, extra, allow_prereleases, created_at): Row = row?;
        let Ok(id) = Uuid::parse_str(&id) else {
            return fail("Invalid instance ID in database");
        };
        let target = TargetEnvironment {
            loader,
            minecraft_version,
            extra_loaders: serde_json::from_str(&extra)?,
            allow_prereleases,
        };
        let instance = ServerInstance { id, name, directory, target, created_at };
        instance.validate()?;
        result.push(instance);
    }
    Ok(result)
}

/// The instance index; rows live in the database and a validated copy is kept in memory.
pub struct InstanceStore {
    root: PathBuf,
    db: Arc<Database>,
    instances: Mutex<Vec<ServerInstance>>,
}

impl InstanceStore {
    /// `root` hosts managed server directories (`servers/<UUID>`).
    pub fn open(db: Arc<Database>, root: &Path) -> Result<Self> {
        let instances = db.read(load_all)?;
        Ok(Self { root: paths::directory(root)?, db, instances: Mutex::new(instances) })
    }

    fn lock(&self) -> std::sync::MutexGuard<'_, Vec<ServerInstance>> {
        self.instances.lock().unwrap_or_else(|e| e.into_inner())
    }

    pub fn all(&self) -> Vec<ServerInstance> {
        self.lock().clone()
    }

    pub fn get(&self, id: Uuid) -> Option<ServerInstance> {
        self.lock().iter().find(|i| i.id == id).cloned()
    }

    /// `custom_directory = None` creates a managed directory under `servers/<UUID>`.
    pub fn create(
        &self,
        name: &str,
        custom_directory: Option<&Path>,
        target: TargetEnvironment,
    ) -> Result<ServerInstance> {
        let mut instances = self.lock();
        let id = Uuid::new_v4();
        let server = match custom_directory {
            Some(dir) => dir.to_path_buf(),
            None => self.root.join("servers").join(id.to_string()),
        };
        let instance = ServerInstance::new(id, name, &server, target, now_millis())?;
        if instances.iter().any(|i| i.directory.eq_ignore_ascii_case(&instance.directory)) {
            return fail("Directory already registered");
        }
        paths::directory(&instance.plugins_directory())?;
        self.db.write(|t| insert(t, &instance))?;
        instances.push(instance.clone());
        log::info!("Registered instance {id} at {}", instance.directory);
        Ok(instance)
    }

    pub fn update(&self, instance: ServerInstance) -> Result<()> {
        instance.validate()?;
        let mut instances = self.lock();
        let Some(position) = instances.iter().position(|i| i.id == instance.id) else {
            return fail("Instance not found");
        };
        if instances[position].directory != instance.directory {
            return fail("Moving server directories is not supported");
        }
        self.db.write(|t| {
            t.execute(
                "UPDATE instances SET name = ?2, loader = ?3, minecraft_version = ?4, extra_loaders = ?5,
                 allow_prereleases = ?6 WHERE id = ?1",
                params![
                    instance.id.to_string(),
                    instance.name,
                    instance.target.loader,
                    instance.target.minecraft_version,
                    serde_json::to_string(&instance.target.extra_loaders)?,
                    instance.target.allow_prereleases,
                ],
            )?;
            Ok(())
        })?;
        log::info!("Updated instance {}", instance.id);
        instances[position] = instance;
        Ok(())
    }

    /// Removes the index row and its inventory records; server files are never touched.
    pub fn unregister(&self, id: Uuid) -> Result<()> {
        let mut instances = self.lock();
        self.db.write(|t| {
            t.execute("DELETE FROM instances WHERE id = ?1", [id.to_string()])?;
            Ok(())
        })?;
        instances.retain(|i| i.id != id);
        log::info!("Unregistered instance {id}; server files retained");
        Ok(())
    }

    pub fn root(&self) -> &Path {
        &self.root
    }
}
