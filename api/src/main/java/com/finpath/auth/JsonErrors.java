package com.finpath.auth;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;

/** Fixed, minimal JSON error bodies for the security layer: a code, never a message or stack trace. */
final class JsonErrors {

    private JsonErrors() {
    }

    /** {@code error} must be a fixed snake_case code, never user input. */
    static void write(HttpServletResponse response, int status, String error) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"status\":" + status + ",\"error\":\"" + error + "\"}");
        response.getWriter().flush();
    }
}
