package hudson.plugins.build_publisher;

import static org.junit.Assert.assertNotNull;
import hudson.matrix.AxisList;
import hudson.matrix.MatrixConfiguration;
import hudson.matrix.MatrixBuild;
import hudson.matrix.MatrixProject;
import hudson.matrix.TextAxis;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Job;
import hudson.model.Run;
import hudson.tasks.Shell;

import java.io.IOException;

import jenkins.model.Jenkins;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.powermock.reflect.Whitebox;

/**
 * @author lucinka
 */
public class PublisherThreadTest {

  private static final int ATTEMPTS = 60;

  @Rule public JenkinsRule target = new JenkinsPublisherRule();

  @Rule public JenkinsRule source = new JenkinsPublisherRule();

  @Before
  public void setUp() {
    target.jenkins.setCrumbIssuer(null);
    source.jenkins.setCrumbIssuer(null);
  }

  public void switchToPublicJenkins() {
      Whitebox.setInternalState(target.jenkins, "theInstance", target.jenkins, Jenkins.class);
  }

  public void switchToInternalJenkins() {
      Whitebox.setInternalState(target.jenkins, "theInstance", source.jenkins, Jenkins.class);
  }

  /**
   * Test if a free style job with name which needs to be encoded is published
   */
  @Test
  public void testPublishFreeStypeProjectWithEncodingNeed() throws Exception {
    switchToInternalJenkins();
    FreeStyleProject project = source.createFreeStyleProject("~`123456789( 0 )-_=qwertyuioplkjhgfdsazxcvbnm,.'\"{}");
    project.getBuildersList().add(new Shell("echo hello"));
    project.getPublishersList().add(publish());
    FreeStyleBuild build = project.scheduleBuild2(0).get();
    switchToPublicJenkins();
    assertNotNull(
            "A Build has not been published.",
            publishedBuild(project.getName(), null, build.getNumber())
    );
  }

  /**
   * Test if a matrix job with name which needs to be encoded is published
   */
  @Test
  public void testPublishMatrixProjectWithEncodingNeed() throws Exception {
    switchToInternalJenkins();
    MatrixProject project = source.createMatrixProject("~`1123456789( 0 )-_=qwertyuioplkjhgfdsazxcvbnm,.'\"{}");
    project.getBuildersList().add(new Shell("echo hello"));
    project.getPublishersList().add(publish());
    TextAxis axis = new TextAxis("user", "~`!1@2#3$4%5^6&7*8(9)0_-=}]{[Poiuytrewqasdfghjkl:;\"'||&&zxcv","bnm<>./?");
    AxisList list = new AxisList();
    list.add(axis);
    project.setAxes(list);
    MatrixBuild build = project.scheduleBuild2(0).get();
    switchToPublicJenkins();
    assertNotNull(
            "A Build of matrix project ~`1123456789( 0 )-_=qwertyuioplkjhgfdsazxcvbnm,.'\"{} has not been published.",
            publishedBuild(project.getName(), null, build.getNumber())
    );
    for(MatrixConfiguration configuration: project.getActiveConfigurations()){
        assertNotNull(
                "A Build of matrix configuration "+ configuration.getName() + " has not been published.",
                publishedBuild(project.getName(), configuration.getName(), build.getNumber())
        );
    }
  }

  /*
   * Test if given build exists with waiting interval
   */
  public Run<?, ?> publishedBuild(String jobName, String configuration, int buildNumber) throws Exception {
    for (int attemptsCount = ATTEMPTS; attemptsCount > 0; attemptsCount--) {
        Job<?, ?> job = (Job<?, ?>) target.jenkins.getItemByFullName(jobName);

        if (configuration != null && job != null) {
            job = ((MatrixProject) job).getItem(configuration);
        }

        if (job != null) {
            Run<?, ?> build = job.getBuildByNumber(buildNumber);
            if (build != null) return build;
        }

        Thread.sleep(1000);
    }
    return null;
  }

  private BuildPublisher publish() throws IOException {
      BuildPublisher internalPublisher = new BuildPublisher();
      internalPublisher.getDescriptor().setPublicInstances(new HudsonInstance[] {
              new HudsonInstance("Public jenkins", target.getURL().toString(), null, null)
      });
      return internalPublisher;
  }
}
