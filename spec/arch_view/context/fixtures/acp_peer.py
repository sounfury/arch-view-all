"""Local stdio ACP peer. No network, model, or provider dependencies."""
import copy
import json
import os
import subprocess
import sys
import time

MODE = sys.argv[1]
SESSION = "fake-session"
prompts = 0
waiting = None
CONFIG = [
    {"id": "model", "name": "Model", "category": "model", "type": "select",
     "currentValue": "fake/one",
     "options": [{"value": "fake/one", "name": "One"}, {"value": "fake/two", "name": "Two"}]},
    {"id": "thought_level", "name": "Thinking", "category": "thought_level", "type": "select",
     "currentValue": "low",
     "options": [{"value": "low", "name": "Thinking: low"}, {"value": "high", "name": "Thinking: high"}]},
]
config = copy.deepcopy(CONFIG)


def config_payload():
    return {"configOptions": copy.deepcopy(config)}


def emit(message, fragmented=False):
    wire = (json.dumps(message, ensure_ascii=False) + "\n").encode()
    if fragmented:
        # Split inside JSON tokens and UTF-8 code points.
        for size in (1, 2, 7, 3, 1):
            sys.stdout.buffer.write(wire[:size])
            sys.stdout.buffer.flush()
            wire = wire[size:]
        for offset in range(0, len(wire), 11):
            sys.stdout.buffer.write(wire[offset:offset + 11])
            sys.stdout.buffer.flush()
    else:
        sys.stdout.buffer.write(wire)
        sys.stdout.buffer.flush()


def result(request, value):
    emit({"jsonrpc": "2.0", "id": request["id"], "result": value}, True)


def update(kind, **fields):
    emit({"jsonrpc": "2.0", "method": "session/update", "params": {
        "sessionId": SESSION, "update": {"sessionUpdate": kind, **fields}}}, True)


def text(value):
    update("agent_message_chunk", content={"type": "text", "text": value})


def check_session(params):
    assert os.path.isabs(params["cwd"])
    assert params["mcpServers"] == []


def permission(request):
    options = [{"optionId": "yes", "kind": "allow_once", "name": "Allow"}]
    if MODE != "permission-cancelled":
        options += [{"optionId": "no", "kind": MODE, "name": "Deny"}]
    # Reuse the client's request ID; directions have separate ID spaces.
    emit({"jsonrpc": "2.0", "id": request["id"],
          "method": "session/request_permission", "params": {
              "sessionId": SESSION, "toolCall": {"toolCallId": "tool-1"},
              "options": options}})
    reply = json.loads(sys.stdin.readline())
    expected = {"outcome": "cancelled"} if MODE == "permission-cancelled" else {
        "outcome": "selected", "optionId": "no"}
    assert reply["result"]["outcome"] == expected
    # Unsupported filesystem/terminal/extension requests must all be refused.
    for method in ("fs/write_text_file", "terminal/create", "extension/unknown"):
        emit({"jsonrpc": "2.0", "id": method, "method": method, "params": {}})
        reply = json.loads(sys.stdin.readline())
        assert reply["id"] == method
        assert reply["error"]["code"] == -32601
    text("denied")
    result(request, {"stopReason": "end_turn"})


def bad_prompt(request):
    if MODE == "rpc-error":
        emit({"jsonrpc": "2.0", "id": request["id"], "error": {
            "code": -32001, "message": "SECRET_AGENT_CREDENTIAL", "data": os.environ.copy()}})
    elif MODE == "invalid-json":
        print('{"broken":', flush=True)
    elif MODE == "invalid-number":
        print('{"jsonrpc":"2.0","id":01,"result":{}}', flush=True)
    elif MODE == "invalid-control":
        sys.stdout.buffer.write(b'{"jsonrpc":"2.0","id":1,"result":"bad\x01text"}\n')
        sys.stdout.buffer.flush()
    elif MODE == "invalid-utf8":
        sys.stdout.buffer.write(b'\xff\n')
        sys.stdout.buffer.flush()
    elif MODE == "invalid-protocol":
        emit({"jsonrpc": "1.0", "id": request["id"], "result": {}})
    elif MODE == "unknown-id":
        emit({"jsonrpc": "2.0", "id": 9999, "result": {}})
    elif MODE == "ambiguous-response":
        emit({"jsonrpc": "2.0", "id": request["id"], "result": {},
              "error": {"code": -1, "message": "secret"}})
    elif MODE == "invalid-stop":
        result(request, {})
    elif MODE == "eof":
        os.close(1)
        time.sleep(30)
    elif MODE == "partial-eof":
        sys.stdout.buffer.write(b'{"jsonrpc":"2.0"')
        sys.stdout.buffer.flush()
        os.close(1)
        time.sleep(30)
    elif MODE == "exit125":
        sys.exit(125)
    elif MODE == "exit7":
        sys.stderr.write("SECRET_AGENT_CREDENTIAL")
        sys.exit(7)
    elif MODE == "timeout":
        time.sleep(30)
    elif MODE == "stdout-limit":
        sys.stdout.buffer.write(b"x" * (1024 * 1024 + 1))
        sys.stdout.buffer.flush()
    elif MODE == "stderr-limit":
        sys.stderr.buffer.write(b"x" * (1024 * 1024 + 1))
        sys.stderr.buffer.flush()
    elif MODE == "combined-limit":
        sys.stderr.buffer.write(b"x" * (600 * 1024))
        sys.stderr.buffer.flush()
        text("y" * (600 * 1024))
    elif MODE == "callback-flood":
        for i in range(80):
            text(str(i))
        result(request, {"stopReason": "end_turn"})
    else:
        return False
    return True


for line in sys.stdin:
    request = json.loads(line)
    method = request.get("method")
    params = request.get("params", {})
    if method == "initialize":
        assert params["protocolVersion"] == 1
        assert params["clientCapabilities"].get("terminal", False) is False
        assert not any(params["clientCapabilities"].get("fs", {}).values())
        if MODE == "init-exit125":
            sys.exit(125)
        if MODE == "blocked-input":
            result(request, {"protocolVersion": 1, "agentCapabilities": {}})
            time.sleep(30)
        if MODE == "tree":
            child = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(30)"])
            with open(sys.argv[2], "w") as pid_file:
                pid_file.write(str(child.pid))
        version = 2 if MODE == "wrong-version" else 1
        result(request, {"protocolVersion": version, "agentCapabilities": {
            "loadSession": MODE != "no-load"}})
    elif method == "session/new":
        check_session(params)
        if MODE == "invalid-session":
            result(request, {"sessionId": None})
        else:
            text("startup message")
            result(request, {"sessionId": SESSION, **config_payload()})
    elif method == "session/load":
        check_session(params)
        assert params["sessionId"] == SESSION
        text("historical replay")
        result(request, config_payload())
    elif method == "session/cancel":
        assert "id" not in request
        assert params["sessionId"] == SESSION
        if waiting:
            text("cancelled")
            result(waiting, {"stopReason": "cancelled"})
            waiting = None
    elif method == "session/set_config_option":
        assert params["sessionId"] == SESSION
        found = next((item for item in config if item["id"] == params["configId"]), None)
        assert found is not None
        assert params["value"] in {choice["value"] for choice in found["options"]}
        found["currentValue"] = params["value"]
        result(request, config_payload())
    elif method == "session/prompt":
        assert params["sessionId"] == SESSION
        assert len(params["prompt"]) == 1
        assert params["prompt"][0]["type"] == "text"
        markdown = params["prompt"][0]["text"]
        prompts += 1
        if MODE in ("reject_once", "reject_always", "permission-cancelled"):
            permission(request)
        elif MODE == "cancel":
            waiting = request
            text("started")
        elif bad_prompt(request):
            pass
        else:
            if MODE == "stderr-per-round":
                sys.stderr.buffer.write(b"z" * (600 * 1024))
                sys.stderr.buffer.flush()
            if MODE == "launch":
                text(json.dumps({"cwd": os.getcwd(), "value": os.environ["ACP_TEST_VALUE"],
                                 "removed": os.environ.get("ACP_TEST_REMOVE"), "argv": sys.argv[2:]}))
            else:
                update("agent_thought_chunk", content={"type": "text", "text": "private thought"})
                text("回答🙂 ")
                update("tool_call", toolCallId="tool-1", title="Read", status="in_progress")
                emit({"jsonrpc": "2.0", "method": "extension/notice", "params": {}})
                update("tool_call_update", toolCallId="tool-1", status="completed")
                text(f"{prompts}:{markdown}")
            result(request, {"stopReason": "end_turn"})
