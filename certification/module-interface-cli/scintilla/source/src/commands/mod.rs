//! One module per subcommand, plus the dispatch table.
//!
//! Which command ran is decided by flags-2-env from the `[commands.*]` tables
//! in `.cli-flags.toml`, never by hand-matching argv here. [`Command`] is the
//! closed set that contract may resolve to, and `command_set_matches_config`
//! fails the build if the two lists disagree.

pub mod benchmark;
pub mod build;
pub mod completion;
pub mod deploy;
pub mod dev;
pub mod docs;
pub mod functions;
pub mod invoke;
pub mod module_interfaces;
pub mod publish;
pub mod revisions;
pub mod validate;

use std::path::Path;

use scintilla_client::Client;

use crate::error::CliError;
use crate::flags::CliArgs;
use crate::secrets;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Command {
    Benchmark,
    Build,
    Deploy,
    Dev,
    Docs,
    Functions,
    Invoke,
    ModuleInterfaces,
    Revisions,
    Publish,
    Validate,
    Completion,
}

impl Command {
    pub const fn as_str(self) -> &'static str {
        return match self {
            Self::Benchmark => "benchmark",
            Self::Build => "build",
            Self::Deploy => "deploy",
            Self::Dev => "dev",
            Self::Docs => "docs",
            Self::Functions => "functions",
            Self::Invoke => "invoke",
            Self::ModuleInterfaces => "module-interfaces",
            Self::Revisions => "revisions",
            Self::Publish => "publish",
            Self::Validate => "validate",
            Self::Completion => "completion",
        };
    }

    pub fn parse(label: &str) -> Result<Self, CliError> {
        return match label {
            "benchmark" => Ok(Self::Benchmark),
            "build" => Ok(Self::Build),
            "deploy" => Ok(Self::Deploy),
            "dev" => Ok(Self::Dev),
            "docs" => Ok(Self::Docs),
            "functions" | "ls" => Ok(Self::Functions),
            "invoke" => Ok(Self::Invoke),
            "module-interfaces" => Ok(Self::ModuleInterfaces),
            "revisions" => Ok(Self::Revisions),
            "publish" => Ok(Self::Publish),
            "validate" => Ok(Self::Validate),
            "completion" => Ok(Self::Completion),
            other => Err(CliError::usage(format!("unsupported command {other:?}"))),
        };
    }

    pub const ALL: [Self; 12] = [
        Self::Benchmark,
        Self::Build,
        Self::Deploy,
        Self::Dev,
        Self::Docs,
        Self::Functions,
        Self::Invoke,
        Self::ModuleInterfaces,
        Self::Revisions,
        Self::Publish,
        Self::Validate,
        Self::Completion,
    ];

    pub const fn needs_network(self) -> bool {
        return matches!(
            self,
            Self::Deploy
                | Self::Dev
                | Self::Functions
                | Self::Invoke
                | Self::Revisions
                | Self::Publish
        );
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum InvokeTarget {
    Current,
    Revision(u64),
    Alias(String),
}

pub fn dispatch(args: &CliArgs, config_path: &Path) -> Result<i32, CliError> {
    if !args.command.needs_network() {
        return match args.command {
            Command::Benchmark => benchmark::run(args),
            Command::Build => build::run(args),
            Command::Docs => docs::run(args),
            Command::ModuleInterfaces => module_interfaces::run(args),
            Command::Validate => validate::run(args),
            Command::Completion => completion::run(args, config_path),
            _ => unreachable!("needs_network covers every other command"),
        };
    }

    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .map_err(|error| {
            CliError::runtime(format!("could not start the async runtime: {error}"))
        })?;

    return runtime.block_on(async {
        let client = client(args)?;
        return match args.command {
            Command::Deploy => deploy::run(&client, args).await,
            Command::Dev => dev::run(&client, args).await,
            Command::Functions => functions::run(&client, args).await,
            Command::Invoke => invoke::run(&client, args).await,
            Command::Revisions => revisions::run(&client, args).await,
            Command::Publish => publish::run(&client, args).await,
            _ => unreachable!("offline commands returned above"),
        };
    });
}

fn client(args: &CliArgs) -> Result<Client, CliError> {
    return Client::with_timeout(&args.base_url, secrets::token(), args.timeout)
        .map_err(|error| CliError::usage(format!("cannot build the client: {error}")));
}

pub fn request_failed(what: &str, error: &scintilla_client::Error) -> CliError {
    return CliError::runtime(format!("{what} failed: {error}"));
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn command_set_matches_config() {
        let config = include_str!("../../.cli-flags.toml");
        for command in Command::ALL {
            let table = format!("[commands.{}]", command.as_str());
            assert!(config.contains(&table), "{table} is missing from .cli-flags.toml");
        }
        let declared = config
            .lines()
            .filter_map(|line| line.trim().strip_prefix("[commands."))
            .filter_map(|line| line.strip_suffix(']'))
            .filter(|name| !name.contains('.'))
            .count();
        assert_eq!(declared, Command::ALL.len());
    }

    #[test]
    fn ls_is_an_alias_for_functions() {
        assert_eq!(Command::parse("ls").unwrap(), Command::Functions);
    }

    #[test]
    fn local_commands_are_offline() {
        assert!(!Command::Benchmark.needs_network());
        assert!(!Command::Build.needs_network());
        assert!(!Command::Docs.needs_network());
        assert!(!Command::ModuleInterfaces.needs_network());
        assert!(!Command::Validate.needs_network());
        assert!(!Command::Completion.needs_network());
        assert!(Command::Deploy.needs_network());
        assert!(Command::Dev.needs_network());
        assert!(Command::Invoke.needs_network());
    }

    #[test]
    fn unknown_commands_are_usage_errors() {
        assert_eq!(Command::parse("not-a-command").unwrap_err().exit_code(), 2);
    }
}
