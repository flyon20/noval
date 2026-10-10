package com.novelanalyzer.sql;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class NginxSseRoutingTest {

    private static final Path REPOSITORY_ROOT = Path.of("..", "..").toAbsolutePath().normalize();
    private static final List<String> CONFIGURATIONS = List.of(
        "docker/nginx/default.conf.template",
        "tools/nginx/noval.cloudflare.conf.example",
        "tools/nginx/install-noval-nginx.sh"
    );
    private static final String RUN_PATH =
        "/api/knowledge/chat-runs/chatrun-00000000-0000-4000-8000-000000000001";
    private static final Pattern LOCATION = Pattern.compile(
        "(?ms)^ {4}location (?:(=|~\\*?|\\^~) )?(\\S+) \\{\\R(.*?)^ {4}\\}"
    );

    static Stream<Arguments> streamRequests() {
        return requests(List.of(
            "/api/knowledge/chat/stream",
            RUN_PATH + "/events/stream",
            RUN_PATH + "/events/stream?afterSequence=42",
            "/api/analysis/123/stream"
        ));
    }

    static Stream<Arguments> restRequests() {
        return requests(List.of(
            "/api/knowledge/chat",
            "/api/knowledge/chat/stream/extra",
            "/api/knowledge/chat-runs",
            RUN_PATH,
            RUN_PATH + "/events?afterSequence=42",
            RUN_PATH + "/cancel",
            RUN_PATH + "/events/stream/extra",
            "/api/knowledge/chat-runs//events/stream"
        ));
    }

    private static Stream<Arguments> requests(List<String> paths) {
        return CONFIGURATIONS.stream().flatMap(config -> paths.stream().map(path -> Arguments.of(config, path)));
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("streamRequests")
    void deliversStreamFramesWithoutProxyBuffering(String configuration, String requestUri) throws Exception {
        Location selected = selectedLocation(configuration, requestUri);

        assertThat(selected.body()).contains(
            "proxy_pass http://noval_backend;",
            "proxy_http_version 1.1;",
            "proxy_set_header Connection \"\";",
            "proxy_buffering off;",
            "proxy_request_buffering off;",
            "proxy_cache off;",
            "gzip off;",
            "proxy_read_timeout 3600s;",
            "proxy_send_timeout 3600s;"
        );
        if (requestUri.startsWith("/api/knowledge/")) {
            assertThat(selected.body()).contains("add_header X-Accel-Buffering no always;");
        }
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("restRequests")
    void keepsNonStreamingRequestsOnTheNormalApiRoute(String configuration, String requestUri) throws Exception {
        Location selected = selectedLocation(configuration, requestUri);

        assertThat(selected.selector()).isEqualTo("/api/");
        assertThat(selected.body()).contains("proxy_read_timeout 120s;").doesNotContain("proxy_buffering off;");
    }

    private static Location selectedLocation(String configuration, String requestUri) throws Exception {
        String source = Files.readString(REPOSITORY_ROOT.resolve(configuration), StandardCharsets.UTF_8);
        String path = requestUri.split("\\?", 2)[0];
        List<Location> locations = new ArrayList<>();
        Matcher matcher = LOCATION.matcher(source);
        while (matcher.find()) {
            locations.add(new Location(matcher.group(1), matcher.group(2), matcher.group(3)));
        }
        assertThat(locations).as("locations in %s", configuration).isNotEmpty();

        Location prefix = null;
        for (Location location : locations) {
            if ("=".equals(location.modifier()) && path.equals(location.selector())) {
                return location;
            }
            if ((location.modifier() == null || "^~".equals(location.modifier()))
                && path.startsWith(location.selector())
                && (prefix == null || location.selector().length() > prefix.selector().length())) {
                prefix = location;
            }
        }
        if (prefix != null && "^~".equals(prefix.modifier())) {
            return prefix;
        }
        for (Location location : locations) {
            if ("~".equals(location.modifier()) || "~*".equals(location.modifier())) {
                int flags = "~*".equals(location.modifier()) ? Pattern.CASE_INSENSITIVE : 0;
                if (Pattern.compile(location.selector(), flags).matcher(path).find()) {
                    return location;
                }
            }
        }
        assertThat(prefix).as("route for %s in %s", requestUri, configuration).isNotNull();
        return prefix;
    }

    private record Location(String modifier, String selector, String body) {
    }
}
