#![forbid(unsafe_code)]

use std::collections::BTreeMap;

#[derive(Clone, Debug, PartialEq)]
pub enum JsonValue {
    Null,
    Bool(bool),
    Number(String),
    String(String),
    Array(Vec<JsonValue>),
    Object(BTreeMap<String, JsonValue>),
}

impl JsonValue {
    pub fn parse(source: &str) -> Result<Self, String> {
        let mut parser = Parser { source, offset: 0 };
        let value = parser.parse_value()?;
        parser.skip_whitespace();
        if parser.offset != source.len() {
            return Err(format!(
                "unexpected trailing JSON at byte {}",
                parser.offset
            ));
        }
        Ok(value)
    }

    pub fn as_object(&self) -> Option<&BTreeMap<String, JsonValue>> {
        match self {
            Self::Object(value) => Some(value),
            _ => None,
        }
    }

    pub fn as_array(&self) -> Option<&[JsonValue]> {
        match self {
            Self::Array(value) => Some(value),
            _ => None,
        }
    }

    pub fn as_str(&self) -> Option<&str> {
        match self {
            Self::String(value) => Some(value),
            _ => None,
        }
    }

    pub fn as_bool(&self) -> Option<bool> {
        match self {
            Self::Bool(value) => Some(*value),
            _ => None,
        }
    }

    pub fn as_i64(&self) -> Option<i64> {
        match self {
            Self::Number(value) => value.parse().ok(),
            _ => None,
        }
    }

    pub fn get(&self, key: &str) -> Option<&JsonValue> {
        self.as_object()?.get(key)
    }

    pub fn compact(&self) -> String {
        let mut output = String::new();
        self.write_compact(&mut output);
        output
    }

    fn write_compact(&self, output: &mut String) {
        match self {
            Self::Null => output.push_str("null"),
            Self::Bool(value) => output.push_str(if *value { "true" } else { "false" }),
            Self::Number(value) => output.push_str(value),
            Self::String(value) => write_json_string(output, value),
            Self::Array(values) => {
                output.push('[');
                for (index, value) in values.iter().enumerate() {
                    if index > 0 {
                        output.push(',');
                    }
                    value.write_compact(output);
                }
                output.push(']');
            }
            Self::Object(values) => {
                output.push('{');
                for (index, (key, value)) in values.iter().enumerate() {
                    if index > 0 {
                        output.push(',');
                    }
                    write_json_string(output, key);
                    output.push(':');
                    value.write_compact(output);
                }
                output.push('}');
            }
        }
    }
}

fn write_json_string(output: &mut String, value: &str) {
    output.push('"');
    for ch in value.chars() {
        match ch {
            '"' => output.push_str("\\\""),
            '\\' => output.push_str("\\\\"),
            '\u{08}' => output.push_str("\\b"),
            '\u{0c}' => output.push_str("\\f"),
            '\n' => output.push_str("\\n"),
            '\r' => output.push_str("\\r"),
            '\t' => output.push_str("\\t"),
            ch if ch <= '\u{1f}' => output.push_str(&format!("\\u{:04x}", ch as u32)),
            ch => output.push(ch),
        }
    }
    output.push('"');
}

struct Parser<'a> {
    source: &'a str,
    offset: usize,
}

impl Parser<'_> {
    fn parse_value(&mut self) -> Result<JsonValue, String> {
        self.skip_whitespace();
        match self.peek_char() {
            Some('"') => self.parse_string().map(JsonValue::String),
            Some('{') => self.parse_object(),
            Some('[') => self.parse_array(),
            Some('t') => {
                self.consume_literal("true")?;
                Ok(JsonValue::Bool(true))
            }
            Some('f') => {
                self.consume_literal("false")?;
                Ok(JsonValue::Bool(false))
            }
            Some('n') => {
                self.consume_literal("null")?;
                Ok(JsonValue::Null)
            }
            Some('-' | '0'..='9') => self.parse_number().map(JsonValue::Number),
            Some(ch) => Err(format!(
                "unexpected JSON token {ch:?} at byte {}",
                self.offset
            )),
            None => Err("unexpected end of JSON".to_owned()),
        }
    }

    fn parse_object(&mut self) -> Result<JsonValue, String> {
        self.expect_char('{')?;
        self.skip_whitespace();
        let mut values = BTreeMap::new();
        if self.peek_char() == Some('}') {
            self.next_char();
            return Ok(JsonValue::Object(values));
        }

        loop {
            self.skip_whitespace();
            if self.peek_char() != Some('"') {
                return Err(format!("expected object key at byte {}", self.offset));
            }
            let key = self.parse_string()?;
            self.skip_whitespace();
            self.expect_char(':')?;
            let value = self.parse_value()?;
            if values.insert(key.clone(), value).is_some() {
                return Err(format!("duplicate JSON object key {key:?}"));
            }
            self.skip_whitespace();
            match self.next_char() {
                Some(',') => {}
                Some('}') => break,
                Some(ch) => {
                    return Err(format!(
                        "expected ',' or '}}', found {ch:?} at byte {}",
                        self.offset.saturating_sub(ch.len_utf8())
                    ));
                }
                None => return Err("unterminated JSON object".to_owned()),
            }
        }
        Ok(JsonValue::Object(values))
    }

    fn parse_array(&mut self) -> Result<JsonValue, String> {
        self.expect_char('[')?;
        self.skip_whitespace();
        let mut values = Vec::new();
        if self.peek_char() == Some(']') {
            self.next_char();
            return Ok(JsonValue::Array(values));
        }

        loop {
            values.push(self.parse_value()?);
            self.skip_whitespace();
            match self.next_char() {
                Some(',') => {}
                Some(']') => break,
                Some(ch) => {
                    return Err(format!(
                        "expected ',' or ']', found {ch:?} at byte {}",
                        self.offset.saturating_sub(ch.len_utf8())
                    ));
                }
                None => return Err("unterminated JSON array".to_owned()),
            }
        }
        Ok(JsonValue::Array(values))
    }

    fn parse_string(&mut self) -> Result<String, String> {
        self.expect_char('"')?;
        let mut output = String::new();
        loop {
            let Some(ch) = self.next_char() else {
                return Err("unterminated JSON string".to_owned());
            };
            match ch {
                '"' => return Ok(output),
                '\\' => {
                    let Some(escape) = self.next_char() else {
                        return Err("unterminated JSON escape".to_owned());
                    };
                    match escape {
                        '"' => output.push('"'),
                        '\\' => output.push('\\'),
                        '/' => output.push('/'),
                        'b' => output.push('\u{08}'),
                        'f' => output.push('\u{0c}'),
                        'n' => output.push('\n'),
                        'r' => output.push('\r'),
                        't' => output.push('\t'),
                        'u' => {
                            let code = self.parse_hex4()?;
                            let Some(decoded) = char::from_u32(code) else {
                                return Err(format!("invalid Unicode escape U+{code:04X}"));
                            };
                            if decoded.is_surrogate() {
                                return Err(
                                    "UTF-16 surrogate escapes are not admitted by this parser"
                                        .to_owned(),
                                );
                            }
                            output.push(decoded);
                        }
                        other => {
                            return Err(format!("unsupported JSON escape \\{other}"));
                        }
                    }
                }
                ch if ch <= '\u{1f}' => {
                    return Err("unescaped control character in JSON string".to_owned());
                }
                ch => output.push(ch),
            }
        }
    }

    fn parse_hex4(&mut self) -> Result<u32, String> {
        let start = self.offset;
        let mut value = 0u32;
        for _ in 0..4 {
            let Some(ch) = self.next_char() else {
                return Err("unterminated Unicode escape".to_owned());
            };
            let Some(digit) = ch.to_digit(16) else {
                return Err(format!(
                    "invalid Unicode escape at byte {start}: expected four hex digits"
                ));
            };
            value = value * 16 + digit;
        }
        Ok(value)
    }

    fn parse_number(&mut self) -> Result<String, String> {
        let start = self.offset;

        if self.peek_char() == Some('-') {
            self.next_char();
        }

        match self.peek_char() {
            Some('0') => {
                self.next_char();
                if matches!(self.peek_char(), Some('0'..='9')) {
                    return Err(format!(
                        "invalid JSON number with leading zero at byte {start}"
                    ));
                }
            }
            Some('1'..='9') => {
                while matches!(self.peek_char(), Some('0'..='9')) {
                    self.next_char();
                }
            }
            _ => {
                return Err(format!("invalid JSON number at byte {start}"));
            }
        }

        if self.peek_char() == Some('.') {
            self.next_char();
            if !matches!(self.peek_char(), Some('0'..='9')) {
                return Err(format!(
                    "JSON fraction must contain at least one digit at byte {start}"
                ));
            }
            while matches!(self.peek_char(), Some('0'..='9')) {
                self.next_char();
            }
        }

        if matches!(self.peek_char(), Some('e' | 'E')) {
            self.next_char();
            if matches!(self.peek_char(), Some('+' | '-')) {
                self.next_char();
            }
            if !matches!(self.peek_char(), Some('0'..='9')) {
                return Err(format!(
                    "JSON exponent must contain at least one digit at byte {start}"
                ));
            }
            while matches!(self.peek_char(), Some('0'..='9')) {
                self.next_char();
            }
        }

        Ok(self.source[start..self.offset].to_owned())
    }

    fn consume_literal(&mut self, literal: &str) -> Result<(), String> {
        if self.source[self.offset..].starts_with(literal) {
            self.offset += literal.len();
            Ok(())
        } else {
            Err(format!(
                "expected JSON literal {literal:?} at byte {}",
                self.offset
            ))
        }
    }

    fn expect_char(&mut self, expected: char) -> Result<(), String> {
        match self.next_char() {
            Some(actual) if actual == expected => Ok(()),
            Some(actual) => Err(format!(
                "expected {expected:?}, found {actual:?} at byte {}",
                self.offset.saturating_sub(actual.len_utf8())
            )),
            None => Err(format!("expected {expected:?}, found end of JSON")),
        }
    }

    fn skip_whitespace(&mut self) {
        while matches!(self.peek_char(), Some(' ' | '\n' | '\r' | '\t')) {
            self.next_char();
        }
    }

    fn peek_char(&self) -> Option<char> {
        self.source[self.offset..].chars().next()
    }

    fn next_char(&mut self) -> Option<char> {
        let ch = self.peek_char()?;
        self.offset += ch.len_utf8();
        Some(ch)
    }
}

trait Surrogate {
    fn is_surrogate(self) -> bool;
}

impl Surrogate for char {
    fn is_surrogate(self) -> bool {
        let value = self as u32;
        (0xd800..=0xdfff).contains(&value)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_and_serializes_nested_json_deterministically() {
        let parsed = JsonValue::parse(
            r#"{"z":[true,false,null,1,-2.5],"a":{"escaped":"line\nquote\"","x":"✓"}}"#,
        )
        .expect("parse");
        assert_eq!(
            parsed.compact(),
            r#"{"a":{"escaped":"line\nquote\"","x":"✓"},"z":[true,false,null,1,-2.5]}"#
        );
    }

    #[test]
    fn rejects_duplicate_object_keys() {
        assert!(JsonValue::parse(r#"{"a":1,"a":2}"#).is_err());
    }

    #[test]
    fn rejects_trailing_material() {
        assert!(JsonValue::parse("{} trailing").is_err());
    }

    #[test]
    fn enforces_exact_json_number_grammar_without_float_round_trips() {
        for valid in ["0", "-0", "10", "-2.5", "1e3", "1E-3", "0.001"] {
            assert!(JsonValue::parse(valid).is_ok(), "{valid} should be valid JSON");
        }
        for invalid in ["01", "-01", "1.", "1e", "1e+", "-", "+1"] {
            assert!(
                JsonValue::parse(invalid).is_err(),
                "{invalid} must be rejected"
            );
        }
        assert_eq!(
            JsonValue::parse("123456789012345678901234567890")
                .expect("large JSON number")
                .compact(),
            "123456789012345678901234567890"
        );
    }

    #[test]
    fn typed_accessors_are_fail_closed() {
        let parsed = JsonValue::parse(r#"{"s":"x","b":true,"n":3,"a":[]}"#).expect("parse");
        assert_eq!(parsed.get("s").and_then(JsonValue::as_str), Some("x"));
        assert_eq!(parsed.get("b").and_then(JsonValue::as_bool), Some(true));
        assert_eq!(parsed.get("n").and_then(JsonValue::as_i64), Some(3));
        assert!(parsed.get("a").and_then(JsonValue::as_array).is_some());
        assert!(parsed.get("s").and_then(JsonValue::as_i64).is_none());
    }
}
