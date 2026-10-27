//! The game protocol's bounds: the request a rule job receives and the step it must print.
use base64::{
    Engine, alphabet,
    engine::{DecodePaddingMode, GeneralPurpose, GeneralPurposeConfig},
};
use sandcrab_daemon::job::{End, Finished};
use serde_json::Value;

/// The request a job reads, as re-encoded compact JSON.
pub const MAX_INPUT: usize = 512 * 1024;
const MAX_BUNDLE: usize = 384 * 1024;
const MAX_STATE: usize = 32 * 1024;
/// Standard output and standard error of one job together.
pub const MAX_OUTPUT: usize = 96 * 1024;
const MEDIA_TYPES: [&str; 6] = [
    "image/png",
    "image/jpeg",
    "image/webp",
    "text/plain",
    "application/json",
    "application/octet-stream",
];
const BASE64: GeneralPurpose = GeneralPurpose::new(
    &alphabet::STANDARD,
    GeneralPurposeConfig::new()
        .with_decode_padding_mode(DecodePaddingMode::RequireCanonical)
        .with_decode_allow_trailing_bits(true),
);

fn shape(value: &Value, depth: usize) -> Result<(), &'static str> {
    if depth > 16 {
        return Err("INVALID_GAME");
    }
    match value {
        Value::Object(map) => {
            if map.len() > 2048 || map.keys().any(|key| key.len() > 256) {
                return Err("INVALID_GAME");
            }
            map.values().try_for_each(|item| shape(item, depth + 1))
        }
        Value::Array(items) => {
            if items.len() > 2048 {
                return Err("INVALID_GAME");
            }
            items.iter().try_for_each(|item| shape(item, depth + 1))
        }
        _ => Ok(()),
    }
}
/// Compact UTF-8 JSON of `value`: at most 16 levels deep, 2048 entries per container, keys of
/// 256 bytes and `maximum` bytes in all.
pub fn encoded(value: &Value, maximum: usize) -> Result<Vec<u8>, &'static str> {
    shape(value, 0)?;
    let bytes = serde_json::to_vec(value).map_err(|_| "INVALID_GAME")?;
    if bytes.len() > maximum {
        return Err("GAME_LIMIT");
    }
    Ok(bytes)
}
fn exactly<'a>(
    value: &'a Value,
    keys: &[&str],
) -> Result<&'a serde_json::Map<String, Value>, &'static str> {
    value
        .as_object()
        .filter(|object| object.len() == keys.len() && keys.iter().all(|k| object.contains_key(*k)))
        .ok_or("INVALID_GAME")
}
fn text(value: &Value, maximum: usize) -> Result<(), &'static str> {
    match value.as_str() {
        Some(text) if !text.is_empty() && text.len() <= maximum => Ok(()),
        _ => Err("INVALID_GAME"),
    }
}

/// Check a GAME request's data and return the input file a job reads.
pub fn input(data: &Value) -> Result<Vec<u8>, &'static str> {
    let object = exactly(data, &["bundle", "request"])?;
    let (bundle, request) = (&object["bundle"], &object["request"]);
    let fields = exactly(bundle, &["protocol", "source", "presentation", "resources"])?;
    if !fields["protocol"].is_u64() || fields["protocol"].as_u64() != Some(1) {
        return Err("INVALID_GAME");
    }
    text(&fields["source"], 256 * 1024)?;
    if !fields["presentation"].is_null() {
        text(&fields["presentation"], 64 * 1024)?;
    }
    let resources = fields["resources"]
        .as_object()
        .filter(|r| r.len() <= 32)
        .ok_or("INVALID_GAME")?;
    for resource in resources.values() {
        let resource = exactly(resource, &["mediaType", "data"])?;
        if !resource["mediaType"]
            .as_str()
            .is_some_and(|media| MEDIA_TYPES.contains(&media))
        {
            return Err("INVALID_GAME");
        }
        let data = resource["data"].as_str().ok_or("INVALID_GAME")?;
        BASE64.decode(data).map_err(|_| "INVALID_GAME")?;
    }
    encoded(bundle, MAX_BUNDLE)?;
    let request = exactly(request, &["mode", "state", "action", "seed"])?;
    let mode = request["mode"].as_str().unwrap_or_default();
    if !["init", "observe", "act"].contains(&mode) {
        return Err("INVALID_GAME");
    }
    if mode == "init"
        && !(request["seed"].is_u64() && request["seed"].as_u64().is_some_and(|s| s <= 0xffff_ffff))
    {
        return Err("INVALID_REQUEST");
    }
    encoded(&request["state"], MAX_STATE)?;
    if mode == "act" && !request["action"].as_str().is_some_and(|a| a.len() <= 128) {
        return Err("INVALID_GAME");
    }
    encoded(data, MAX_INPUT)
}

/// The step a finished job printed: its new state, observation and presentation.
pub fn output(finished: Finished) -> Result<Value, &'static str> {
    if finished.truncated {
        return Err("GAME_LIMIT");
    }
    if finished.exit_code != 0 || finished.end != End::Normal {
        return Err("GAME_FAILED");
    }
    let [stdout, _] = finished.output;
    let value = sandcrab_core::parse(&stdout).map_err(|_| "INVALID_GAME_OUTPUT")?;
    let step = exactly(&value, &["state", "observation", "presentation"])
        .map_err(|_| "INVALID_GAME_OUTPUT")?;
    for part in step.values() {
        encoded(part, MAX_STATE)?;
    }
    Ok(value)
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    fn data() -> Value {
        json!({"bundle":{"protocol":1,"source":"export function init(){}","presentation":null,"resources":{}},
            "request":{"mode":"init","state":null,"action":null,"seed":1}})
    }
    fn finished(stdout: &str) -> Finished {
        Finished {
            exit_code: 0,
            output: [stdout.as_bytes().to_vec(), vec![]],
            truncated: false,
            end: End::Normal,
        }
    }
    #[test]
    fn input_is_reencoded_and_its_shape_is_checked_before_dispatch() {
        assert_eq!(
            input(&data()).unwrap(),
            serde_json::to_vec(&data()).unwrap()
        );
        for invalid in [json!({"shell":"id"}), json!({"bundle":{},"request":{}})] {
            assert_eq!(input(&invalid), Err("INVALID_GAME"));
        }
        for (pointer, value, code) in [
            ("/bundle/protocol", json!(1.0), "INVALID_GAME"),
            ("/bundle/protocol", json!(true), "INVALID_GAME"),
            ("/bundle/source", json!(""), "INVALID_GAME"),
            ("/bundle/presentation", json!(""), "INVALID_GAME"),
            (
                "/bundle/resources",
                json!({"a":{"mediaType":"text/html","data":""}}),
                "INVALID_GAME",
            ),
            (
                "/bundle/resources",
                json!({"a":{"mediaType":"text/plain","data":"YQ"}}),
                "INVALID_GAME",
            ),
            ("/request/mode", json!("shell"), "INVALID_GAME"),
            ("/request/seed", json!(1_u64 << 32), "INVALID_REQUEST"),
            ("/request/seed", json!(-1), "INVALID_REQUEST"),
            (
                "/request/state",
                json!({"text":"猫".repeat(12000)}),
                "GAME_LIMIT",
            ),
        ] {
            let mut changed = data();
            *changed.pointer_mut(pointer).unwrap() = value;
            assert_eq!(input(&changed), Err(code), "{pointer}");
        }
        let mut resource = data();
        resource["bundle"]["resources"] = json!({"a":{"mediaType":"text/plain","data":"YQ=="}});
        assert!(input(&resource).is_ok());
        let mut act = data();
        act["request"] = json!({"mode":"act","state":{},"action":"x".repeat(129),"seed":null});
        assert_eq!(input(&act), Err("INVALID_GAME"));
        act["request"]["action"] = "light".into();
        assert!(input(&act).is_ok());
    }
    #[test]
    fn depth_and_container_sizes_are_bounded() {
        let mut deep = json!({});
        for _ in 0..18 {
            deep = json!({ "next": deep });
        }
        assert_eq!(encoded(&deep, MAX_STATE), Err("INVALID_GAME"));
        let wide: Vec<_> = (0..2049).collect();
        assert_eq!(encoded(&json!(wide), MAX_INPUT), Err("INVALID_GAME"));
        assert_eq!(
            encoded(&json!({"k".repeat(257): 1}), MAX_STATE),
            Err("INVALID_GAME")
        );
    }
    #[test]
    fn a_step_is_accepted_only_from_a_normal_bounded_exit() {
        let step =
            r#"{"state":{},"observation":{"text":"Ready","actions":[]},"presentation":null}"#;
        assert_eq!(
            output(finished(step)).unwrap()["observation"]["text"],
            "Ready"
        );
        let large = format!(
            r#"{{"state":{{}},"observation":{{"text":"{}"}},"presentation":null}}"#,
            "x".repeat(32768)
        );
        assert_eq!(output(finished(&large)).unwrap_err(), "GAME_LIMIT");
        for (stdout, code) in [
            ("not json", "INVALID_GAME_OUTPUT"),
            (r#"{"state":{},"observation":{}}"#, "INVALID_GAME_OUTPUT"),
            (
                r#"{"state":{},"state":{},"observation":{},"presentation":null}"#,
                "INVALID_GAME_OUTPUT",
            ),
        ] {
            assert_eq!(output(finished(stdout)).unwrap_err(), code, "{stdout}");
        }
        let mut truncated = finished(step);
        truncated.truncated = true;
        assert_eq!(output(truncated).unwrap_err(), "GAME_LIMIT");
        for (exit_code, end) in [
            (1, End::Normal),
            (0, End::Timeout),
            (137, End::ResourceLimit),
        ] {
            let mut failed = finished(step);
            (failed.exit_code, failed.end) = (exit_code, end);
            assert_eq!(output(failed).unwrap_err(), "GAME_FAILED");
        }
    }
}
