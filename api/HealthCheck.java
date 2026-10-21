import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Container HEALTHCHECK (no curl/wget in the JRE image). Exit 0 only if /actuator/health/liveness is 200 (no DB check, so it never wakes Neon). Reads PORT, which must match server.port. */
public class HealthCheck {
    public static void main(String[] args) throws Exception {
        String port = System.getenv().getOrDefault("PORT", "8080");
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/actuator/health/liveness"))
                .timeout(Duration.ofSeconds(3)).build();
        HttpResponse<Void> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding());
        System.exit(response.statusCode() == 200 ? 0 : 1);
    }
}
