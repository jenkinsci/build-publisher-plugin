package hudson.plugins.build_publisher;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 *  Represents status of build publishing
 */
public class StatusInfo {

    State state;
    Exception exception;
    String text;
    String serverName;

    public StatusInfo(StatusInfo.State state, String text, String serverName,
            Exception exception) {
        this.exception = exception;
        this.text = text;
        this.state = state;
        this.serverName = serverName;
    }

    @Override
    public String toString() {
        StringWriter w = new StringWriter();
        w.write(text);

        w.write("<br/><br/><ul><li>");
        switch (state) {
        case PENDING:
        case FAILURE_PENDING:
            w.write("<a href=\"" + StatusAction.URL
                    + "/abortTransfer\"> Remove from queue </a>");
            break;
        case INPROGRESS:
            w.write("<a href=\"" + StatusAction.URL
                    + "/abortTransfer\">Abort transmission</a>");
            break;
        default:
            w.write("<a href=\"" + StatusAction.URL
                    + "/publishAgain\"> Publish again </a>");
            break;
        }
        w.write("</li></ul>");

        if (exception != null) {
            w.write("<br/><br/>");
            exception.printStackTrace(new PrintWriter(w));
        }

        return w.getBuffer().toString();
    }

    public static enum State {
        PENDING, INPROGRESS, SUCCESS, FAILURE, FAILURE_PENDING, INTERRUPTED
    }

}