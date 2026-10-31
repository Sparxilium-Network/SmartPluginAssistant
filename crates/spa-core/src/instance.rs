//! UUID-identified instances in one central index. Unregistering never deletes server files.

use crate::{fail, json, now_millis, paths, Result};
use serde::{Deserialize, Serialize};
use spa_api::TargetEnvironment;
use std::path::{Path, PathBuf};
use std::sync::Mutex;
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

    fn validate(&self) -> Result<()> {
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

#[derive(Serialize, Deserialize)]
struct Index {
    schema: i32,
    instances: Vec<ServerInstance>,
}

pub struct InstanceStore {
    root: PathBuf,
    index: PathBuf,
    instances: Mutex<Vec<ServerInstance>>,
}

impl InstanceStore {
    pub fn open(root: &Path) -> Result<Self> {
        let root = paths::directory(root)?;
        let index = paths::child(&root, "instances.json")?;
        let instances = if index.exists() {
            let data: Index = json::read(&index)?;
            if data.schema != 1 {
                return fail("Unsupported instance index");
            }
            for instance in &data.instances {
                instance.validate()?;
            }
            let mut ids: Vec<_> = data.instances.iter().map(|i| i.id).collect();
            ids.sort();
            ids.dedup();
            if ids.len() != data.instances.len() {
                return fail("Duplicate instance ID");
            }
            data.instances
        } else {
            Vec::new()
        };
        Ok(Self { root, index, instances: Mutex::new(instances) })
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
        let mut updated = instances.clone();
        updated.push(instance.clone());
        self.persist(&mut instances, updated)?;
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
        let mut updated = instances.clone();
        updated[position] = instance.clone();
        self.persist(&mut instances, updated)?;
        log::info!("Updated instance {}", instance.id);
        Ok(())
    }

    pub fn unregister(&self, id: Uuid) -> Result<()> {
        let mut instances = self.lock();
        let updated: Vec<_> = instances.iter().filter(|i| i.id != id).cloned().collect();
        self.persist(&mut instances, updated)?;
        log::info!("Unregistered instance {id}; server files retained");
        Ok(())
    }

    fn persist(&self, current: &mut Vec<ServerInstance>, updated: Vec<ServerInstance>) -> Result<()> {
        json::write(&self.index, &Index { schema: 1, instances: updated.clone() })?;
        *current = updated;
        Ok(())
    }

    pub fn root(&self) -> &Path {
        &self.root
    }
}
