package hudson.plugins.build_publisher;

import jenkins.model.Jenkins;

import org.jvnet.hudson.test.JenkinsRule;
import org.powermock.reflect.Whitebox;

/**
 * @author lucinka
 */
public class JenkinsPublisherRule extends JenkinsRule {

    @Override
    public void before() throws Throwable{
        super.before();
        Whitebox.setInternalState(jenkins, "theInstance", null, Jenkins.class);
    }

    @Override
    public void after() throws Exception {
        Whitebox.setInternalState(jenkins, "theInstance", jenkins, Jenkins.class);
        super.after();
        Whitebox.setInternalState(jenkins, "theInstance", null, Jenkins.class);
    }
}