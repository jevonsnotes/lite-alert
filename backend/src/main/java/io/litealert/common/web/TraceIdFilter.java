package io.litealert.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

import static io.litealert.common.util.IdGenerator.traceId;

/**
 * Assigns or honors an {@code X-Trace-Id} header for every request.
 * Trace id is mirrored to MDC for log correlation and exposed back to clients
 * in the response header — they can quote it when reporting issues.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Trace-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String incoming = request.getHeader(HEADER);
        String id = (incoming == null || incoming.isBlank()) ? traceId() : incoming;
        TraceIdHolder.set(id);
        MDC.put("traceId", id);
        response.setHeader(HEADER, id);
        try {
            chain.doFilter(request, response);
        } finally {
            TraceIdHolder.clear();
            MDC.remove("traceId");
        }
    }
}
