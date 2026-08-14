package com.bycn.edoc.api;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Le filtre protege /api/**, et uniquement lui. */
class ApiKeyFilterTest {

    private static final String KEY = "cle-de-test";

    private static MockHttpServletRequest request(String uri, String providedKey) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.setRequestURI(uri);
        if (providedKey != null) {
            request.addHeader(ApiKeyFilter.HEADER, providedKey);
        }
        return request;
    }

    /** @return true si la requete a traverse le filtre */
    private static boolean passesThrough(ApiKeyFilter filter, MockHttpServletRequest request,
                                         MockHttpServletResponse response) throws Exception {
        FilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        return response.getStatus() != HttpServletResponse.SC_UNAUTHORIZED;
    }

    private static ApiKeyFilter filterWithKey(String key) {
        return new ApiKeyFilter(new ApiProperties(key, true));
    }

    @Test
    void the_right_key_is_let_through() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(passesThrough(filterWithKey(KEY), request("/api/v1/extractions", KEY), response)).isTrue();
    }

    @Test
    void a_wrong_key_is_rejected() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(passesThrough(filterWithKey(KEY), request("/api/v1/extractions", "mauvaise"), response))
                .isFalse();
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
    }

    @Test
    void a_missing_key_is_rejected() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(passesThrough(filterWithKey(KEY), request("/api/v1/extractions", null), response))
                .isFalse();
    }

    @Test
    void no_configured_key_means_no_authentication_local_development_only() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(passesThrough(filterWithKey(""), request("/api/v1/extractions", null), response)).isTrue();
    }

    @Test
    void non_api_routes_are_not_filtered_at_all() {
        // shouldNotFilter evite d'exiger une cle sur, par exemple, un futur point de sante.
        ApiKeyFilter filter = filterWithKey(KEY);

        assertThat(filter.shouldNotFilter(request("/actuator/health", null))).isTrue();
        assertThat(filter.shouldNotFilter(request("/api/v1/extractions", null))).isFalse();
    }
}
