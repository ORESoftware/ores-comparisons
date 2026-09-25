import json

def handler(payload=None, context=None):
    payload = payload or {}
    return {
        "accepted": True,
        "stack": "scintilla-run",
        "scenario": "forms-chat-workflow",
        "form_id": payload.get("form_id", "demo"),
        "integrations": ["ores-forms", "opto-sync", "ores-chat", "ores-convo"],
    }

if __name__ == "__main__":
    print(json.dumps(handler({})))
