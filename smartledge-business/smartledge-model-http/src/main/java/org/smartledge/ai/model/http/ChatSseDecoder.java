package org.smartledge.ai.model.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.smartledge.ai.rag.runtime.model.ChatResult;
import org.smartledge.ai.rag.runtime.model.ChatStreamEvent;
import org.smartledge.ai.rag.runtime.model.ModelCallException;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;

import static org.smartledge.ai.rag.runtime.model.ModelCallException.Kind.*;

/** Incremental SSE framing and a single-choice Chat Completions state machine. No business decisions. */
final class ChatSseDecoder {
    private final ObjectMapper mapper;
    private final ModelHttpSettings settings;
    private final Consumer<ChatStreamEvent> output;
    private final ByteArrayOutputStream line = new ByteArrayOutputStream();
    private final StringBuilder data = new StringBuilder();
    private final TreeMap<Integer, Tool> tools = new TreeMap<>();
    private String id, model, finish;
    private ChatResult.Usage usage;
    private int bytes, eventBytes;
    private boolean cr, done, hasText, firstLine = true;

    ChatSseDecoder(ObjectMapper mapper, ModelHttpSettings settings, Consumer<ChatStreamEvent> output) {
        this.mapper = mapper; this.settings = settings; this.output = output;
    }
    boolean done() { return done; }
    void accept(ByteBuffer buffer) {
        while (buffer.hasRemaining()) {
            int b = buffer.get() & 255;
            if (++bytes > settings.maxResponseBytes()) { throw error(LIMIT); }
            if (done) { continue; } // [DONE] is the transport terminator; never publish a second completion.
            if (cr) { cr = false; if (b == '\n') { continue; } }
            if (b == '\r' || b == '\n') { line(); cr = b == '\r'; }
            else {
                if (++eventBytes > Math.min(settings.maxResponseBytes(), 256 * 1024)) { throw error(LIMIT); }
                line.write(b);
            }
        }
    }
    void eof() {
        if (line.size() > 0) { line(); }
        if (!data.isEmpty()) { dispatch(); }
        if (!done) { throw error(INVALID_RESPONSE); }
    }
    private void line() {
        String value;
        try {
            value = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(line.toByteArray())).toString();
        } catch (java.nio.charset.CharacterCodingException e) { throw error(INVALID_RESPONSE); }
        line.reset();
        if (firstLine) { firstLine = false; if (value.startsWith("\uFEFF")) { value = value.substring(1); } }
        if (value.isEmpty()) { dispatch(); eventBytes = 0; return; }
        int colon = value.indexOf(':');
        String field = colon < 0 ? value : value.substring(0, colon);
        String content = colon < 0 ? "" : value.substring(colon + 1);
        if (content.startsWith(" ")) { content = content.substring(1); }
        if (field.equals("data")) { data.append(content).append('\n'); }
    }
    private void dispatch() {
        if (data.isEmpty()) { return; }
        String payload = data.substring(0, data.length() - 1); data.setLength(0);
        if (payload.equals("[DONE]")) {
            if (finish == null || id == null || model == null) { throw error(INVALID_RESPONSE); }
            List<ChatResult.ToolCall> calls = completeTools();
            if (!tools.isEmpty() && !finish.equals("tool_calls")) { throw error(INVALID_RESPONSE); }
            if (finish.equals("tool_calls") && tools.isEmpty()) { throw error(INVALID_RESPONSE); }
            if (finish.equals("stop") && !hasText) { throw error(INVALID_RESPONSE); }
            done = true;
            output.accept(new ChatStreamEvent("", "", null, facts(calls), true));
            return;
        }
        try {
            JsonNode root = mapper.readTree(payload);
            if (root == null || !root.isObject() || root.hasNonNull("error")) { throw error(INVALID_RESPONSE); }
            id = identity(root, "id", id); model = identity(root, "model", model);
            JsonNode u = root.get("usage");
            if (u != null && !u.isNull()) {
                if (!u.isObject()) { throw error(INVALID_RESPONSE); }
                usage = new ChatResult.Usage(token(u, "prompt_tokens", usage == null ? null : usage.promptTokens()),
                    token(u, "completion_tokens", usage == null ? null : usage.completionTokens()),
                    token(u, "total_tokens", usage == null ? null : usage.totalTokens()));
            }
            JsonNode choices = root.get("choices");
            if (choices == null || !choices.isArray() || choices.size() > 1) { throw error(INVALID_RESPONSE); }
            if (choices.isEmpty()) {
                if (u == null || u.isNull()) { throw error(INVALID_RESPONSE); }
                output.accept(new ChatStreamEvent("", "", null, facts(List.of()), false)); return;
            }
            JsonNode choice = choices.get(0);
            if (!choice.path("index").isIntegralNumber() || !choice.path("index").canConvertToInt()
                || choice.path("index").intValue() != 0) { throw error(INVALID_RESPONSE); }
            JsonNode delta = choice.get("delta");
            if (delta == null || !delta.isObject()) { throw error(INVALID_RESPONSE); }
            String text = string(delta, "content"), reasoning = string(delta, "reasoning_content");
            JsonNode toolCalls = delta.get("tool_calls");
            boolean toolContent = toolCalls != null && !toolCalls.isNull() && !toolCalls.isEmpty();
            if (finish != null && (!text.isEmpty() || !reasoning.isEmpty() || toolContent)) { throw error(INVALID_RESPONSE); }
            String reason = string(choice, "finish_reason");
            if (!reason.isEmpty()) {
                if (!Set.of("stop", "length", "content_filter", "tool_calls").contains(reason)
                    || (finish != null && !finish.equals(reason))) { throw error(INVALID_RESPONSE); }
                finish = reason;
            }
            hasText |= !text.isEmpty();
            output.accept(new ChatStreamEvent(text, reasoning, null, facts(List.of()), false));
            if (toolCalls != null && !toolCalls.isNull()) {
                if (!toolCalls.isArray()) { throw error(INVALID_RESPONSE); }
                for (JsonNode call : toolCalls) { tool(call); }
            }
        } catch (java.io.IOException | IllegalArgumentException e) { throw error(INVALID_RESPONSE); }
    }
    private void tool(JsonNode call) {
        JsonNode indexNode = call.get("index");
        if (indexNode == null || !indexNode.isIntegralNumber() || !indexNode.canConvertToInt()
            || indexNode.intValue() < 0 || indexNode.intValue() >= 128) { throw error(INVALID_RESPONSE); }
        int index = indexNode.intValue();
        Tool tool = tools.computeIfAbsent(index, ignored -> new Tool());
        String callId = string(call, "id"), type = string(call, "type");
        if (!type.isEmpty() && !type.equals("function")) { throw error(INVALID_RESPONSE); }
        if (!callId.isEmpty()) {
            if (tool.id != null && !tool.id.equals(callId)) { throw error(INVALID_RESPONSE); }
            for (var entry : tools.entrySet()) {
                if (entry.getKey() != index && callId.equals(entry.getValue().id)) { throw error(INVALID_RESPONSE); }
            }
            tool.id = callId;
        }
        JsonNode function = call.get("function");
        if (function != null && !function.isObject()) { throw error(INVALID_RESPONSE); }
        String name = function == null ? "" : string(function, "name");
        String args = function == null ? "" : string(function, "arguments");
        if (!name.isEmpty()) {
            if (tool.name != null && !tool.name.equals(name)) { throw error(INVALID_RESPONSE); }
            tool.name = name;
        }
        tool.bytes += args.getBytes(StandardCharsets.UTF_8).length;
        if (tool.bytes > settings.maxToolArgumentBytes()) { throw error(LIMIT); }
        tool.args.append(args);
        output.accept(new ChatStreamEvent("", "", new ChatStreamEvent.ToolDelta(index, callId, name, args), facts(List.of()), false));
    }
    private List<ChatResult.ToolCall> completeTools() {
        List<ChatResult.ToolCall> result = new ArrayList<>();
        for (var entry : tools.entrySet()) {
            Tool tool = entry.getValue();
            if (entry.getKey() != result.size() || tool.id == null || tool.name == null) { throw error(INVALID_RESPONSE); }
            if (tool.args.toString().getBytes(StandardCharsets.UTF_8).length > settings.maxToolArgumentBytes()) { throw error(LIMIT); }
            // Complete raw arguments belong to the registered tool's schema/fallback policy.
            // Transport validates framing, identity and byte limits, never tool business parameters.
            result.add(new ChatResult.ToolCall(tool.id, tool.name, tool.args.toString()));
        }
        return result;
    }
    private ChatResult facts(List<ChatResult.ToolCall> calls) { return new ChatResult("", id, model, finish, usage, calls); }
    private static String string(JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value == null || value.isNull()) { return ""; }
        if (!value.isTextual()) { throw error(INVALID_RESPONSE); }
        return value.textValue();
    }
    private static String identity(JsonNode node, String key, String previous) {
        String value = string(node, key);
        if (value.isBlank() || previous != null && !previous.equals(value)) { throw error(INVALID_RESPONSE); }
        return value;
    }
    private static Integer token(JsonNode node, String key, Integer previous) {
        JsonNode value = node.get(key);
        if (value == null || value.isNull()) { return previous; }
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0) { throw error(INVALID_RESPONSE); }
        return value.intValue();
    }
    private static ModelCallException error(ModelCallException.Kind kind) { return new ModelCallException(kind, 200, "chat-sse"); }
    private static final class Tool { String id, name; int bytes; final StringBuilder args = new StringBuilder(); }
}
