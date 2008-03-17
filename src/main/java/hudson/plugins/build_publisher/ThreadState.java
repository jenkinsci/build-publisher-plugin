package hudson.plugins.build_publisher;

import hudson.Util;
import hudson.model.AbstractBuild;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.IOException;

import org.apache.commons.httpclient.HttpMethod;
import org.apache.commons.httpclient.Header;
import org.kohsuke.stapler.StaplerResponse;
import org.kohsuke.stapler.StaplerRequest;

import javax.servlet.http.HttpServletResponse;

/**
 * The current state of {@link PublisherThread}.
 *
 * <p>
 * The state is bundled into this immutable object so that
 * {@link PublisherThread} could update the state atomically
 * without locking.
 *
 * <p>
 * Even the singleton states don't use anonymous class so
 * that Jelly views can be associated with each state.
 *
 * @author Kohsuke Kawaguchi
 */
public abstract class ThreadState {
    private ThreadState() {
    }

    /**
     * {@link PublisherThread} encountered an error and is waiting before
     * it attempts the tranmission again.
     */
    public static class ErrorRecoveryWait extends ThreadState {
        /**
         * If {@link System#currentTimeMillis()} gets bigger than this value
         * the time out will be over.
         */
        public final long timeout;

        /**
         * Cause of the problem.
         */
        public final Throwable cause;

        /**
         * Failed while sending this record.
         */
        public final AbstractBuild build;

        /**
         * Failed {@link HttpMethod}, if the failure was due to an error on the server. Possibly null.
         */
        public final HttpMethod method;

        /*package*/ ErrorRecoveryWait(long timeout,AbstractBuild build,Throwable cause, HttpMethod method) {
            this.timeout = timeout;
            this.build = build;
            this.cause = cause;
            this.method = method;
        }

        public String getTimeoutString() {
            return Util.getTimeSpanString(timeout-System.currentTimeMillis());
        }

        public String getStackTrace() {
            return printStackTrace(cause);
        }

        /**
         * Dumps the server output.
         */
        public void doOutput(StaplerRequest req, StaplerResponse rsp) throws IOException {
            if(method==null)
                rsp.sendError(HttpServletResponse.SC_NOT_FOUND);
            else {
                Header ct = method.getResponseHeader("Content-type");
                if(ct!=null)
                    rsp.setContentType(ct.getValue());
                else
                    rsp.setContentType("text/plain;charset=UTF-8");
                rsp.getWriter().write(method.getResponseBodyAsString());
            }
        }
    }

    /**
     * {@link PublisherThread} is doing nothing and looking for something to work on.
     */
    public static class Idle extends ThreadState {
        private Idle() {}
        
    }

    /**
     * {@link PublisherThread} is dead.
     */
    public static class Dead extends ThreadState {
        /**
         * Cause of the death.
         */
        public final Throwable cause;

        /*package*/ Dead(Throwable cause) {
            this.cause = cause;
        }

        public String getStackTrace() {
            return printStackTrace(cause);
        }
    }

    /**
     * {@link PublisherThread} is publishing a build.
     */
    public static class Publishing extends ThreadState {
        public final AbstractBuild build;

        /*package*/ Publishing(AbstractBuild build) {
            this.build = build;
        }
    }

    public static final ThreadState IDLE = new Idle();


    static String printStackTrace(Throwable cause) {
        StringWriter sw = new StringWriter();
        cause.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }
}
