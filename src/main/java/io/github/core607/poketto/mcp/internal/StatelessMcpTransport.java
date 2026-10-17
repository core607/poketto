package io.github.core607.poketto.mcp.internal;

import io.github.core607.poketto.assets.ImageMemoryAdmission;
import io.github.core607.poketto.assets.ImageRequestScope;
import io.github.core607.poketto.auth.AuthException;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpStatelessServerHandler;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStatelessServerTransport;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

/**
 * Streamable HTTP without sessions: each POST is answered with one JSON response, and no
 * {@code Mcp-Session-Id} is issued, so there is no session table, idle expiry or orphaned session.
 * The SDK's stateless handler runs the tools; this transport adds what the SDK leaves to it:
 * cancellation by key and request ID, and image memory held until the response bytes are written.
 */
final class StatelessMcpTransport implements McpStatelessServerTransport {
    private static final Logger log = LoggerFactory.getLogger(StatelessMcpTransport.class);
    private static final int CLIENT_NAME_LENGTH = 64;
    private final McpJsonMapper mapper;
    private final McpIdentities identities;
    private final McpCalls calls;
    private final ImageMemoryAdmission memory;
    private final ObjectMapper json;
    private volatile McpStatelessServerHandler handler;
    private volatile boolean closing;

    StatelessMcpTransport(
            McpJsonMapper mapper,
            McpIdentities identities,
            McpCalls calls,
            ImageMemoryAdmission memory,
            ObjectMapper json) {
        this.mapper = mapper;
        this.identities = identities;
        this.calls = calls;
        this.memory = memory;
        this.json = json;
    }

    RouterFunction<ServerResponse> routerFunction() {
        return RouterFunctions.route()
                .POST("/mcp", this::post)
                .GET("/mcp", request -> notAllowed())
                .DELETE("/mcp", request -> notAllowed())
                .build();
    }

    @Override
    public void setMcpHandler(McpStatelessServerHandler handler) {
        this.handler = handler;
    }

    @Override
    public Mono<Void> closeGracefully() {
        return Mono.fromRunnable(() -> {
            closing = true;
            calls.close();
        });
    }

    private ServerResponse post(ServerRequest request) throws Exception {
        McpStatelessServerHandler current = handler;
        if (closing || current == null) {
            return ServerResponse.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        var accept = request.headers().asHttpHeaders().getAccept();
        if (!accept.contains(MediaType.APPLICATION_JSON) || !accept.contains(MediaType.TEXT_EVENT_STREAM)) {
            return ServerResponse.badRequest().build();
        }
        McpIdentities.Identity identity;
        try {
            identity = identities.currentIdentity();
        } catch (SecurityException | AuthException denied) {
            return problem(HttpStatus.FORBIDDEN, "MCP credential unavailable");
        }
        McpSchema.JSONRPCMessage message;
        try {
            message = McpSchema.deserializeJsonRpcMessage(mapper, request.body(String.class));
        } catch (RuntimeException invalid) {
            return error(HttpStatus.BAD_REQUEST, null, McpSchema.ErrorCodes.INVALID_REQUEST, "Invalid message format");
        }
        var context = new HashMap<String, Object>();
        context.put(McpIdentities.IDENTITY_CONTEXT, identity);
        context.put(McpIdentities.CLIENT_CONTEXT, client(request));
        if (message instanceof McpSchema.JSONRPCNotification notification) {
            notify(current, context, identity, notification);
            return ServerResponse.accepted().build();
        }
        if (!(message instanceof McpSchema.JSONRPCRequest call)) {
            return error(
                    HttpStatus.BAD_REQUEST,
                    null,
                    McpSchema.ErrorCodes.INVALID_REQUEST,
                    "The server accepts requests and notifications");
        }
        if (!call.method().equals("tools/call")) {
            return respond(current, context, call, null);
        }
        String key = McpCalls.requestKey(call.id());
        McpCancellation cancellation = key == null ? null : calls.start(identity, key);
        if (cancellation == null) {
            return error(HttpStatus.OK, call.id(), -32600, "MCP request id unavailable");
        }
        try {
            context.put(McpCancellation.CONTEXT_KEY, cancellation);
            return toolCall(current, context, call);
        } finally {
            calls.finish(identity, key, cancellation);
        }
    }

    private ServerResponse toolCall(
            McpStatelessServerHandler current, Map<String, Object> context, McpSchema.JSONRPCRequest call)
            throws Exception {
        String tool = imageTool(call);
        if (tool == null) {
            return respond(current, context, call, null);
        }
        ImageRequestScope scope = memory.acquire(ImageMemoryAdmission.MCP_BYTES).orElse(null);
        if (scope == null) {
            McpSchema.CallToolResult refusal = McpToolOutcomes.recorded(
                    json,
                    tool,
                    () -> McpToolOutcomes.failure(
                            json, "UNAVAILABLE", "IMAGE_MEMORY_BUSY", "Image memory is busy; retry this read later."));
            return written(mapper.writeValueAsString(McpSchema.JSONRPCResponse.result(call.id(), refusal)), null);
        }
        context.put(ImageRequestScope.ATTRIBUTE, scope);
        try {
            return respond(current, context, call, scope);
        } catch (Exception | Error failure) {
            scope.responseComplete();
            throw failure;
        }
    }

    private ServerResponse respond(
            McpStatelessServerHandler current,
            Map<String, Object> context,
            McpSchema.JSONRPCRequest call,
            ImageRequestScope scope)
            throws Exception {
        var transportContext = McpTransportContext.create(context);
        McpSchema.JSONRPCResponse response;
        try {
            response = current.handleRequest(transportContext, call)
                    .contextWrite(reactor -> reactor.put(McpTransportContext.KEY, transportContext))
                    .block();
        } catch (RuntimeException failure) {
            if (scope != null) {
                scope.responseComplete();
            }
            if (failure instanceof McpError refused && refused.getJsonRpcError() != null) {
                return written(
                        mapper.writeValueAsString(
                                McpSchema.JSONRPCResponse.error(call.id(), refused.getJsonRpcError())),
                        null);
            }
            log.error("mcp request failed method={}", call.method());
            return error(HttpStatus.OK, call.id(), McpSchema.ErrorCodes.INTERNAL_ERROR, "Request failed");
        }
        String body;
        if (scope == null) {
            body = mapper.writeValueAsString(response);
        } else {
            try (var producer = scope.producer()) {
                body = mapper.writeValueAsString(response);
            }
        }
        return written(body, scope);
    }

    /** Holds an image reservation's producer across the blocking write of its response bytes. */
    private static ServerResponse written(String body, ImageRequestScope scope) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).build((request, response) -> {
            try {
                response.setContentLength(bytes.length);
                if (scope == null) {
                    response.getOutputStream().write(bytes);
                } else {
                    try (var producer = scope.producer()) {
                        response.getOutputStream().write(bytes);
                    }
                }
                return null;
            } finally {
                if (scope != null) {
                    scope.responseComplete();
                }
            }
        });
    }

    private void notify(
            McpStatelessServerHandler current,
            Map<String, Object> context,
            McpIdentities.Identity identity,
            McpSchema.JSONRPCNotification notification) {
        if (notification.method().equals("notifications/cancelled")) {
            if (notification.params() instanceof Map<?, ?> params) {
                String key = McpCalls.requestKey(params.get("requestId"));
                if (key != null) {
                    calls.cancel(identity, key);
                }
            }
            return;
        }
        var transportContext = McpTransportContext.create(context);
        try {
            current.handleNotification(transportContext, notification)
                    .contextWrite(reactor -> reactor.put(McpTransportContext.KEY, transportContext))
                    .block();
        } catch (RuntimeException failure) {
            log.warn("mcp notification failed method={}", notification.method());
        }
    }

    private static String imageTool(McpSchema.JSONRPCRequest request) {
        if (request.params() instanceof Map<?, ?> params && params.get("name") instanceof String tool) {
            if (tool.equals("get_asset") || tool.equals("get_artifact")) {
                return tool;
            }
        }
        return null;
    }

    /** The first product token of the User-Agent, without controls or surrogates, bounded in length. */
    static String client(ServerRequest request) {
        String agent = request.headers().firstHeader("User-Agent");
        if (agent == null || agent.isBlank()) {
            return "";
        }
        var result = new StringBuilder();
        agent.strip()
                .split("\\s+", 2)[0]
                .codePoints()
                .filter(code -> !Character.isISOControl(code))
                .filter(code -> code < 0xd800 || code > 0xdfff)
                .limit(CLIENT_NAME_LENGTH)
                .forEach(result::appendCodePoint);
        return result.toString();
    }

    private ServerResponse error(HttpStatus status, Object id, int code, String message) throws Exception {
        var error = new McpSchema.JSONRPCResponse.JSONRPCError(code, message, null);
        String body = mapper.writeValueAsString(McpSchema.JSONRPCResponse.error(id, error));
        return ServerResponse.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Cache-Control", "no-store")
                .body(body);
    }

    private static ServerResponse problem(HttpStatus status, String title) {
        return ServerResponse.status(status)
                .header("Cache-Control", "no-store")
                .body(Map.of("type", "about:blank", "title", title, "status", status.value()));
    }

    private static ServerResponse notAllowed() {
        return ServerResponse.status(HttpStatus.METHOD_NOT_ALLOWED)
                .header("Allow", "POST")
                .build();
    }
}
