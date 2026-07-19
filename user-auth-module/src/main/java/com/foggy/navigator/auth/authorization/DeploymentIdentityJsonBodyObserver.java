package com.foggy.navigator.auth.authorization;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.async.ByteArrayFeeder;
import com.foggy.navigator.common.authorization.DeploymentIdentityResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.util.Locale;

/**
 * Observer-only JSON field-name detector for client attempts to supply the
 * server-owned deployment identity. It never reads a token value, buffers the
 * raw request body, or changes the stream returned to the legacy converter.
 */
@ControllerAdvice(annotations = Controller.class)
public class DeploymentIdentityJsonBodyObserver extends RequestBodyAdviceAdapter {

    private static final JsonFactory JSON_FACTORY = new JsonFactory();

    @Override
    public boolean supports(MethodParameter methodParameter,
                            Type targetType,
                            Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public HttpInputMessage beforeBodyRead(HttpInputMessage inputMessage,
                                           MethodParameter parameter,
                                           Type targetType,
                                           Class<? extends HttpMessageConverter<?>> converterType) {
        try {
            HttpServletRequest request = currentRequest();
            if (!isJson(inputMessage.getHeaders().getContentType())
                    || request == null
                    || Boolean.TRUE.equals(request.getAttribute(
                    LegacyAuthorizationContextAdapter.DEPLOYMENT_IDENTITY_OVERRIDE_ATTRIBUTE))) {
                return inputMessage;
            }
            return new ObservingHttpInputMessage(inputMessage, request);
        } catch (RuntimeException ignored) {
            // A pre-converter observer failure must not alter legacy body handling.
            return inputMessage;
        }
    }

    private static HttpServletRequest currentRequest() {
        Object requestAttributes = RequestContextHolder.getRequestAttributes();
        if (requestAttributes instanceof ServletRequestAttributes servletRequestAttributes) {
            return servletRequestAttributes.getRequest();
        }
        return null;
    }

    private static boolean isJson(MediaType contentType) {
        if (contentType == null || !"application".equalsIgnoreCase(contentType.getType())) {
            return false;
        }
        String subtype = contentType.getSubtype().toLowerCase(Locale.ROOT);
        return "json".equals(subtype) || subtype.endsWith("+json");
    }

    private static final class ObservingHttpInputMessage implements HttpInputMessage {

        private final HttpInputMessage delegate;
        private final HttpServletRequest request;
        private InputStream observingBody;

        private ObservingHttpInputMessage(HttpInputMessage delegate, HttpServletRequest request) {
            this.delegate = delegate;
            this.request = request;
        }

        @Override
        public HttpHeaders getHeaders() {
            return delegate.getHeaders();
        }

        @Override
        public InputStream getBody() throws IOException {
            if (observingBody == null) {
                InputStream delegateBody = delegate.getBody();
                try {
                    observingBody = new JsonFieldNameObservingInputStream(delegateBody, request);
                } catch (IOException | RuntimeException ignored) {
                    // An observer setup failure must be indistinguishable from no observer.
                    observingBody = delegateBody;
                }
            }
            return observingBody;
        }
    }

    private static final class JsonFieldNameObservingInputStream extends FilterInputStream {

        private HttpServletRequest request;
        private JsonParser parser;
        private ByteArrayFeeder feeder;
        private boolean observerActive = true;

        private JsonFieldNameObservingInputStream(InputStream delegate, HttpServletRequest request)
                throws IOException {
            super(delegate);
            this.request = request;
            this.parser = JSON_FACTORY.createNonBlockingByteArrayParser();
            this.feeder = (ByteArrayFeeder) parser.getNonBlockingInputFeeder();
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value < 0) {
                finishObservation();
            } else {
                observe(new byte[]{(byte) value}, 0, 1);
            }
            return value;
        }

        @Override
        public int read(byte[] bytes) throws IOException {
            return read(bytes, 0, bytes.length);
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            int count = super.read(bytes, offset, length);
            if (count < 0) {
                finishObservation();
            } else if (count > 0) {
                observe(bytes, offset, count);
            }
            return count;
        }

        @Override
        public void close() throws IOException {
            finishObservation();
            super.close();
        }

        private void observe(byte[] bytes, int offset, int length) {
            if (!observerActive) {
                return;
            }
            try {
                drainAvailableTokens();
                if (!observerActive || feeder == null || !feeder.needMoreInput()) {
                    return;
                }
                feeder.feedInput(bytes, offset, offset + length);
                drainAvailableTokens();
            } catch (IOException | RuntimeException ignored) {
                closeObserver();
            }
        }

        private void finishObservation() {
            if (!observerActive) {
                return;
            }
            try {
                feeder.endOfInput();
                drainAvailableTokens();
            } catch (RuntimeException | IOException ignored) {
                // Malformed JSON remains the legacy converter's concern.
            } finally {
                closeObserver();
            }
        }

        private void drainAvailableTokens() throws IOException {
            JsonToken token;
            while (observerActive && (token = parser.nextToken()) != null && token != JsonToken.NOT_AVAILABLE) {
                if (token == JsonToken.FIELD_NAME
                        && DeploymentIdentityResolver.isServerOwnedIdentityOverrideAttempt(parser.currentName())) {
                    request.setAttribute(LegacyAuthorizationContextAdapter.DEPLOYMENT_IDENTITY_OVERRIDE_ATTRIBUTE,
                            Boolean.TRUE);
                    closeObserver();
                }
            }
        }

        private void closeObserver() {
            observerActive = false;
            request = null;
            feeder = null;
            if (parser != null) {
                try {
                    parser.close();
                } catch (IOException ignored) {
                    // Observer cleanup must not affect the wrapped stream.
                }
                parser = null;
            }
        }
    }
}
