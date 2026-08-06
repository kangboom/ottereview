package com.ssafy.ottereview.ai.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AiThreadTraceFilter extends OncePerRequestFilter {

    public static final String TRACE_ID_ATTRIBUTE =
            AiThreadTraceFilter.class.getName() + ".TRACE_ID";
    private static final String TRACE_HEADER = "X-AI-Thread-Trace-Id";
    private static final String ANALYZE_ALL_PATH = "/api/ai/all";

    public static String getTraceId(HttpServletRequest request) {
        Object traceId = request.getAttribute(TRACE_ID_ATTRIBUTE);
        if (traceId instanceof String value) {
            return value;
        }

        String generatedTraceId = UUID.randomUUID().toString().substring(0, 8);
        request.setAttribute(TRACE_ID_ATTRIBUTE, generatedTraceId);
        return generatedTraceId;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !ANALYZE_ALL_PATH.equals(request.getRequestURI());
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String traceId = getTraceId(request);
        response.setHeader(TRACE_HEADER, traceId);

        logThread(traceId, "servlet-dispatch-start", request);
        try {
            filterChain.doFilter(request, response);
        } finally {
            log.info(
                    "[AI_THREAD_TRACE] traceId={} stage=servlet-dispatch-end "
                            + "dispatcher={} asyncStarted={} thread={}",
                    traceId,
                    request.getDispatcherType(),
                    request.isAsyncStarted(),
                    Thread.currentThread().getName());
        }
    }

    private void logThread(String traceId, String stage, HttpServletRequest request) {
        log.info("[AI_THREAD_TRACE] traceId={} stage={} dispatcher={} thread={}",
                traceId, stage, request.getDispatcherType(), Thread.currentThread().getName());
    }
}
