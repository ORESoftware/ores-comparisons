// GENERATED. DO NOT EDIT.
pub type Outcome {
  Ok
  Error
}

pub fn outcome_to_string(value: Outcome) -> String {
  case value {
    Ok -> "ok"
    Error -> "error"
  }
}

pub type RequestObservation {
  RequestObservation(
    id: String,
    requestId: String,
    route: String,
    statusCode: Int,
    durationMs: Int,
    outcome: Outcome,
    createdAt: String
  )
}

