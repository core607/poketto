package io.github.core607.poketto.mcp.internal;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.http.HttpServletResponse;

/** Completes aborted servlet streams; it owns no admission or image reservation. */
final class McpStreamCompletion implements AsyncListener {
    static AsyncContext watch(AsyncContext context) {
        context.addListener(new McpStreamCompletion());
        return context;
    }

    @Override
    public void onComplete(AsyncEvent event) {}

    @Override
    public void onTimeout(AsyncEvent event) {
        complete(event, 503);
    }

    @Override
    public void onError(AsyncEvent event) {
        complete(event, 500);
    }

    @Override
    public void onStartAsync(AsyncEvent event) {
        event.getAsyncContext().addListener(this);
    }

    private static void complete(AsyncEvent event, int status) {
        try {
            var response = (HttpServletResponse) event.getAsyncContext().getResponse();
            if (!response.isCommitted()) {
                response.setStatus(status);
            }
            // Spring's SSE builder ignores complete/error after sendFailed; finish the servlet directly.
            event.getAsyncContext().complete();
        } catch (IllegalStateException completed) {
            // A competing completion has already finished this request.
        }
    }
}
