package io.github.core607.poketto.mcp.internal;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.core607.poketto.assets.ImageMemoryAdmission;
import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.workspace.WorkspaceId;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpStatelessServerHandler;
import io.modelcontextprotocol.spec.McpSchema;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.function.BiFunction;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Drives the stateless transport's router as a servlet would, one POST per call. */
final class McpTransportHarness {
    private static final List<HttpMessageConverter<?>> CONVERTERS =
            List.of(new StringHttpMessageConverter(StandardCharsets.UTF_8), new JacksonJsonHttpMessageConverter());
    final McpIdentities identities = mock(McpIdentities.class);
    final McpCalls calls = new McpCalls();
    final StatelessMcpTransport transport;
    final McpIdentities.Identity identity;

    McpTransportHarness(ImageMemoryAdmission memory, McpStatelessServerHandler handler) {
        var principal = mock(AuthPrincipal.class);
        when(principal.subjectId()).thenReturn(UUID.randomUUID());
        when(principal.accountId()).thenReturn(UUID.randomUUID());
        identity = new McpIdentities.Identity(principal, WorkspaceId.random());
        when(identities.currentIdentity()).thenReturn(identity);
        transport = new StatelessMcpTransport(
                new JacksonMcpJsonMapper(JsonMapper.builder().build()), identities, calls, memory, new ObjectMapper());
        transport.setMcpHandler(handler);
    }

    /** The routed response before it is written, so a test can observe what it still holds. */
    ServerResponse route(String body) throws Exception {
        var servlet = new MockHttpServletRequest("POST", "/mcp");
        servlet.addHeader("Accept", "application/json, text/event-stream");
        servlet.setContentType("application/json");
        servlet.setContent(body.getBytes(StandardCharsets.UTF_8));
        var request = ServerRequest.create(servlet, CONVERTERS);
        return transport.routerFunction().route(request).orElseThrow().handle(request);
    }

    static MockHttpServletResponse write(ServerResponse response) throws Exception {
        var output = new MockHttpServletResponse();
        response.writeTo(new MockHttpServletRequest(), output, () -> CONVERTERS);
        return output;
    }

    MockHttpServletResponse post(String body) throws Exception {
        return write(route(body));
    }

    static McpStatelessServerHandler handler(
            BiFunction<McpTransportContext, McpSchema.JSONRPCRequest, McpSchema.JSONRPCResponse> requests) {
        return new McpStatelessServerHandler() {
            @Override
            public Mono<McpSchema.JSONRPCResponse> handleRequest(
                    McpTransportContext context, McpSchema.JSONRPCRequest request) {
                return Mono.fromCallable(() -> requests.apply(context, request));
            }

            @Override
            public Mono<Void> handleNotification(
                    McpTransportContext context, McpSchema.JSONRPCNotification notification) {
                return Mono.empty();
            }
        };
    }

    static String toolCall(Object id, String tool) {
        String encoded = id instanceof String text ? "\"" + text + "\"" : String.valueOf(id);
        return "{\"jsonrpc\":\"2.0\",\"method\":\"tools/call\",\"id\":" + encoded + ",\"params\":{\"name\":\"" + tool
                + "\",\"arguments\":{}}}";
    }

    static String cancelled(Object id) {
        String encoded = id instanceof String text ? "\"" + text + "\"" : String.valueOf(id);
        return "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\",\"params\":{\"requestId\":" + encoded
                + "}}";
    }
}
