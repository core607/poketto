use crate::MAX_FRAME;
use serde_json::{Number, Value, json};
use std::collections::BTreeMap;
use std::fs::File;
use std::io::{self, Read};
use std::str::FromStr;

pub struct Invocation {
    pub operation: String,
    pub arguments: Value,
}

#[derive(Clone, Copy, PartialEq)]
enum Kind {
    Text,
    Flag,
    Append,
}

struct Command {
    operation: &'static str,
    positions: &'static [&'static str],
    variadic: bool,
    options: &'static [(&'static str, Kind)],
    help: &'static str,
}

fn command(operation: &str, child: Option<&str>) -> Option<Command> {
    use Kind::{Append, Flag, Text};
    let (operation, positions, variadic, options, help) = match (operation, child) {
        ("status", None) => (
            "status",
            &[][..],
            false,
            &[][..],
            include_str!("../help/status.txt"),
        ),
        ("sync", None) => (
            "sync",
            &[][..],
            false,
            &[][..],
            include_str!("../help/sync.txt"),
        ),
        ("recover", None) => (
            "recover",
            &[][..],
            false,
            &[("skip-local", Flag)][..],
            include_str!("../help/recover.txt"),
        ),
        ("move", None) => (
            "move",
            &["source", "destination"][..],
            false,
            &[][..],
            include_str!("../help/move.txt"),
        ),
        ("edit", None) => (
            "edit",
            &["path"][..],
            false,
            &[
                ("old", Text),
                ("old-file", Text),
                ("new", Text),
                ("new-file", Text),
                ("new-stdin", Flag),
            ][..],
            include_str!("../help/edit.txt"),
        ),
        ("create", None) => (
            "create",
            &["path"][..],
            false,
            &[("text", Text), ("text-file", Text), ("stdin", Flag)][..],
            include_str!("../help/create.txt"),
        ),
        ("save", None) => (
            "save",
            &["paths"][..],
            true,
            &[("delete", Append)][..],
            include_str!("../help/save.txt"),
        ),
        ("export", None) => (
            "export",
            &["paths"][..],
            true,
            &[("output", Text), ("public", Flag)][..],
            include_str!("../help/export.txt"),
        ),
        ("artifact", Some("create")) => (
            "artifact_create",
            &["path"][..],
            false,
            &[("type", Text)][..],
            include_str!("../help/artifact-create.txt"),
        ),
        ("artifact", Some("remove")) => (
            "artifact_remove",
            &["artifact_id"][..],
            false,
            &[][..],
            include_str!("../help/artifact-remove.txt"),
        ),
        ("media", Some("list")) => (
            "media_list",
            &[][..],
            false,
            &[
                ("prefix", Text),
                ("offset", Text),
                ("limit", Text),
                ("index-version", Text),
                ("commit", Text),
            ][..],
            include_str!("../help/media-list.txt"),
        ),
        ("media", Some("fetch")) => (
            "media_fetch",
            &["path"][..],
            false,
            &[("commit", Text), ("output", Text)][..],
            include_str!("../help/media-fetch.txt"),
        ),
        ("media", Some("import")) => (
            "media_import",
            &["file"][..],
            false,
            &[
                ("as", Text),
                ("type", Text),
                ("key", Text),
                ("replace", Flag),
            ][..],
            include_str!("../help/media-import.txt"),
        ),
        ("media", Some("link")) => (
            "media_link",
            &["path"][..],
            false,
            &[("asset", Text), ("revision", Text), ("replace", Flag)][..],
            include_str!("../help/media-link.txt"),
        ),
        _ => return None,
    };
    Some(Command {
        operation,
        positions,
        variadic,
        options,
        help,
    })
}

fn help(argument: &str) -> bool {
    matches!(argument, "--help" | "-h")
}

fn negative_number(argument: &str) -> bool {
    argument.starts_with('-') && argument[1..].parse::<f64>().is_ok()
}

fn read_text(
    values: &BTreeMap<String, Vec<String>>,
    text: &str,
    filename: &str,
    stdin: &str,
) -> Result<String, String> {
    if let Some(value) = values.get(text) {
        return Ok(value[0].clone());
    }
    let stream: Box<dyn Read> = if values.contains_key(stdin) {
        Box::new(io::stdin())
    } else {
        Box::new(
            File::open(&values[filename][0])
                .map_err(|error| format!("cannot read text input: {error}"))?,
        )
    };
    let mut raw = Vec::new();
    stream
        .take(MAX_FRAME as u64 + 1)
        .read_to_end(&mut raw)
        .map_err(|error| format!("cannot read text input: {error}"))?;
    if raw.len() > MAX_FRAME {
        return Err(
            "cannot read text input: text input exceeds 512 KiB; use smaller exact edits".into(),
        );
    }
    String::from_utf8(raw).map_err(|_| "cannot read text input: input is not UTF-8".into())
}

fn integer(value: &str) -> Result<Value, String> {
    // argparse's int accepts a sign, whitespace, leading zeroes and digit separators.
    let text = value.trim();
    let (negative, digits) = if let Some(rest) = text.strip_prefix('-') {
        (true, rest)
    } else {
        (false, text.strip_prefix('+').unwrap_or(text))
    };
    if digits.is_empty()
        || digits.starts_with('_')
        || digits.ends_with('_')
        || digits.contains("__")
        || !digits.chars().all(|c| c.is_ascii_digit() || c == '_')
    {
        return Err(format!("invalid integer value: {value}"));
    }
    let stripped = digits.replace('_', "");
    let significant = stripped.trim_start_matches('0');
    let normalized = if significant.is_empty() {
        "0".to_string()
    } else {
        format!("{}{significant}", if negative { "-" } else { "" })
    };
    Number::from_str(&normalized)
        .map(Value::Number)
        .map_err(|_| format!("invalid integer value: {value}"))
}

pub fn parse(mut args: Vec<String>) -> Result<Option<Invocation>, String> {
    if args.first().is_some_and(|value| help(value)) {
        print!("{}", include_str!("../help/poketto.txt"));
        return Ok(None);
    }
    if args.is_empty() {
        return Err("the following arguments are required: operation".into());
    }
    let operation = args.remove(0);
    let child = if matches!(operation.as_str(), "media" | "artifact") {
        if args.first().is_some_and(|value| help(value)) {
            print!(
                "{}",
                if operation == "media" {
                    include_str!("../help/media.txt")
                } else {
                    include_str!("../help/artifact.txt")
                }
            );
            return Ok(None);
        }
        if args.is_empty() {
            return Err(format!("{operation} requires a subcommand"));
        }
        Some(args.remove(0))
    } else {
        None
    };
    let spec =
        command(&operation, child.as_deref()).ok_or_else(|| "invalid subcommand".to_string())?;
    let mut values = BTreeMap::<String, Vec<String>>::new();
    let mut positions = Vec::new();
    let mut index = 0;
    let mut positional_only = false;
    while index < args.len() {
        let item = &args[index];
        index += 1;
        if positional_only {
            positions.push(item.clone());
            continue;
        }
        if help(item) {
            print!("{}", spec.help);
            return Ok(None);
        }
        if item == "--" {
            positional_only = true;
            continue;
        }
        if let Some(option) = item.strip_prefix("--") {
            let (name, inline) = option
                .split_once('=')
                .map_or((option, None), |(key, value)| (key, Some(value)));
            let exact = spec.options.iter().find(|(key, _)| *key == name);
            let matches: Vec<_> = spec
                .options
                .iter()
                .filter(|(key, _)| key.starts_with(name))
                .collect();
            let &(key, kind) = exact
                .or_else(|| {
                    if matches.len() == 1 {
                        Some(matches[0])
                    } else {
                        None
                    }
                })
                .ok_or_else(|| format!("unrecognized or ambiguous argument: --{name}"))?;
            let value = if kind == Kind::Flag {
                if inline.is_some() {
                    return Err(format!("--{key} does not take a value"));
                }
                String::new()
            } else if let Some(value) = inline {
                value.to_string()
            } else {
                let value = args
                    .get(index)
                    .ok_or_else(|| format!("--{key} expected one argument"))?;
                if value.starts_with('-') && value != "-" && !negative_number(value) {
                    return Err(format!("--{key} expected one argument"));
                }
                index += 1;
                value.clone()
            };
            if kind == Kind::Append {
                values.entry(key.to_string()).or_default().push(value);
            } else {
                values.insert(key.to_string(), vec![value]);
            }
        } else if item.starts_with('-') && item != "-" && !negative_number(item) {
            return Err(format!("unrecognized argument: {item}"));
        } else {
            positions.push(item.clone());
        }
    }
    if !spec.variadic && positions.len() != spec.positions.len() {
        return Err(format!(
            "expected positional arguments: {}",
            spec.positions.join(" ")
        ));
    }
    let text = |key: &str| {
        values
            .get(key)
            .map(|items| Value::String(items[0].clone()))
            .unwrap_or(Value::Null)
    };
    let default = |key: &str, fallback: &str| {
        values
            .get(key)
            .map_or(fallback.to_string(), |items| items[0].clone())
    };
    let required = |key: &str| -> Result<Value, String> {
        values
            .get(key)
            .map(|items| Value::String(items[0].clone()))
            .ok_or_else(|| format!("the following arguments are required: --{key}"))
    };
    let group = |names: &[&str]| -> Result<(), String> {
        if names
            .iter()
            .filter(|name| values.contains_key(**name))
            .count()
            != 1
        {
            Err(format!(
                "exactly one of {} is required",
                names
                    .iter()
                    .map(|name| format!("--{name}"))
                    .collect::<Vec<_>>()
                    .join(", ")
            ))
        } else {
            Ok(())
        }
    };
    let arguments = match spec.operation {
        "status" | "sync" => json!({}),
        "recover" => {
            if values.contains_key("skip-local") {
                json!({"skipLocal": true})
            } else {
                json!({})
            }
        }
        "move" => json!({"source": positions[0], "destination": positions[1]}),
        "create" => {
            group(&["text", "text-file", "stdin"])?;
            json!({"path": positions[0], "text": read_text(&values, "text", "text-file", "stdin")?})
        }
        "edit" => {
            group(&["old", "old-file"])?;
            group(&["new", "new-file", "new-stdin"])?;
            json!({"path": positions[0], "oldText": read_text(&values, "old", "old-file", "")?,
                   "newText": read_text(&values, "new", "new-file", "new-stdin")?})
        }
        "save" => {
            let deletes = values.get("delete").cloned().unwrap_or_default();
            if positions.is_empty() && deletes.is_empty() {
                return Err("save requires selected files or explicit --delete paths".into());
            }
            json!({"writes": positions, "deletes": deletes})
        }
        "export" => {
            if positions.is_empty() {
                return Err("the following arguments are required: paths".into());
            }
            json!({"paths": positions, "output": required("output")?, "publicOnly": values.contains_key("public")})
        }
        "artifact_create" => {
            json!({"path": positions[0], "mediaType": default("type", "application/octet-stream")})
        }
        "artifact_remove" => json!({"artifactId": positions[0]}),
        "media_list" => {
            json!({"prefix": default("prefix", ""), "offset": integer(&default("offset", "0"))?,
                              "limit": integer(&default("limit", "100"))?, "indexVersion": text("index-version"), "commit": text("commit")})
        }
        "media_fetch" => {
            json!({"path": positions[0], "commit": text("commit"), "output": text("output")})
        }
        "media_import" => {
            json!({"file": positions[0], "path": required("as")?, "mediaType": default("type", "application/octet-stream"),
                                "key": required("key")?, "replace": values.contains_key("replace")})
        }
        "media_link" => {
            json!({"path": positions[0], "assetId": required("asset")?, "revision": required("revision")?,
                              "replace": values.contains_key("replace")})
        }
        _ => unreachable!(),
    };
    let envelope = json!({"requestId": "00000000-0000-4000-8000-000000000000", "operation": spec.operation, "arguments": arguments});
    if serde_json::to_vec(&envelope)
        .map_err(|error| error.to_string())?
        .len()
        > MAX_FRAME
    {
        return Err(
            "encoded request exceeds 512 KiB; split the text into smaller exact edits".into(),
        );
    }
    Ok(Some(Invocation {
        operation: spec.operation.into(),
        arguments,
    }))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn invocation(input: &[&str]) -> Invocation {
        parse(input.iter().map(|value| value.to_string()).collect())
            .unwrap()
            .unwrap()
    }

    #[test]
    fn defaults_and_explicit_deletions_are_preserved() {
        assert_eq!(
            invocation(&["media", "list"]).arguments,
            json!({"prefix": "", "offset": 0, "limit": 100, "indexVersion": null, "commit": null})
        );
        assert_eq!(
            invocation(&["save", "a.md", "--delete", "b.md", "--delete=c.md"]).arguments,
            json!({"writes": ["a.md"], "deletes": ["b.md", "c.md"]})
        );
    }

    #[test]
    fn text_options_are_exclusive_and_preserve_empty_values() {
        assert_eq!(
            invocation(&["edit", "a.md", "--old=ab", "--new="]).arguments,
            json!({"path": "a.md", "oldText": "ab", "newText": ""})
        );
        assert!(
            parse(vec![
                "create".into(),
                "a.md".into(),
                "--stdin".into(),
                "--text=x".into()
            ])
            .is_err()
        );
    }

    #[test]
    fn escaped_encoded_text_is_bounded_before_bridge_access() {
        assert!(
            parse(vec![
                "create".into(),
                "a.md".into(),
                "--text".into(),
                "\0".repeat(MAX_FRAME / 5)
            ])
            .is_err()
        );
    }

    #[test]
    fn integers_keep_the_python_input_range() {
        assert_eq!(integer(" +0001_000 ").unwrap(), json!(1000));
        assert_eq!(integer("-0").unwrap(), json!(0));
        assert_eq!(
            integer("184467440737095516160").unwrap().to_string(),
            "184467440737095516160"
        );
        assert!(integer("1__0").is_err());
    }
}
