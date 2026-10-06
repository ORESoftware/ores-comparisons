use oreslang_format::{format_source, is_formatted};

#[test]
fn uses_two_blank_lines_between_sibling_functions() {
    let src = r#"fnc one() => int {
return 1;
}
fnc two(): int {
return 2;
}



pub routine main() => void {
return;
}
"#;
    let expected = r#"fnc one() -> int {
  return 1;
}


fnc two() -> int {
  return 2;
}


pub routine main() -> void {
  return;
}
"#;
    assert_eq!(format_source(src).unwrap(), expected);
}

#[test]
fn module_owned_class_is_indented_as_a_module_member() {
    let src = r#"define module Accounts as
define class Account as
pub let int balance = 100;

pub read() => int {
return self.balance;
}
end

pub fnc seed() => int {
return 100;
}
end
"#;

    let expected = r#"define module Accounts as
  define class Account as
    pub let int balance = 100;

    pub read() -> int {
      return self.balance;
    }
  end

  pub fnc seed() -> int {
    return 100;
  }
end
"#;

    assert_eq!(format_source(src).unwrap(), expected);
}

#[test]
fn tuple_and_fixed_sequence_metadata_remain_stable() {
    let src = r#"fnc build() => void {
val pair: Tuple<align=64, size=2>[int, string] = (1, "one");
val fixed: FixedArray<size=4, align=32>[4 of int] = [1, 2, 3, 4];
return;
}
"#;

    let expected = r#"fnc build() -> void {
  val pair: Tuple<align=64, size=2>[int, string] = (1, "one");
  val fixed: FixedArray<size=4, align=32>[4 of int] = [1, 2, 3, 4];
  return;
}
"#;

    assert_eq!(format_source(src).unwrap(), expected);
}

#[test]
fn channel_select_and_nb_callback_spellings_remain_stable() {
    let src = r#"fnc poll() => void {
val choice = nb select first from cases;
val read = try readch input;
val wrote = try writech output, 42;
nb cb writech output, 42 || -> {
stdio.stdout.write("sent");
};
return;
}
"#;

    let expected = r#"fnc poll() -> void {
  val choice = nb select first from cases;
  val read = try readch input;
  val wrote = try writech output, 42;
  nb cb writech output, 42 || -> {
    stdio.stdout.write("sent");
  };
  return;
}
"#;

    assert_eq!(format_source(src).unwrap(), expected);
}

#[test]
fn import_selector_spellings_remain_stable() {
    let src = r#"import * as util from "../util";
import module Math from "../math";
import actor Worker from "../worker";
import class Box from "../box";
import types X, Y, Z from "../types";
import types (A, B, C) from "../more-types";

define module App as
pub fnc main() => void {
return;
}
end
"#;

    let expected = r#"import * as util from "../util";
import module Math from "../math";
import actor Worker from "../worker";
import class Box from "../box";
import types X, Y, Z from "../types";
import types (A, B, C) from "../more-types";

define module App as
  pub fnc main() -> void {
    return;
  }
end
"#;

    assert_eq!(format_source(src).unwrap(), expected);
}

#[test]
fn class_contract_headers_and_nested_contracts_indent_without_confusion() {
    let src = r#"define class Child extends Parent<int>, Audited implements Named, Serializable as
pub val String name;

pub render() => String {
return self.name;
}


define interface LocalShape
fnc project(String value) -> String;
end

define trait Retryable
fnc retry(): bool;
end
end
"#;
    let expected = r#"define class Child extends Parent<int>, Audited implements Named, Serializable as
  pub val String name;

  pub render() -> String {
    return self.name;
  }

  define interface LocalShape
    fnc project(String value) => String;
  end

  define trait Retryable
    fnc retry() => bool;
  end
end
"#;
    assert_eq!(format_source(src).unwrap(), expected);
}

#[test]
fn comments_and_strings_do_not_change_block_depth() {
    let src = r#"fnc text() => String {
// } should not dedent
return "{still text}";
}

fnc template() => String {
return `}`;
}
"#;
    let expected = r#"fnc text() -> String {
  // } should not dedent
  return "{still text}";
}


fnc template() -> String {
  return `}`;
}
"#;
    assert_eq!(format_source(src).unwrap(), expected);
}

#[test]
fn formats_if_fi_and_actor_bodies() {
    let src = r#"shared actor Cleaner {
pub fnc compact() => void {
if ready; do
actor.gc();
else
return;
fi
}
}
"#;
    let expected = r#"shared actor Cleaner {
  pub fnc compact() -> void {
    if ready; then
      actor.gc();
    else
      return;
    fi
  }
}
"#;
    assert_eq!(format_source(src).unwrap(), expected);
}

#[test]
fn formatter_is_idempotent_and_normalizes_crlf() {
    let src = "pub routine main() => void {
  return;  
}
";
    let once = format_source(src).unwrap();
    let twice = format_source(&once).unwrap();
    assert_eq!(once, twice);
    assert_eq!(
        once,
        "pub routine main() -> void {
  return;
}
"
    );
    assert!(is_formatted(&once).unwrap());
}

#[test]
fn rejects_unbalanced_blocks() {
    let err = format_source(
        "fnc nope() -> void {
",
    )
    .unwrap_err();
    assert!(err.message().contains("unterminated"));
}

#[test]
fn local_interfaces_inside_functions_override_outer_callable_context() {
    let src = r#"fnc outer() => void {
define interface Local
fnc run(): int;
end
return;
}
"#;
    let expected = r#"fnc outer() -> void {
  define interface Local
    fnc run() => int;
  end
  return;
}
"#;
    assert_eq!(format_source(src).unwrap(), expected);
}

#[test]
fn braced_interfaces_use_signature_arrows() {
    let src = r#"pub interface Shape {
fnc area() -> f64;
}
"#;
    let expected = r#"pub interface Shape {
  fnc area() => f64;
}
"#;
    assert_eq!(format_source(src).unwrap(), expected);
}

#[test]
fn annotations_stay_attached_to_the_next_function() {
    let src = r#"fnc one() => int {
return 1;
}
@Ret<self>
fnc two() => int {
return 2;
}
"#;
    let expected = r#"fnc one() -> int {
  return 1;
}


@Ret<self>
fnc two() -> int {
  return 2;
}
"#;
    assert_eq!(format_source(src).unwrap(), expected);
}

#[test]
fn canonicalizes_parenthesized_callable_return_types() {
    let src = r#"fnc make_callback(): (() => int) {
return || -> {
return 1;
};
}

define interface Factory
fnc make_callback() -> (() => int);
end
"#;
    let expected = r#"fnc make_callback() -> (() => int) {
  return || -> {
    return 1;
  };
}

define interface Factory
  fnc make_callback() => (() => int);
end
"#;
    assert_eq!(format_source(src).unwrap(), expected);
}

#[test]
fn callable_arrow_ignores_braces_inside_default_strings() {
    let src = r#"fnc render(String marker = "{") => String {
return marker;
}
"#;
    let expected = r#"fnc render(String marker = "{") -> String {
  return marker;
}
"#;
    assert_eq!(format_source(src).unwrap(), expected);
}

#[test]
fn rejects_multiline_templates_instead_of_mutating_literal_bytes() {
    let src = "fnc template() => String {\n  return `line one\n    line two`;\n}\n";
    let err = format_source(src).unwrap_err();
    assert!(
        err.message()
            .contains("multiline string/template literals are not formatted yet")
    );
}

#[test]
fn validates_if_fi_and_do_done_terminators() {
    let valid = r#"fnc work() => void {
if ready; do
while busy; do
tick();
done
else
return;
fi
}
"#;
    let formatted = format_source(valid).unwrap();
    assert!(formatted.contains("  if ready; then"));
    assert!(formatted.contains("    while busy; do"));
    assert!(formatted.contains("  fi"));

    let wrong = "if ready; do\nreturn;\ndone\n";
    let err = format_source(wrong).unwrap_err();
    assert!(err.message().contains("`done` where `fi` was expected"));

    let orphan = "else\nreturn;\n";
    let err = format_source(orphan).unwrap_err();
    assert!(
        err.message()
            .contains("without a matching `if ... then` block")
    );
}

#[test]
fn rejects_unterminated_keyword_blocks() {
    let err = format_source("if ready; do\nreturn;\n").unwrap_err();
    assert!(err.message().contains("expected `fi`"));

    let err = format_source("while ready; do\ntick();\n").unwrap_err();
    assert!(err.message().contains("expected `done`"));
}

#[test]
fn multiline_block_comments_cannot_change_structure() {
    let src = r#"define class Box as
/*
end
if fake; do
}
*/
pub get() => int {
return 1;
}
end
"#;
    let expected = r#"define class Box as
  /*
  end
  if fake; do
  }
  */
  pub get() -> int {
    return 1;
  }
end
"#;
    assert_eq!(format_source(src).unwrap(), expected);
}

#[test]
fn inline_block_comments_do_not_confuse_callable_boundaries() {
    let src = r#"/* fake ( { end */ fnc real(String marker = "{") => String {
return marker;
}
"#;
    let expected = r#"/* fake ( { end */ fnc real(String marker = "{") -> String {
  return marker;
}
"#;
    assert_eq!(format_source(src).unwrap(), expected);
}

#[test]
fn block_comments_act_as_lexical_separators() {
    let src = r#"define/* comment */class Box as
pub get() => int {
return 1;
}
end
"#;
    let expected = r#"define/* comment */class Box as
  pub get() -> int {
    return 1;
  }
end
"#;
    assert_eq!(format_source(src).unwrap(), expected);
}

#[test]
fn canonicalizes_conditional_compatibility_spellings() {
    let src = r#"fnc choose(int value) => int {
if value < 0 do
return -1;
elseif value == 0; do
return 0;
else if value == 1 then
return 1;
else
return 2;
fi
}
"#;
    let expected = r#"fnc choose(int value) -> int {
  if value < 0; then
    return -1;
  elif value == 0; then
    return 0;
  elif value == 1; then
    return 1;
  else
    return 2;
  fi
}
"#;
    assert_eq!(format_source(src).unwrap(), expected);
}

#[test]
fn conditional_rewrites_ignore_strings_and_comments() {
    let src = r#"fnc text() => String {
// elseif fake; do
if ready; do
return "else if nope do";
else
return "then";
fi
}
"#;
    let expected = r#"fnc text() -> String {
  // elseif fake; do
  if ready; then
    return "else if nope do";
  else
    return "then";
  fi
}
"#;
    assert_eq!(format_source(src).unwrap(), expected);
}

#[test]
fn canonicalizes_braced_branch_aliases_without_inventing_then() {
    let src = r#"if first {
work();
}
elseif second {
other();
}
else if third {
third_work();
}
else {
fallback();
}
fi
"#;
    let expected = r#"if first {
  work();
}
elif second {
  other();
}
elif third {
  third_work();
}
else {
  fallback();
}
fi
"#;
    assert_eq!(format_source(src).unwrap(), expected);
}

#[test]
fn nested_braced_if_chain_keeps_enclosing_callable_indent() {
    let src = r#"fnc choose(int value) => int {
if value < 0 {
return -1;
}
else if value == 0 {
return 0;
}
else {
return 1;
}
fi
}
"#;
    let expected = r#"fnc choose(int value) -> int {
  if value < 0 {
    return -1;
  }
  elif value == 0 {
    return 0;
  }
  else {
    return 1;
  }
  fi
}
"#;
    assert_eq!(format_source(src).unwrap(), expected);
}
