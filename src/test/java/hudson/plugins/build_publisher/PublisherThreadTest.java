package hudson.plugins.build_publisher;

import static org.junit.Assert.assertTrue;
import hudson.matrix.AxisList;
import hudson.matrix.MatrixConfiguration;
import hudson.matrix.MatrixBuild;
import hudson.matrix.MatrixProject;
import hudson.matrix.TextAxis;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Job;
import hudson.plugins.build_publisher.BuildPublisher.BuildPublisherDescriptor;
import hudson.tasks.Shell;

import java.io.IOException;
import java.net.URL;

import jenkins.model.Jenkins;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.powermock.reflect.Whitebox;

/**
 *
 * @author lucinka
 */
public class PublisherThreadTest {

  private static final int ATTEMPTS = 60;

  @Rule
  public JenkinsRule publicRule = new JenkinsPublisherRule();

  @Rule
  public JenkinsRule internalRule = new JenkinsPublisherRule();

  private Jenkins publicJenkins;

  private Jenkins jenkins;

  private URL publicJenkinsURL;

  private URL jenkinsURL;

  private BuildPublisherDescriptor internalDescriptor;

  private BuildPublisher internalPublisher;

  @Before
  public void preparePublicJenkins() throws IOException {
    publicJenkins = publicRule.jenkins;
    publicJenkinsURL = publicRule.getURL();
    this.jenkins = internalRule.jenkins;
    jenkinsURL = internalRule.getURL();
    switchToInternalJenkins();
    createBuildPublishers();
    publicJenkins.setCrumbIssuer(null);
    jenkins.setCrumbIssuer(null);
  }

  public void switchToPublicJenkins() {
      Whitebox.setInternalState(publicJenkins, "theInstance", publicJenkins, Jenkins.class);
  }

  public void switchToInternalJenkins() {
      Whitebox.setInternalState(publicJenkins, "theInstance", jenkins, Jenkins.class);
  }

  /**
   * Test if a free style job with name which needs to be encoded is published
   */
  @Test
  public void testPublishFreeStypeProjectWithEncodingNeed() throws Exception {
    switchToInternalJenkins();
    FreeStyleProject project = internalRule.createFreeStyleProject("~`123456789( 0 )-_=qwertyuioplkjhgfdsazxcvbnm,.'\"{}");
    project.getBuildersList().add(new Shell("echo hello"));
    project.getPublishersList().add(internalPublisher);
    FreeStyleBuild build = project.scheduleBuild2(0).get();
    switchToPublicJenkins();
    assertTrue(
            "A Build has not been published.",
            waitForResult(project.getName(), null, build.getNumber())
    );
  }

  /**
   * Test if a matrix job with name which needs to be encoded is published
   */
  @Test
  public void testPublishMatrixProjectWithEncodingNeed() throws Exception {
    switchToInternalJenkins();
    MatrixProject project = internalRule.createMatrixProject("~`1123456789( 0 )-_=qwertyuioplkjhgfdsazxcvbnm,.'\"{}");
    project.getBuildersList().add(new Shell("echo hello"));
    project.getPublishersList().add(internalPublisher);
    TextAxis axis = new TextAxis("user", "~`!1@2#3$4%5^6&7*8(9)0_-=}]{[Poiuytrewqasdfghjkl:;\"'||&&zxcv","bnm<>./?");
    AxisList list = new AxisList();
    list.add(axis);
    project.setAxes(list);
    MatrixBuild build = project.scheduleBuild2(0).get();
    switchToPublicJenkins();
    assertTrue(
            "A Build of matrix project ~`1123456789( 0 )-_=qwertyuioplkjhgfdsazxcvbnm,.'\"{} has not been published.",
            waitForResult(project.getName(), null, build.getNumber())
    );
    for(MatrixConfiguration configuration: project.getActiveConfigurations()){
        assertTrue(
                "A Build of matrix configuration "+ configuration.getName() + " has not been published.",
                waitForResult(project.getName(), configuration.getName(), build.getNumber())
        );
    }
  }

  /*
   * Test if given build exists with waiting interval
   */
  public boolean waitForResult(String jobName, String configuration, int buildNumber) throws Exception {
    for (int attemptsCount = ATTEMPTS; attemptsCount > 0; attemptsCount--) {
        Job<?, ?> job = (Job<?, ?>) publicJenkins.getItemByFullName(jobName);

        if (configuration != null && job != null) {
            job = ((MatrixProject) job).getItem(configuration);
        }

        if (job != null && job.getBuildByNumber(buildNumber) != null) return true;

        Thread.sleep(1000);
    }
    return false;
  }

  public void createBuildPublishers(){
      internalPublisher = new BuildPublisher();
      internalDescriptor = (BuildPublisherDescriptor) internalPublisher.getDescriptor();
      internalDescriptor.setPublicInstances(new HudsonInstance[] {
              new HudsonInstance("Public jenkins", publicJenkinsURL.toString(), null, null)
      });
  }
}
