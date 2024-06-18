package me.sonam.auth.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.client.*;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * this token filter will be invoked automatically by the webclient for intercepting request
 * to add a access-token by making a client-credential flow http call.
 * Don't add it manually to a webclient to avoid getting calling twice.
 */
@Service
public class TokenFilter {
    private static final Logger LOG = LoggerFactory.getLogger(TokenFilter.class);

    @Value("${auth-server.root}${auth-server.oauth2token.path}")
    private String oauth2TokenEndpoint;

    @Value("${auth-server.oauth2token.grantType}")
    private String grantType;

    @Value("${user-rest-service.root}${user-rest-service.userByAuthId}")
    private String userByAuthIdEp;

    @Autowired
    private TokenRequestFilter tokenRequestFilter;

    private WebClient.Builder webClientBuilder;

    private RequestCache requestCache;
    @Value("${auth-server.oauth2token.issuerTokenPath:}")
    private String accessTokenPath;

    public TokenFilter(WebClient.Builder webClientBuilder, RequestCache requestCache) {
        this.webClientBuilder = webClientBuilder;
        this.requestCache = requestCache;
    }

    public ExchangeFilterFunction renewTokenFilter() {
        return (request, next) -> {
            LOG.info("request.path: {}", request.url().getPath());
            if (request.url().getPath().equals(accessTokenPath)) {
                LOG.debug("no need to request access token when going to that path: {}", request.url().getPath());
                ClientRequest clientRequest = ClientRequest.from(request).build();
                return next.exchange(clientRequest);
            }
            else {
                LOG.info("going thru jwt request ") ;
                for (TokenRequestFilter.RequestFilter requestFilter : tokenRequestFilter.getRequestFilters()) {
                    LOG.debug("jwt.out: {}", requestFilter.getOut());
                    String[] outMatches = requestFilter.getOut().split(",");

                    for (String outPath : outMatches) {
                        LOG.info("outPath: {}", outPath);
                        if (request.url().getPath().matches(outPath.trim())) {
                            LOG.info("path {} matches with outbound request matches: {}",
                                    outPath, request.url().getPath());
                            LOG.info("make a token request");

                            return getClientRequest(request, next, requestFilter, outPath);
                        }
                    }
                }

                LOG.info("no outbound path match found");
                LOG.info("printing bearer header: {}", request.headers().get(HttpHeaders.AUTHORIZATION));
                ClientRequest filtered = ClientRequest.from(request)
                        .build();
                return next.exchange(filtered);
            }
        };
    }

    private Mono<ClientResponse> getClientRequest(ClientRequest request, ExchangeFunction next, TokenRequestFilter.RequestFilter requestFilter, String outPath) {
        if (requestFilter.getAccessToken().getOption().equals(TokenRequestFilter.RequestFilter.AccessToken.JwtOption.request)) {
            return getAccessToken(oauth2TokenEndpoint.toString(), grantType, requestFilter.getAccessToken().getScopes(), requestFilter.getAccessToken().getBase64EncodedClientIdSecret())
                    .flatMap(accessToken -> {
                        LOG.info("got accessToken using client-credential: {}", accessToken);
                        ClientRequest clientRequest = ClientRequest.from(request)
                                .headers(headers -> {
                                    headers.set(HttpHeaders.ORIGIN, request.headers().getFirst(HttpHeaders.ORIGIN));
                                    headers.setBearerAuth(accessToken);
                                    LOG.info("added access-token to http header");
                                }).build();
                        return Mono.just(clientRequest);
                    }).flatMap(clientRequest -> next.exchange(clientRequest));
        }
        else {
            LOG.info("not going to request a token, forward the request with a Auth token");
            ClientRequest filtered = ClientRequest.from(request)
                    .build();
            return next.exchange(filtered);
        }
    }

    private Mono<String> getAccessToken(final String oauthEndpoint, String grantType, String scopes, final String base64EncodeClientIdSecret) {
        LOG.info("making a access-token request to endpoint: {}",oauthEndpoint);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("grant_type", grantType);

        if (scopes != null && !scopes.isEmpty()) {
            body.add("scope", scopes);
            LOG.info("added scope to body: {}", scopes);
        }
        else {
            LOG.info("scope is null, not adding to body");
        }

        LOG.info("add body payload for grant type and scopes: {}", body);

        WebClient.ResponseSpec responseSpec = webClientBuilder.build().post().uri(oauthEndpoint)
                .bodyValue(body)
                .headers(httpHeaders -> httpHeaders.setBasicAuth(base64EncodeClientIdSecret))
                .accept(MediaType.APPLICATION_JSON)
                .retrieve();

        return responseSpec.bodyToMono(Map.class).map(map -> {
            LOG.debug("response for '{}' is in map: {}", oauthEndpoint, map);
            if (map.get("access_token") != null) {
                return map.get("access_token").toString();
            }
            else {
                LOG.error("nothing to return");
                return "nothing";
            }
        }).onErrorResume(throwable -> {
            LOG.error("client credentials access token rest call failed: {}", throwable.getMessage());
            String errorMessage = throwable.getMessage();

            if (throwable instanceof WebClientResponseException) {
                WebClientResponseException webClientResponseException = (WebClientResponseException) throwable;
                LOG.error("error body contains: {}", webClientResponseException.getResponseBodyAsString());
                errorMessage = webClientResponseException.getResponseBodyAsString();
            }
            return Mono.error(new RuntimeException(errorMessage));
        });
    }
}