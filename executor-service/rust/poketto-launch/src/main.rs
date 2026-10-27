#![forbid(unsafe_code)]
//! Poketto's sandbox launcher. Each systemd unit runs it as the execution account with the
//! launch record and policy the supervisor wrote; it chooses the fixed command for the
//! record's mode and replaces itself with bubblewrap. `--verify` checks the tools and the
//! sandbox program directory without launching.
use sandcrab_launch::{
    Error, enter_bootstrap, exec, sandbox_arguments, verify_programs, verify_tools,
};
use serde_json::Value;
use std::{
    fs,
    path::{Path, PathBuf},
    process::ExitCode,
};

/// Programs bound read-only into every repository sandbox from the selected release.
const PROGRAMS: [&str; 3] = ["poketto", "poketto-shell", "poketto-syncfs"];
/// The baseline installer stays a Python program run inside the sandbox.
const INSTALL_BASELINE: &str = include_str!("install_baseline.py");
/// Parameters originate in verified server leases and are passed as positional arguments.
const INITIALIZE: &str = concat!(
    "set -eu; git -c core.hooksPath=/dev/null clone --no-local --quiet \"$1/snapshot.bundle\" \"$1/work/repository\"; ",
    "git -C \"$1/work/repository\" remote remove origin; ",
    "git -C \"$1/work/repository\" -c core.hooksPath=/dev/null checkout --quiet --detach \"$2\"; ",
    "test \"$(git -C \"$1/work/repository\" rev-parse HEAD)\" = \"$2\"; ",
    "test ! -e \"$1/work/repository/.git/objects/info/alternates\"; ",
    "test -z \"$(git -C \"$1/work/repository\" remote)\""
);

/// The release's sandbox programs live beside the resolved launcher.
fn program_directory() -> Result<PathBuf, Error> {
    let launcher = std::env::current_exe()?;
    Ok(launcher
        .parent()
        .ok_or_else(|| Error("Launcher has no directory".into()))?
        .join("bin"))
}
fn text<'a>(record: &'a Value, key: &str) -> Result<&'a str, Error> {
    record[key]
        .as_str()
        .ok_or_else(|| Error(format!("Launch record lacks {key}")))
}
fn read_json(path: &Path) -> Result<Value, Error> {
    sandcrab_core::parse(&fs::read(path)?).map_err(|_| Error("Invalid launch record".into()))
}

/// Bubblewrap arguments and the complete environment of the replaced process.
type Launch = (Vec<String>, Vec<(String, String)>);
/// The launch for one record.
fn plan(record: &Value) -> Result<Launch, Error> {
    let root = PathBuf::from(text(record, "root")?);
    let tools = PathBuf::from(text(record, "tools")?);
    let (bwrap, seccomp) = verify_tools(&tools)?;
    let mode = text(record, "mode")?;
    let programs = if mode == "game" {
        None
    } else {
        let directory = program_directory()?;
        verify_programs(&directory, &PROGRAMS)?;
        Some(directory)
    };
    let policy = read_json(Path::new(text(record, "settings")?))?;
    enter_bootstrap(&root)?;
    let (root_text, tools_text) = (root.display().to_string(), tools.display().to_string());
    // The compile cache is read-only here: install-compile-cache.sh, run as root, is its only writer.
    let mut path =
        format!("{root_text}/bootstrap:{tools_text}:{tools_text}/extracted/usr/bin:/usr/bin:/bin");
    if let Some(programs) = &programs {
        path = format!("{}:{path}", programs.display());
    }
    let mut environment = vec![
        ("HOME".into(), format!("{root_text}/home")),
        ("TMPDIR".into(), "/tmp".into()),
        ("PATH".into(), path),
        ("POKETTO_BRIDGE".into(), format!("{root_text}/bridge")),
        (
            "NODE_COMPILE_CACHE".into(),
            format!("{tools_text}/compile-cache"),
        ),
        ("GIT_CONFIG_NOSYSTEM".into(), "1".into()),
        ("GIT_CONFIG_GLOBAL".into(), "/dev/null".into()),
        ("SANDBOX_RUNTIME".into(), "1".into()),
    ];
    let command: Vec<String> = match (mode, &programs) {
        ("initialize", _) => vec![
            "/bin/bash".into(),
            "-c".into(),
            INITIALIZE.into(),
            "initialize".into(),
            root_text.clone(),
            text(record, "commit")?.into(),
        ],
        ("shell", Some(programs)) => vec![
            programs.join("poketto-shell").display().to_string(),
            format!("{root_text}/work/repository"),
        ],
        ("baseline", _) => vec![
            "/usr/bin/python3".into(),
            "-c".into(),
            INSTALL_BASELINE.into(),
            root_text.clone(),
            text(record, "commit")?.into(),
        ],
        ("game", _) => {
            environment.retain(|(name, _)| name != "POKETTO_BRIDGE");
            vec![
                format!("{tools_text}/node"),
                "--max-old-space-size=64".into(),
                format!("{root_text}/bootstrap/game-entry.mjs"),
                format!("{root_text}/input.json"),
            ]
        }
        _ => return Err(Error("Invalid execution mode".into())),
    };
    let arguments = sandbox_arguments(&bwrap, &seccomp, &policy, &command)?;
    Ok((arguments, environment))
}

fn run(args: &[String]) -> Result<(), Error> {
    match args {
        [flag, tools] if flag == "--verify" => {
            verify_tools(Path::new(tools))?;
            verify_programs(&program_directory()?, &PROGRAMS)
        }
        [record] if !record.starts_with('-') => {
            if rustix::process::geteuid().is_root() {
                return Err(Error("Dedicated unprivileged account required".into()));
            }
            let (arguments, environment) = plan(&read_json(Path::new(record))?)?;
            Err(exec(&arguments, &environment))
        }
        _ => Err(Error(
            "Usage: poketto-launch RECORD | --verify TOOLS".into(),
        )),
    }
}

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().skip(1).collect();
    match run(&args) {
        Ok(()) => ExitCode::SUCCESS,
        Err(error) => {
            eprintln!("{error}");
            ExitCode::FAILURE
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    /// The policies each Python worker mode writes; test_launcher.py keeps them current.
    const POLICIES: &str = include_str!("../testdata/policies.json");
    #[test]
    fn every_worker_mode_reaches_the_filter_with_a_literal_command() {
        let policies: Value = serde_json::from_str(POLICIES).unwrap();
        let command: Vec<String> = ["/bin/bash", "-c", "printf \"$HOME\""]
            .map(String::from)
            .to_vec();
        for mode in ["initialize", "shell", "baseline", "game"] {
            let args = sandbox_arguments(
                Path::new("/tools/bwrap"),
                Path::new("/tools/apply-seccomp"),
                &policies[mode],
                &command,
            )
            .unwrap();
            assert_eq!(args[args.len() - 4], "/tools/apply-seccomp", "{mode}");
            assert_eq!(args[args.len() - 3..], command[..], "{mode}");
            for flag in [
                "--unshare-net",
                "--unshare-user",
                "--unshare-pid",
                "--die-with-parent",
                "--new-session",
            ] {
                assert!(args.contains(&flag.to_string()), "{mode} {flag}");
            }
            let at = |flag: &str| args.iter().position(|a| a == flag).unwrap();
            assert_eq!(args[at("--cap-drop") + 1], "ALL");
            assert_eq!(args[at("--remount-ro") + 1], "/");
            let triples: Vec<_> = args.windows(3).collect();
            for source in ["/", "/dev", "/proc"] {
                let bind = ["--ro-bind".to_string(), source.into(), source.into()];
                assert!(!triples.contains(&&bind[..]), "{mode} {source}");
            }
        }
    }
    #[test]
    fn the_initializer_and_installer_match_the_python_launcher_byte_for_byte() {
        assert!(INSTALL_BASELINE.starts_with("\nimport pathlib, subprocess, sys\n"));
        assert!(INSTALL_BASELINE.contains("raise SystemExit(10)"));
        assert!(INITIALIZE.starts_with("set -eu; git -c core.hooksPath=/dev/null clone"));
        assert!(INITIALIZE.ends_with("test -z \"$(git -C \"$1/work/repository\" remote)\""));
    }
    #[test]
    fn records_with_missing_fields_or_unknown_modes_never_launch() {
        for record in [
            serde_json::json!({}),
            serde_json::json!({"root": "/fixture"}),
            serde_json::json!({"root": "/fixture", "tools": "/nonexistent-tools", "mode": "shell"}),
        ] {
            assert!(plan(&record).is_err(), "{record}");
        }
        assert!(run(&["--verify".into(), "/nonexistent-tools".into()]).is_err());
        assert!(run(&["--bogus".into()]).is_err());
        assert!(run(&[]).is_err());
    }
}
