package hudson.plugins.build_publisher;

import org.apache.commons.httpclient.HttpMethod;
import org.apache.commons.httpclient.URIException;

import java.io.IOException;

/**
 * Indicates an error on the server.
 *
 * <p>
 * The point of this exception type is to capture the failed {@link HttpMethod},
 * so that we can later record the error message on the server, which is often
 * crucial in diagnosing a problem.
 *
 * @author Kohsuke Kawaguchi
 */
public class ServerFailureException extends IOException {
    private final HttpMethod method;

    public ServerFailureException(HttpMethod method, String message, Throwable cause) {
        super(message, cause);
        this.method = method;
    }

    public ServerFailureException(HttpMethod method, Throwable cause) {
        super(cause);
        this.method = method;
    }

    public ServerFailureException(HttpMethod method) throws URIException {
        this(method,method.getURI()+" responded with status "+method.getStatusCode(),null);

    }

    public HttpMethod getMethod() {
        return method;
    }
}
