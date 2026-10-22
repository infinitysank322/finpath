package com.finpath.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.InternalAuthenticationServiceException;

/** How a failure inside Spring's redirect filter is answered: 400, 404 or a fixed 500, never a message. */
@ExtendWith(OutputCaptureExtension.class)
class SignInStartFailureHandlerTest {

    private MockHttpServletResponse fail(Throwable cause) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        ReturnUrlAuthorizationRequestResolver.failureHandler().onAuthenticationFailure(
                new MockHttpServletRequest(), response, new InternalAuthenticationServiceException("wrapped", cause));
        return response;
    }

    @Test
    void rejectedParameterIs400WithItsCode() throws Exception {
        MockHttpServletResponse response = fail(new PendingLogin.Rejected("invalid_redirect"));
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString()).isEqualTo("{\"status\":400,\"error\":\"invalid_redirect\"}");
    }

    @Test
    void unknownRegistrationIs404() throws Exception {
        MockHttpServletResponse response = fail(new ReturnUrlAuthorizationRequestResolver.UnknownRegistration());
        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(response.getContentAsString()).isEqualTo("{\"status\":404,\"error\":\"not_found\"}");
    }

    @Test
    void anyOtherFailureIsAFixed500WithoutTheMessage(CapturedOutput output) throws Exception {
        MockHttpServletResponse response = fail(new IllegalArgumentException("secret detail a@example.com"));
        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.getContentAsString()).isEqualTo("{\"status\":500,\"error\":\"server_error\"}");
        assertThat(output.getAll()).contains("IllegalArgumentException").doesNotContain("secret detail");
    }
}
