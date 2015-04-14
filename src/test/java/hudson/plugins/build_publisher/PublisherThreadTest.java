package hudson.plugins.build_publisher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import hudson.FilePath;
import hudson.Launcher;
import hudson.matrix.AxisList;
import hudson.matrix.MatrixConfiguration;
import hudson.matrix.MatrixBuild;
import hudson.matrix.MatrixProject;
import hudson.matrix.TextAxis;
import hudson.model.BuildListener;
import hudson.model.FreeStyleBuild;
import hudson.model.AbstractBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Job;
import hudson.model.Run;
import hudson.tasks.Builder;
import hudson.tasks.ArtifactArchiver;
import hudson.tasks.Shell;
import hudson.util.DescribableList;

import java.io.File;
import java.io.IOException;
import java.util.Map;

import jenkins.model.ArtifactManager;
import jenkins.model.ArtifactManagerFactory;
import jenkins.model.ArtifactManagerFactoryDescriptor;
import jenkins.model.StandardArtifactManager;
import jenkins.model.ArtifactManagerConfiguration;
import jenkins.model.Jenkins;
import jenkins.util.VirtualFile;

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
    FreeStyleBuild build = source.buildAndAssertSuccess(project);
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
    MatrixBuild build = source.buildAndAssertSuccess(project);
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

    @Test
    public void publishArtifacts() throws Exception {
        switchToInternalJenkins();
        FreeStyleProject p = source.createFreeStyleProject();
        p.getBuildersList().add(new CreateArtifact());
        p.getPublishersList().add(new ArtifactArchiver("artifact", null, false));
        p.getPublishersList().add(publish());
        source.buildAndAssertSuccess(p);

        switchToPublicJenkins();
        FreeStyleBuild build = (FreeStyleBuild) publishedBuild(p.getName(), null, 1);
        assertNotNull(build);
        assertEquals(1, build.getArtifacts().size());
    }

    @Test
    public void publishArtifactsInNonstandardArchiver() throws Exception {
        switchToInternalJenkins();
        ArtifactManagerConfiguration amc = ArtifactManagerConfiguration.get();
        DescribableList<ArtifactManagerFactory, ArtifactManagerFactoryDescriptor> factories = amc.getArtifactManagerFactories();
        factories.clear();
        factories.add(new CustomArtifactManager.Factory());

        FreeStyleProject p = source.createFreeStyleProject();
        p.getBuildersList().add(new CreateArtifact());
        p.getPublishersList().add(new ArtifactArchiver("artifact", null, false));
        p.getPublishersList().add(publish());
        FreeStyleBuild srcBuild = source.buildAndAssertSuccess(p);
        assertEquals(1, srcBuild.getArtifacts().size());

        switchToPublicJenkins();
        FreeStyleBuild build = (FreeStyleBuild) publishedBuild(p.getName(), null, 1);
        assertNotNull(build);
        assertEquals(1, build.getArtifacts().size());
        assertFalse(build.getArtifactsDir().exists());
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

    private static final class CreateArtifact extends Builder {
        @Override
        public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws InterruptedException, IOException {
            build.getWorkspace().child("artifact").write("content", "UTF-8");
            return true;
        }
    }

    // Archive artifacts in special_archive/ instead of archive/ directory
    private static final class CustomArtifactManager extends StandardArtifactManager {

        private static final String DESTINATION = "special_archive";

        public CustomArtifactManager(Run<?, ?> build) {
            super(build);
        }

        @SuppressWarnings("deprecation")
        @Override public void archive(
                FilePath workspace, Launcher launcher, BuildListener listener, Map<String, String> artifacts
        ) throws IOException, InterruptedException {
            super.archive(workspace, launcher, listener, artifacts);
            File archive = build.getArtifactsDir();
            archive.renameTo(new File(archive.getParentFile(), DESTINATION));

            assert !archive.exists();
        }

        @Override public VirtualFile root() {
            return VirtualFile.forFile(new File(build.getRootDir(), DESTINATION));
        }

        private static final class Factory extends ArtifactManagerFactory {
            @Override public ArtifactManager managerFor(Run<?, ?> build) {
                return new CustomArtifactManager(build);
            }

            private static final class Descriptor extends ArtifactManagerFactoryDescriptor {
                @Override public String getDisplayName() {
                    return "Custom archiver";
                }
            }
        }
    }
}
